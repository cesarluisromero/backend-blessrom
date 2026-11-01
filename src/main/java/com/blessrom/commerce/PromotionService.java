package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@ApplicationScoped @Transactional
public class PromotionService {
    @Inject Db db;
    public record Applied(UUID id,long version,String code,BigDecimal discount) {}

    public List<Map<String,Object>> list(){return db.rows("SELECT p.*,(SELECT count(*) FROM coupon_redemptions r WHERE r.promotion_id=p.id) AS usage_count FROM promotions p ORDER BY p.created_at DESC");}

    public Map<String,Object> create(Api.Promotion input){
        validate(input);UUID id=UUID.randomUUID();
        db.execute("INSERT INTO promotions(id,name,code,discount_type,discount_value,minimum_subtotal,usage_limit,starts_at,ends_at,active) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,input.name().trim(),code(input.code()),input.discountType(),input.discountValue(),input.minimumSubtotal(),input.usageLimit(),input.startsAt(),input.endsAt(),input.active());
        return get(id);
    }

    public Map<String,Object> update(UUID id,Api.UpdatePromotion input){
        var current=db.one("SELECT version FROM promotions WHERE id=? FOR UPDATE",id);Problem.version(Db.version(current),input.version());validate(input.promotion());var p=input.promotion();
        db.execute("UPDATE promotions SET name=?,code=?,discount_type=?,discount_value=?,minimum_subtotal=?,usage_limit=?,starts_at=?,ends_at=?,active=?,updated_at=now(),version=version+1 WHERE id=? AND version=?",
            p.name().trim(),code(p.code()),p.discountType(),p.discountValue(),p.minimumSubtotal(),p.usageLimit(),p.startsAt(),p.endsAt(),p.active(),id,input.version());
        return get(id);
    }

    Applied apply(String rawCode,BigDecimal subtotal,boolean lock){
        if(rawCode==null||rawCode.isBlank())return null;
        String normalized=code(rawCode);
        var rows=db.rows("SELECT * FROM promotions WHERE code=?"+(lock?" FOR UPDATE":""),normalized);
        if(rows.isEmpty())throw new Problem(400,"INVALID_COUPON","El cupón no existe.");
        var p=rows.getFirst();Instant now=Instant.now(),start=Instant.parse(p.get("starts_at").toString()),end=Instant.parse(p.get("ends_at").toString());
        if(!Boolean.TRUE.equals(p.get("active"))||now.isBefore(start)||!now.isBefore(end))throw Problem.conflict("COUPON_NOT_ACTIVE","El cupón no está vigente.");
        if(subtotal.compareTo(new BigDecimal(p.get("minimum_subtotal").toString()))<0)throw Problem.conflict("COUPON_MINIMUM_NOT_MET","El subtotal no alcanza la compra mínima del cupón.");
        if(p.get("usage_limit")!=null){long used=((Number)db.one("SELECT count(*) AS n FROM coupon_redemptions WHERE promotion_id=?",p.get("id")).get("n")).longValue();if(used>=((Number)p.get("usage_limit")).longValue())throw Problem.conflict("COUPON_LIMIT_REACHED","El cupón alcanzó su límite de usos.");}
        BigDecimal value=new BigDecimal(p.get("discount_value").toString());
        BigDecimal discount="PERCENT".equals(p.get("discount_type"))?subtotal.multiply(value).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP):value.setScale(2);
        discount=discount.min(subtotal).setScale(2);
        return new Applied(Db.id(p,"id"),Db.version(p),normalized,discount);
    }

    void redeem(UUID promotionId,UUID orderId){if(promotionId!=null)db.execute("INSERT INTO coupon_redemptions(promotion_id,order_id) VALUES(?,?)",promotionId,orderId);}
    void release(UUID orderId){db.execute("DELETE FROM coupon_redemptions WHERE order_id=?",orderId);}
    private Map<String,Object> get(UUID id){var result=db.one("SELECT p.*,(SELECT count(*) FROM coupon_redemptions r WHERE r.promotion_id=p.id) AS usage_count FROM promotions p WHERE p.id=?",id);return result;}
    private static void validate(Api.Promotion p){if(!p.endsAt().isAfter(p.startsAt()))throw new Problem(400,"INVALID_PROMOTION_PERIOD","La fecha final debe ser posterior a la fecha inicial.");if("PERCENT".equals(p.discountType())&&p.discountValue().compareTo(new BigDecimal("100"))>0)throw new Problem(400,"INVALID_PROMOTION_VALUE","El porcentaje no puede superar 100.");}
    private static String code(String value){return value.trim().toUpperCase(Locale.ROOT);}
}
