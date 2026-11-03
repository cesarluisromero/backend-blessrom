package com.blessrom.commerce;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class HomeIntegrationTest {
    @Inject HomeService home;
    @Inject MediaService media;
    @Inject Db db;

    private HomeService.HomeContent content(String title) {
        return new HomeService.HomeContent("Tarapoto",title,"Moda para cada día",null,"",50,50,
            new HomeService.Action("Ver catálogo",new HomeService.Target("catalog",null)),null,List.of());
    }

    @Test @TestTransaction
    @SuppressWarnings("unchecked")
    void draftsStayPrivateAndPublicationUsesAnImmutableRevision() {
        db.execute("UPDATE storefront_pages SET draft_content=NULL,published_revision_id=NULL,published_at=NULL,version=0 WHERE page_key='home'");
        assertNull(home.publicHome());
        var draft=home.saveDraft(new HomeService.SaveDraft(0,content("Portada inicial")),"test");
        assertEquals(1L,((Number)draft.get("version")).longValue());
        assertNull(home.publicHome(),"El borrador no puede aparecer en la tienda.");
        var published=home.publish(1,"test");
        UUID revision=(UUID)published.get("publishedRevisionId");
        assertNotNull(revision);assertNull(published.get("draft"));
        assertEquals("Portada inicial",((HomeService.HomeContent)home.publicHome().get("content")).title());
        home.saveDraft(new HomeService.SaveDraft(2,content("Texto aún sin publicar")),"test");
        assertEquals(revision,home.publicHome().get("revisionId"));
        assertEquals("Portada inicial",((HomeService.HomeContent)home.publicHome().get("content")).title());
        assertEquals(1,((Number)db.one("SELECT count(*) AS n FROM storefront_revisions WHERE id=?",revision).get("n")).intValue());
    }

    @Test @TestTransaction
    void staleSavesCannotOverwriteTheCurrentDraft() {
        long version=Db.version(db.one("SELECT version FROM storefront_pages WHERE page_key='home'"));
        home.saveDraft(new HomeService.SaveDraft(version,content("Cambio más reciente")),"test");
        assertEquals("VERSION_CONFLICT",assertThrows(Problem.class,()->home.saveDraft(new HomeService.SaveDraft(version,content("Cambio antiguo")),"test")).code);
        assertEquals("Cambio más reciente",((HomeService.HomeContent)home.admin().get("draft")).title());
    }

    @Test @TestTransaction
    void unpublishedOrForeignAssetsCannotBeUsedForTheHomePage() {
        UUID id=UUID.randomUUID();
        db.execute("INSERT INTO media_assets(id,page_key,status,original_filename) VALUES(?,'home','PENDING','upload.webp')",id);
        assertThrows(Problem.class,()->media.homeAsset(id));
    }

    @Test void privateEditorRequiresAuthentication() {
        given().get("/api/v1/admin/storefront/home").then().statusCode(401).header("Cache-Control","no-store");
    }
}
