package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaServiceTest {
    private final MediaService.UploadedObject uploaded=new MediaService.UploadedObject("large",1280,720,1234);

    @Test void completionRequiresTheRealR2MimeTypeAndLength() {
        assertDoesNotThrow(()->MediaService.validateObjectMetadata(uploaded,new MediaService.ObjectMetadata("image/webp",1234)));
        assertThrows(Problem.class,()->MediaService.validateObjectMetadata(uploaded,new MediaService.ObjectMetadata("image/png",1234)));
        assertThrows(Problem.class,()->MediaService.validateObjectMetadata(uploaded,new MediaService.ObjectMetadata("image/webp",1200)));
        assertThrows(Problem.class,()->MediaService.validateObjectMetadata(uploaded,new MediaService.ObjectMetadata("image/webp",0)));
        assertThrows(Problem.class,()->MediaService.validateObjectMetadata(uploaded,new MediaService.ObjectMetadata(null,1234)));
        assertThrows(Problem.class,()->MediaService.validateObjectMetadata(uploaded,new MediaService.ObjectMetadata("image/webp",10_485_761)));
    }

    @Test void missingRemoteObjectNeverMakesTheAssetReady() {
        class MemoryDb extends Db {
            boolean madeReady;
            @Override public Map<String,Object> one(String sql,Object... args) {return new LinkedHashMap<>(Map.of("id",args[0],"status","PENDING"));}
            @Override public List<Map<String,Object>> rows(String sql,Object... args) {
                return List.of("original","large","medium","thumbnail").stream()
                    .map(variant->new LinkedHashMap<String,Object>(Map.of("variant",variant,"object_key",variant+".webp"))).map(row->(Map<String,Object>)row).toList();
            }
            @Override public int execute(String sql,Object... args) {if(sql.contains("status='READY'"))madeReady=true;return 1;}
        }
        var db=new MemoryDb();
        var service=new MediaService(){@Override ObjectMetadata headObject(String key){throw Problem.conflict("MEDIA_UPLOAD_INCOMPLETE","Falta el archivo.");}};
        service.db=db;service.enabled=true;service.accessKey=Optional.of("test");service.secretKey=Optional.of("test");service.expirySeconds=600;
        var objects=List.of("original","large","medium","thumbnail").stream().map(variant->new MediaService.UploadedObject(variant,100,100,1234)).toList();
        assertEquals("MEDIA_UPLOAD_INCOMPLETE",assertThrows(Problem.class,()->service.completeHome(UUID.randomUUID(),new MediaService.CompleteUpload(objects))).code);
        assertFalse(db.madeReady);
    }
}
