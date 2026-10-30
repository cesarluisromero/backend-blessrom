package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
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
        var content=OrderEmailTemplate.render(event,name,orderId,new BigDecimal(order.get("total").toString()),detail);
        db.execute("INSERT INTO notification_outbox(id,order_id,event,recipient,subject,text_body,html_body) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID(),orderId,event,recipient,content.subject(),content.text(),content.html());
    }

    public List<Map<String,Object>> list(UUID orderId) {
        return db.rows("SELECT id,event,recipient,status,attempts,last_error,created_at,sent_at FROM notification_outbox WHERE order_id=? ORDER BY created_at,id",orderId);
    }

    static String title(String event) {
        return OrderEmailTemplate.title(event);
    }
}
