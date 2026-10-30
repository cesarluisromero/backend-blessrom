package com.blessrom.commerce;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;
@ApplicationScoped @Transactional
public class ShippingService {
    @Inject Db db;
    public List<Map<String,Object>> list(boolean admin) { return db.rows("SELECT * FROM shipping_rates"+(admin?"":" WHERE active=true")+" ORDER BY department_name,province_name,district_name"); }
    public Map<String,Object> create(Api.Rate input) {
        UUID id=UUID.randomUUID();
        db.execute("INSERT INTO shipping_rates(id,department_name,province_name,district_code,district_name,amount,active) VALUES(?,?,?,?,?,?,?)",id,clean(input.departmentName()),clean(input.provinceName()),input.districtCode(),clean(input.districtName()),input.amount(),input.active());
        return db.one("SELECT * FROM shipping_rates WHERE id=?",id);
    }
    public Map<String,Object> update(UUID id,Api.UpdateRate input) {
        var old=db.one("SELECT * FROM shipping_rates WHERE id=? FOR UPDATE",id);Problem.version(Db.version(old),input.version());
        var r=input.rate();
        db.execute("UPDATE shipping_rates SET department_name=?,province_name=?,district_code=?,district_name=?,amount=?,active=?,version=version+1 WHERE id=?",clean(r.departmentName()),clean(r.provinceName()),r.districtCode(),clean(r.districtName()),r.amount(),r.active(),id);
        return db.one("SELECT * FROM shipping_rates WHERE id=?",id);
    }
    private static String clean(String value) { return value.trim().replaceAll("\\s+"," "); }
}

