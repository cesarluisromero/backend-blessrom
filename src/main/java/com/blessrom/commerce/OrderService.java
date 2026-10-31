package com.blessrom.commerce;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped @Transactional
public class OrderService {
    @Inject Db db;
    @Inject Json json;
    @Inject CartService carts;
    @Inject InventoryService inventory;
    @Inject NotificationService notifications;
    @Inject ReturnService returns;
    @ConfigProperty(name="blessrom.reservation-minutes",defaultValue="30") int reservationMinutes;
    public record Result(Map<String,Object> order,boolean replayed) {}
    public Result checkout(UUID cartId,String token,String key,Api.Checkout input) { return checkout(cartId,token,key,input,null); }
    @SuppressWarnings("unchecked")
    public Result checkout(UUID cartId,String token,String key,Api.Checkout input,String customerSubject) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}")) throw new Problem(400,"INVALID_IDEMPOTENCY_KEY","Envía Idempotency-Key de 8 a 100 caracteres alfanuméricos, guion o guion bajo.");
        var cart=carts.authorize(cartId,token,true);String requestHash=Tokens.hash(json.write(input));
        var previous=db.rows("SELECT id,idempotency_key,request_hash FROM orders WHERE cart_id=?",cartId);
        if(!previous.isEmpty()) {
            var old=previous.getFirst();
            if(!key.equals(old.get("idempotency_key"))||!requestHash.equals(old.get("request_hash")))
                throw Problem.conflict("IDEMPOTENCY_CONFLICT","El carrito ya se procesó con otra solicitud.");
            return new Result(view(Db.id(old,"id")),true);
        }
        carts.active(cart);
        var quote=carts.calculate(cartId,input.districtCode());
        if(!input.quoteFingerprint().equals(quote.get("fingerprint")))
            throw Problem.conflict("QUOTE_CHANGED","Cambió la cotización. Consúltala y confirma los nuevos importes.");
        var items=(List<Map<String,Object>>)quote.get("items");
        for(var item:items) {
            UUID variant=(UUID)item.get("variantId");
            var stock=db.one("SELECT * FROM inventory WHERE variant_id=? FOR UPDATE",variant);
            if(Db.integer(stock,"on_hand")-Db.integer(stock,"reserved")<((Number)item.get("quantity")).intValue())
                throw Problem.conflict("INSUFFICIENT_STOCK","No hay stock suficiente para completar el pedido.");
        }
        UUID id=UUID.randomUUID();Instant expiry=Instant.now().plus(reservationMinutes,ChronoUnit.MINUTES);
        var customer=new LinkedHashMap<String,Object>();
        customer.put("recipient",input.recipient());customer.put("email",input.email());customer.put("phone",input.phone());
        customer.put("address",input.address());customer.put("districtCode",input.districtCode());customer.put("districtName",quote.get("districtName"));
        if(customerSubject!=null) db.execute("INSERT INTO customer_profiles(subject) VALUES(?) ON CONFLICT(subject) DO NOTHING",customerSubject);
        db.execute("INSERT INTO orders(id,cart_id,idempotency_key,request_hash,token_hash,access_expires_at,subtotal,shipping,total,customer,reservation_expires_at,customer_subject) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
            id,cartId,key,requestHash,cart.get("token_hash"),Instant.now().plus(30,ChronoUnit.DAYS),
            new BigDecimal(quote.get("subtotal").toString()),new BigDecimal(quote.get("shipping").toString()),new BigDecimal(quote.get("total").toString()),json.write(customer),expiry,customerSubject);
        for(var item:items) {
            UUID variant=(UUID)item.get("variantId");int quantity=((Number)item.get("quantity")).intValue();
            db.execute("UPDATE inventory SET reserved=reserved+?,version=version+1 WHERE variant_id=?",quantity,variant);
            db.execute("INSERT INTO order_items(order_id,variant_id,sku,name,attributes,quantity,unit_price,line_total) VALUES(?,?,?,?,?::jsonb,?,?,?)",
                id,variant,item.get("sku"),item.get("name"),json.write(item.get("attributes")),quantity,new BigDecimal(item.get("unitPrice").toString()),new BigDecimal(item.get("lineTotal").toString()));
            inventory.movement(variant,id,0,quantity,"Reserva de pedido","guest");
        }
        db.execute("UPDATE carts SET status='CHECKED_OUT' WHERE id=?",cartId);
        history(id,"PENDING_PAYMENT","guest");
        notifications.enqueue(id,"ORDER_CREATED",null);
        return new Result(view(id),false);
    }
    public Map<String,Object> get(UUID id,String token) {
        var order=db.one("SELECT token_hash,access_expires_at FROM orders WHERE id=?",id);Tokens.authorize(token,order,"access_expires_at");return view(id);
    }
    public Map<String,Object> adminGet(UUID id) { var order=view(id);order.put("notifications",notifications.list(id));return order; }
    public Map<String,Object> accountGet(UUID id,String subject) { db.one("SELECT id FROM orders WHERE id=? AND customer_subject=?",id,subject);return view(id); }
    public Map<String,Object> transition(UUID id,Api.OrderStatus input,String actor) {
        var order=db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",id);
        long current=((Number)order.get("version")).longValue();
        if(current!=input.version()) throw Problem.conflict("VERSION_CONFLICT","El pedido cambió; vuelve a consultarlo.");
        String from=order.get("status").toString(),to=input.status();
        if(!allowed(from,to)) throw Problem.conflict("INVALID_ORDER_TRANSITION","No se puede cambiar el pedido de "+from+" a "+to+".");
        if("PAYMENT_CONFIRMED".equals(to)) {
            var payment=db.rows("SELECT status FROM order_payments WHERE order_id=?",id);
            if(payment.isEmpty()||!"APPROVED".equals(payment.getFirst().get("status")))
                throw Problem.conflict("PAYMENT_NOT_APPROVED","El pago o acuerdo de pago debe aprobarse primero.");
            confirmInventory(id,actor);
        }
        if("SHIPPED".equals(to)&&db.rows("SELECT order_id FROM order_shipments WHERE order_id=?",id).isEmpty())
            throw Problem.conflict("SHIPMENT_REQUIRED","Registra transportista, seguimiento y fecha estimada antes de marcar el pedido como enviado.");
        if("CANCELLED".equals(to)) {
            release(order,to,actor);
        } else {
            int updated=db.execute("UPDATE orders SET status=?,version=version+1 WHERE id=? AND version=?",to,id,current);
            if(updated!=1) throw Problem.conflict("VERSION_CONFLICT","El pedido cambió; vuelve a consultarlo.");
            if("SHIPPED".equals(to)) db.execute("UPDATE order_shipments SET shipped_at=COALESCE(shipped_at,now()) WHERE order_id=?",id);
            if("DELIVERED".equals(to)) db.execute("UPDATE order_shipments SET delivered_at=COALESCE(delivered_at,now()) WHERE order_id=?",id);
            history(id,to,actor);
            notifications.enqueue(id,to,"SHIPPED".equals(to)?shipmentDetail(id):null);
        }
        return view(id);
    }
    public Map<String,Object> saveShipment(UUID id,Api.Shipment input,String actor) {
        var order=db.one("SELECT status FROM orders WHERE id=? FOR UPDATE",id);
        String status=order.get("status").toString();
        if(!Set.of("PREPARING","READY_TO_SHIP").contains(status))
            throw Problem.conflict("SHIPMENT_NOT_EDITABLE","Los datos de envío sólo pueden editarse durante la preparación del pedido.");
        var existing=db.rows("SELECT version FROM order_shipments WHERE order_id=? FOR UPDATE",id);
        if(existing.isEmpty()) {
            if(input.version()!=0) throw Problem.conflict("VERSION_CONFLICT","Los datos de envío cambiaron; vuelve a consultar el pedido.");
            db.execute("INSERT INTO order_shipments(order_id,carrier,tracking_number,estimated_delivery,notes,updated_by) VALUES(?,?,?,?,?,?)",
                id,input.carrier().trim(),input.trackingNumber().trim(),input.estimatedDelivery(),blankToNull(input.notes()),actor);
        } else {
            long current=((Number)existing.getFirst().get("version")).longValue();
            if(current!=input.version()) throw Problem.conflict("VERSION_CONFLICT","Los datos de envío cambiaron; vuelve a consultar el pedido.");
            int updated=db.execute("UPDATE order_shipments SET carrier=?,tracking_number=?,estimated_delivery=?,notes=?,updated_by=?,updated_at=now(),version=version+1 WHERE order_id=? AND version=?",
                input.carrier().trim(),input.trackingNumber().trim(),input.estimatedDelivery(),blankToNull(input.notes()),actor,id,current);
            if(updated!=1) throw Problem.conflict("VERSION_CONFLICT","Los datos de envío cambiaron; vuelve a consultar el pedido.");
        }
        return adminGet(id);
    }
    private static String blankToNull(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private String shipmentDetail(UUID id) {
        var shipment=db.one("SELECT carrier,tracking_number,estimated_delivery FROM order_shipments WHERE order_id=?",id);
        return "Transportista: "+shipment.get("carrier")+" · Seguimiento: "+shipment.get("tracking_number")+" · Entrega estimada: "+shipment.get("estimated_delivery");
    }
    static boolean allowed(String from,String to) {
        return switch(from) {
            case "PENDING_PAYMENT" -> "PAYMENT_CONFIRMED".equals(to)||"CANCELLED".equals(to);
            case "PAYMENT_CONFIRMED" -> "PREPARING".equals(to);
            case "PREPARING" -> "READY_TO_SHIP".equals(to);
            case "READY_TO_SHIP" -> "SHIPPED".equals(to);
            case "SHIPPED" -> "DELIVERED".equals(to);
            default -> false;
        };
    }
    private void confirmInventory(UUID id,String actor) {
        var items=db.rows("SELECT variant_id,quantity FROM order_items WHERE order_id=? ORDER BY variant_id",id);
        for(var item:items) {
            UUID variant=Db.id(item,"variant_id");int quantity=Db.integer(item,"quantity");
            var stock=db.one("SELECT * FROM inventory WHERE variant_id=? FOR UPDATE",variant);
            if(Db.integer(stock,"on_hand")<quantity||Db.integer(stock,"reserved")<quantity)
                throw Problem.conflict("RESERVATION_INCONSISTENT","La reserva de inventario del pedido es inconsistente.");
            db.execute("UPDATE inventory SET on_hand=on_hand-?,reserved=reserved-?,version=version+1 WHERE variant_id=?",quantity,quantity,variant);
            inventory.movement(variant,id,-quantity,-quantity,"Pago confirmado",actor);
        }
    }
    public Map<String,Object> list(int page,int size) {
        CatalogService.validatePage(page,size);
        return Map.of("items",db.rows("SELECT o.id,o.version,o.status,o.currency,o.total,o.created_at,o.reservation_expires_at,p.status AS payment_status,p.method AS payment_method FROM orders o LEFT JOIN order_payments p ON p.order_id=o.id ORDER BY o.created_at DESC,o.id LIMIT ? OFFSET ?",size,page*size),
            "page",page,"size",size,"total",db.one("SELECT count(*) AS n FROM orders").get("n"));
    }
    public Map<String,Object> listByCustomer(String subject,int page,int size) {
        CatalogService.validatePage(page,size);
        return Map.of("items",db.rows("SELECT o.id,o.version,o.status,o.currency,o.total,o.created_at,o.reservation_expires_at,p.status AS payment_status,p.method AS payment_method FROM orders o LEFT JOIN order_payments p ON p.order_id=o.id WHERE o.customer_subject=? ORDER BY o.created_at DESC,o.id LIMIT ? OFFSET ?",subject,size,page*size),
            "page",page,"size",size,"total",db.one("SELECT count(*) AS n FROM orders WHERE customer_subject=?",subject).get("n"));
    }
    public Map<String,Object> cancel(UUID id,String token) {
        var order=db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",id);
        Tokens.authorize(token,order,"access_expires_at");
        release(order,"CANCELLED","guest");return view(id);
    }
    public void expire(UUID id) {
        var order=db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",id);
        if(!Instant.parse(order.get("reservation_expires_at").toString()).isAfter(Instant.now()))
            release(order,"EXPIRED","scheduler");
    }
    private void release(Map<String,Object> order,String status,String actor) {
        if(!"PENDING_PAYMENT".equals(order.get("status"))) return;
        UUID id=Db.id(order,"id");
        var items=db.rows("SELECT variant_id,quantity FROM order_items WHERE order_id=? ORDER BY variant_id",id);
        for(var item:items) {
            UUID variant=Db.id(item,"variant_id");int quantity=Db.integer(item,"quantity");
            db.one("SELECT * FROM inventory WHERE variant_id=? FOR UPDATE",variant);
            db.execute("UPDATE inventory SET reserved=reserved-?,version=version+1 WHERE variant_id=?",quantity,variant);
            inventory.movement(variant,id,0,-quantity,"Liberación: "+status,actor);
        }
        db.execute("UPDATE orders SET status=?,version=version+1 WHERE id=?",status,id);
        db.execute("UPDATE order_payments SET version=version+1,status='REJECTED',reviewed_at=now(),reviewed_by=?,review_note=? WHERE order_id=? AND status IN ('SUBMITTED','PENDING_PROVIDER')",actor,"Pedido "+status.toLowerCase(Locale.ROOT),id);
        history(id,status,actor);
        notifications.enqueue(id,status,null);
    }
    private void history(UUID id,String status,String actor) {
        db.execute("INSERT INTO order_history(id,order_id,status,actor) VALUES(?,?,?,?)",UUID.randomUUID(),id,status,actor);
    }
    private Map<String,Object> view(UUID id) {
        var order=db.one("SELECT id,version,status,currency,subtotal,shipping,total,customer,created_at,reservation_expires_at FROM orders WHERE id=?",id);
        order.put("customer",json.read(order.get("customer"),Map.class));
        var items=db.rows("SELECT variant_id,sku,name,attributes,quantity,unit_price,line_total FROM order_items WHERE order_id=? ORDER BY variant_id",id);
        for(var item:items) item.put("attributes",json.read(item.get("attributes"),Map.class));
        order.put("items",items);
        order.put("history",db.rows("SELECT status,actor,created_at FROM order_history WHERE order_id=? ORDER BY created_at,id",id));
        var payments=db.rows("SELECT version,status,method,reference,(proof_data IS NOT NULL) AS has_proof,provider_reference,checkout_url,provider_status,submitted_at,reviewed_at,reviewed_by,review_note FROM order_payments WHERE order_id=?",id);
        order.put("payment",payments.isEmpty()?null:payments.getFirst());
        var shipments=db.rows("SELECT version,carrier,tracking_number,estimated_delivery,notes,shipped_at,delivered_at,updated_at FROM order_shipments WHERE order_id=?",id);
        order.put("shipment",shipments.isEmpty()?null:shipments.getFirst());
        order.put("returns",returns.listForOrder(id));
        return order;
    }
}

