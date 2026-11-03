package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;

/** A short operational feed; order history and inventory movements remain the detailed audit. */
@ApplicationScoped
public class ActivityService {
    @Inject Db db;

    @Transactional
    public void record(String type,String entityType,UUID entityId,String label,String actor) {
        db.execute("INSERT INTO activity_events(id,event_type,entity_type,entity_id,label,actor) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID(),type,entityType,entityId,label,actorLabel(actor));
    }

    public List<Map<String,Object>> recent(int limit) {
        return db.rows("SELECT id,event_type AS type,entity_type AS \"entityType\",entity_id AS \"entityId\",label,actor,created_at AS \"createdAt\" FROM activity_events ORDER BY created_at DESC,id DESC LIMIT ?",Math.max(1,Math.min(limit,50)));
    }

    static String actorLabel(String actor) {
        if(actor==null||Set.of("scheduler","system").contains(actor))return "Sistema";
        return switch(actor) {
            case "guest","customer" -> "Cliente";
            case "mercado-pago" -> "Mercado Pago";
            default -> "Administrador";
        };
    }
}
