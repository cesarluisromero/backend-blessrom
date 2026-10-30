package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@ApplicationScoped @Transactional
public class MercadoPagoService {
    @Inject Db db; @Inject Json json; @Inject OrderService orders;
    private volatile HttpClient client;
    @ConfigProperty(name="blessrom.payments.mercado-pago.enabled") boolean enabled;
    @ConfigProperty(name="blessrom.payments.mercado-pago.access-token") Optional<String> accessToken;
    @ConfigProperty(name="blessrom.payments.mercado-pago.webhook-secret") Optional<String> webhookSecret;
    @ConfigProperty(name="blessrom.payments.mercado-pago.store-url") String storeUrl;
    @ConfigProperty(name="blessrom.payments.mercado-pago.webhook-url") String webhookUrl;

    @SuppressWarnings("unchecked")
    public Map<String,Object> checkout(UUID orderId,String cartToken) {
        requireEnabled();
        var order=db.one("SELECT id,status,total,currency,customer,token_hash,access_expires_at FROM orders WHERE id=? FOR UPDATE",orderId);
        Tokens.authorize(cartToken,order,"access_expires_at");
        if(!"PENDING_PAYMENT".equals(order.get("status"))) throw Problem.conflict("PAYMENT_CLOSED","El pedido ya no admite pagos.");
        var existing=db.rows("SELECT method,status,provider_reference,checkout_url FROM order_payments WHERE order_id=? FOR UPDATE",orderId);
        if(!existing.isEmpty()) {
            var payment=existing.getFirst();
            if("MERCADO_PAGO".equals(payment.get("method"))&&payment.get("checkout_url")!=null&&"PENDING_PROVIDER".equals(payment.get("status")))
                return Map.of("preferenceId",payment.get("provider_reference"),"checkoutUrl",payment.get("checkout_url"));
            throw Problem.conflict("PAYMENT_ALREADY_SELECTED","El pedido ya tiene un medio de pago registrado.");
        }
        var customer=json.read(order.get("customer"),Map.class);
        String returnUrl=storeUrl.replaceAll("/$","")+"/pedido/"+orderId;
        var item=new LinkedHashMap<String,Object>();
        item.put("id",orderId.toString());item.put("title","Pedido Blessrom "+orderId);item.put("currency_id","PEN");item.put("quantity",1);item.put("unit_price",new BigDecimal(order.get("total").toString()));
        var body=new LinkedHashMap<String,Object>();
        body.put("items",List.of(item));body.put("external_reference",orderId.toString());body.put("notification_url",webhookUrl);
        body.put("back_urls",Map.of("success",returnUrl+"?pago=aprobado","pending",returnUrl+"?pago=pendiente","failure",returnUrl+"?pago=rechazado"));
        body.put("auto_return","approved");body.put("payer",Map.of("email",customer.get("email")));
        Map<String,Object> preference;
        try { preference=postPreference(orderId.toString(),body); }
        catch(Exception e) { throw new Problem(502,"PAYMENT_PROVIDER_UNAVAILABLE","Mercado Pago no está disponible temporalmente. Intenta nuevamente."); }
        String preferenceId=required(preference,"id"),checkoutUrl=required(preference,"init_point");
        db.execute("INSERT INTO order_payments(order_id,status,method,provider_reference,checkout_url,provider_status) VALUES(?,'PENDING_PROVIDER','MERCADO_PAGO',?,?,'created')",orderId,preferenceId,checkoutUrl);
        return Map.of("preferenceId",preferenceId,"checkoutUrl",checkoutUrl);
    }

