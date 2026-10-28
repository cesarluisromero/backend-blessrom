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
    @ConfigProperty(name="blessrom.reservation-minutes",defaultValue="30") int reservationMinutes;
    public record Result(Map<String,Object> order,boolean replayed) {}
    @SuppressWarnings("unchecked")
    public Result checkout(UUID cartId,String token,String key,Api.Checkout input) {
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
        db.execute("INSERT INTO orders(id,cart_id,idempotency_key,request_hash,token_hash,access_expires_at,subtotal,shipping,total,customer,reservation_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?)",
            id,cartId,key,requestHash,cart.get("token_hash"),Instant.now().plus(30,ChronoUnit.DAYS),
            new BigDecimal(quote.get("subtotal").toString()),new BigDecimal(quote.get("shipping").toString()),new BigDecimal(quote.get("total").toString()),json.write(customer),expiry);
        for(var item:items) {
            UUID variant=(UUID)item.get("variantId");int quantity=((Number)item.get("quantity")).intValue();
            db.execute("UPDATE inventory SET reserved=reserved+?,version=version+1 WHERE variant_id=?",quantity,variant);
            db.execute("INSERT INTO order_items(order_id,variant_id,sku,name,attributes,quantity,unit_price,line_total) VALUES(?,?,?,?,?::jsonb,?,?,?)",
                id,variant,item.get("sku"),item.get("name"),json.write(item.get("attributes")),quantity,new BigDecimal(item.get("unitPrice").toString()),new BigDecimal(item.get("lineTotal").toString()));
            inventory.movement(variant,id,0,quantity,"Reserva de pedido","guest");
        }
        db.execute("UPDATE carts SET status='CHECKED_OUT' WHERE id=?",cartId);
        history(id,"PENDING_PAYMENT","guest");
        return new Result(view(id),false);
    }
    public Map<String,Object> get(UUID id,String token) {
        var order=db.one("SELECT token_hash,access_expires_at FROM orders WHERE id=?",id);Tokens.authorize(token,order,"access_expires_at");return view(id);
    }
    public Map<String,Object> adminGet(UUID id) { return view(id); }
    public Map<String,Object> list(int page,int size) {
        CatalogService.validatePage(page,size);
        return Map.of("items",db.rows("SELECT id,status,currency,total,created_at,reservation_expires_at FROM orders ORDER BY created_at DESC,id LIMIT ? OFFSET ?",size,page*size),
            "page",page,"size",size,"total",db.one("SELECT count(*) AS n FROM orders").get("n"));
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
        db.execute("UPDATE orders SET status=? WHERE id=?",status,id);history(id,status,actor);
    }
    private void history(UUID id,String status,String actor) {
        db.execute("INSERT INTO order_history(id,order_id,status,actor) VALUES(?,?,?,?)",UUID.randomUUID(),id,status,actor);
    }
    private Map<String,Object> view(UUID id) {
        var order=db.one("SELECT id,status,currency,subtotal,shipping,total,customer,created_at,reservation_expires_at FROM orders WHERE id=?",id);
        order.put("customer",json.read(order.get("customer"),Map.class));
        var items=db.rows("SELECT variant_id,sku,name,attributes,quantity,unit_price,line_total FROM order_items WHERE order_id=? ORDER BY variant_id",id);
        for(var item:items) item.put("attributes",json.read(item.get("attributes"),Map.class));
        order.put("items",items);
        order.put("history",db.rows("SELECT status,actor,created_at FROM order_history WHERE order_id=? ORDER BY created_at,id",id));
        return order;
    }
}

