package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped
public class ReportService {
    @Inject Db db;

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String,Object> dashboard(int requestedDays) {
        int days=Math.max(1,Math.min(requestedDays,365));
        var result=new LinkedHashMap<String,Object>();
        result.put("days",days);
        result.put("summary",db.one("""
            SELECT count(*) AS orders,
              count(*) FILTER (WHERE op.status='APPROVED') AS paid_orders,
              COALESCE(sum(o.total) FILTER (WHERE op.status='APPROVED'),0) AS sales,
              COALESCE(sum(o.discount) FILTER (WHERE op.status='APPROVED'),0) AS discounts,
              COALESCE((SELECT sum(r.refund_amount) FROM order_returns r WHERE r.status='REFUNDED' AND r.resolved_at>=now()-(? * interval '1 day')),0) AS refunds,
              COALESCE(avg(o.total) FILTER (WHERE op.status='APPROVED'),0) AS average_ticket
            FROM orders o LEFT JOIN order_payments op ON op.order_id=o.id
            WHERE o.created_at>=now()-(? * interval '1 day')
            """,days,days));
        result.put("daily",db.rows("""
            SELECT to_char(o.created_at::date,'YYYY-MM-DD') AS day,count(*) AS orders,
              count(*) FILTER (WHERE op.status='APPROVED') AS paid_orders,
              COALESCE(sum(o.total) FILTER (WHERE op.status='APPROVED'),0) AS sales
            FROM orders o LEFT JOIN order_payments op ON op.order_id=o.id
            WHERE o.created_at>=now()-(? * interval '1 day')
            GROUP BY o.created_at::date ORDER BY o.created_at::date
            """,days));
        result.put("statuses",db.rows("""
            SELECT status,count(*) AS total FROM orders
            WHERE created_at>=now()-(? * interval '1 day') GROUP BY status ORDER BY total DESC
            """,days));
        result.put("topProducts",db.rows("""
            SELECT oi.sku,oi.name,sum(oi.quantity) AS units,sum(oi.line_total) AS sales
            FROM order_items oi JOIN orders o ON o.id=oi.order_id JOIN order_payments op ON op.order_id=o.id AND op.status='APPROVED'
            WHERE o.created_at>=now()-(? * interval '1 day')
            GROUP BY oi.sku,oi.name ORDER BY units DESC,sales DESC LIMIT 10
            """,days));
        result.put("coupons",db.rows("""
            SELECT o.coupon_code AS code,count(*) AS orders,sum(o.discount) AS discount,sum(o.total) AS sales
            FROM orders o JOIN order_payments op ON op.order_id=o.id AND op.status='APPROVED'
            WHERE o.created_at>=now()-(? * interval '1 day') AND o.coupon_code IS NOT NULL
            GROUP BY o.coupon_code ORDER BY orders DESC,discount DESC LIMIT 10
            """,days));
        result.put("returns",db.rows("""
            SELECT status,count(*) AS total,COALESCE(sum(refund_amount),0) AS amount FROM order_returns
            WHERE created_at>=now()-(? * interval '1 day') GROUP BY status ORDER BY total DESC
            """,days));
        result.put("criticalInventory",db.rows("""
            SELECT v.id AS variant_id,v.sku,COALESCE(pr.content->>'name',p.slug) AS name,
              i.on_hand,i.reserved,(i.on_hand-i.reserved) AS available
            FROM inventory i JOIN variants v ON v.id=i.variant_id JOIN products p ON p.id=v.product_id
              LEFT JOIN product_revisions pr ON pr.id=p.published_revision
            WHERE p.archived=false AND (i.on_hand-i.reserved)<=5
            ORDER BY available,i.on_hand,v.sku LIMIT 20
            """));
        return result;
    }
}
