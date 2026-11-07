package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
@Transactional
public class AiStudioService {
    @Inject Db db;@Inject Json json;@Inject MediaService media;@Inject HomeService home;@Inject ActivityService activity;
    @ConfigProperty(name="blessrom.ai.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="blessrom.ai.worker-url",defaultValue="http://ai-image-worker:8090") String workerUrl;
    @ConfigProperty(name="blessrom.ai.worker-token") Optional<String> workerToken;

    @RegisterForReflection public record UploadRequest(@NotBlank @Size(max=255) String filename,
        @Pattern(regexp="image/(jpeg|png|webp|avif)") String contentType,@Min(1) @Max(20_971_520) long byteSize,UUID productId) {}
    @RegisterForReflection public record CompleteSource(@Pattern(regexp="[a-fA-F0-9]{64}") String sha256) {}
    @RegisterForReflection public record CutoutRequest(UUID sourceUploadId,UUID frontUploadId,UUID backUploadId,UUID interiorUploadId,
        @Pattern(regexp="TOP|BOTTOM|DRESS") String garmentKind,@Pattern(regexp="GHOST_MANNEQUIN") String effect) {}
    @RegisterForReflection public record CoverRequest(@NotBlank @Pattern(regexp="MANUAL|SMART") String selectionMode,
        @Size(max=8) List<UUID> assetIds,UUID categoryId,UUID collectionId,@Size(max=1000) String brief,@Min(0) long version) {}
    @RegisterForReflection public record Review(@NotBlank @Pattern(regexp="IMAGE|COPY|BOTH|CUTOUT|GHOST") String scope,@Size(max=1000) String feedback) {}
    @RegisterForReflection public record CopyUpdate(@Size(max=100) String eyebrow,@NotBlank @Size(max=180) String title,
        @NotNull @Size(max=1000) String description,@NotBlank @Size(max=80) String buttonLabel) {}
    @RegisterForReflection public record ApplyRequest(@NotNull @Size(min=1,max=3) List<UUID> resultIds,@Min(0) Long homeVersion,
        @Pattern(regexp="GHOST|CUTOUT_FALLBACK") String productImageMode) {}
    @RegisterForReflection public record WorkerResult(@Min(0) @Max(2) int slot,Map<String,Object> content,
        List<MediaService.UploadedObject> desktopObjects,List<MediaService.UploadedObject> mobileObjects) {}
    @RegisterForReflection public record WorkerCompletion(@NotNull List<WorkerResult> results) {}

    public Map<String,Object> status(){return Map.of("enabled",enabled,"configured",enabled&&workerToken.filter(v->!v.isBlank()).isPresent(),
        "manualUploadAvailable",true,"maxCorrections",3,"catalogCanvas","1600x2000","desktopCover","2160x720","mobileCover","1024x1280");}

    public Map<String,Object> startUpload(UploadRequest input,String actor){
        requireEnabled();if(input.productId()!=null)db.one("SELECT id FROM products WHERE id=?",input.productId());
        UUID id=UUID.randomUUID();String extension=switch(input.contentType()){case "image/png"->"png";case "image/webp"->"webp";case "image/avif"->"avif";default->"jpg";};
        String key="ai-studio/originals/"+id+"/source."+extension;
        db.execute("INSERT INTO ai_studio_uploads(id,product_id,object_key,original_filename,content_type,byte_size,created_by) VALUES(?,?,?,?,?,?,?)",
            id,input.productId(),key,input.filename().replaceAll("[^A-Za-z0-9._-]","_"),input.contentType(),input.byteSize(),actor);
        return Map.of("id",id,"uploadUrl",media.aiPutUrl(key,input.contentType()),"contentType",input.contentType(),"expiresIn",600);
    }

    public Map<String,Object> completeUpload(UUID id,CompleteSource input,String actor){
        var row=db.one("SELECT * FROM ai_studio_uploads WHERE id=? AND created_by=? FOR UPDATE",id,actor);
        if(!"UPLOADING".equals(row.get("status")))return source(row);
        var remote=media.aiHead(String.valueOf(row.get("object_key")));
        if(remote.byteSize()!=((Number)row.get("byte_size")).longValue()||!String.valueOf(row.get("content_type")).equalsIgnoreCase(remote.contentType().split(";",2)[0]))
            throw Problem.conflict("AI_SOURCE_MISMATCH","El archivo recibido no coincide con la fotografía seleccionada.");
        db.execute("UPDATE ai_studio_uploads SET status='READY',sha256=? WHERE id=?",input==null?null:input.sha256(),id);
        return source(db.one("SELECT * FROM ai_studio_uploads WHERE id=?",id));
    }

    public Map<String,Object> createCutout(UUID productId,CutoutRequest input,String idempotency,String actor){
        requireEnabled();requireKey(idempotency);UUID front=input.frontUploadId()!=null?input.frontUploadId():input.sourceUploadId();
        if(front==null)throw new Problem(400,"AI_FRONT_REQUIRED","Selecciona la fotografía frontal.");boolean ghost=input.frontUploadId()!=null||"GHOST_MANNEQUIN".equals(input.effect());
        if(ghost&&(input.garmentKind()==null||input.garmentKind().isBlank()))throw new Problem(400,"AI_GARMENT_KIND_REQUIRED","Indica si la prenda es superior, inferior o vestido.");if(ghost)rejectUnsupportedCategory(productId);
        var prior=prior(idempotency,actor);if(prior!=null){var old=map(prior.get("request"));UUID oldFront=uuid(old.get(old.get("frontUploadId")!=null?"frontUploadId":"sourceUploadId"));if(productId.equals(prior.get("product_id"))&&front.equals(oldFront))return jobView(prior);throw Problem.conflict("IDEMPOTENCY_CONFLICT","La misma clave fue usada con otra solicitud.");}
        var source=ownedUpload(front,productId,actor);if(input.backUploadId()!=null)ownedUpload(input.backUploadId(),productId,actor);if(input.interiorUploadId()!=null)ownedUpload(input.interiorUploadId(),productId,actor);
        var productCopy=db.one("SELECT COALESCE(d.content->>'name',r.content->>'name',p.slug) AS name FROM products p LEFT JOIN product_drafts d ON d.product_id=p.id LEFT JOIN product_revisions r ON r.id=p.published_revision WHERE p.id=?",productId);
        var cutout=media.startStagedProduct(productId,String.valueOf(source.get("original_filename")),String.valueOf(productCopy.get("name")));
        UUID cutoutId=(UUID)cutout.get("assetId");var request=new LinkedHashMap<String,Object>();request.put("sourceUploadId",front);request.put("frontUploadId",front);request.put("stage","CUTOUT");request.put("cutoutAssetId",cutoutId);request.put("outputAssetId",cutoutId);
        if(ghost){var ghostAsset=media.startStagedProduct(productId,"maniqui-fantasma.webp",String.valueOf(productCopy.get("name")));request.put("pipeline","GHOST_MANNEQUIN");request.put("ghostAssetId",ghostAsset.get("assetId"));request.put("garmentKind",input.garmentKind());if(input.backUploadId()!=null)request.put("backUploadId",input.backUploadId());if(input.interiorUploadId()!=null)request.put("interiorUploadId",input.interiorUploadId());}
        return enqueue("CUTOUT",productId,null,request,idempotency,actor);
    }

    public Map<String,Object> createCovers(CoverRequest input,String idempotency,String actor){
        requireEnabled();requireKey(idempotency);var prior=prior(idempotency,actor);if(prior!=null){if("home".equals(prior.get("page_key")))return jobView(prior);throw Problem.conflict("IDEMPOTENCY_CONFLICT","La misma clave fue usada con otra solicitud.");}var page=db.one("SELECT version FROM storefront_pages WHERE page_key='home'");Problem.version(Db.version(page),input.version());
        List<UUID> ids=eligibleAssets(input);if(ids.isEmpty())throw new Problem(400,"AI_SOURCES_REQUIRED","Aprueba al menos una prenda antes de generar portadas.");
        var sources=new ArrayList<Map<String,Object>>();for(UUID id:ids){var asset=media.stagedAsset(id);@SuppressWarnings("unchecked")var urls=(Map<String,String>)asset.get("urls");UUID productId=(UUID)asset.get("product_id");var copy=db.one("SELECT COALESCE(d.content->>'name',r.content->>'name',p.slug) AS name,COALESCE(d.content->>'description',r.content->>'description','') AS description FROM products p LEFT JOIN product_drafts d ON d.product_id=p.id LEFT JOIN product_revisions r ON r.id=p.published_revision WHERE p.id=?",productId);var sourceItem=new LinkedHashMap<String,Object>();sourceItem.put("assetId",id);sourceItem.put("imageUrl",urls.get("original"));sourceItem.put("altText",Objects.toString(asset.get("alt_text"),"Prenda Blessrom"));sourceItem.put("name",copy.get("name"));sourceItem.put("description",copy.get("description"));sources.add(sourceItem);}
        var outputs=new ArrayList<Map<String,Object>>();for(int slot=0;slot<3;slot++){
            var desktop=media.startStagedHome("portada-ia-"+(slot+1)+"-pc.webp","Portada Blessrom generada con prendas aprobadas");
            var mobile=media.startStagedHome("portada-ia-"+(slot+1)+"-celular.webp","Portada móvil Blessrom generada con prendas aprobadas");
            outputs.add(Map.of("slot",slot,"desktopAssetId",desktop.get("assetId"),"mobileAssetId",mobile.get("assetId")));
        }
        var request=new LinkedHashMap<String,Object>();request.put("selectionMode",input.selectionMode());request.put("sources",sources);request.put("outputs",outputs);request.put("brief",Objects.requireNonNullElse(input.brief(),""));request.put("homeVersion",input.version());
        return enqueue("COVER",null,"home",request,idempotency,actor);
    }

    public Map<String,Object> job(UUID id,String actor){var row=db.one("SELECT * FROM ai_studio_jobs WHERE id=? AND created_by=?",id,actor);return jobView(row);}
    public List<Map<String,Object>> productJobs(UUID productId,String actor){return db.rows("SELECT * FROM ai_studio_jobs WHERE product_id=? AND created_by=? ORDER BY created_at DESC LIMIT 10",productId,actor).stream().map(this::jobView).toList();}
    public List<Map<String,Object>> homeJobs(String actor){return db.rows("SELECT * FROM ai_studio_jobs WHERE page_key='home' AND created_by=? ORDER BY created_at DESC LIMIT 10",actor).stream().map(this::jobView).toList();}
    public List<Map<String,Object>> eligibleMedia(){var result=new ArrayList<Map<String,Object>>();for(var row:db.rows("SELECT DISTINCT r.adopted_asset_id FROM ai_studio_results r JOIN media_assets m ON m.id=r.adopted_asset_id WHERE r.status='APPLIED' AND m.status IN ('STAGED','READY') AND r.adopted_asset_id IS NOT NULL ORDER BY r.adopted_asset_id")){UUID id=(UUID)row.get("adopted_asset_id");var asset=media.stagedAsset(id);result.add(Map.of("assetId",id,"altText",Objects.toString(asset.get("alt_text"),"Prenda Blessrom"),"media",asset));}return result;}

    public Map<String,Object> approve(UUID jobId,UUID resultId,Review input,String actor){
        var job=ownJob(jobId,actor);var result=db.one("SELECT * FROM ai_studio_results WHERE id=? AND job_id=? FOR UPDATE",resultId,jobId);var request=map(job.get("request"));
        if("CUTOUT".equals(job.get("job_type"))&&"GHOST_MANNEQUIN".equals(request.get("pipeline"))){String stage=Objects.toString(request.get("stage"),"CUTOUT");
            if("CUTOUT".equals(stage)){if(!"CUTOUT".equals(input.scope()))throw Problem.conflict("AI_STAGE_MISMATCH","Primero aprueba el recorte transparente.");request.put("stage","GHOST");request.remove("feedback");db.execute("UPDATE ai_studio_results SET cutout_approved=true,status='GENERATED',feedback=NULL,updated_at=now() WHERE id=?",resultId);db.execute("UPDATE ai_studio_jobs SET request=?::jsonb,status='QUEUED',error_code=NULL,error_message=NULL,updated_at=now() WHERE id=?",json.write(request),jobId);return job(jobId,actor);}
            if(!"GHOST".equals(input.scope()))throw Problem.conflict("AI_STAGE_MISMATCH","Revisa el resultado de maniquí fantasma.");db.execute("UPDATE ai_studio_results SET ghost_approved=true,image_approved=true,copy_approved=true,status='APPROVED',feedback=NULL,updated_at=now() WHERE id=?",resultId);return job(jobId,actor);}
        boolean image=Boolean.TRUE.equals(result.get("image_approved")),copy=Boolean.TRUE.equals(result.get("copy_approved")),cutout=Boolean.TRUE.equals(result.get("cutout_approved"));
        if(Set.of("IMAGE","BOTH","CUTOUT").contains(input.scope()))image=true;if("CUTOUT".equals(input.scope()))cutout=true;if(Set.of("COPY","BOTH").contains(input.scope()))copy=true;
        db.execute("UPDATE ai_studio_results SET image_approved=?,copy_approved=?,cutout_approved=?,status=?,feedback=NULL,updated_at=now() WHERE id=?",image,copy,cutout,image&&copy?"APPROVED":"GENERATED",resultId);
        return job(jobId,actor);
    }

    public Map<String,Object> reject(UUID jobId,UUID resultId,Review input,String actor){
        if(input.feedback()==null||input.feedback().isBlank())throw new Problem(400,"AI_FEEDBACK_REQUIRED","Describe la mejora antes de regenerar.");
        var job=ownJob(jobId,actor);var result=db.one("SELECT * FROM ai_studio_results WHERE id=? AND job_id=? FOR UPDATE",resultId,jobId);
        var stagedRequest=map(job.get("request"));if("CUTOUT".equals(job.get("job_type"))&&"GHOST_MANNEQUIN".equals(stagedRequest.get("pipeline"))){String stage=Objects.toString(stagedRequest.get("stage"),"CUTOUT");String feedback=input.feedback().trim();
            if("CUTOUT".equals(stage)){if(!"CUTOUT".equals(input.scope()))throw Problem.conflict("AI_STAGE_MISMATCH","Corrige primero el recorte transparente.");int revision=((Number)result.get("cutout_revision")).intValue();if(revision>=3)throw Problem.conflict("AI_RETRY_LIMIT","Se alcanzó el máximo de tres correcciones del recorte.");media.resetStaged(uuid(stagedRequest.get("cutoutAssetId")));stagedRequest.put("feedback",feedback);stagedRequest.put("stage","CUTOUT");db.execute("UPDATE ai_studio_results SET cutout_revision=cutout_revision+1,cutout_approved=false,ghost_approved=false,image_approved=false,status='REJECTED',feedback=?,updated_at=now() WHERE id=?",feedback,resultId);}
            else {if(!"GHOST".equals(input.scope()))throw Problem.conflict("AI_STAGE_MISMATCH","Corrige el maniquí fantasma.");int revision=((Number)result.get("ghost_revision")).intValue();if(revision>=3)throw Problem.conflict("AI_RETRY_LIMIT","Se alcanzó el máximo de tres correcciones del maniquí fantasma.");media.resetStaged(uuid(stagedRequest.get("ghostAssetId")));stagedRequest.put("feedback",feedback);stagedRequest.put("stage","GHOST");db.execute("UPDATE ai_studio_results SET ghost_revision=ghost_revision+1,ghost_approved=false,image_approved=false,status='REJECTED',feedback=?,updated_at=now() WHERE id=?",feedback,resultId);}
            db.execute("UPDATE ai_studio_jobs SET request=?::jsonb,status='QUEUED',attempts=attempts+1,error_code=NULL,error_message=NULL,updated_at=now() WHERE id=?",json.write(stagedRequest),jobId);return job(jobId,actor);}
        int revision=((Number)result.get("revision")).intValue();if(revision>=3)throw Problem.conflict("AI_RETRY_LIMIT","Se alcanzó el máximo de tres correcciones para este resultado.");
        var request=map(job.get("request"));request.put("feedback",input.feedback().trim());request.put("scope",input.scope());request.put("targetSlot",result.get("slot"));request.put("resultId",resultId);
        if(Set.of("IMAGE","BOTH","CUTOUT").contains(input.scope()))for(UUID asset:resultAssets(request,((Number)result.get("slot")).intValue()))media.resetStaged(asset);
        boolean imageApproved=Boolean.TRUE.equals(result.get("image_approved")),copyApproved=Boolean.TRUE.equals(result.get("copy_approved"));if(Set.of("IMAGE","BOTH","CUTOUT").contains(input.scope()))imageApproved=false;if(Set.of("COPY","BOTH").contains(input.scope()))copyApproved=false;
        db.execute("UPDATE ai_studio_results SET revision=revision+1,cutout_revision=cutout_revision+?,cutout_approved=CASE WHEN ?=1 THEN false ELSE cutout_approved END,status='REJECTED',image_approved=?,copy_approved=?,feedback=?,updated_at=now() WHERE id=?","CUTOUT".equals(input.scope())?1:0,"CUTOUT".equals(input.scope())?1:0,imageApproved,copyApproved,input.feedback().trim(),resultId);
        db.execute("UPDATE ai_studio_jobs SET request=?::jsonb,status='QUEUED',attempts=attempts+1,error_code=NULL,error_message=NULL,updated_at=now() WHERE id=?",json.write(request),jobId);
        return job(jobId,actor);
    }

    public Map<String,Object> updateCopy(UUID jobId,UUID resultId,CopyUpdate input,String actor){
        ownJob(jobId,actor);var content=new LinkedHashMap<String,Object>();content.put("eyebrow",Objects.requireNonNullElse(input.eyebrow(),""));content.put("title",input.title().trim());content.put("description",input.description());content.put("buttonLabel",input.buttonLabel().trim());content.put("target","catalog");
        db.execute("UPDATE ai_studio_results SET content=?::jsonb,copy_approved=false,status='GENERATED',updated_at=now() WHERE id=? AND job_id=?",json.write(content),resultId,jobId);return job(jobId,actor);
    }

    public Map<String,Object> apply(UUID jobId,ApplyRequest input,String actor){
        var job=ownJob(jobId,actor);String type=String.valueOf(job.get("job_type"));var requested=new LinkedHashSet<>(input.resultIds());
        var results=new ArrayList<Map<String,Object>>();for(UUID resultId:requested)results.add(db.one("SELECT * FROM ai_studio_results WHERE job_id=? AND id=?",jobId,resultId));results.sort(Comparator.comparingInt(row->((Number)row.get("slot")).intValue()));
        if(results.size()!=requested.size())throw Problem.conflict("AI_RESULT_REQUIRED","Selecciona un resultado válido.");
        if("CUTOUT".equals(type)){
            var request=map(job.get("request"));var selected=results.getFirst();if("GHOST_MANNEQUIN".equals(request.get("pipeline"))){String mode=Objects.requireNonNullElse(input.productImageMode(),"GHOST");
                if("GHOST".equals(mode)){if(!Boolean.TRUE.equals(selected.get("ghost_approved")))throw Problem.conflict("AI_APPROVAL_REQUIRED","Aprueba el maniquí fantasma antes de añadirlo al borrador.");db.execute("UPDATE ai_studio_results SET adopted_asset_id=? WHERE id=?",uuid(request.get("ghostAssetId")),selected.get("id"));}
                else {int revision=((Number)selected.get("ghost_revision")).intValue();if(!Boolean.TRUE.equals(selected.get("cutout_approved"))||revision<3)throw Problem.conflict("AI_FALLBACK_NOT_AVAILABLE","El recorte de respaldo se habilita después de tres correcciones fallidas del maniquí.");db.execute("UPDATE ai_studio_results SET adopted_asset_id=?,image_approved=true,copy_approved=true WHERE id=?",uuid(request.get("cutoutAssetId")),selected.get("id"));}}
            else if(!Boolean.TRUE.equals(selected.get("image_approved"))||!Boolean.TRUE.equals(selected.get("copy_approved")))throw Problem.conflict("AI_APPROVAL_REQUIRED","Aprueba el recorte antes de añadirlo al borrador.");
            UUID product=(UUID)job.get("product_id");db.execute("INSERT INTO product_drafts(product_id,content) SELECT p.id,r.content FROM products p JOIN product_revisions r ON r.id=p.published_revision WHERE p.id=? ON CONFLICT(product_id) DO NOTHING",product);
            db.execute("UPDATE ai_studio_results SET status='APPLIED',updated_at=now() WHERE id=?",results.getFirst().get("id"));db.execute("UPDATE ai_studio_jobs SET status='APPLIED',updated_at=now() WHERE id=?",jobId);
            activity.record("AI_PRODUCT_IMAGE_APPLIED","product",product,"Recorte IA añadido al borrador",actor);return job(jobId,actor);
        }
        if(results.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("image_approved"))||!Boolean.TRUE.equals(r.get("copy_approved"))))throw Problem.conflict("AI_APPROVAL_REQUIRED","Aprueba imagen y texto antes de añadir el resultado al borrador.");
        if(input.homeVersion()==null)throw new Problem(400,"VERSION_REQUIRED","Indica la versión actual de la portada.");
        var admin=home.admin();Problem.version(((Number)admin.get("version")).longValue(),input.homeVersion());HomeService.HomeContent current=(HomeService.HomeContent)Objects.requireNonNullElse(admin.get("draft"),admin.get("published"));
        var slides=new ArrayList<HomeService.HomeSlide>();if(current!=null&&current.slides()!=null)slides.addAll(current.slides());var request=map(job.get("request"));
        for(var result:results){int slot=((Number)result.get("slot")).intValue();var output=output(request,slot);UUID desktop=uuid(output.get("desktopAssetId")),mobile=uuid(output.get("mobileAssetId"));media.promoteHome(desktop);media.promoteHome(mobile);var copy=map(result.get("content"));
            slides.add(new HomeService.HomeSlide(text(copy,"eyebrow"),text(copy,"title"),text(copy,"description"),desktop,"Portada de "+text(copy,"title"),50,50,new HomeService.Action(text(copy,"buttonLabel"),new HomeService.Target("catalog",null)),null,mobile,true));
        }
        if(slides.size()>6)throw Problem.conflict("HOME_SLIDE_LIMIT","La portada admite como máximo seis diapositivas.");
        HomeService.HomeSlide first=slides.getFirst();HomeService.HomeContent content=new HomeService.HomeContent(first.eyebrow(),first.title(),first.description(),first.heroAssetId(),first.heroAlt(),first.frameX(),first.frameY(),first.primary(),first.secondary(),current==null?List.of():current.shortcuts(),slides);
        var saved=home.saveDraft(new HomeService.SaveDraft(input.homeVersion(),content),actor);for(var result:results)db.execute("UPDATE ai_studio_results SET status='APPLIED',updated_at=now() WHERE id=?",result.get("id"));db.execute("UPDATE ai_studio_jobs SET status='APPLIED',updated_at=now() WHERE id=?",jobId);
        return saved;
    }

    Map<String,Object> claim(){
        if(!enabled)return null;var rows=db.rows("SELECT * FROM ai_studio_jobs WHERE status='QUEUED' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");if(rows.isEmpty())return null;var job=rows.getFirst();UUID lease=UUID.randomUUID();
        db.execute("UPDATE ai_studio_jobs SET status='RUNNING',lease_token=?,lease_expires_at=now()+interval '15 minutes',updated_at=now() WHERE id=?",lease,job.get("id"));var request=map(job.get("request"));var work=new LinkedHashMap<String,Object>();work.put("jobId",job.get("id"));work.put("jobType",job.get("job_type"));work.put("leaseToken",lease);work.put("request",request);
        if("CUTOUT".equals(job.get("job_type"))){String stage=Objects.toString(request.get("stage"),"CUTOUT");if("GHOST".equals(stage)){var sources=new ArrayList<Map<String,Object>>();sources.add(Map.of("role","CUTOUT","imageUrl",media.aiAssetGetUrl(uuid(request.get("cutoutAssetId")),"original")));sources.add(sourceUrl("FRONT",uuid(request.get("frontUploadId"))));if(request.get("backUploadId")!=null)sources.add(sourceUrl("BACK",uuid(request.get("backUploadId"))));if(request.get("interiorUploadId")!=null)sources.add(sourceUrl("INTERIOR",uuid(request.get("interiorUploadId"))));work.put("sources",sources);work.put("outputs",media.uploadTargets(uuid(request.get("ghostAssetId"))).get("uploads"));}else {var source=db.one("SELECT * FROM ai_studio_uploads WHERE id=?",uuid(request.get("sourceUploadId")));work.put("sourceUrl",media.aiGetUrl(String.valueOf(source.get("object_key"))));work.put("outputs",media.uploadTargets(uuid(request.get("cutoutAssetId")!=null?request.get("cutoutAssetId"):request.get("outputAssetId"))).get("uploads"));}}
        else {var outputs=new ArrayList<Map<String,Object>>();Integer target=request.get("targetSlot") instanceof Number n?n.intValue():null;boolean copyOnly="COPY".equals(request.get("scope"));for(Object value:list(request.get("outputs"))){var output=map(value);int slot=((Number)output.get("slot")).intValue();if(target!=null&&slot!=target)continue;outputs.add(copyOnly?Map.of("slot",slot):Map.of("slot",slot,"desktop",media.uploadTargets(uuid(output.get("desktopAssetId"))).get("uploads"),"mobile",media.uploadTargets(uuid(output.get("mobileAssetId"))).get("uploads")));}work.put("outputs",outputs);}
        return work;
    }

    void completeWorker(UUID jobId,UUID lease,WorkerCompletion completion){
        var job=db.one("SELECT * FROM ai_studio_jobs WHERE id=? AND lease_token=? AND status='RUNNING' FOR UPDATE",jobId,lease);var request=map(job.get("request"));
        for(var value:completion.results()){int slot=value.slot();UUID resultId=existingResult(jobId,slot);if("CUTOUT".equals(job.get("job_type"))){String stage=Objects.toString(request.get("stage"),"CUTOUT");boolean ghostPipeline="GHOST_MANNEQUIN".equals(request.get("pipeline"));UUID asset=uuid("GHOST".equals(stage)?request.get("ghostAssetId"):(request.get("cutoutAssetId")!=null?request.get("cutoutAssetId"):request.get("outputAssetId")));media.completeStagedProduct((UUID)job.get("product_id"),asset,new MediaService.CompleteUpload(value.desktopObjects()));var keys=new LinkedHashMap<String,Object>();keys.put("assetId",asset);if(ghostPipeline){keys.put("cutoutAssetId",uuid(request.get("cutoutAssetId")));keys.put("ghostAssetId",uuid(request.get("ghostAssetId")));}upsertResult(resultId,jobId,slot,value.content(),value.desktopObjects(),null,keys,asset,null,true);if(ghostPipeline)db.execute("UPDATE ai_studio_results SET cutout_approved=CASE WHEN ?='GHOST' THEN cutout_approved ELSE false END,ghost_approved=false,image_approved=false,copy_approved=true,status='GENERATED',updated_at=now() WHERE job_id=? AND slot=?",stage,jobId,slot);}
            else {var output=output(request,slot);UUID desktop=uuid(output.get("desktopAssetId")),mobile=uuid(output.get("mobileAssetId"));if(value.desktopObjects()!=null)media.completeStagedHome(desktop,new MediaService.CompleteUpload(value.desktopObjects()));if(value.mobileObjects()!=null)media.completeStagedHome(mobile,new MediaService.CompleteUpload(value.mobileObjects()));upsertResult(resultId,jobId,slot,value.content(),value.desktopObjects(),value.mobileObjects(),Map.of("desktopAssetId",desktop,"mobileAssetId",mobile),desktop,mobile,false,Objects.toString(request.get("scope"),"BOTH"));}}
        db.execute("UPDATE ai_studio_jobs SET status='REVIEW_REQUIRED',lease_token=NULL,lease_expires_at=NULL,updated_at=now() WHERE id=?",jobId);
    }

    void failWorker(UUID jobId,UUID lease,String message){db.execute("UPDATE ai_studio_jobs SET status='FAILED',error_code='AI_WORKER_FAILED',error_message=?,lease_token=NULL,lease_expires_at=NULL,updated_at=now() WHERE id=? AND lease_token=?",message.length()>500?message.substring(0,500):message,jobId,lease);}
    String workerUrl(){return workerUrl;}String workerToken(){return workerToken.orElse("");}

    private Map<String,Object> enqueue(String type,UUID product,String page,Map<String,Object> request,String key,String actor){String body=json.write(request),hash=hash(body);var prior=db.rows("SELECT * FROM ai_studio_jobs WHERE created_by=? AND idempotency_key=?",actor,key);if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("request_hash")))throw Problem.conflict("IDEMPOTENCY_CONFLICT","La misma clave fue usada con otra solicitud.");return jobView(prior.getFirst());}UUID id=UUID.randomUUID();db.execute("INSERT INTO ai_studio_jobs(id,job_type,product_id,page_key,request,request_hash,idempotency_key,created_by) VALUES(?,?,?,?,?::jsonb,?,?,?)",id,type,product,page,body,hash,key,actor);return jobView(db.one("SELECT * FROM ai_studio_jobs WHERE id=?",id));}
    private Map<String,Object> ownJob(UUID id,String actor){return db.one("SELECT * FROM ai_studio_jobs WHERE id=? AND created_by=?",id,actor);}
    private Map<String,Object> prior(String key,String actor){var rows=db.rows("SELECT * FROM ai_studio_jobs WHERE created_by=? AND idempotency_key=?",actor,key);return rows.isEmpty()?null:rows.getFirst();}
    private Map<String,Object> jobView(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","job_type","product_id","page_key","status","attempts","error_code","error_message","created_at","updated_at"))result.put(key,row.get(key));var request=map(row.get("request"));result.put("stage",request.get("stage"));var rows=db.rows("SELECT * FROM ai_studio_results WHERE job_id=? ORDER BY slot,revision DESC",row.get("id"));for(var item:rows){item.put("content",map(item.get("content")));item.put("desktop_objects",jsonValue(item.get("desktop_objects")));item.put("mobile_objects",jsonValue(item.get("mobile_objects")));item.put("private_keys",map(item.get("private_keys")));item.put("stage",request.get("stage"));var keys=map(item.get("private_keys"));if(keys.get("assetId")!=null)item.put("media",media.stagedAsset(uuid(keys.get("assetId"))));if(keys.get("cutoutAssetId")!=null)item.put("cutoutMedia",media.stagedAsset(uuid(keys.get("cutoutAssetId"))));if(keys.get("desktopAssetId")!=null)item.put("desktopMedia",media.stagedAsset(uuid(keys.get("desktopAssetId"))));if(keys.get("mobileAssetId")!=null)item.put("mobileMedia",media.stagedAsset(uuid(keys.get("mobileAssetId"))));item.put("fallbackAllowed",item.get("ghost_revision") instanceof Number n&&n.intValue()>=3&&Boolean.TRUE.equals(item.get("cutout_approved")));}result.put("results",rows);return result;}
    private Map<String,Object> source(Map<String,Object> row){return Map.of("id",row.get("id"),"status",row.get("status"),"filename",row.get("original_filename"),"expiresAt",row.get("expires_at"));}
    private Map<String,Object> ownedUpload(UUID id,UUID productId,String actor){return db.one("SELECT * FROM ai_studio_uploads WHERE id=? AND product_id=? AND created_by=? AND status='READY' AND expires_at>now()",id,productId,actor);}
    private Map<String,Object> sourceUrl(String role,UUID id){var source=db.one("SELECT object_key FROM ai_studio_uploads WHERE id=? AND status='READY' AND expires_at>now()",id);return Map.of("role",role,"imageUrl",media.aiGetUrl(String.valueOf(source.get("object_key"))));}
    private void rejectUnsupportedCategory(UUID productId){var row=db.one("SELECT lower(COALESCE(c.name,'')||' '||COALESCE(c.slug,'')) AS category FROM products p LEFT JOIN product_drafts d ON d.product_id=p.id LEFT JOIN product_revisions r ON r.id=p.published_revision LEFT JOIN categories c ON c.id=COALESCE(NULLIF(d.content->>'categoryId','')::uuid,NULLIF(r.content->>'categoryId','')::uuid) WHERE p.id=?",productId);String category=Objects.toString(row.get("category"),"");if(category.matches(".*(accesorio|calzado|zapato|zapatilla|gorra|sombrero|cintur|bolso|mochila|cartera|reloj).*"))throw new Problem(400,"AI_GHOST_UNSUPPORTED_GARMENT","El maniquí fantasma está disponible para polos, camisas, casacas, vestidos, pantalones y shorts; no para accesorios o calzado.");}
    private List<UUID> eligibleAssets(CoverRequest input){var ids=new ArrayList<UUID>();if("MANUAL".equals(input.selectionMode())){if(input.assetIds()!=null)for(UUID id:input.assetIds()){db.one("SELECT r.id FROM ai_studio_results r JOIN media_assets m ON m.id=r.adopted_asset_id WHERE r.adopted_asset_id=? AND r.status='APPLIED' AND m.status IN ('STAGED','READY')",id);ids.add(id);}}else{for(var row:db.rows("SELECT DISTINCT r.adopted_asset_id FROM ai_studio_results r JOIN media_assets m ON m.id=r.adopted_asset_id WHERE r.status='APPLIED' AND m.status IN ('STAGED','READY') AND r.adopted_asset_id IS NOT NULL ORDER BY r.adopted_asset_id LIMIT 4"))ids.add((UUID)row.get("adopted_asset_id"));}return ids.stream().limit(4).toList();}
    private void upsertResult(UUID id,UUID job,int slot,Map<String,Object> content,Object desktop,Object mobile,Object keys,UUID adopted,UUID adoptedMobile,boolean copyApproved){upsertResult(id,job,slot,content,desktop,mobile,keys,adopted,adoptedMobile,copyApproved,"BOTH");}
    private void upsertResult(UUID id,UUID job,int slot,Map<String,Object> content,Object desktop,Object mobile,Object keys,UUID adopted,UUID adoptedMobile,boolean copyApproved,String scope){if(id==null){id=UUID.randomUUID();db.execute("INSERT INTO ai_studio_results(id,job_id,slot,content,desktop_objects,mobile_objects,private_keys,adopted_asset_id,adopted_mobile_asset_id,copy_approved) VALUES(?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?)",id,job,slot,json.write(content==null?Map.of():content),json.write(desktop),json.write(mobile),json.write(keys),adopted,adoptedMobile,copyApproved);}else {var prior=db.one("SELECT * FROM ai_studio_results WHERE id=?",id);Object nextContent="IMAGE".equals(scope)?prior.get("content"):json.write(content==null?Map.of():content);Object nextDesktop=desktop==null?prior.get("desktop_objects"):json.write(desktop);Object nextMobile=mobile==null?prior.get("mobile_objects"):json.write(mobile);boolean imageOk="COPY".equals(scope)&&Boolean.TRUE.equals(prior.get("image_approved"));boolean copyOk="IMAGE".equals(scope)&&Boolean.TRUE.equals(prior.get("copy_approved"));db.execute("UPDATE ai_studio_results SET status='GENERATED',content=?::jsonb,desktop_objects=?::jsonb,mobile_objects=?::jsonb,private_keys=?::jsonb,adopted_asset_id=?,adopted_mobile_asset_id=?,image_approved=?,copy_approved=?,updated_at=now() WHERE id=?",nextContent,nextDesktop,nextMobile,json.write(keys),adopted,adoptedMobile,imageOk,copyOk,id);}}
    private UUID existingResult(UUID job,int slot){var rows=db.rows("SELECT id FROM ai_studio_results WHERE job_id=? AND slot=? ORDER BY revision DESC LIMIT 1",job,slot);return rows.isEmpty()?null:(UUID)rows.getFirst().get("id");}
    private List<UUID> resultAssets(Map<String,Object> request,int slot){if(request.get("outputAssetId")!=null)return List.of(uuid(request.get("outputAssetId")));var output=output(request,slot);return List.of(uuid(output.get("desktopAssetId")),uuid(output.get("mobileAssetId")));}
    private Map<String,Object> output(Map<String,Object> request,int slot){return list(request.get("outputs")).stream().map(this::map).filter(v->((Number)v.get("slot")).intValue()==slot).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){return value==null?new LinkedHashMap<>():value instanceof Map<?,?> map?new LinkedHashMap<>((Map<String,Object>)map):json.read(value,LinkedHashMap.class);}
    private Object jsonValue(Object value){return value==null||value instanceof Map<?,?>||value instanceof List<?>?value:json.read(value,Object.class);}
    @SuppressWarnings("unchecked") private List<Object> list(Object value){return value instanceof List<?> list?(List<Object>)list:List.of();}
    private static UUID uuid(Object value){return value instanceof UUID id?id:UUID.fromString(String.valueOf(value));}
    private static String text(Map<String,Object> map,String key){return Objects.toString(map.get(key),"").trim();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void requireEnabled(){if(!enabled)throw new Problem(503,"AI_STUDIO_DISABLED","El Estudio IA todavía no está habilitado.");}
    private static void requireKey(String key){if(key==null||key.isBlank()||key.length()>200)throw new Problem(400,"IDEMPOTENCY_KEY_REQUIRED","Envía una clave de idempotencia válida.");}
}
