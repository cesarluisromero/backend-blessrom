package com.blessrom.commerce;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class AiStudioDispatcher {
    @Inject AiStudioService studio;@Inject Json json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Scheduled(every="${blessrom.ai.poll-interval:5s}",concurrentExecution=SKIP)
    void dispatch(){Map<String,Object> work=studio.claim();if(work==null)return;UUID id=(UUID)work.get("jobId"),lease=(UUID)work.get("leaseToken");
        try{var request=HttpRequest.newBuilder(URI.create(studio.workerUrl().replaceAll("/+$","")+"/process")).timeout(Duration.ofMinutes(14)).header("Authorization","Bearer "+studio.workerToken()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(work))).build();var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Worker HTTP "+response.statusCode());studio.completeWorker(id,lease,json.read(response.body(),AiStudioService.WorkerCompletion.class));}
        catch(InterruptedException e){Thread.currentThread().interrupt();studio.failWorker(id,lease,"El procesamiento fue interrumpido.");}
        catch(Exception e){studio.failWorker(id,lease,Objects.requireNonNullElse(e.getMessage(),"El worker no pudo procesar la imagen."));}}
}
