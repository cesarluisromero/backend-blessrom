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
    @ConfigProperty(name="blessrom.media.r2.access-key-id",defaultValue="") String accessKey;
    @ConfigProperty(name="blessrom.media.r2.secret-access-key",defaultValue="") String secretKey;
    @ConfigProperty(name="blessrom.media.public-base-url",defaultValue="https://media.blessrom.com") String publicBaseUrl;
    @ConfigProperty(name="blessrom.media.upload-expiry-seconds",defaultValue="600") int expirySeconds;

    public record StartUpload(@NotBlank @Size(max=255) String filename,@Size(max=300) String altText) {}
    public record UploadedObject(@NotBlank @Pattern(regexp="original|large|medium|thumbnail") String variant,
        @Min(1) @Max(10000) int width,@Min(1) @Max(10000) int height,@Min(1) @Max(10_485_760) long byteSize) {}
    public record CompleteUpload(@NotNull @Size(min=4,max=4) List<@NotNull @Valid UploadedObject> objects) {}

    public Map<String,Object> start(UUID productId,StartUpload input) {
        requireConfigured();
        db.one("SELECT id FROM products WHERE id=?",productId);
        UUID assetId=UUID.randomUUID();
        String safeName=input.filename().replaceAll("[^A-Za-z0-9._-]","_");
        db.execute("INSERT INTO media_assets(id,product_id,original_filename,alt_text) VALUES(?,?,?,?)",
            assetId,productId,safeName,input.altText());
        var uploads=new ArrayList<Map<String,Object>>();
        for(String variant:VARIANTS) {
            String key="products/"+productId+"/"+assetId+"/"+variant+".webp";
            db.execute("INSERT INTO media_objects(asset_id,variant,object_key) VALUES(?,?,?)",assetId,variant,key);
            uploads.add(Map.of("variant",variant,"objectKey",key,"uploadUrl",presignPut(key),"contentType","image/webp"));
        }
        return Map.of("assetId",assetId,"expiresIn",expirySeconds,"uploads",uploads);
    }

    public Map<String,Object> complete(UUID productId,UUID assetId,CompleteUpload input) {
        requireConfigured();
        var asset=db.one("SELECT * FROM media_assets WHERE id=? AND product_id=? FOR UPDATE",assetId,productId);
        if(!"PENDING".equals(asset.get("status"))) throw Problem.conflict("MEDIA_ALREADY_COMPLETED","La carga ya fue finalizada.");
        var supplied=new HashMap<String,UploadedObject>();
        for(var object:input.objects()) if(supplied.put(object.variant(),object)!=null)
            throw new Problem(400,"DUPLICATE_MEDIA_VARIANT","Cada variante debe aparecer una sola vez.");
        if(!supplied.keySet().equals(new HashSet<>(VARIANTS)))
            throw new Problem(400,"MISSING_MEDIA_VARIANT","Completa las cuatro variantes WebP.");
        for(String variant:VARIANTS) {
            var value=supplied.get(variant);
            db.execute("UPDATE media_objects SET width=?,height=?,byte_size=? WHERE asset_id=? AND variant=?",
                value.width(),value.height(),value.byteSize(),assetId,variant);
        }
        db.execute("UPDATE media_assets SET status='READY',ready_at=now() WHERE id=?",assetId);
        return asset(productId,assetId);
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

    private void requireConfigured() {
        if(!enabled||accessKey.isBlank()||secretKey.isBlank())
            throw new Problem(503,"MEDIA_STORAGE_DISABLED","Cloudflare R2 todavía no está configurado.");
        if(expirySeconds<60||expirySeconds>3600) throw new IllegalStateException("R2 upload expiry must be between 60 and 3600 seconds");
    }

    private String publicUrl(String key) { return publicBaseUrl.replaceAll("/+$","")+"/"+path(key); }

    private String presignPut(String key) {
        try {
            URI base=URI.create(endpoint.replaceAll("/+$",""));
            Instant now=Instant.now();String date=DATE.format(now),amzDate=AMZ_DATE.format(now);
            String scope=date+"/auto/s3/aws4_request";
            String canonicalUri="/"+path(bucket)+"/"+path(key);
            var query=new TreeMap<String,String>();
            query.put("X-Amz-Algorithm","AWS4-HMAC-SHA256");
            query.put("X-Amz-Credential",accessKey+"/"+scope);
            query.put("X-Amz-Date",amzDate);query.put("X-Amz-Expires",String.valueOf(expirySeconds));
            query.put("X-Amz-SignedHeaders","content-type;host");
            String canonicalQuery=query.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
            String headers="content-type:image/webp\n"+"host:"+base.getAuthority()+"\n";
            String request="PUT\n"+canonicalUri+"\n"+canonicalQuery+"\n"+headers+"\ncontent-type;host\nUNSIGNED-PAYLOAD";
            String toSign="AWS4-HMAC-SHA256\n"+amzDate+"\n"+scope+"\n"+hex(sha256(request.getBytes(StandardCharsets.UTF_8)));
            byte[] keyDate=hmac(("AWS4"+secretKey).getBytes(StandardCharsets.UTF_8),date);
            byte[] keyRegion=hmac(keyDate,"auto"),keyService=hmac(keyRegion,"s3"),keySigning=hmac(keyService,"aws4_request");
            String signature=hex(hmac(keySigning,toSign));
            return base+canonicalUri+"?"+canonicalQuery+"&X-Amz-Signature="+signature;
        } catch(Exception e) { throw new IllegalStateException("No se pudo firmar la carga a R2",e); }
    }

    private static String path(String value) { return Arrays.stream(value.split("/",-1)).map(MediaService::encode).reduce((a,b)->a+"/"+b).orElse(""); }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("%7E","~"); }
    private static byte[] sha256(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value); }
    private static byte[] hmac(byte[] key,String value) throws Exception { return hmac(key,value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hmac(byte[] key,byte[] value) throws Exception { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value); }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
}
