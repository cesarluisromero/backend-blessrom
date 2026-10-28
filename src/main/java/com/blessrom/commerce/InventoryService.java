package com.blessrom.commerce;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;
@ApplicationScoped @Transactional
public class InventoryService {
    @Inject Db db;
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