    public void webhook(String signature,String requestId,String dataId) {
        requireEnabled(); validateSignature(signature,requestId,dataId);
        Map<String,Object> remote;
        try { remote=getPayment(dataId); }
        catch(Exception e) { throw new Problem(502,"PAYMENT_PROVIDER_UNAVAILABLE","No se pudo verificar el pago."); }
        UUID orderId;
        try { orderId=UUID.fromString(String.valueOf(remote.get("external_reference"))); }
        catch(Exception e) { throw new Problem(400,"INVALID_PAYMENT_REFERENCE","Referencia de pago inválida."); }
        var order=db.one("SELECT id,version,status,total,currency FROM orders WHERE id=? FOR UPDATE",orderId);
        var payment=db.one("SELECT * FROM order_payments WHERE order_id=? AND method='MERCADO_PAGO' FOR UPDATE",orderId);
        String status=String.valueOf(remote.get("status"));
        db.execute("UPDATE order_payments SET external_payment_id=?,provider_status=? WHERE order_id=?",dataId,status,orderId);
        if(!"approved".equals(status)||"APPROVED".equals(payment.get("status"))) return;
        BigDecimal paid=new BigDecimal(String.valueOf(remote.get("transaction_amount")));
        if(!"PEN".equals(String.valueOf(remote.get("currency_id")))||paid.compareTo(new BigDecimal(order.get("total").toString()))!=0)
            throw Problem.conflict("PAYMENT_AMOUNT_MISMATCH","El importe confirmado no coincide con el pedido.");
        db.execute("UPDATE order_payments SET version=version+1,status='APPROVED',reviewed_at=now(),reviewed_by='mercado-pago',review_note='Confirmado automáticamente por Mercado Pago' WHERE order_id=?",orderId);
        if("PENDING_PAYMENT".equals(order.get("status"))) orders.transition(orderId,new Api.OrderStatus(Db.version(order),"PAYMENT_CONFIRMED"),"mercado-pago");
    }

    void validateSignature(String signature,String requestId,String dataId) {
        String secret=webhookSecret.filter(s->!s.isBlank()).orElseThrow(()->new Problem(503,"PAYMENT_NOT_CONFIGURED","Webhook de Mercado Pago no configurado."));
        if(signature==null||requestId==null||dataId==null) throw new Problem(401,"INVALID_WEBHOOK_SIGNATURE","Firma de webhook ausente.");
        Map<String,String> parts=new HashMap<>();for(String part:signature.split(",")){var pair=part.trim().split("=",2);if(pair.length==2)parts.put(pair[0],pair[1]);}
        String ts=parts.get("ts"),provided=parts.get("v1");if(ts==null||provided==null)throw new Problem(401,"INVALID_WEBHOOK_SIGNATURE","Firma de webhook inválida.");
        try {
            if(Math.abs(Instant.now().getEpochSecond()-Long.parseLong(ts))>300)throw new Problem(401,"INVALID_WEBHOOK_SIGNATURE","Firma de webhook vencida.");
            String manifest="id:"+dataId+";request-id:"+requestId+";ts:"+ts+";";Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String expected=HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
            if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),provided.getBytes(StandardCharsets.US_ASCII)))throw new Problem(401,"INVALID_WEBHOOK_SIGNATURE","Firma de webhook inválida.");
        } catch(Problem e){throw e;}catch(Exception e){throw new Problem(401,"INVALID_WEBHOOK_SIGNATURE","Firma de webhook inválida.");}
    }
    private void requireEnabled(){if(!enabled||accessToken.isEmpty()||accessToken.get().isBlank())throw new Problem(503,"PAYMENT_NOT_CONFIGURED","Mercado Pago todavía no está habilitado.");}
    private String bearer(){return "Bearer "+accessToken.orElseThrow();}
    @SuppressWarnings("unchecked") private Map<String,Object> postPreference(String key,Map<String,Object> body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("https://api.mercadopago.com/checkout/preferences")).timeout(java.time.Duration.ofSeconds(8)).header("Authorization",bearer()).header("X-Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build();
        var response=client().send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Mercado Pago HTTP "+response.statusCode());return json.read(response.body(),Map.class);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> getPayment(String id)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("https://api.mercadopago.com/v1/payments/"+id)).timeout(java.time.Duration.ofSeconds(8)).header("Authorization",bearer()).GET().build();
        var response=client().send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException("Mercado Pago HTTP "+response.statusCode());return json.read(response.body(),Map.class);
    }

    private HttpClient client() {
        var current=client;
        if(current==null) synchronized(this) {
            current=client;
            if(current==null) client=current=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        }
        return current;
    }
    private static String required(Map<String,Object> map,String key){Object value=map.get(key);if(value==null||value.toString().isBlank())throw new Problem(502,"INVALID_PROVIDER_RESPONSE","Respuesta inválida de Mercado Pago.");return value.toString();}
}
