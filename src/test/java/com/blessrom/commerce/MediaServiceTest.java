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

    @Test void eachHomeRoleRequiresItsFourExactDimensions() {
        var desktop=new HashMap<>(Map.of(
            "original",image("original",2160,720),"large",image("large",1200,400),
            "medium",image("medium",720,240),"thumbnail",image("thumbnail",320,107)));
        var mobile=Map.of(
            "original",image("original",1024,1280),"large",image("large",960,1200),
            "medium",image("medium",720,900),"thumbnail",image("thumbnail",320,400));
        assertDoesNotThrow(()->MediaService.validateHomeDimensions("DESKTOP",desktop));
        assertDoesNotThrow(()->MediaService.validateHomeDimensions("MOBILE",mobile));
        desktop.put("thumbnail",image("thumbnail",320,106));
        assertEquals("HOME_MEDIA_DIMENSIONS_INVALID",assertThrows(Problem.class,
            ()->MediaService.validateHomeDimensions("DESKTOP",desktop)).code);
        assertThrows(Problem.class,()->MediaService.validateHomeDimensions("DESKTOP",mobile));
    }

    private MediaService.UploadedObject image(String variant,int width,int height) {
        return new MediaService.UploadedObject(variant,width,height,1234);
    }
}
