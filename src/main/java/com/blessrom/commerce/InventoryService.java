package com.blessrom.commerce;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;
@ApplicationScoped @Transactional
public class InventoryService {
    static final String PUBLIC_VARIANT="p.archived=false AND EXISTS(SELECT 1 FROM jsonb_array_elements(r.content->'variants') pv WHERE pv->>'sku'=v.sku AND pv->>'enabled'='true')";
    @Inject Db db;
    @Inject ActivityService activity;
    public Map<String,Object> list(String query,String stockLevel,int page,int size) {
        CatalogService.validatePage(page,size);
        if(query!=null&&query.length()>100)throw new Problem(400,"INVALID_QUERY","La búsqueda admite hasta 100 caracteres.");
        var args=new ArrayList<Object>();var where=new StringBuilder(" WHERE p.archived=false");
        if(query!=null&&!query.isBlank()) {
            where.append(" AND (v.sku ILIKE ? OR COALESCE(d.content,r.content)->>'name' ILIKE ?)");args.add("%"+query.trim()+"%");args.add("%"+query.trim()+"%");
        }
        switch(stockLevel==null?"all":stockLevel) {
            case "all" -> {}
            case "critical" -> where.append(" AND ").append(PUBLIC_VARIANT).append(" AND i.on_hand-i.reserved<=5");
            case "out" -> where.append(" AND i.on_hand-i.reserved=0");
            default -> throw new Problem(400,"INVALID_STOCK_LEVEL","Nivel de inventario no válido.");
        }
        String join=" FROM inventory i JOIN variants v ON v.id=i.variant_id JOIN products p ON p.id=v.product_id LEFT JOIN product_revisions r ON r.id=p.published_revision LEFT JOIN product_drafts d ON d.product_id=p.id";
        long total=((Number)db.one("SELECT count(*) AS total"+join+where,args.toArray()).get("total")).longValue();
        args.add(size);args.add(page*size);
        var items=db.rows("SELECT v.id AS variant_id,v.sku,p.id AS product_id,COALESCE(d.content,r.content)->>'name' AS name,COALESCE(d.content,r.content)->>'imageUrl' AS \"imageUrl\",i.on_hand,i.reserved,i.on_hand-i.reserved AS available,i.version,"+
            "EXISTS(SELECT 1 FROM jsonb_array_elements(r.content->'variants') pv WHERE pv->>'sku'=v.sku AND pv->>'enabled'='true') AS published"+join+where+" ORDER BY available,v.sku,v.id LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    public Map<String,Object> get(UUID variant) { return db.one("SELECT *,on_hand-reserved AS available FROM inventory WHERE variant_id=?",variant); }
    public Map<String,Object> adjust(UUID variant,Api.Adjustment input,String actor) {
        var stock=db.one("SELECT * FROM inventory WHERE variant_id=? FOR UPDATE",variant);
        Problem.version(Db.version(stock),input.version());
        long next=(long)Db.integer(stock,"on_hand")+input.delta();
        if(next<Db.integer(stock,"reserved")||next>Integer.MAX_VALUE)
            throw Problem.conflict("INVALID_STOCK","El stock no puede ser negativo ni menor que lo reservado.");
        if(input.delta()==0) throw new Problem(400,"EMPTY_ADJUSTMENT","El ajuste debe modificar el stock.");
        db.execute("UPDATE inventory SET on_hand=?,version=version+1 WHERE variant_id=?",(int)next,variant);
        movement(variant,null,input.delta(),0,input.reason(),actor);
        activity.record("INVENTORY_ADJUSTED","inventory",variant,"Inventario ajustado ("+(input.delta()>0?"+":"")+input.delta()+")",actor);
        return get(variant);
    }
    void movement(UUID variant,UUID order,int onHand,int reserved,String reason,String actor) {
        db.execute("INSERT INTO inventory_movements(id,variant_id,order_id,on_hand_delta,reserved_delta,reason,actor) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),variant,order,onHand,reserved,reason,actor);
    }
    public List<Map<String,Object>> movements(UUID variant,int page,int size) {
        CatalogService.validatePage(page,size);
        return db.rows("SELECT * FROM inventory_movements WHERE variant_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",variant,size,page*size);
    }
}

