package com.blessrom.commerce;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class ProductPurgeService {
 @Inject Db db;@Inject Json json;@Inject MediaService media;@Inject ActivityService activity;
 @Transactional public Map<String,Object> status(UUID id){var row=db.one("SELECT id,status,attempts,error_message FROM product_media_purges WHERE id=?",id);var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("status",row.get("status"));result.put("attempts",row.get("attempts"));result.put("errorMessage",row.get("error_message"));return result;}
 @Transactional public Object retry(UUID id){db.execute("UPDATE product_media_purges SET status='QUEUED',attempts=0,next_attempt_at=now(),error_message=NULL WHERE id=? AND status='FAILED'",id);return status(id);}
 @Transactional public Map<String,Object> enqueue(UUID product,UUID asset,Long version,String key,String actor){
  if(key==null||!key.matches("[A-Za-z0-9_.-]{8,200}"))throw new Problem(400,"IDEMPOTENCY_REQUIRED","Envía una clave de idempotencia.");
  var completed=db.rows("SELECT id,product_id,asset_id FROM product_media_purges WHERE actor=? AND idempotency_key=?",actor,key);
  if(!completed.isEmpty()){var row=completed.getFirst();if(!product.equals(row.get("product_id"))||!Objects.equals(asset,row.get("asset_id")))throw Problem.conflict("IDEMPOTENCY_CONFLICT","La clave corresponde a otra eliminación.");return status((UUID)row.get("id"));}
  var p=db.one("SELECT * FROM products WHERE id=? FOR UPDATE",product);
  var replay=db.rows("SELECT id,product_id,asset_id FROM product_media_purges WHERE actor=? AND idempotency_key=?",actor,key);
  if(!replay.isEmpty()){var row=replay.getFirst();if(!product.equals(row.get("product_id"))||!Objects.equals(asset,row.get("asset_id")))throw Problem.conflict("IDEMPOTENCY_CONFLICT","La clave corresponde a otra eliminación.");return status((UUID)row.get("id"));}
  if(version!=null)Problem.version(Db.version(p),version);
  if(Boolean.TRUE.equals(p.get("deleting")))throw Problem.conflict("PRODUCT_DELETING","El producto se está eliminando.");
  if(asset==null){
   if(!db.rows("SELECT 1 FROM order_items o JOIN variants v ON v.id=o.variant_id WHERE v.product_id=? LIMIT 1",product).isEmpty())throw Problem.conflict("PRODUCT_HAS_ORDERS","El producto tiene pedidos o devoluciones. Utiliza Archivar.");
   if(!db.rows("SELECT 1 FROM inventory i JOIN variants v ON v.id=i.variant_id WHERE v.product_id=? AND (i.on_hand>0 OR i.reserved>0) LIMIT 1",product).isEmpty())throw Problem.conflict("PRODUCT_HAS_STOCK","Resuelve el stock físico y las reservas en Inventario antes de eliminar.");
  }
  var ids=new LinkedHashSet<UUID>();
  for(var row:db.rows("SELECT id FROM media_assets WHERE product_id=?"+(asset==null?"":" AND id=?")+" FOR UPDATE",asset==null?new Object[]{product}:new Object[]{product,asset}))ids.add((UUID)row.get("id"));
  if(asset!=null&&ids.isEmpty())throw Problem.missing();
  var jobs=db.rows("SELECT * FROM ai_studio_jobs WHERE product_id=? ORDER BY id FOR UPDATE",product);
  if(jobs.stream().anyMatch(j->Set.of("QUEUED","RUNNING").contains(j.get("status"))))throw Problem.conflict("PRODUCT_AI_RUNNING","Espera a que finalice el trabajo del Estudio IA.");
  var removedJobs=new ArrayList<UUID>();var eligibleUploads=new HashSet<String>();
  for(var job:jobs)if(asset==null||job.get("request").toString().contains(asset.toString())||!db.rows("SELECT id FROM ai_studio_results WHERE job_id=? AND (adopted_asset_id=? OR private_keys::text LIKE ?)",job.get("id"),asset,"%"+asset+"%").isEmpty()){
   removedJobs.add((UUID)job.get("id"));
   var request=json.read(job.get("request"),Map.class);
   for(String field:List.of("sourceUploadId","frontUploadId","backUploadId","interiorUploadId"))if(request.get(field)!=null)eligibleUploads.add(request.get(field).toString());
   for(String field:List.of("outputAssetId","cutoutAssetId","ghostAssetId"))if(request.get(field)!=null){UUID candidate=UUID.fromString(request.get(field).toString());if(!db.rows("SELECT id FROM media_assets WHERE id=? AND product_id=? AND (status IN ('STAGED','PENDING') OR id=?)",candidate,product,asset).isEmpty())ids.add(candidate);}
  }
  var keys=new LinkedHashSet<String>();for(UUID id:ids)for(var row:db.rows("SELECT object_key FROM media_objects WHERE asset_id=?",id))keys.add(row.get("object_key").toString());
  rejectShared(product,ids,keys);
  for(UUID id:removedJobs)db.execute("DELETE FROM ai_studio_jobs WHERE id=?",id);
  // Remove obsolete source references from completed cover jobs, preserving their independently generated images.
  for(var job:db.rows("SELECT id,request,status FROM ai_studio_jobs WHERE page_key='home' ORDER BY id FOR UPDATE")){
   var request=json.read(job.get("request"),LinkedHashMap.class);Object sources=request.get("sources");if(!(sources instanceof List<?> list))continue;
   var remaining=list.stream().filter(value->!(value instanceof Map<?,?> source)||source.get("assetId")==null||!ids.contains(UUID.fromString(source.get("assetId").toString()))).toList();
   if(remaining.size()!=list.size()){if(Set.of("QUEUED","RUNNING").contains(job.get("status")))throw Problem.conflict("PRODUCT_AI_RUNNING","Una portada IA está usando estas imágenes. Espera a que termine.");request.put("sources",remaining);db.execute("UPDATE ai_studio_jobs SET request=?::jsonb WHERE id=?",json.write(request),job.get("id"));}
  }
  // Only originals no remaining job references are eligible for deletion.
  for(var upload:db.rows("SELECT id,object_key FROM ai_studio_uploads WHERE product_id=? FOR UPDATE",product)){
   UUID id=(UUID)upload.get("id");if((asset==null||eligibleUploads.contains(id.toString()))&&db.rows("SELECT id FROM ai_studio_jobs WHERE request::text LIKE ? LIMIT 1","%"+id+"%").isEmpty()){keys.add(upload.get("object_key").toString());db.execute("DELETE FROM ai_studio_uploads WHERE id=?",id);}
  }
  Instant eligible=Instant.now().plusSeconds(30);
  for(UUID id:ids){var row=db.one("SELECT COALESCE(upload_expires_at,ready_at+interval '1 hour',created_at+interval '1 hour') AS expires FROM media_assets WHERE id=?",id);Instant expires=Instant.parse(row.get("expires").toString()).plusSeconds(30);if(expires.isAfter(eligible))eligible=expires;}
  // Original upload links may remain usable after the source row was detached.
  if(!removedJobs.isEmpty()||asset==null){Instant originalsSafe=Instant.now().plusSeconds(630);if(originalsSafe.isAfter(eligible))eligible=originalsSafe;}
  for(UUID id:ids)db.execute("UPDATE media_assets SET status='PURGE_PENDING',is_primary=false WHERE id=?",id);
  if(asset==null)db.execute("UPDATE products SET archived=true,deleting=true,version=version+1 WHERE id=?",product);
  else{
   var urls=keys.stream().filter(k->k.startsWith("products/")).map(media::publicUrl).toList();
   for(String url:urls){db.execute("UPDATE product_drafts SET content=jsonb_set(content,'{imageUrl}','null'::jsonb) WHERE product_id=? AND content->>'imageUrl'=?",product,url);db.execute("UPDATE product_revisions SET content=jsonb_set(content,'{imageUrl}','null'::jsonb) WHERE product_id=? AND content->>'imageUrl'=?",product,url);}
   db.execute("UPDATE media_assets SET is_primary=true WHERE id=(SELECT id FROM media_assets WHERE product_id=? AND status='READY' ORDER BY is_primary DESC,display_order,created_at,id LIMIT 1)",product);
   db.execute("UPDATE products SET version=version+1 WHERE id=?",product);
  }
  UUID id=UUID.randomUUID();db.execute("INSERT INTO product_media_purges(id,product_id,asset_id,actor,idempotency_key,keys,asset_ids,next_attempt_at) VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?)",id,product,asset,actor,key,json.write(keys),json.write(ids),eligible);
  activity.record(asset==null?"PRODUCT_DELETE_REQUESTED":"PRODUCT_IMAGE_DELETE_REQUESTED","product",product,"Eliminación solicitada",actor);return status(id);
 }
 private void rejectShared(UUID product,Set<UUID> ids,Set<String> keys){
  for(UUID id:ids)if(!db.rows("SELECT id FROM storefront_revisions WHERE content::text LIKE ? LIMIT 1","%"+id+"%").isEmpty()||!db.rows("SELECT page_key FROM storefront_pages WHERE draft_content::text LIKE ?","%"+id+"%").isEmpty())throw Problem.conflict("MEDIA_SHARED","Una portada referencia esta imagen. Reemplázala allí antes de eliminar.");
  for(String key:keys){String url=media.publicUrl(key);if(!db.rows("SELECT id FROM collections WHERE cover_image_url=? UNION ALL SELECT product_id FROM product_drafts WHERE product_id<>? AND content->>'imageUrl'=? UNION ALL SELECT product_id FROM product_revisions WHERE product_id<>? AND content->>'imageUrl'=?",url,product,url,product,url).isEmpty())throw Problem.conflict("MEDIA_SHARED","Otra colección o producto utiliza esta imagen. Reemplaza esa referencia primero.");}
 }
 record Work(UUID id,UUID token,List<String> keys){}
 @Transactional Work claim(){var rows=db.rows("SELECT * FROM product_media_purges WHERE (status='QUEUED' AND next_attempt_at<=now()) OR (status='RUNNING' AND lease_expires_at<=now()) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("id"),token=UUID.randomUUID();db.execute("UPDATE product_media_purges SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_expires_at=now()+interval '10 minutes' WHERE id=?",token,id);return new Work(id,token,List.of(json.read(row.get("keys"),String[].class)));}
 @Transactional void finish(Work work){var row=db.one("SELECT * FROM product_media_purges WHERE id=? AND lease_token=? FOR UPDATE",work.id(),work.token());UUID product=(UUID)row.get("product_id");for(UUID id:json.read(row.get("asset_ids"),UUID[].class))db.execute("DELETE FROM media_assets WHERE id=? AND status='PURGE_PENDING'",id);
  if(row.get("asset_id")==null){
   db.execute("DELETE FROM cart_items WHERE variant_id IN (SELECT id FROM variants WHERE product_id=?)",product);
   db.execute("DELETE FROM inventory_movements WHERE variant_id IN (SELECT id FROM variants WHERE product_id=?)",product);
   db.execute("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM variants WHERE product_id=?)",product);
   db.execute("DELETE FROM variants WHERE product_id=?",product);db.execute("DELETE FROM product_embeddings WHERE product_id=?",product);
   db.execute("UPDATE products SET published_revision=NULL WHERE id=?",product);db.execute("DELETE FROM product_drafts WHERE product_id=?",product);db.execute("DELETE FROM product_revisions WHERE product_id=?",product);db.execute("DELETE FROM products WHERE id=? AND deleting=true",product);
  }
  db.execute("UPDATE product_media_purges SET status='SUCCEEDED',keys='[]'::jsonb,asset_ids='[]'::jsonb,error_message=NULL,lease_token=NULL WHERE id=?",work.id());
 }
 @Transactional void failed(Work work){db.execute("UPDATE product_media_purges SET status=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'QUEUED' END,error_message='No se pudo completar la limpieza. Reintenta desde administración.',next_attempt_at=now()+interval '1 minute',lease_token=NULL WHERE id=? AND lease_token=?",work.id(),work.token());}
 @Scheduled(every="15s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP) void process(){Work work=claim();if(work==null)return;try{for(String key:work.keys())media.purgeProductObject(key);media.purgeCachedUrls(work.keys());finish(work);}catch(RuntimeException e){failed(work);}}
}
