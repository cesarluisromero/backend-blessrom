package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class EmbeddingService {
    private static final Logger LOG=Logger.getLogger(EmbeddingService.class);
    @Inject Db db; @Inject Json json;
    @ConfigProperty(name="blessrom.ai.enabled") boolean enabled;
    @ConfigProperty(name="blessrom.ai.api-key") Optional<String> apiKey;
    @ConfigProperty(name="blessrom.ai.embedding-model") String model;
    @ConfigProperty(name="blessrom.ai.embedding-dimensions") int dimensions;
    @ConfigProperty(name="blessrom.ai.embeddings-endpoint") String endpoint;
    private volatile HttpClient client;

    @Scheduled(every="${blessrom.ai.embedding-sync-every:10m}",delayed="60s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void scheduledSync() {
        try { if(configured()) syncPending(50); }
        catch(Exception error) { LOG.warnf("No se pudo sincronizar el índice vectorial: %s",safe(error)); }
    }

    public Map<String,Object> status() {
        long indexed=((Number)db.one("SELECT count(*) AS total FROM product_embeddings").get("total")).longValue();
        long pending=((Number)db.one("""
            SELECT count(*) AS total FROM products p
            WHERE p.archived=false AND p.published_revision IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM product_embeddings e WHERE e.product_id=p.id AND e.revision_id=p.published_revision)
            """).get("total")).longValue();
        return Map.of("enabled",configured(),"model",model,"dimensions",dimensions,"indexed",indexed,"pending",pending);
    }

    public Map<String,Object> syncPending(int requestedLimit) {
        requireConfigured();
        int limit=Math.max(1,Math.min(requestedLimit,100));
        var rows=db.rows("""
            SELECT p.id,p.published_revision,concat_ws(E'\n',pr.content->>'name',pr.content->>'description',pr.content::text) AS embedding_text
            FROM products p JOIN product_revisions pr ON pr.id=p.published_revision
            LEFT JOIN product_embeddings e ON e.product_id=p.id AND e.revision_id=p.published_revision
            WHERE p.archived=false AND e.product_id IS NULL
            ORDER BY pr.created_at LIMIT ?
            """,limit);
        if(rows.isEmpty()) return Map.of("indexed",0,"remaining",0);
        List<String> inputs=rows.stream().map(row->row.get("embedding_text").toString()).toList();
        List<double[]> vectors=embeddings(inputs);
        if(vectors.size()!=rows.size()) throw new IllegalStateException("Cantidad de embeddings inesperada");
        for(int index=0;index<rows.size();index++) {
            var row=rows.get(index);
            db.execute("""
                INSERT INTO product_embeddings(product_id,revision_id,embedding_model,embedding,updated_at)
                VALUES(?,?,?,CAST(? AS vector),now())
                ON CONFLICT(product_id) DO UPDATE SET revision_id=EXCLUDED.revision_id,
                    embedding_model=EXCLUDED.embedding_model,embedding=EXCLUDED.embedding,updated_at=now()
                """,row.get("id"),row.get("published_revision"),model,literal(vectors.get(index)));
        }
        long remaining=((Number)status().get("pending")).longValue();
        LOG.infof("Índice vectorial actualizado: %d productos; pendientes: %d",rows.size(),remaining);
        return Map.of("indexed",rows.size(),"remaining",remaining);
    }

    public List<UUID> semanticProductIds(String query,int requestedLimit) {
        if(!configured()||query==null||query.isBlank()) return List.of();
        long count=((Number)db.one("SELECT count(*) AS total FROM product_embeddings").get("total")).longValue();
        if(count==0) return List.of();
        try {
            double[] vector=embeddings(List.of(query)).getFirst();
            int limit=Math.max(1,Math.min(requestedLimit,20));
            return db.rows("""
                SELECT e.product_id FROM product_embeddings e
                JOIN products p ON p.id=e.product_id
                WHERE p.archived=false AND p.published_revision=e.revision_id
                  AND EXISTS (SELECT 1 FROM variants v JOIN inventory i ON i.variant_id=v.id
                              WHERE v.product_id=p.id AND i.on_hand>i.reserved)
                ORDER BY e.embedding <=> CAST(? AS vector) LIMIT ?
                """,literal(vector),limit).stream().map(row->(UUID)row.get("product_id")).toList();
        } catch(Exception error) {
            LOG.warnf("Búsqueda vectorial no disponible; se usará búsqueda local: %s",safe(error));
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<double[]> embeddings(List<String> inputs) {
        var body=new LinkedHashMap<String,Object>();
        body.put("model",model);body.put("input",inputs);body.put("encoding_format","float");body.put("dimensions",dimensions);
        try {
            var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(25))
                .header("Authorization","Bearer "+apiKey.orElseThrow()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build();
            var response=client().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300) throw new IllegalStateException("OpenAI HTTP "+response.statusCode());
            Map<String,Object> parsed=json.read(response.body(),Map.class);
            Object raw=parsed.get("data");if(!(raw instanceof List<?> data))throw new IllegalStateException("Respuesta de embeddings inválida");
            var ordered=new TreeMap<Integer,double[]>();
            for(Object entry:data) {
                if(!(entry instanceof Map<?,?> item)||!(item.get("embedding") instanceof List<?> values))continue;
                int index=((Number)item.get("index")).intValue();double[] vector=new double[values.size()];
                for(int i=0;i<values.size();i++)vector[i]=((Number)values.get(i)).doubleValue();
                if(vector.length!=dimensions)throw new IllegalStateException("Dimensión de embedding inválida");
                ordered.put(index,vector);
            }
            return new ArrayList<>(ordered.values());
        } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException("Consulta de embeddings interrumpida",error); }
        catch(Exception error) { throw new IllegalStateException("No se pudieron generar embeddings: "+safe(error),error); }
    }

    private boolean configured(){return enabled&&apiKey.isPresent()&&!apiKey.get().isBlank()&&dimensions==1536;}
    private void requireConfigured(){if(!configured())throw new Problem(503,"EMBEDDINGS_NOT_CONFIGURED","La indexación vectorial no está configurada.");}
    private static String literal(double[] vector){var text=new StringBuilder("[");for(int i=0;i<vector.length;i++){if(i>0)text.append(',');text.append(Double.toString(vector[i]));}return text.append(']').toString();}
    private HttpClient client(){var current=client;if(current==null)synchronized(this){current=client;if(current==null)client=current=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();}return current;}
    private static String safe(Exception error){String message=error.getMessage();if(message==null)return error.getClass().getSimpleName();message=message.replaceAll("[\\r\\n\\t]+"," ");return message.length()>300?message.substring(0,300):message;}
}
