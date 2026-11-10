package com.blessrom.commerce;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class HomeSlidePurgeService {
    private static final Logger LOG=Logger.getLogger(HomeSlidePurgeService.class);
    private static final int MAX_ATTEMPTS=5;
    private static final String STORAGE_ERROR="No se pudo completar la eliminación de las imágenes. Revisa el almacenamiento de portada.";
    private static final Set<String> ACTIVE_AI_JOBS=Set.of("QUEUED","RUNNING");
    @Inject Db db;
    @Inject Json json;
    @Inject MediaService media;

    public Map<String,Object> status(UUID id) {
        return view(db.one("SELECT id,status,attempts,error_message FROM storefront_slide_purges WHERE id=?",id));
    }

    Map<String,Object> replay(UUID desktop,long version,String key,String actor) {
        var rows=db.rows("SELECT * FROM storefront_slide_purges WHERE requested_by=? AND idempotency_key=?",actor,key);
        if(rows.isEmpty())return null;
        var job=rows.getFirst();
        if(!desktop.equals(job.get("desktop_asset_id"))||version!=((Number)job.get("page_version")).longValue())
            throw Problem.conflict("IDEMPOTENCY_CONFLICT","La misma clave fue usada con otra diapositiva o versión.");
        return view(job);
    }

    /** The caller holds the storefront page lock and commits this work with the new revision. */
    Map<String,Object> enqueue(UUID desktop,Set<UUID> ids,long version,String key,String actor) {
        String assetIds=json.write(ids);
        removeAiReferences(ids,assetIds);
        var objectKeys=new LinkedHashSet<String>();
        Instant eligible=Instant.now();
        for(UUID id:ids.stream().sorted().toList()) {
            var asset=db.one("SELECT * FROM media_assets WHERE id=? AND page_key='home' AND status='READY' FOR UPDATE",id);
            eligible=later(eligible,lastUploadExpiry(asset).plusSeconds(30));
            for(var object:db.rows("SELECT object_key FROM media_objects WHERE asset_id=? ORDER BY variant",id)) {
                String objectKey=(String)object.get("object_key");
                if(!objectKey.startsWith("storefront/home/"+id+"/"))
                    throw Problem.conflict("HOME_MEDIA_INVALID","La imagen de portada contiene un archivo que no le pertenece.");
                objectKeys.add(objectKey);
            }
            db.execute("UPDATE media_assets SET status='PURGE_PENDING' WHERE id=?",id);
        }
        rejectCatalogReferences(objectKeys);
        UUID purgeId=UUID.randomUUID();
        db.execute("""
            INSERT INTO storefront_slide_purges(id,desktop_asset_id,page_version,idempotency_key,requested_by,asset_ids,object_keys,next_attempt_at)
            VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?)
            """,purgeId,desktop,version,key,actor,assetIds,json.write(objectKeys),eligible);
        return status(purgeId);
    }

    void removeHistoricalRevisions(Set<UUID> ids,UUID newRevision) {
        db.execute("""
            WITH targets AS (SELECT jsonb_array_elements_text(?::jsonb) AS id)
            DELETE FROM storefront_revisions r
            WHERE r.page_key='home' AND r.id<>? AND (
                r.content->>'heroAssetId' IN (SELECT id FROM targets)
                OR EXISTS(
                    SELECT 1 FROM jsonb_array_elements(
                        CASE WHEN jsonb_typeof(r.content->'slides')='array' THEN r.content->'slides' ELSE '[]'::jsonb END
                    ) AS slide WHERE slide->>'heroAssetId' IN (SELECT id FROM targets)
                                  OR slide->>'mobileAssetId' IN (SELECT id FROM targets)
                )
            )
            """,json.write(ids),newRevision);
    }

    private void rejectCatalogReferences(Set<String> objectKeys) {
        var urls=objectKeys.stream().map(media::publicUrl).toList();
        if(!db.rows("""
            WITH targets AS (SELECT jsonb_array_elements_text(?::jsonb) AS url)
            SELECT 1 FROM (
                SELECT cover_image_url AS url FROM collections
                UNION ALL SELECT content->>'imageUrl' FROM product_drafts
                UNION ALL SELECT content->>'imageUrl' FROM product_revisions
            ) refs JOIN targets USING(url) LIMIT 1
            """,json.write(urls)).isEmpty())
            throw Problem.conflict("HOME_SLIDE_MEDIA_SHARED","El catálogo utiliza una de estas imágenes. Quita esa referencia antes de eliminarla.");
    }

    private void removeAiReferences(Set<UUID> ids,String assetIds) {
        var jobs=db.rows("""
            WITH targets AS (SELECT jsonb_array_elements_text(?::jsonb) AS id)
            SELECT j.* FROM ai_studio_jobs j WHERE
                EXISTS (
                    SELECT 1 FROM jsonb_array_elements(
                        CASE WHEN jsonb_typeof(j.request->'outputs')='array' THEN j.request->'outputs' ELSE '[]'::jsonb END
                    ) AS output JOIN targets t ON t.id IN (output->>'desktopAssetId',output->>'mobileAssetId')
                ) OR EXISTS (
                    SELECT 1 FROM ai_studio_results r JOIN targets t ON t.id IN (
                        r.adopted_asset_id::text,r.adopted_mobile_asset_id::text,
                        r.private_keys->>'assetId',r.private_keys->>'desktopAssetId',r.private_keys->>'mobileAssetId',
                        r.private_keys->>'cutoutAssetId',r.private_keys->>'ghostAssetId'
                    ) WHERE r.job_id=j.id
                )
            ORDER BY j.id FOR UPDATE OF j
            """,assetIds);
        if(jobs.stream().anyMatch(job->ACTIVE_AI_JOBS.contains(job.get("status"))))
            throw Problem.conflict("HOME_SLIDE_MEDIA_IN_USE","Estudio IA está utilizando estas imágenes. Espera a que termine el trabajo.");
        Set<String> targetIds=new HashSet<>();
        ids.forEach(id->targetIds.add(id.toString()));
        for(var job:jobs)pruneAiJob(job,targetIds);
    }

    private void pruneAiJob(Map<String,Object> job,Set<String> ids) {
        UUID jobId=Db.id(job,"id");
        var request=object(job.get("request"));
        var affectedSlots=new HashSet<Integer>();
        List<?> outputs=request.get("outputs") instanceof List<?> list?list:List.of();
        for(Object value:outputs) {
            if(value instanceof Map<?,?> output&&references(output,ids,"desktopAssetId","mobileAssetId"))
                affectedSlots.add(slot(output.get("slot")));
        }
        var results=db.rows("SELECT * FROM ai_studio_results WHERE job_id=? ORDER BY id FOR UPDATE",jobId);
        for(var result:results) {
            if(references(result,ids,"adopted_asset_id","adopted_mobile_asset_id")||references(object(result.get("private_keys")),ids,
                "assetId","desktopAssetId","mobileAssetId","cutoutAssetId","ghostAssetId"))
                affectedSlots.add(slot(result.get("slot")));
        }
        int remainingResults=results.size();
        for(var result:results) {
            if(affectedSlots.contains(slot(result.get("slot")))) {
                db.execute("DELETE FROM ai_studio_results WHERE id=?",result.get("id"));
                remainingResults--;
            }
        }
        var remainingOutputs=outputs.stream().filter(value->!(value instanceof Map<?,?> output)||!affectedSlots.contains(slot(output.get("slot")))).toList();
        if(remainingResults==0&&remainingOutputs.isEmpty()) {
            db.execute("DELETE FROM ai_studio_jobs WHERE id=?",jobId);
            return;
        }
        request.put("outputs",remainingOutputs);
        if(affectedSlots.contains(slot(request.get("targetSlot")))) {
            request.remove("targetSlot");
            request.remove("scope");
            request.remove("feedback");
        }
        db.execute("UPDATE ai_studio_jobs SET request=?::jsonb,updated_at=now() WHERE id=?",json.write(request),jobId);
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> object(Object value) {
        return value==null?new LinkedHashMap<>():json.read(value,LinkedHashMap.class);
    }

    private static int slot(Object value) {return value instanceof Number number?number.intValue():-1;}

    private static boolean references(Map<?,?> value,Set<String> ids,String... fields) {
        for(String field:fields)if(value.get(field)!=null&&ids.contains(value.get(field).toString()))return true;
        return false;
    }

    private static Instant lastUploadExpiry(Map<String,Object> asset) {
        if(asset.get("upload_expires_at")!=null)return Instant.parse(asset.get("upload_expires_at").toString());
        // Existing READY images were signed before the last completion timestamp.
        Object completed=Objects.requireNonNullElse(asset.get("ready_at"),asset.get("created_at"));
        return Instant.parse(completed.toString()).plusSeconds(3600);
    }

    private static Instant later(Instant first,Instant second) {return first.isAfter(second)?first:second;}

    private static Map<String,Object> view(Map<String,Object> job) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",job.get("id"));
        result.put("status",job.get("status"));
        result.put("attempts",job.get("attempts"));
        if(job.get("error_message")!=null)result.put("errorMessage",job.get("error_message"));
        return result;
    }

    record Work(UUID id,UUID token,List<String> keys) {}

    @Transactional
    Work claim() {
        db.execute("""
            UPDATE storefront_slide_purges SET status='FAILED',error_message=?,finished_at=now(),lease_token=NULL,lease_expires_at=NULL
            WHERE status='RUNNING' AND lease_expires_at<=now() AND attempts>=?
            """,STORAGE_ERROR,MAX_ATTEMPTS);
        UUID token=UUID.randomUUID();
        var rows=db.rows("""
            WITH candidate AS (
                SELECT id FROM storefront_slide_purges WHERE attempts<? AND (
                    (status='QUEUED' AND next_attempt_at<=now()) OR (status='RUNNING' AND lease_expires_at<=now())
                ) ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE storefront_slide_purges p SET status='RUNNING',attempts=p.attempts+1,
                error_message=NULL,lease_token=?,lease_expires_at=now()+interval '5 minutes'
            FROM candidate c WHERE p.id=c.id RETURNING p.id,p.object_keys
            """,MAX_ATTEMPTS,token);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        return new Work(Db.id(row,"id"),token,List.of(json.read(row.get("object_keys"),String[].class)));
    }

    @Transactional
    void finish(Work work) {
        var rows=db.rows("SELECT asset_ids FROM storefront_slide_purges WHERE id=? AND status='RUNNING' AND lease_token=? FOR UPDATE",work.id(),work.token());
        if(rows.isEmpty())return;
        UUID[] assetIds=json.read(rows.getFirst().get("asset_ids"),UUID[].class);
        for(UUID assetId:assetIds) {
            if(db.execute("DELETE FROM media_assets WHERE id=? AND page_key='home' AND status='PURGE_PENDING'",assetId)!=1)
                throw Problem.conflict("HOME_PURGE_ASSET_CHANGED","Cambió una imagen pendiente de eliminación.");
        }
        db.execute("""
            UPDATE storefront_slide_purges SET status='SUCCEEDED',object_keys='[]'::jsonb,finished_at=now(),lease_token=NULL,lease_expires_at=NULL
            WHERE id=? AND lease_token=?
            """,work.id(),work.token());
    }

    @Transactional
    void retryOrFail(Work work) {
        db.execute("""
            UPDATE storefront_slide_purges SET status=CASE WHEN attempts>=? THEN 'FAILED' ELSE 'QUEUED' END,
                next_attempt_at=now()+attempts*interval '30 seconds',error_message=?,
                finished_at=CASE WHEN attempts>=? THEN now() ELSE NULL END,lease_token=NULL,lease_expires_at=NULL
            WHERE id=? AND status='RUNNING' AND lease_token=?
            """,MAX_ATTEMPTS,STORAGE_ERROR,MAX_ATTEMPTS,work.id(),work.token());
    }

    @Scheduled(every="15s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void process() {
        Work work=claim();
        if(work==null)return;
        try {
            for(String key:work.keys())media.purgeHomeObject(key);
            finish(work);
        } catch(RuntimeException error) {
            LOG.warnf("No se pudo completar la purga de portada %s (%s).",work.id(),error.getClass().getSimpleName());
            retryOrFail(work);
        }
    }
}
