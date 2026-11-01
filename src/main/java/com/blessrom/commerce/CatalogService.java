package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped
@Transactional
public class CatalogService {
    @Inject Db db;
    @Inject Json json;
    public List<Map<String,Object>> categories() { return db.rows("SELECT * FROM categories WHERE active=true ORDER BY display_order,name"); }
    public List<Map<String,Object>> adminCategories() { return db.rows("SELECT * FROM categories ORDER BY display_order,name"); }
    public Map<String,Object> createCategory(Api.Category input) {
        UUID id=UUID.randomUUID();
        if(input.parentId()!=null) db.one("SELECT id FROM categories WHERE id=?",input.parentId());
        db.execute("INSERT INTO categories(id,name,slug,parent_id,description,display_order,active) VALUES(?,?,?,?,?,?,?)",id,input.name(),input.slug(),input.parentId(),
            Objects.requireNonNullElse(input.description(),""),Objects.requireNonNullElse(input.displayOrder(),0),Objects.requireNonNullElse(input.active(),true));
        return db.one("SELECT * FROM categories WHERE id=?",id);
    }
    public List<Map<String,Object>> collections(boolean admin) {
        String where=admin?"":" WHERE active=true AND (starts_at IS NULL OR starts_at<=now()) AND (ends_at IS NULL OR ends_at>now())";
        return db.rows("SELECT * FROM collections"+where+" ORDER BY display_order,name");
    }
    public Map<String,Object> createCollection(Api.Collection input) {
        if(input.startsAt()!=null&&input.endsAt()!=null&&!input.endsAt().isAfter(input.startsAt()))
            throw new Problem(400,"INVALID_COLLECTION_DATES","La fecha final debe ser posterior a la fecha inicial.");
        UUID id=UUID.randomUUID();
        db.execute("INSERT INTO collections(id,name,slug,description,starts_at,ends_at,display_order,active) VALUES(?,?,?,?,?,?,?,?)",id,input.name(),input.slug(),
            Objects.requireNonNullElse(input.description(),""),input.startsAt(),input.endsAt(),Objects.requireNonNullElse(input.displayOrder(),0),Objects.requireNonNullElse(input.active(),true));
        return db.one("SELECT * FROM collections WHERE id=?",id);
    }
    public List<Map<String,Object>> attributes(boolean admin) {
        String where=admin?"":" WHERE d.active=true";
        var definitions=db.rows("SELECT d.* FROM attribute_definitions d"+where+" ORDER BY d.display_order,d.name");
        for(var definition:definitions) {
            String optionWhere=admin?"":" AND active=true";
            definition.put("options",db.rows("SELECT * FROM attribute_options WHERE attribute_id=?"+optionWhere+" ORDER BY display_order,label",definition.get("id")));
        }
        return definitions;
    }
    public Map<String,Object> createAttribute(Api.AttributeDefinition input) {
        UUID id=UUID.randomUUID();
        db.execute("INSERT INTO attribute_definitions(id,code,name,type,filterable,display_order,active) VALUES(?,?,?,?,?,?,?)",id,input.code(),input.name(),input.type(),
            Objects.requireNonNullElse(input.filterable(),true),Objects.requireNonNullElse(input.displayOrder(),0),Objects.requireNonNullElse(input.active(),true));
        return attributes(true).stream().filter(row->id.equals(row.get("id"))).findFirst().orElseThrow();
    }
    public Map<String,Object> createAttributeOption(UUID attributeId,Api.AttributeOption input) {
        db.one("SELECT id FROM attribute_definitions WHERE id=?",attributeId);UUID id=UUID.randomUUID();
        db.execute("INSERT INTO attribute_options(id,attribute_id,value,label,swatch,display_order,active) VALUES(?,?,?,?,?,?,?)",id,attributeId,input.value(),input.label(),input.swatch(),
            Objects.requireNonNullElse(input.displayOrder(),0),Objects.requireNonNullElse(input.active(),true));
        return db.one("SELECT * FROM attribute_options WHERE id=?",id);
    }
    public Map<String,Object> create(Api.CreateProduct input) {
        UUID id=UUID.randomUUID();
        db.execute("INSERT INTO products(id,slug) VALUES(?,?)",id,input.slug());
        saveDraft(id,input.draft());
        return adminProduct(id);
    }
    public Map<String,Object> edit(UUID id,Api.UpdateDraft input) {
        var product=lock(id);Problem.version(Db.version(product),input.version());
        saveDraft(id,input.draft());
        db.execute("UPDATE products SET version=version+1 WHERE id=?",id);
        return adminProduct(id);
    }
    private void saveDraft(UUID productId,Api.Draft draft) {
        db.one("SELECT id FROM categories WHERE id=?",draft.categoryId());
        var collectionIds=draft.collectionIds()==null?List.<UUID>of():draft.collectionIds();
        if(collectionIds.size()!=new HashSet<>(collectionIds).size()) throw new Problem(400,"DUPLICATE_COLLECTION","No repitas una colección.");
        for(UUID collectionId:collectionIds) db.one("SELECT id FROM collections WHERE id=?",collectionId);
        var skus=new HashSet<String>();
        for(var variant:draft.variants()) {
            if(!skus.add(variant.sku())) throw Problem.conflict("DUPLICATE_SKU","Los SKU de las variantes deben ser distintos.");
            var existing=db.rows("SELECT * FROM variants WHERE sku=?",variant.sku());
            if(!existing.isEmpty()&&!productId.equals(existing.getFirst().get("product_id")))
                throw Problem.conflict("SKU_IN_USE","El SKU ya pertenece a otro producto.");
            if(existing.isEmpty()) {
                UUID id=UUID.randomUUID();
                db.execute("INSERT INTO variants(id,product_id,sku) VALUES(?,?,?)",id,productId,variant.sku());
                db.execute("INSERT INTO inventory(variant_id) VALUES(?)",id);
            }
        }
        db.execute("INSERT INTO product_drafts(product_id,content) VALUES(?,?::jsonb) ON CONFLICT(product_id) DO UPDATE SET content=EXCLUDED.content",productId,json.write(draft));
    }
    public Map<String,Object> publish(UUID id,long expected) {
        var product=lock(id);Problem.version(Db.version(product),expected);
        var draft=db.one("SELECT content FROM product_drafts WHERE product_id=?",id);
        Api.Draft content=json.read(draft.get("content"),Api.Draft.class);
        if(content.variants().stream().noneMatch(Api.Variant::enabled))
            throw Problem.conflict("NO_ENABLED_VARIANTS","Activa al menos una variante antes de publicar.");
        UUID revision=UUID.randomUUID();
        db.execute("INSERT INTO product_revisions(id,product_id,content) VALUES(?,?,?::jsonb)",revision,id,json.write(content));
        db.execute("UPDATE products SET published_revision=?,archived=false,version=version+1 WHERE id=?",revision,id);
        db.execute("DELETE FROM product_drafts WHERE product_id=?",id);
        return adminProduct(id);
    }
    public void archive(UUID id,long expected) {
        var product=lock(id);Problem.version(Db.version(product),expected);
        db.execute("UPDATE products SET archived=true,version=version+1 WHERE id=?",id);
    }
    private Map<String,Object> lock(UUID id) { return db.one("SELECT * FROM products WHERE id=? FOR UPDATE",id); }
    public Map<String,Object> adminProduct(UUID id) {
        var product=db.one("SELECT * FROM products WHERE id=?",id);
        var draft=db.rows("SELECT content FROM product_drafts WHERE product_id=?",id);
        product.put("draft",draft.isEmpty()?null:content(id,draft.getFirst().get("content"),false));
        if(product.get("published_revision")!=null)
            product.put("published",content(id,db.one("SELECT content FROM product_revisions WHERE id=?",product.get("published_revision")).get("content"),false));
        return product;
    }
    public Map<String,Object> list(boolean admin,int page,int size,String query,UUID category) { return list(admin,page,size,query,category,null); }
    public Map<String,Object> list(boolean admin,int page,int size,String query,UUID category,UUID collection) {
        validatePage(page,size);
        String where=admin?" WHERE true":" WHERE p.archived=false AND p.published_revision IS NOT NULL";
        var args=new ArrayList<Object>();
        if(query!=null&&!query.isBlank()) {
            if(query.length()>100) throw new Problem(400,"INVALID_QUERY","La búsqueda admite hasta 100 caracteres.");
            where+=" AND (r.content->>'name' ILIKE ? OR p.slug ILIKE ?)";args.add("%"+query+"%");args.add("%"+query+"%");
        }
        if(category!=null) { where+=" AND r.content->>'categoryId' IN (WITH RECURSIVE descendants AS (SELECT id FROM categories WHERE id=? UNION ALL SELECT c.id FROM categories c JOIN descendants d ON c.parent_id=d.id) SELECT id::text FROM descendants)";args.add(category); }
        if(collection!=null) { where+=" AND COALESCE(r.content->'collectionIds','[]'::jsonb) @> ?::jsonb";args.add("[\""+collection+"\"]"); }
        String join=" FROM products p LEFT JOIN product_revisions r ON r.id=p.published_revision";
        long total=((Number)db.one("SELECT count(*) AS total"+join+where,args.toArray()).get("total")).longValue();
        args.add(size);args.add(page*size);
        var rows=db.rows("SELECT p.*"+join+where+" ORDER BY p.slug LIMIT ? OFFSET ?",args.toArray());
        var items=rows.stream().map(p->admin?adminProduct(Db.id(p,"id")):publicProduct(Db.id(p,"id"))).toList();
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    public Map<String,Object> publicProduct(UUID id) {
        var p=db.one("SELECT p.*,r.content FROM products p JOIN product_revisions r ON r.id=p.published_revision WHERE p.id=? AND p.archived=false",id);
        Object raw=p.remove("content");
        var content=content(id,raw,true);
        var media=publicMedia(id);
        if(!media.isEmpty()) {
            content.put("media",media);
            @SuppressWarnings("unchecked") var urls=(Map<String,String>)media.getFirst().get("urls");
            content.put("imageUrl",urls.get("medium"));
        }
        p.put("content",content);return p;
    }
    private List<Map<String,Object>> publicMedia(UUID productId) {
        var assets=db.rows("SELECT id,alt_text,is_primary FROM media_assets WHERE product_id=? AND status='READY' ORDER BY is_primary DESC,display_order,created_at",productId);
        for(var asset:assets) {
            var urls=new LinkedHashMap<String,String>();
            for(var object:db.rows("SELECT variant,object_key FROM media_objects WHERE asset_id=? ORDER BY variant",asset.get("id")))
                urls.put(String.valueOf(object.get("variant")),mediaUrl(String.valueOf(object.get("object_key"))));
            asset.put("urls",urls);
        }
        return assets;
    }
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="blessrom.media.public-base-url",defaultValue="https://media.blessrom.com")
    String mediaBaseUrl;
    private String mediaUrl(String key) { return mediaBaseUrl.replaceAll("/+$","")+"/"+key; }
    private Map<String,Object> content(UUID productId,Object raw,boolean publicOnly) {
        Api.Draft draft=json.read(raw,Api.Draft.class);
        var data=new LinkedHashMap<String,Object>();
        data.put("name",draft.name());data.put("description",draft.description());data.put("categoryId",draft.categoryId());data.put("imageUrl",draft.imageUrl());
        data.put("collectionIds",draft.collectionIds()==null?List.of():draft.collectionIds());
        var variants=new ArrayList<Map<String,Object>>();
        for(var v:draft.variants()) {
            if(publicOnly&&!v.enabled()) continue;
            var row=db.one("SELECT v.id,v.sku,i.on_hand-i.reserved AS available FROM variants v JOIN inventory i ON i.variant_id=v.id WHERE v.product_id=? AND v.sku=?",productId,v.sku());
            row.put("attributes",v.attributes());row.put("price",v.price().setScale(2).toPlainString());row.put("enabled",v.enabled());variants.add(row);
        }
        data.put("variants",variants);return data;
    }
    static void validatePage(int page,int size) { if(page<0||page>100000||size<1||size>100) throw new Problem(400,"INVALID_PAGE","Usa page >= 0 y size entre 1 y 100."); }
}

