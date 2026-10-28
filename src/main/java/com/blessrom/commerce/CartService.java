package com.blessrom.commerce;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped @Transactional
public class CartService {
    @Inject Db db;
    @Inject Json json;
    public Map<String,Object> create() {
        UUID id=UUID.randomUUID();String token=Tokens.create();Instant expiry=Instant.now().plus(30,ChronoUnit.DAYS);
        db.execute("INSERT INTO carts(id,token_hash,expires_at) VALUES(?,?,?)",id,Tokens.hash(token),expiry);
        return Map.of("id",id,"accessToken",token,"expiresAt",expiry.toString());
    }
    Map<String,Object> authorize(UUID id,String token,boolean lock) {
        var cart=db.one("SELECT * FROM carts WHERE id=?"+(lock?" FOR UPDATE":""),id);Tokens.authorize(token,cart,"expires_at");return cart;
    }
    void active(Map<String,Object> cart) {
        if(!"ACTIVE".equals(cart.get("status"))) throw Problem.conflict("CART_CLOSED","El carrito ya fue procesado.");
    }
    public Map<String,Object> get(UUID id,String token) {
        var cart=authorize(id,token,false);
        return Map.of("id",id,"status",cart.get("status"),"expiresAt",cart.get("expires_at"),"items",lines(id,false));
    }
    public Map<String,Object> set(UUID id,String token,UUID variant,int quantity) {
        var cart=authorize(id,token,true);active(cart);
        if(quantity<1||quantity>100) throw new Problem(400,"INVALID_QUANTITY","La cantidad debe estar entre 1 y 100.");
        var item=db.one("SELECT v.*,p.archived,r.content FROM variants v JOIN products p ON p.id=v.product_id LEFT JOIN product_revisions r ON r.id=p.published_revision WHERE v.id=?",variant);
        enabled(item);
        var existing=db.rows("SELECT 1 FROM cart_items WHERE cart_id=? AND variant_id=?",id,variant);
        if(existing.isEmpty()&&((Number)db.one("SELECT count(*) AS n FROM cart_items WHERE cart_id=?",id).get("n")).intValue()>=50)
            throw Problem.conflict("CART_LIMIT","El carrito admite hasta 50 variantes.");
        db.execute("INSERT INTO cart_items(cart_id,variant_id,quantity) VALUES(?,?,?) ON CONFLICT(cart_id,variant_id) DO UPDATE SET quantity=EXCLUDED.quantity",id,variant,quantity);
        return get(id,token);
    }
    public void remove(UUID id,String token,UUID variant) {
        active(authorize(id,token,true));db.execute("DELETE FROM cart_items WHERE cart_id=? AND variant_id=?",id,variant);
    }
    private Api.Variant enabled(Map<String,Object> item) {
        if(Boolean.TRUE.equals(item.get("archived"))||item.get("content")==null) throw Problem.conflict("UNAVAILABLE_PRODUCT","Hay productos no disponibles.");
        Api.Draft draft=json.read(item.get("content"),Api.Draft.class);
        return draft.variants().stream().filter(v->v.sku().equals(item.get("sku"))&&v.enabled()).findFirst()
            .orElseThrow(()->Problem.conflict("UNAVAILABLE_PRODUCT","Hay variantes no disponibles."));
    }
    List<Map<String,Object>> lines(UUID id,boolean strict) {
        var rows=db.rows("SELECT ci.variant_id,ci.quantity,v.sku,p.archived,p.published_revision,r.content,i.on_hand-i.reserved AS available FROM cart_items ci JOIN variants v ON v.id=ci.variant_id JOIN products p ON p.id=v.product_id LEFT JOIN product_revisions r ON r.id=p.published_revision JOIN inventory i ON i.variant_id=v.id WHERE ci.cart_id=? ORDER BY ci.variant_id",id);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var line=new LinkedHashMap<String,Object>();
            line.put("variantId",row.get("variant_id"));line.put("sku",row.get("sku"));line.put("quantity",row.get("quantity"));
            try {
                var variant=enabled(row);var draft=json.read(row.get("content"),Api.Draft.class);
                int qty=Db.integer(row,"quantity");
                line.put("name",draft.name());line.put("attributes",variant.attributes());line.put("unitPrice",variant.price().setScale(2).toPlainString());
                line.put("lineTotal",variant.price().multiply(BigDecimal.valueOf(qty)).setScale(2).toPlainString());
                line.put("revision",row.get("published_revision"));
                line.put("available",Db.integer(row,"available")>=qty);
                if(strict&&!Boolean.TRUE.equals(line.get("available"))) throw Problem.conflict("INSUFFICIENT_STOCK","No hay stock suficiente.");
            } catch(Problem e) {
                if(strict) throw e;
                line.put("available",false);
            }
            result.add(line);
        }
        return result;
    }
    public Map<String,Object> quote(UUID id,String token,String district) {
        active(authorize(id,token,true));return calculate(id,district);
    }
    Map<String,Object> calculate(UUID id,String district) {
        if(district==null||!district.matches("[0-9]{6}")) throw new Problem(400,"INVALID_DISTRICT","Indica el código UBIGEO del distrito.");
        // Same lock order in every checkout: cart, products, shipping rate, inventory.
        db.rows("SELECT id FROM products WHERE id IN (SELECT v.product_id FROM cart_items ci JOIN variants v ON v.id=ci.variant_id WHERE ci.cart_id=?) ORDER BY id FOR SHARE",id);
        var rates=db.rows("SELECT * FROM shipping_rates WHERE district_code=? AND active=true FOR SHARE",district);
        if(rates.isEmpty()) throw Problem.conflict("NO_SHIPPING_RATE","No hay una tarifa de envío activa para ese distrito.");
        var rate=rates.getFirst();var items=lines(id,true);
        if(items.isEmpty()) throw Problem.conflict("EMPTY_CART","Agrega productos antes de finalizar la compra.");
        BigDecimal subtotal=items.stream().map(i->new BigDecimal(i.get("lineTotal").toString())).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2);
        BigDecimal shipping=(BigDecimal)rate.get("amount");
        StringBuilder fingerprint=new StringBuilder(id.toString()).append('|').append(rate.get("id")).append('|').append(rate.get("version"));
        for(var item:items) fingerprint.append('|').append(item.get("variantId")).append('|').append(item.get("revision")).append('|').append(item.get("quantity")).append('|').append(item.get("unitPrice"));
        return Map.of("cartId",id,"currency","PEN","items",items,"subtotal",subtotal.toPlainString(),
            "shipping",shipping.toPlainString(),"total",subtotal.add(shipping).toPlainString(),"fingerprint",Tokens.hash(fingerprint.toString()),
            "districtName",rate.get("district_name"));
    }
}

