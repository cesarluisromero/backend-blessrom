package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeServiceTest {
    private HomeService.HomeContent content(UUID asset,double x,HomeService.Target target,List<HomeService.Action> shortcuts) {
        return new HomeService.HomeContent("Desde Tarapoto","Tu próxima prenda","Prendas para cada día",asset,"Prendas sobre fondo azul",x,50,
            new HomeService.Action("Ver catálogo",target),null,shortcuts);
    }

    @Test void fixedPageAllowsOnlyTypedInternalDestinations() {
        assertDoesNotThrow(()->HomeService.validateStructure(content(null,50,new HomeService.Target("catalog",null),List.of())));
        assertDoesNotThrow(()->HomeService.validateStructure(content(null,50,new HomeService.Target("category",UUID.randomUUID()),List.of())));
        assertThrows(Problem.class,()->HomeService.validateStructure(content(null,50,new HomeService.Target("https://example.com",null),List.of())));
        assertThrows(Problem.class,()->HomeService.validateStructure(content(null,50,new HomeService.Target("category",null),List.of())));
        assertThrows(Problem.class,()->HomeService.validateStructure(content(null,50,new HomeService.Target("catalog",UUID.randomUUID()),List.of())));
    }

    @Test void encuadreAndShortcutLimitAreEnforcedBeforeSaving() {
        assertThrows(Problem.class,()->HomeService.validateStructure(content(null,Double.NaN,new HomeService.Target("catalog",null),List.of())));
        assertThrows(Problem.class,()->HomeService.validateStructure(content(null,101,new HomeService.Target("catalog",null),List.of())));
        var action=new HomeService.Action("Catálogo",new HomeService.Target("catalog",null));
        assertThrows(Problem.class,()->HomeService.validateStructure(content(null,50,new HomeService.Target("catalog",null),Collections.nCopies(7,action))));
    }

    @Test void heroImagesRequireAnAccessibleDescription() {
        var invalid=new HomeService.HomeContent("","Portada","",UUID.randomUUID()," ",50,50,
            new HomeService.Action("Catálogo",new HomeService.Target("catalog",null)),null,List.of());
        assertEquals("HOME_IMAGE_ALT_REQUIRED",assertThrows(Problem.class,()->HomeService.validateStructure(invalid)).code);
    }

    @Test void removingTheFirstSlideKeepsTheRemainingCoverAndItsImages() {
        var removed=slide(UUID.randomUUID(),UUID.randomUUID(),true);
        var kept=slide(UUID.randomUUID(),UUID.randomUUID(),true);
        var revision=revision(removed,kept);
        var deletion=HomeService.withoutPublishedSlide(revision,removed.heroAssetId());
        assertEquals(Set.of(removed.heroAssetId(),removed.mobileAssetId()),deletion.assetIds());
        assertEquals(List.of(kept),deletion.content().slides());
        assertEquals(kept.heroAssetId(),deletion.content().heroAssetId());
        assertEquals(2,revision.slides().size());
    }

    @Test void removalProtectsSharedImagesAndTheLastVisibleSlide() {
        var removed=slide(UUID.randomUUID(),UUID.randomUUID(),true);
        var shared=slide(UUID.randomUUID(),removed.mobileAssetId(),true);
        assertEquals("HOME_SLIDE_MEDIA_SHARED",assertThrows(Problem.class,
            ()->HomeService.withoutPublishedSlide(revision(removed,shared),removed.heroAssetId())).code);
        var disabled=slide(UUID.randomUUID(),null,false);
        assertEquals("HOME_LAST_SLIDE",assertThrows(Problem.class,
            ()->HomeService.withoutPublishedSlide(revision(removed,disabled),removed.heroAssetId())).code);
    }

    private HomeService.HomeSlide slide(UUID desktop,UUID mobile,boolean enabled) {
        return new HomeService.HomeSlide("Tarapoto","Portada","",desktop,"Fotografía de portada",50,50,
            new HomeService.Action("Catálogo",new HomeService.Target("catalog",null)),null,mobile,enabled);
    }

    private HomeService.HomeContent revision(HomeService.HomeSlide first,HomeService.HomeSlide second) {
        return new HomeService.HomeContent(first.eyebrow(),first.title(),first.description(),first.heroAssetId(),first.heroAlt(),
            first.frameX(),first.frameY(),first.primary(),first.secondary(),List.of(),List.of(first,second));
    }
}
