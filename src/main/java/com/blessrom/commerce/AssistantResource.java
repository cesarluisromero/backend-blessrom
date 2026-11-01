package com.blessrom.commerce;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Path("/api/v1/assistant") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
@Tag(name="Asistente de compra")
public class AssistantResource {
    @Inject AssistantService assistant;
    @Context HttpHeaders headers;
    private static final ConcurrentHashMap<String,Window> REQUESTS=new ConcurrentHashMap<>();

    @POST @Path("/messages") public Object message(@Valid Api.AssistantRequest request) {
        limit(client());
        return assistant.reply(request);
    }

    private String client() {
        String value=headers.getHeaderString("X-Forwarded-For");
        if(value==null||value.isBlank()) value=headers.getHeaderString("X-Real-IP");
        return value==null||value.isBlank()?"unknown":value.split(",",2)[0].trim();
    }
    private static void limit(String client) {
        long minute=Instant.now().getEpochSecond()/60;
        Window window=REQUESTS.compute(client,(key,old)->old==null||old.minute()!=minute?new Window(minute,1):new Window(minute,old.count()+1));
        if(window.count()>20) throw new Problem(429,"ASSISTANT_RATE_LIMIT","Has enviado muchas consultas. Intenta nuevamente en un minuto.");
        if(REQUESTS.size()>5000) REQUESTS.entrySet().removeIf(entry->entry.getValue().minute()<minute-2);
    }
    private record Window(long minute,int count) {}
}
