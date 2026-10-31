package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.*;
import java.util.*;

@ApplicationScoped @Transactional
public class ReturnService {
    @Inject Db db;
    @Inject InventoryService inventory;
    @Inject NotificationService notifications;

    public UUID request(String subject,UUID orderId,Api.ReturnRequest input) {
        var order=db.one("SELECT id,status FROM orders WHERE id=? AND customer_subject=? FOR UPDATE",orderId,subject);
        if(!Set.of("SHIPPED","DELIVERED").contains(order.get("status").toString()))
            throw Problem.conflict("RETURN_NOT_AVAILABLE","Sólo puedes solicitar una devolución cuando el pedido fue enviado o entregado.");
        var shipment=db.one("SELECT shipped_at,delivered_at FROM order_shipments WHERE order_id=?",orderId);
        Object start=shipment.get("delivered_at")!=null?shipment.get("delivered_at"):shipment.get("shipped_at");
        if(start==null||Instant.parse(start.toString()).isBefore(Instant.now().minus(30,java.time.temporal.ChronoUnit.DAYS)))
            throw Problem.conflict("RETURN_WINDOW_EXPIRED","El plazo de 30 días para solicitar la devolución terminó.");
        if(!db.rows("SELECT id FROM order_returns WHERE order_id=?",orderId).isEmpty())
            throw Problem.conflict("RETURN_ALREADY_EXISTS","Este pedido ya tiene una solicitud de devolución o cambio.");
        var requested=new LinkedHashMap<UUID,Integer>();
        for(var item:input.items()) {
            if(requested.put(item.variantId(),item.quantity())!=null)
                throw new Problem(400,"DUPLICATE_RETURN_ITEM","No repitas un producto en la solicitud.");
        }
        var purchased=db.rows("SELECT variant_id,quantity FROM order_items WHERE order_id=?",orderId);
        var quantities=new HashMap<UUID,Integer>();
        for(var item:purchased) quantities.put(Db.id(item,"variant_id"),Db.integer(item,"quantity"));
        for(var item:requested.entrySet())
            if(!quantities.containsKey(item.getKey())||item.getValue()>quantities.get(item.getKey()))
                throw new Problem(400,"INVALID_RETURN_QUANTITY","La cantidad solicitada supera lo comprado.");
        UUID id=UUID.randomUUID();
        db.execute("INSERT INTO order_returns(id,order_id,requested_resolution,reason,customer_subject) VALUES(?,?,?,?,?)",
            id,orderId,input.requestedResolution(),input.reason().trim(),subject);
        for(var item:requested.entrySet())
            db.execute("INSERT INTO order_return_items(return_id,variant_id,quantity) VALUES(?,?,?)",id,item.getKey(),item.getValue());
        notifications.enqueue(orderId,"RETURN_REQUESTED","Tipo: "+("REFUND".equals(input.requestedResolution())?"Reembolso":"Cambio")+" · "+input.reason().trim());
        return id;
    }

    public UUID action(UUID id,Api.ReturnAction input,String actor) {
        var request=db.one("SELECT * FROM order_returns WHERE id=? FOR UPDATE",id);
        Problem.version(Db.version(request),input.version());
        String from=request.get("status").toString(),to=input.status();
        if(!allowed(from,to)) throw Problem.conflict("INVALID_RETURN_TRANSITION","No se puede cambiar la solicitud de "+from+" a "+to+".");
        String requested=request.get("requested_resolution").toString();
        if(("REFUNDED".equals(to)&&!"REFUND".equals(requested))||("EXCHANGED".equals(to)&&!"EXCHANGE".equals(requested)))
            throw Problem.conflict("INVALID_RETURN_RESOLUTION","La resolución debe coincidir con lo aprobado para la solicitud.");
        if(("REFUNDED".equals(to)||"EXCHANGED".equals(to))&&blank(input.reference())==null)
            throw new Problem(400,"RESOLUTION_REFERENCE_REQUIRED","Registra la referencia del reembolso o del cambio.");
        boolean restock=input.restock()&&Set.of("REFUNDED","EXCHANGED").contains(to);
        if(restock) restock(id,Db.id(request,"order_id"),actor);
        db.execute("UPDATE order_returns SET status=?,admin_note=?,resolution_reference=?,restocked=?,resolved_at=CASE WHEN ? IN ('REFUNDED','EXCHANGED','REJECTED') THEN now() ELSE resolved_at END,updated_at=now(),version=version+1 WHERE id=? AND version=?",
            to,input.note().trim(),blank(input.reference()),restock,to,id,input.version());
        UUID orderId=Db.id(request,"order_id");
        notifications.enqueue(orderId,"RETURN_"+to,input.note().trim()+(input.reference()==null?"":" · Referencia: "+input.reference().trim()));
        return orderId;
    }

    private void restock(UUID returnId,UUID orderId,String actor) {
        for(var item:db.rows("SELECT variant_id,quantity FROM order_return_items WHERE return_id=? ORDER BY variant_id",returnId)) {
            UUID variant=Db.id(item,"variant_id");int quantity=Db.integer(item,"quantity");
            db.one("SELECT variant_id FROM inventory WHERE variant_id=? FOR UPDATE",variant);
            db.execute("UPDATE inventory SET on_hand=on_hand+?,version=version+1 WHERE variant_id=?",quantity,variant);
            inventory.movement(variant,orderId,quantity,0,"Reposición por devolución",actor);
        }
    }

    public List<Map<String,Object>> listForOrder(UUID orderId) {
        var requests=db.rows("SELECT id,version,status,requested_resolution,reason,admin_note,resolution_reference,restocked,created_at,updated_at,resolved_at FROM order_returns WHERE order_id=? ORDER BY created_at DESC",orderId);
        for(var request:requests) request.put("items",db.rows("SELECT r.variant_id,r.quantity,o.sku,o.name FROM order_return_items r JOIN order_returns x ON x.id=r.return_id JOIN order_items o ON o.order_id=x.order_id AND o.variant_id=r.variant_id WHERE r.return_id=? ORDER BY o.name",request.get("id")));
        return requests;
    }

    static boolean allowed(String from,String to) {
        return switch(from) {
            case "REQUESTED" -> "APPROVED".equals(to)||"REJECTED".equals(to);
            case "APPROVED" -> "RECEIVED".equals(to);
            case "RECEIVED" -> "REFUNDED".equals(to)||"EXCHANGED".equals(to);
            default -> false;
        };
    }
    private static String blank(String value) { return value==null||value.isBlank()?null:value.trim(); }
}
