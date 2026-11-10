package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
@Transactional
public class MediaService {
    private static final List<String> VARIANTS=List.of("original","large","medium","thumbnail");
    private static final DateTimeFormatter AMZ_DATE=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    @Inject Db db;
    @ConfigProperty(name="blessrom.media.r2.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="blessrom.media.r2.endpoint",defaultValue="https://invalid.r2.cloudflarestorage.com") String endpoint;
    @ConfigProperty(name="blessrom.media.r2.bucket",defaultValue="blessrom-media-production") String bucket;
    @ConfigProperty(name="blessrom.media.r2.access-key-id") Optional<String> accessKey;
    @ConfigProperty(name="blessrom.media.r2.secret-access-key") Optional<String> secretKey;
    @ConfigProperty(name="blessrom.media.public-base-url",defaultValue="https://media.blessrom.com") String publicBaseUrl;
    @ConfigProperty(name="blessrom.media.upload-expiry-seconds",defaultValue="600") int expirySeconds;
    private volatile HttpClient client;

    public record StartUpload(@NotBlank @Size(max=255) String filename,@Size(max=300) String altText,
        @Pattern(regexp="DESKTOP|MOBILE") String role) {
        public StartUpload(String filename,String altText) { this(filename,altText,null); }
    }
    public record UploadedObject(@NotBlank @Pattern(regexp="original|large|medium|thumbnail") String variant,
        @Min(1) @Max(10000) int width,@Min(1) @Max(10000) int height,@Min(1) @Max(10_485_760) long byteSize) {}
    public record CompleteUpload(@NotNull @Size(min=4,max=4) List<@NotNull @Valid UploadedObject> objects) {}
    public record UpdateMedia(@Size(max=300) String altText,Boolean primary) {}
    public record MediaOrder(@NotNull @Size(min=1,max=50) List<@NotNull UUID> assetIds) {}

    public Map<String,Object> start(UUID productId,StartUpload input) {
        requireConfigured();
        db.one("SELECT id FROM products WHERE id=?",productId);
        return startOwned(productId,null,input);
    }

    public Map<String,Object> startHome(StartUpload input) {
        requireConfigured();
        db.one("SELECT page_key FROM storefront_pages WHERE page_key='home'");
        return startOwned(null,"home",input);
    }

    private Map<String,Object> startOwned(UUID productId,String pageKey,StartUpload input) {
        UUID assetId=UUID.randomUUID();
        String safeName=input.filename().replaceAll("[^A-Za-z0-9._-]","_");
        db.execute("INSERT INTO media_assets(id,product_id,page_key,original_filename,alt_text,home_role) VALUES(?,?,?,?,?,?)",
            assetId,productId,pageKey,safeName,input.altText(),pageKey==null?null:input.role());
        var uploads=new ArrayList<Map<String,Object>>();
        for(String variant:VARIANTS) {
            String key=(productId==null?"storefront/"+pageKey:"products/"+productId)+"/"+assetId+"/"+variant+".webp";
            db.execute("INSERT INTO media_objects(asset_id,variant,object_key) VALUES(?,?,?)",assetId,variant,key);
            uploads.add(Map.of("variant",variant,"objectKey",key,"uploadUrl",presign("PUT",key,"image/webp"),"contentType","image/webp"));
        }
        if(pageKey!=null)rememberUploadExpiry(assetId);
        return Map.of("assetId",assetId,"expiresIn",expirySeconds,"uploads",uploads);
    }

    public Map<String,Object> complete(UUID productId,UUID assetId,CompleteUpload input) {
        requireConfigured();
        var asset=db.one("SELECT * FROM media_assets WHERE id=? AND product_id=? FOR UPDATE",assetId,productId);
        completeObjects(assetId,asset,input);
        // Serialize selection of the first image with other completions for this product.
        db.one("SELECT id FROM products WHERE id=? FOR UPDATE",productId);
        db.execute("UPDATE media_assets SET status='READY',ready_at=now(),is_primary=NOT EXISTS(SELECT 1 FROM media_assets WHERE product_id=? AND status='READY' AND is_primary) WHERE id=?",productId,assetId);
        return asset(productId,assetId);
    }

    public Map<String,Object> completeHome(UUID assetId,CompleteUpload input) {
        requireConfigured();
        var asset=db.one("SELECT * FROM media_assets WHERE id=? AND page_key='home' FOR UPDATE",assetId);
        completeObjects(assetId,asset,input);
        db.execute("UPDATE media_assets SET status='READY',ready_at=now(),is_primary=false WHERE id=?",assetId);
        return homeAsset(assetId);
    }

    public Map<String,Object> startStagedProduct(UUID productId,String filename,String altText) {
        requireConfigured();db.one("SELECT id FROM products WHERE id=?",productId);
        return startOwned(productId,null,new StartUpload(filename,altText));
    }

    public Map<String,Object> startStagedHome(String filename,String altText) {
        return startStagedHome(filename,altText,null);
    }

    public Map<String,Object> startStagedHome(String filename,String altText,String role) {
        requireConfigured();return startOwned(null,"home",new StartUpload(filename,altText,role));
    }

    public Map<String,Object> uploadTargets(UUID assetId) {
        requireConfigured();
        var owner=db.one("SELECT page_key,status FROM media_assets WHERE id=? FOR UPDATE",assetId);
        boolean home=owner.get("page_key")!=null;
        if(home&&!"PENDING".equals(owner.get("status")))
            throw Problem.conflict("HOME_MEDIA_UPLOAD_CLOSED","La imagen de portada ya no admite cargas.");
        var rows=db.rows("SELECT variant,object_key FROM media_objects WHERE asset_id=? ORDER BY variant",assetId);
        if(rows.size()!=VARIANTS.size())throw Problem.missing();
        var uploads=new ArrayList<Map<String,Object>>();
        for(var row:rows){String key=String.valueOf(row.get("object_key"));uploads.add(Map.of("variant",String.valueOf(row.get("variant")),"objectKey",key,"uploadUrl",presign("PUT",key,"image/webp"),"contentType","image/webp"));}
        if(home)rememberUploadExpiry(assetId);
        return Map.of("assetId",assetId,"uploads",uploads);
    }

    private void rememberUploadExpiry(UUID assetId) {
        db.execute("UPDATE media_assets SET upload_expires_at=? WHERE id=?",Instant.now().plusSeconds(expirySeconds),assetId);
    }

    public Map<String,Object> completeStagedProduct(UUID productId,UUID assetId,CompleteUpload input) {
        var asset=db.one("SELECT * FROM media_assets WHERE id=? AND product_id=? FOR UPDATE",assetId,productId);
        completeObjects(assetId,asset,input);db.execute("UPDATE media_assets SET status='STAGED',ready_at=now(),is_primary=false WHERE id=?",assetId);
        return stagedAsset(assetId);
    }

    public Map<String,Object> completeStagedHome(UUID assetId,CompleteUpload input) {
        var asset=db.one("SELECT * FROM media_assets WHERE id=? AND page_key='home' FOR UPDATE",assetId);
        completeObjects(assetId,asset,input);db.execute("UPDATE media_assets SET status='STAGED',ready_at=now(),is_primary=false WHERE id=?",assetId);
        return stagedAsset(assetId);
    }

    public Map<String,Object> stagedAsset(UUID assetId) {
        var asset=db.one("SELECT id,product_id,page_key,status,alt_text,home_role AS role FROM media_assets WHERE id=? AND status IN ('STAGED','READY')",assetId);
        var objects=db.rows("SELECT variant,object_key,width,height,byte_size FROM media_objects WHERE asset_id=? ORDER BY variant",assetId);
        var urls=new LinkedHashMap<String,String>();for(var object:objects)urls.put(String.valueOf(object.get("variant")),publicUrl(String.valueOf(object.get("object_key"))));
        asset.put("urls",urls);asset.put("objects",objects);return asset;
    }

    public void promoteHome(UUID assetId) {db.execute("UPDATE media_assets SET status='READY' WHERE id=? AND page_key='home' AND status='STAGED'",assetId);}
    public void resetStaged(UUID assetId) {
        if(db.execute("UPDATE media_assets SET status='PENDING',ready_at=NULL WHERE id=? AND status IN ('PENDING','STAGED')",assetId)!=1)
            throw Problem.conflict("AI_RESULT_NOT_REGENERABLE","El resultado ya no puede regenerarse.");
        db.execute("UPDATE media_objects SET width=NULL,height=NULL,byte_size=NULL WHERE asset_id=?",assetId);
    }
    public void promoteProduct(UUID productId) {
        db.execute("UPDATE media_assets m SET status='READY' WHERE m.product_id=? AND m.status='STAGED' AND EXISTS(SELECT 1 FROM ai_studio_results r WHERE r.adopted_asset_id=m.id AND r.status='APPLIED')",productId);
        db.execute("UPDATE media_assets SET is_primary=true WHERE id=(SELECT id FROM media_assets WHERE product_id=? AND status='READY' ORDER BY is_primary DESC,display_order,created_at LIMIT 1) AND NOT EXISTS(SELECT 1 FROM media_assets WHERE product_id=? AND status='READY' AND is_primary)",productId,productId);
    }

    public String aiPutUrl(String key,String contentType){requireConfigured();return presign("PUT",key,contentType);}
    public String aiGetUrl(String key){requireConfigured();return presign("GET",key,null);}
    public String aiAssetGetUrl(UUID assetId,String variant){var row=db.one("SELECT object_key FROM media_objects WHERE asset_id=? AND variant=?",assetId,variant);return aiGetUrl(String.valueOf(row.get("object_key")));}
    public ObjectMetadata aiHead(String key){requireConfigured();return headObject(key);}

    private void completeObjects(UUID assetId,Map<String,Object> asset,CompleteUpload input) {
        if(!"PENDING".equals(asset.get("status"))) throw Problem.conflict("MEDIA_ALREADY_COMPLETED","La carga ya fue finalizada.");
        var supplied=new HashMap<String,UploadedObject>();
        if(input==null||input.objects()==null||input.objects().size()!=VARIANTS.size())
            throw new Problem(400,"MISSING_MEDIA_VARIANT","Completa las cuatro variantes WebP.");
        for(var object:input.objects()) {
            if(object==null||object.width()<1||object.width()>10000||object.height()<1||object.height()>10000||object.byteSize()<1||object.byteSize()>10_485_760)
                throw new Problem(400,"INVALID_MEDIA_OBJECT","Revisa el tamaño y las dimensiones de la imagen.");
            if(supplied.put(object.variant(),object)!=null)
            throw new Problem(400,"DUPLICATE_MEDIA_VARIANT","Cada variante debe aparecer una sola vez.");
        }
        if(!supplied.keySet().equals(new HashSet<>(VARIANTS)))
            throw new Problem(400,"MISSING_MEDIA_VARIANT","Completa las cuatro variantes WebP.");
        validateHomeDimensions((String)asset.get("home_role"),supplied);
        var objects=db.rows("SELECT variant,object_key FROM media_objects WHERE asset_id=?",assetId);
        if(objects.size()!=VARIANTS.size())throw Problem.conflict("MEDIA_UPLOAD_INCOMPLETE","La carga no contiene todos los archivos esperados.");
        // The browser supplies dimensions, but never decides whether an object exists in R2.
        // Verify every object before making any asset visible in the storefront.
        for(var object:objects) {
            String variant=String.valueOf(object.get("variant"));
            validateObjectMetadata(supplied.get(variant),headObject(String.valueOf(object.get("object_key"))));
        }
        for(String variant:VARIANTS) {
            var value=supplied.get(variant);
            db.execute("UPDATE media_objects SET width=?,height=?,byte_size=? WHERE asset_id=? AND variant=?",
                value.width(),value.height(),value.byteSize(),assetId,variant);
        }
    }

    public Map<String,Object> update(UUID productId,UUID assetId,UpdateMedia input) {
        var current=db.one("SELECT * FROM media_assets WHERE id=? AND product_id=? AND status='READY' FOR UPDATE",assetId,productId);
        String alt=input.altText()==null?(String)current.get("alt_text"):input.altText().trim();
        if(Boolean.TRUE.equals(input.primary())) {
            db.execute("UPDATE media_assets SET is_primary=false WHERE product_id=? AND status='READY' AND is_primary",productId);
            db.execute("UPDATE media_assets SET alt_text=?,is_primary=true WHERE id=?",alt,assetId);
        } else db.execute("UPDATE media_assets SET alt_text=? WHERE id=?",alt,assetId);
        return asset(productId,assetId);
    }

    public List<Map<String,Object>> reorder(UUID productId,MediaOrder input) {
        db.one("SELECT id FROM products WHERE id=?",productId);
        var current=db.rows("SELECT id FROM media_assets WHERE product_id=? AND status='READY'",productId)
            .stream().map(row->Db.id(row,"id")).collect(java.util.stream.Collectors.toSet());
        var requested=new LinkedHashSet<>(input.assetIds());
        if(requested.size()!=input.assetIds().size()||!requested.equals(current))
            throw new Problem(400,"INVALID_MEDIA_ORDER","El orden debe incluir cada imagen disponible una sola vez.");
        for(int index=0;index<input.assetIds().size();index++)
            db.execute("UPDATE media_assets SET display_order=? WHERE id=? AND product_id=?",index,input.assetIds().get(index),productId);
        return list(productId);
    }

    public void delete(UUID productId,UUID assetId) {
        requireConfigured();
        var current=db.one("SELECT * FROM media_assets WHERE id=? AND product_id=? AND status='READY' FOR UPDATE",assetId,productId);
        var objects=db.rows("SELECT object_key FROM media_objects WHERE asset_id=?",assetId);
        for(var object:objects) deleteObject(String.valueOf(object.get("object_key")));
        db.execute("UPDATE media_assets SET status='DELETED',is_primary=false WHERE id=?",assetId);
        if(Boolean.TRUE.equals(current.get("is_primary"))) db.execute("UPDATE media_assets SET is_primary=true WHERE id=(SELECT id FROM media_assets WHERE product_id=? AND status='READY' ORDER BY display_order,created_at LIMIT 1)",productId);
    }

    public List<Map<String,Object>> list(UUID productId) {
        db.one("SELECT id FROM products WHERE id=?",productId);
        return db.rows("SELECT id FROM media_assets WHERE product_id=? AND status='READY' ORDER BY display_order,created_at",productId)
            .stream().map(row->asset(productId,Db.id(row,"id"))).toList();
    }

    private Map<String,Object> asset(UUID productId,UUID assetId) {
        var asset=db.one("SELECT * FROM media_assets WHERE id=? AND product_id=?",assetId,productId);
        var objects=db.rows("SELECT variant,object_key,width,height,byte_size FROM media_objects WHERE asset_id=? ORDER BY variant",assetId);
        var urls=new LinkedHashMap<String,String>();
        for(var object:objects) urls.put(String.valueOf(object.get("variant")),publicUrl(String.valueOf(object.get("object_key"))));
        asset.put("urls",urls);asset.put("objects",objects);return asset;
    }

    /** Only READY assets owned by the homepage can be referenced by a page draft or revision. */
    public Map<String,Object> homeAsset(UUID assetId) {
        var asset=db.one("SELECT id,alt_text,is_primary,home_role AS role FROM media_assets WHERE id=? AND page_key='home' AND status='READY'",assetId);
        var urls=new LinkedHashMap<String,String>();
        var objects=db.rows("SELECT variant,object_key,width,height,byte_size FROM media_objects WHERE asset_id=? ORDER BY variant",assetId);
        for(var object:objects)
            urls.put(String.valueOf(object.get("variant")),publicUrl(String.valueOf(object.get("object_key"))));
        asset.put("urls",urls);asset.put("objects",objects);return asset;
    }

    void requireHomeRole(UUID assetId,String expected) {
        var asset=db.one("SELECT home_role FROM media_assets WHERE id=? AND page_key='home' AND status='READY'",assetId);
        Object role=asset.get("home_role");
        if(role!=null&&!expected.equals(role))
            throw new Problem(400,"HOME_MEDIA_ROLE_MISMATCH","La imagen no corresponde al tamaño de escritorio o celular de esta diapositiva.");
    }

    static void validateHomeDimensions(String role,Map<String,UploadedObject> objects) {
        if(role==null)return;
        int[][] dimensions=switch(role) {
            case "DESKTOP" -> new int[][]{{2160,720},{1200,400},{720,240},{320,107}};
            case "MOBILE" -> new int[][]{{1024,1280},{960,1200},{720,900},{320,400}};
            default -> throw new Problem(400,"INVALID_HOME_MEDIA_ROLE","Elige una imagen para escritorio o celular.");
        };
        for(int index=0;index<VARIANTS.size();index++) {
            String variant=VARIANTS.get(index);
            var object=objects.get(variant);
            int width=dimensions[index][0],height=dimensions[index][1];
            if(object==null||object.width()!=width||object.height()!=height)
                throw new Problem(400,"HOME_MEDIA_DIMENSIONS_INVALID","La variante "+variant+" debe medir "+width+" × "+height+" píxeles.");
        }
    }

    record ObjectMetadata(String contentType,long byteSize) {}

    static void validateObjectMetadata(UploadedObject supplied,ObjectMetadata remote) {
        if(supplied==null||remote==null||remote.contentType()==null
            ||!"image/webp".equalsIgnoreCase(remote.contentType().split(";",2)[0].trim())
            ||remote.byteSize()<1||remote.byteSize()>10_485_760||remote.byteSize()!=supplied.byteSize())
            throw Problem.conflict("MEDIA_OBJECT_MISMATCH","El archivo subido no coincide con la imagen WebP esperada. Vuelve a cargarla.");
    }

    ObjectMetadata headObject(String key) {
        try {
            var request=HttpRequest.newBuilder(URI.create(presign("HEAD",key,null)))
                .method("HEAD",HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build();
            var response=client().send(request,HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()==404)throw Problem.conflict("MEDIA_UPLOAD_INCOMPLETE","Termina de subir las cuatro imágenes antes de confirmar.");
            if(response.statusCode()<200||response.statusCode()>=300)
                throw new Problem(502,"MEDIA_STORAGE_UNAVAILABLE","No se pudo verificar la carga en el almacenamiento. Inténtalo de nuevo.");
            return new ObjectMetadata(response.headers().firstValue("Content-Type").orElse(null),response.headers().firstValueAsLong("Content-Length").orElse(-1));
        } catch(Problem e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(502,"MEDIA_STORAGE_UNAVAILABLE","Se interrumpió la verificación de la carga.");}
        catch(Exception e){throw new Problem(502,"MEDIA_STORAGE_UNAVAILABLE","No se pudo verificar la carga en el almacenamiento. Inténtalo de nuevo.");}
    }

    private HttpClient client() {
        var current=client;
        if(current==null)synchronized(this){current=client;if(current==null)client=current=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();}
        return current;
    }

    private void requireConfigured() {
        if(!enabled||accessKey.filter(value->!value.isBlank()).isEmpty()||secretKey.filter(value->!value.isBlank()).isEmpty())
            throw new Problem(503,"MEDIA_STORAGE_DISABLED","Cloudflare R2 todavía no está configurado.");
        if(expirySeconds<60||expirySeconds>3600) throw new IllegalStateException("R2 upload expiry must be between 60 and 3600 seconds");
    }

    String publicUrl(String key) { return publicBaseUrl.replaceAll("/+$","")+"/"+path(key); }

    private String presign(String method,String key,String contentType) {
        try {
            URI base=URI.create(endpoint.replaceAll("/+$",""));
            Instant now=Instant.now();String date=DATE.format(now),amzDate=AMZ_DATE.format(now);
            String scope=date+"/auto/s3/aws4_request";
            String canonicalUri="/"+path(bucket)+"/"+path(key);
            var query=new TreeMap<String,String>();
            query.put("X-Amz-Algorithm","AWS4-HMAC-SHA256");
            String configuredAccessKey=accessKey.orElseThrow();
            String configuredSecretKey=secretKey.orElseThrow();
            query.put("X-Amz-Credential",configuredAccessKey+"/"+scope);
            query.put("X-Amz-Date",amzDate);query.put("X-Amz-Expires",String.valueOf(expirySeconds));
            String signedHeaders=contentType!=null?"content-type;host":"host";
            query.put("X-Amz-SignedHeaders",signedHeaders);
            String canonicalQuery=query.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
            String headers=(contentType!=null?"content-type:"+contentType+"\n":"")+"host:"+base.getAuthority()+"\n";
            String request=method+"\n"+canonicalUri+"\n"+canonicalQuery+"\n"+headers+"\n"+signedHeaders+"\nUNSIGNED-PAYLOAD";
            String toSign="AWS4-HMAC-SHA256\n"+amzDate+"\n"+scope+"\n"+hex(sha256(request.getBytes(StandardCharsets.UTF_8)));
            byte[] keyDate=hmac(("AWS4"+configuredSecretKey).getBytes(StandardCharsets.UTF_8),date);
            byte[] keyRegion=hmac(keyDate,"auto"),keyService=hmac(keyRegion,"s3"),keySigning=hmac(keyService,"aws4_request");
            String signature=hex(hmac(keySigning,toSign));
            return base+canonicalUri+"?"+canonicalQuery+"&X-Amz-Signature="+signature;
        } catch(Exception e) { throw new IllegalStateException("No se pudo firmar la carga a R2",e); }
    }

    private void deleteObject(String key) {
        try {
            var request=HttpRequest.newBuilder(URI.create(presign("DELETE",key,null))).DELETE().timeout(Duration.ofSeconds(15)).build();
            int status=client().send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
            if(status!=404&&(status<200||status>=300)) throw new IllegalStateException("R2 rechazó la eliminación con estado "+status);
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Se interrumpió la eliminación en R2",e); }
        catch(Exception e) { throw new IllegalStateException("No se pudo eliminar el objeto de R2",e); }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void purgeHomeObject(String key) {
        requireConfigured();
        if(!key.startsWith("storefront/home/"))throw new IllegalArgumentException("Clave de portada inválida");
        try {
            var deletion=HttpRequest.newBuilder(URI.create(presign("DELETE",key,null)))
                .DELETE().timeout(Duration.ofSeconds(15)).build();
            int status=client().send(deletion,HttpResponse.BodyHandlers.discarding()).statusCode();
            // DELETE is idempotent for the purge queue: an object removed by a previous attempt is already in the desired state.
            if(status!=404&&(status<200||status>=300))throw new IllegalStateException("R2 no confirmó la eliminación");
            var check=HttpRequest.newBuilder(URI.create(presign("HEAD",key,null)))
                .method("HEAD",HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build();
            if(client().send(check,HttpResponse.BodyHandlers.discarding()).statusCode()!=404)
                throw new IllegalStateException("R2 no confirmó que el objeto está ausente");
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la eliminación en R2",error);
        } catch(java.io.IOException error) {
            throw new IllegalStateException("No se pudo verificar la eliminación en R2",error);
        }
    }

    private static String path(String value) { return Arrays.stream(value.split("/",-1)).map(MediaService::encode).reduce((a,b)->a+"/"+b).orElse(""); }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("%7E","~"); }
    private static byte[] sha256(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value); }
    private static byte[] hmac(byte[] key,String value) throws Exception { return hmac(key,value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hmac(byte[] key,byte[] value) throws Exception { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value); }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
}
