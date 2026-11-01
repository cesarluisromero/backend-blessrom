package com.blessrom.commerce;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantServiceTest {
    @Test
    void explicitColorKeepsOnlyTheBestLexicalMatch() {
        var recommendations=AssistantService.recommend("Busco algo azul",List.of(
            product("blue","Polo azul Blessrom","Polo urbano","DEMO-AZUL-M","{color=azul}","59.9"),
            product("pink","Polo Rosa Bronco","Polo urbano","DEMO-ROSA-M","{color=rosa}","50")
        ));

        assertEquals(List.of("Polo azul Blessrom"),recommendations.stream().map(item->item.get("name")).toList());
        assertEquals("59.90",recommendations.getFirst().get("price"));
    }

    @Test
    void genericSemanticQueryKeepsVectorCandidatesWhenThereIsNoLiteralMatch() {
        var recommendations=AssistantService.recommend("Busco algo fresco y elegante para clima cálido",List.of(
            product("linen","Camisa de lino","Prenda natural","LINO-M","{material=lino}","99.9"),
            product("polo","Polo azul","Prenda urbana","POLO-M","{color=azul}","59.9")
        ));

        assertEquals(2,recommendations.size());
    }

    private static Map<String,Object> product(String id,String name,String description,String sku,String attributes,String price) {
        var row=new LinkedHashMap<String,Object>();
        row.put("id",UUID.nameUUIDFromBytes(id.getBytes()));
        row.put("slug",id);
        row.put("name",name);
        row.put("description",description);
        row.put("image_url","");
        row.put("sku",sku);
        row.put("price",price);
        row.put("attributes",attributes);
        row.put("available",5);
        return row;
    }
}
