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
}
