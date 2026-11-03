package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class ReportService {
    @Inject Db db;
    @Inject ActivityService activity;

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String,Object> dashboard(int days) {return dashboard(days,null,null);}

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String,Object> dashboard(int requestedDays,LocalDate requestedFrom,LocalDate requestedTo) {
        int days=Math.max(1,Math.min(requestedDays,365));
        LocalDate to=requestedTo==null?LocalDate.now(QueryFilters.LIMA):requestedTo;
        LocalDate from=requestedFrom==null?to.minusDays(days-1L):requestedFrom;
        if(to.isBefore(from)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>365)
            throw new Problem(400,"INVALID_DATE_RANGE","El período debe tener entre 1 y 366 días.");
        Instant start=from.atStartOfDay(QueryFilters.LIMA).toInstant(),end=to.plusDays(1).atStartOfDay(QueryFilters.LIMA).toInstant();
        var result=new LinkedHashMap<String,Object>();
        result.put("days",java.time.temporal.ChronoUnit.DAYS.between(from,to)+1);
        result.put("from",from.toString());result.put("to",to.toString());result.put("timezone","America/Lima");
        result.put("metricBasis","ORDER_CREATED_APPROVED_AMOUNT");
        result.put("summary",db.one("""
            SELECT count(*) AS orders,count(*) FILTER(WHERE op.status='APPROVED') AS paid_orders,
              COALESCE(sum(o.total) FILTER(WHERE op.status='APPROVED'),0) AS sales,
              COALESCE(sum(o.discount) FILTER(WHERE op.status='APPROVED'),0) AS discounts,
              COALESCE((SELECT sum(r.refund_amount) FROM order_returns r WHERE r.status='REFUNDED' AND r.resolved_at>=? AND r.resolved_at<?),0) AS refunds,
              COALESCE(avg(o.total) FILTER(WHERE op.status='APPROVED'),0) AS average_ticket
            FROM orders o LEFT JOIN order_payments op ON op.order_id=o.id WHERE o.created_at>=? AND o.created_at<?
            """,start,end,start,end));
        result.put("daily",db.rows("""
            SELECT to_char(o.created_at AT TIME ZONE 'America/Lima','YYYY-MM-DD') AS day,count(*) AS orders,
              count(*) FILTER(WHERE op.status='APPROVED') AS paid_orders,
              COALESCE(sum(o.total) FILTER(WHERE op.status='APPROVED'),0) AS sales
            FROM orders o LEFT JOIN order_payments op ON op.order_id=o.id WHERE o.created_at>=? AND o.created_at<?
            GROUP BY 1 ORDER BY 1
            """,start,end));
        result.put("statuses",db.rows("SELECT status,count(*) AS total FROM orders WHERE created_at>=? AND created_at<? GROUP BY status ORDER BY total DESC,status",start,end));
        result.put("topProducts",db.rows("""
            SELECT oi.sku,oi.name,sum(oi.quantity) AS units,sum(oi.line_total) AS sales
            FROM order_items oi JOIN orders o ON o.id=oi.order_id JOIN order_payments op ON op.order_id=o.id AND op.status='APPROVED'
            WHERE o.created_at>=? AND o.created_at<? GROUP BY oi.sku,oi.name ORDER BY units DESC,sales DESC,oi.sku LIMIT 10
            """,start,end));
        result.put("coupons",db.rows("""
            SELECT o.coupon_code AS code,count(*) AS orders,sum(o.discount) AS discount,sum(o.total) AS sales
            FROM orders o JOIN order_payments op ON op.order_id=o.id AND op.status='APPROVED'
            WHERE o.created_at>=? AND o.created_at<? AND o.coupon_code IS NOT NULL
            GROUP BY o.coupon_code ORDER BY orders DESC,discount DESC,o.coupon_code LIMIT 10
            """,start,end));
        result.put("returns",db.rows("SELECT status,count(*) AS total,COALESCE(sum(refund_amount),0) AS amount FROM order_returns WHERE created_at>=? AND created_at<? GROUP BY status ORDER BY total DESC,status",start,end));

        // Operational queues deliberately include older records outside the reporting period.
        var actions=db.one("""
            SELECT count(*) FILTER(WHERE o.status='PENDING_PAYMENT' AND p.status='SUBMITTED') AS "paymentsToReview",
              count(*) FILTER(WHERE o.status IN ('PAYMENT_CONFIRMED','PREPARING')) AS "ordersToPrepare",
              count(*) FILTER(WHERE o.status='READY_TO_SHIP') AS "readyToShip",
              count(*) FILTER(WHERE o.status IN ('EXPIRED','CANCELLED') AND p.status='APPROVED') AS "paymentExceptions"
            FROM orders o LEFT JOIN order_payments p ON p.order_id=o.id
            """);
        actions.put("returnsPending",db.one("SELECT count(*) AS n FROM order_returns WHERE status IN ('REQUESTED','APPROVED','RECEIVED')").get("n"));
        String inventoryJoin=" FROM inventory i JOIN variants v ON v.id=i.variant_id JOIN products p ON p.id=v.product_id LEFT JOIN product_revisions r ON r.id=p.published_revision";
        String critical=" WHERE "+InventoryService.PUBLIC_VARIANT+" AND i.on_hand-i.reserved<=5";
        Object criticalCount=db.one("SELECT count(*) AS n"+inventoryJoin+critical).get("n");
        actions.put("criticalStock",criticalCount);result.put("actions",actions);result.put("criticalInventoryCount",criticalCount);
        result.put("criticalInventory",db.rows("SELECT v.id AS variant_id,v.sku,r.content->>'name' AS name,p.id AS product_id,i.version,i.on_hand,i.reserved,i.on_hand-i.reserved AS available"+inventoryJoin+critical+" ORDER BY available,i.on_hand,v.sku,v.id LIMIT 20"));
        result.put("recentActivity",activity.recent(10));
        return result;
    }
}
