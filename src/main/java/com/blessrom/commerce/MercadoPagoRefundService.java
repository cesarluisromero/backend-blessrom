package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class MercadoPagoRefundService {
    private static final Logger LOG=Logger.getLogger(MercadoPagoRefundService.class);
    @Inject Json json;
    @ConfigProperty(name="blessrom.payments.mercado-pago.enabled") boolean enabled;
    @ConfigProperty(name="blessrom.payments.mercado-pago.access-token") Optional<String> accessToken;
    private volatile HttpClient client;

    public record Result(String id,String status,BigDecimal amount) {}

    public Result refund(String providerOrderId,String transactionId,BigDecimal amount,UUID returnId) {
        requireEnabled();
        if(providerOrderId==null||providerOrderId.isBlank()||transactionId==null||transactionId.isBlank())
            throw Problem.conflict("REFUND_PAYMENT_REFERENCE_MISSING","El pago de Mercado Pago no contiene las referencias necesarias para reembolsarlo.");
        BigDecimal normalized=amount.setScale(2);
        if(normalized.signum()<=0) throw Problem.conflict("INVALID_REFUND_AMOUNT","El importe del reembolso debe ser mayor a cero.");
        Map<String,Object> body=Map.of("transactions",List.of(Map.of("id",transactionId,"amount",normalized.toPlainString())));
        try {
            var request=HttpRequest.newBuilder(URI.create("https://api.mercadopago.com/v1/orders/"+encode(providerOrderId)+"/refund"))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization",bearer())
                .header("X-Idempotency-Key","blessrom-return-"+returnId)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build();
            var response=client().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)
                throw new IllegalStateException("HTTP "+response.statusCode()+" body="+truncate(response.body()));
            return parse(json.read(response.body(),Map.class),normalized);
        } catch(Problem e) { throw e; }
        catch(Exception e) {
            LOG.warnf("Mercado Pago rechazó el reembolso de la devolución %s: %s",returnId,truncate(e.getMessage()));
            throw new Problem(502,"REFUND_PROVIDER_UNAVAILABLE","Mercado Pago no pudo procesar el reembolso. No se modificó la devolución; inténtalo nuevamente.");
        }
    }

    @SuppressWarnings("unchecked")
    static Result parse(Map<String,Object> response,BigDecimal expected) {
        Object transactions=response.get("transactions");
        if(!(transactions instanceof Map<?,?> transactionMap)) throw invalidResponse();
        Object refunds=transactionMap.get("refunds");
        if(!(refunds instanceof List<?> list)||list.isEmpty()||!(list.getFirst() instanceof Map<?,?> refund)) throw invalidResponse();
        String id=value(refund.get("id")),status=value(refund.get("status"));
        BigDecimal amount;
        try { amount=new BigDecimal(value(refund.get("amount"))).setScale(2); }
        catch(Exception e) { throw invalidResponse(); }
        if(id==null||status==null||!"processed".equalsIgnoreCase(status)||amount.compareTo(expected.setScale(2))!=0) throw invalidResponse();
        return new Result(id,status.toLowerCase(Locale.ROOT),amount);
    }

    private void requireEnabled(){if(!enabled||accessToken.isEmpty()||accessToken.get().isBlank())throw new Problem(503,"PAYMENT_NOT_CONFIGURED","Mercado Pago todavía no está habilitado.");}
    private String bearer(){return "Bearer "+accessToken.orElseThrow();}
    private HttpClient client(){var current=client;if(current==null)synchronized(this){current=client;if(current==null)client=current=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();}return current;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String value(Object value){return value==null||value.toString().isBlank()?null:value.toString();}
    private static Problem invalidResponse(){return new Problem(502,"INVALID_REFUND_RESPONSE","Mercado Pago devolvió una respuesta de reembolso inválida. Verifica la operación antes de reintentar.");}
    private static String truncate(String value){if(value==null)return "sin detalle";String clean=value.replaceAll("[\\r\\n\\t]+"," ");return clean.length()>1200?clean.substring(0,1200):clean;}
}
