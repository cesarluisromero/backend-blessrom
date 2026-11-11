package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class ProductSuggestionService {
    @Inject Db db;
    @Inject Json json;
    @Inject MediaService media;
    @Inject AiStudioService studio;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Map<String,Object> suggest(UUID uploadId,String actor) {
        if(!Boolean.TRUE.equals(studio.status().get("configured")))
            throw new Problem(503,"AI_STUDIO_UNAVAILABLE","El asistente IA no está disponible en este momento.");
        var source=db.one("SELECT object_key FROM ai_studio_uploads WHERE id=? AND created_by=? AND product_id IS NULL AND status='READY' AND expires_at>now()",uploadId,actor);
        var categories=db.rows("SELECT id,name FROM categories WHERE active=true AND selectable=true ORDER BY display_order,name");
        var colors=db.rows("SELECT o.value,o.label FROM attribute_options o JOIN attribute_definitions d ON d.id=o.attribute_id WHERE d.code='color' AND o.active=true AND o.selectable=true ORDER BY o.display_order,o.label");
        var request=Map.of("imageUrl",media.aiGetUrl(String.valueOf(source.get("object_key"))),"categories",categories,"colors",colors);
        try {
            var call=HttpRequest.newBuilder(URI.create(studio.workerUrl().replaceAll("/+$","")+"/suggest-product"))
                .timeout(Duration.ofSeconds(90)).header("Authorization","Bearer "+studio.workerToken())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(request))).build();
            var response=client.send(call,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new Problem(502,"AI_SUGGESTION_FAILED","No se pudieron obtener sugerencias. Puedes continuar manualmente.");
            @SuppressWarnings("unchecked") var result=(Map<String,Object>)json.read(response.body(),Map.class);
            Set<String> categoryIds=new HashSet<>(),colorValues=new HashSet<>();
            for(var row:categories)categoryIds.add(String.valueOf(row.get("id")));
            for(var row:colors)colorValues.add(String.valueOf(row.get("value")));
            String category=Objects.toString(result.get("categoryId"),""),color=Objects.toString(result.get("color"),"");
            return Map.of("name",limit(result.get("name"),180),"description",limit(result.get("description"),5000),
                "categoryId",categoryIds.contains(category)?category:"","color",colorValues.contains(color)?color:"");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(503,"AI_SUGGESTION_INTERRUPTED","El asistente fue interrumpido. Puedes continuar manualmente.");}
        catch(java.io.IOException e){throw new Problem(503,"AI_SUGGESTION_UNAVAILABLE","El asistente no respondió. Puedes continuar manualmente.");}
    }
    private String limit(Object value,int length){String text=Objects.toString(value,"").trim();return text.substring(0,Math.min(text.length(),length));}
}
