package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped @Transactional
public class CustomerService {
    @Inject Db db;
    @Inject OrderService orders;

    void ensure(String subject) {
        db.execute("INSERT INTO customer_profiles(subject) VALUES(?) ON CONFLICT(subject) DO NOTHING",subject);
    }

    public Map<String,Object> profile(String subject) {
        ensure(subject);
        return db.one("SELECT subject,email,first_name,last_name,phone,created_at,updated_at FROM customer_profiles WHERE subject=?",subject);
    }

    public Map<String,Object> updateProfile(String subject,Api.CustomerProfile input) {
        ensure(subject);
        db.execute("UPDATE customer_profiles SET email=?,first_name=?,last_name=?,phone=?,updated_at=now() WHERE subject=?",
            normalizeEmail(input.email()),clean(input.firstName()),clean(input.lastName()),clean(input.phone()),subject);
        return profile(subject);
    }

    public List<Map<String,Object>> addresses(String subject) {
        ensure(subject);
        return db.rows("SELECT id,version,label,recipient,phone,address,district_code,district_name,is_default,created_at,updated_at FROM customer_addresses WHERE customer_subject=? ORDER BY is_default DESC,created_at,id",subject);
    }

    public Map<String,Object> createAddress(String subject,Api.CustomerAddress input) {
        ensure(subject);
        var district=activeDistrict(input.districtCode());
        UUID id=UUID.randomUUID();
        boolean first=addresses(subject).isEmpty();
        if(input.isDefault()||first) clearDefaultExcept(subject,null);
        boolean makeDefault=input.isDefault()||first;
        db.execute("INSERT INTO customer_addresses(id,customer_subject,label,recipient,phone,address,district_code,district_name,is_default) VALUES(?,?,?,?,?,?,?,?,?)",
            id,subject,clean(input.label()),clean(input.recipient()),clean(input.phone()),clean(input.address()),input.districtCode(),district.get("district_name"),makeDefault);
        return address(subject,id);
    }

    public Map<String,Object> updateAddress(String subject,UUID id,Api.CustomerAddress input) {
        address(subject,id);
        var district=activeDistrict(input.districtCode());
        if(input.isDefault()) clearDefaultExcept(subject,id);
        int updated=db.execute("UPDATE customer_addresses SET version=version+1,label=?,recipient=?,phone=?,address=?,district_code=?,district_name=?,is_default=?,updated_at=now() WHERE id=? AND customer_subject=? AND version=?",
            clean(input.label()),clean(input.recipient()),clean(input.phone()),clean(input.address()),input.districtCode(),district.get("district_name"),input.isDefault(),id,subject,input.version());
        if(updated!=1) throw Problem.conflict("VERSION_CONFLICT","La dirección cambió; vuelve a cargarla.");
        ensureDefault(subject);
        return address(subject,id);
    }

    public void deleteAddress(String subject,UUID id,long version) {
        int deleted=db.execute("DELETE FROM customer_addresses WHERE id=? AND customer_subject=? AND version=?",id,subject,version);
        if(deleted!=1) throw Problem.conflict("VERSION_CONFLICT","La dirección cambió o ya fue eliminada.");
        ensureDefault(subject);
    }

    public Map<String,Object> orderList(String subject,int page,int size) { return orders.listByCustomer(subject,page,size); }
    public Map<String,Object> order(String subject,UUID id) { return orders.accountGet(id,subject); }

    private Map<String,Object> address(String subject,UUID id) {
        return db.one("SELECT id,version,label,recipient,phone,address,district_code,district_name,is_default,created_at,updated_at FROM customer_addresses WHERE id=? AND customer_subject=?",id,subject);
    }
    private Map<String,Object> activeDistrict(String code) { return db.one("SELECT district_name FROM shipping_rates WHERE district_code=? AND active=true",code); }
    private void clearDefaultExcept(String subject,UUID id) {
        if(id==null) db.execute("UPDATE customer_addresses SET is_default=false,version=version+1,updated_at=now() WHERE customer_subject=? AND is_default",subject);
        else db.execute("UPDATE customer_addresses SET is_default=false,version=version+1,updated_at=now() WHERE customer_subject=? AND is_default AND id<>?",subject,id);
    }
    private void ensureDefault(String subject) {
        if(db.rows("SELECT id FROM customer_addresses WHERE customer_subject=? AND is_default",subject).isEmpty())
            db.execute("UPDATE customer_addresses SET is_default=true,version=version+1,updated_at=now() WHERE id=(SELECT id FROM customer_addresses WHERE customer_subject=? ORDER BY created_at,id LIMIT 1)",subject);
    }
    private static String normalizeEmail(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String clean(String value) { return value.trim().replaceAll("\\s+"," "); }
}
