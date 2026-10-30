package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.*;

@ApplicationScoped
public class NotificationService {
    @Inject Db db;
    @Inject Json json;
    @ConfigProperty(name="blessrom.notifications.enabled",defaultValue="false") boolean enabled;

    @SuppressWarnings("unchecked")
    public void enqueue(UUID orderId,String event,String detail) {
        if(!enabled) return;
        var order=db.one("SELECT customer,total FROM orders WHERE id=?",orderId);
        var customer=json.read(order.get("customer"),Map.class);
        String recipient=Objects.toString(customer.get("email"),"");
        String name=Objects.toString(customer.get("recipient"),"Cliente");
        if(recipient.isBlank()) return;
        var content=content(event,name,orderId,new BigDecimal(order.get("total").toString()),detail);
        db.execute("INSERT INTO notification_outbox(id,order_id,event,recipient,subject,text_body,html_body) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID(),orderId,event,recipient,content.subject(),content.text(),content.html());
    }

    public List<Map<String,Object>> list(UUID orderId) {
        return db.rows("SELECT id,event,recipient,status,attempts,last_error,created_at,sent_at FROM notification_outbox WHERE order_id=? ORDER BY created_at,id",orderId);
    }

    record Content(String subject,String text,String html) {}
    static String title(String event) {
        return switch(event) {
            case "ORDER_CREATED" -> "Recibimos tu pedido";
            case "PAYMENT_CONFIRMED" -> "Tu pago fue aprobado";
            case "PAYMENT_REJECTED" -> "Necesitamos que revises tu pago";
            case "PREPARING" -> "Estamos preparando tu pedido";
            case "SHIPPED" -> "Tu pedido fue enviado";
            case "DELIVERED" -> "Tu pedido fue entregado";
            case "CANCELLED" -> "Tu pedido fue cancelado";
            case "EXPIRED" -> "La reserva de tu pedido venció";
            default -> "Actualización de tu pedido";
        };
    }
    private static Content content(String event,String name,UUID orderId,BigDecimal total,String detail) {
        String heading=title(event),safeName=escape(name),safeDetail=detail==null?null:escape(detail);
        String amount=NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-PE")).format(total);
        String message=switch(event) {
            case "ORDER_CREATED" -> "Tu pedido fue creado y reservamos el stock mientras registras el pago.";
            case "PAYMENT_CONFIRMED" -> "Aprobamos tu pago o acuerdo de pago. Pronto iniciaremos la preparación.";
            case "PAYMENT_REJECTED" -> "No pudimos aprobar la información enviada.";
            case "PREPARING" -> "Tu pedido ya está en preparación.";
            case "SHIPPED" -> "Tu pedido salió para entrega.";
            case "DELIVERED" -> "Registramos tu pedido como entregado. Gracias por elegir Blessrom.";
            case "CANCELLED" -> "El pedido fue cancelado y liberamos el inventario reservado.";
            case "EXPIRED" -> "La reserva venció antes de confirmarse el pago y liberamos el inventario.";
            default -> "El estado de tu pedido fue actualizado.";
        };
        String detailText=detail==null?"":"\nObservación: "+detail;
        String text="Hola "+name+",\n\n"+message+detailText+"\n\nPedido: "+orderId+"\nTotal: "+amount+"\n\nBlessrom · Tarapoto, Perú";
        String html="""
            <!doctype html><html lang="es"><body style="margin:0;background:#f5f3ef;font-family:Arial,sans-serif;color:#132a4a">
            <div style="max-width:620px;margin:0 auto;padding:32px 18px"><div style="background:#132a4a;color:white;padding:22px;text-align:center;letter-spacing:4px">BLESSROM</div>
            <div style="background:white;padding:32px;border:1px solid #d9d5cc"><p>Hola %s,</p><h1 style="font-family:Georgia,serif">%s</h1><p style="line-height:1.6">%s</p>
            %s<p><strong>Pedido:</strong> %s<br><strong>Total:</strong> %s</p><p style="margin-top:30px">Blessrom · Tarapoto, Perú</p></div></div></body></html>
            """.formatted(safeName,escape(heading),escape(message),safeDetail==null?"":"<p><strong>Observación:</strong> "+safeDetail+"</p>",orderId,escape(amount));
        return new Content("Blessrom · "+heading,text,html);
    }
    private static String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
