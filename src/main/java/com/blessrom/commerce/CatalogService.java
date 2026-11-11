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
    @Inject HomeService home;
    @Inject MediaService media;
    @Inject CatalogManagement management;
    @Inject ActivityService activity;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="blessrom.payments.mercado-pago.enabled",defaultValue="false") boolean mercadoPagoEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="blessrom.payments.mercado-pago.access-token") Optional<String> mercadoPagoToken;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="blessrom.payments.mercado-pago.webhook-secret") Optional<String> mercadoPagoSecret;
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
        db.execute("INSERT INTO collections(id,name,slug,description,starts_at,ends_at,display_order,active,featured,cover_image_url) VALUES(?,?,?,?,?,?,?,?,?,?)",id,input.name(),input.slug(),
            Objects.requireNonNullElse(input.description(),""),input.startsAt(),input.endsAt(),Objects.requireNonNullElse(input.displayOrder(),0),Objects.requireNonNullElse(input.active(),true),
            Objects.requireNonNullElse(input.featured(),false),input.coverImageUrl());
        return db.one("SELECT * FROM collections WHERE id=?",id);
    }
    public Map<String,Object> updateCollection(UUID id,Api.UpdateCollection input) {
        var current=db.one("SELECT * FROM collections WHERE id=? FOR UPDATE",id);Problem.version(Db.version(current),input.version());
        var value=input.collection();
        if(value.startsAt()!=null&&value.endsAt()!=null&&!value.endsAt().isAfter(value.startsAt()))
            throw new Problem(400,"INVALID_COLLECTION_DATES","La fecha final debe ser posterior a la fecha inicial.");
        db.execute("UPDATE collections SET name=?,slug=?,description=?,starts_at=?,ends_at=?,display_order=?,active=?,featured=?,cover_image_url=?,version=version+1 WHERE id=?",
            value.name(),value.slug(),Objects.requireNonNullElse(value.description(),""),value.startsAt(),value.endsAt(),Objects.requireNonNullElse(value.displayOrder(),0),
            Objects.requireNonNullElse(value.active(),true),Objects.requireNonNullElse(value.featured(),false),value.coverImageUrl(),id);
        return db.one("SELECT * FROM collections WHERE id=?",id);
    }
    public Map<String,Object> storefront() {
        var featured=db.rows("SELECT * FROM collections WHERE active=true AND featured=true AND (starts_at IS NULL OR starts_at<=now()) AND (ends_at IS NULL OR ends_at>now()) ORDER BY display_order,name LIMIT 6");
        for(var collection:featured) {
            UUID id=Db.id(collection,"id");
            collection.put("products",list(false,0,4,null,null,id).get("items"));
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("featuredCollections",featured.stream().filter(c->!((List<?>)c.get("products")).isEmpty()).toList());
        result.put("home",home.publicHome());
        var methods=new ArrayList<String>(List.of("YAPE","PLIN","BANK_TRANSFER","CASH_ON_DELIVERY"));
        if(mercadoPagoEnabled&&mercadoPagoToken.filter(s->!s.isBlank()).isPresent()&&mercadoPagoSecret.filter(s->!s.isBlank()).isPresent())methods.addFirst("MERCADO_PAGO");
        result.put("paymentMethods",methods);
        return result;
    }
    public Map<String,Object> collectionLanding(String slug,int page,int size) {
        var collection=db.one("SELECT * FROM collections WHERE slug=? AND active=true AND (starts_at IS NULL OR starts_at<=now()) AND (ends_at IS NULL OR ends_at>now())",slug);
        return Map.of("entity",collection,"products",list(false,page,size,null,null,Db.id(collection,"id")));
    }
    public Map<String,Object> categoryLanding(String slug,int page,int size) {
        var category=db.one("SELECT * FROM categories WHERE slug=? AND active=true",slug);
        return Map.of("entity",category,"products",list(false,page,size,null,Db.id(category,"id"),null));
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
        activity.record("PRODUCT_DRAFT_SAVED","product",id,"Borrador de producto guardado","admin");
        return adminProduct(id);
    }
    public Map<String,Object> edit(UUID id,Api.UpdateDraft input) {
        var product=lock(id);Problem.version(Db.version(product),input.version());
        saveDraft(id,input.draft());
        db.execute("UPDATE products SET version=version+1 WHERE id=?",id);
        activity.record("PRODUCT_DRAFT_SAVED","product",id,"Borrador de producto guardado","admin");
        return adminProduct(id);
    }
    private void saveDraft(UUID productId,Api.Draft draft) {
        management.validateAssignments(productId,draft);
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
        media.promoteProduct(id);
        db.execute("DELETE FROM product_drafts WHERE product_id=?",id);
        activity.record("PRODUCT_PUBLISHED","product",id,"Producto publicado","admin");
        return adminProduct(id);
    }
    public void archive(UUID id,long expected) {
        var product=lock(id);Problem.version(Db.version(product),expected);
        db.execute("UPDATE products SET archived=true,version=version+1 WHERE id=?",id);
        activity.record("PRODUCT_ARCHIVED","product",id,"Producto archivado","admin");
    }
    private Map<String,Object> lock(UUID id) { var row=db.one("SELECT * FROM products WHERE id=? FOR UPDATE",id);if(Boolean.TRUE.equals(row.get("deleting")))throw Problem.conflict("PRODUCT_DELETING","El producto se está eliminando.");return row; }
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
        return list(admin,page,size,query,category,collection,null,null,null,null,false,"name");
    }
    public Map<String,Object> list(boolean admin,int page,int size,String query,UUID category,UUID collection,
        String color,String sizeValue,java.math.BigDecimal minPrice,java.math.BigDecimal maxPrice,boolean available,String sort) {
        return list(admin,page,size,query,category,collection,color,sizeValue,minPrice,maxPrice,available,sort,false);
    }
    public Map<String,Object> list(boolean admin,int page,int size,String query,UUID category,UUID collection,
        String color,String sizeValue,java.math.BigDecimal minPrice,java.math.BigDecimal maxPrice,boolean available,String sort,boolean includeFacets) {
        if(admin) return adminList(page,size,query,null,category,collection);
        validatePage(page,size);
        validatePrices(minPrice,maxPrice);
        var filter=publicFilter(query,category,collection,color,sizeValue,minPrice,maxPrice,available);
        var args=new ArrayList<Object>(filter.args());
        String join=" FROM products p LEFT JOIN product_revisions r ON r.id=p.published_revision";
        long total=((Number)db.one("SELECT count(*) AS total"+join+filter.sql(),args.toArray()).get("total")).longValue();
        args.add(size);args.add(page*size);
        String order=switch(Objects.requireNonNullElse(sort,"name")) {
            case "price-asc" -> "(SELECT min((v->>'price')::numeric) FROM jsonb_array_elements(r.content->'variants') v WHERE v->>'enabled'='true') ASC,p.slug";
            case "price-desc" -> "(SELECT min((v->>'price')::numeric) FROM jsonb_array_elements(r.content->'variants') v WHERE v->>'enabled'='true') DESC,p.slug";
            case "newest" -> "r.created_at DESC,p.id";
            default -> "lower(r.content->>'name'),p.slug";
        };
        var rows=db.rows("SELECT p.*"+join+filter.sql()+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray());
        var items=rows.stream().map(p->publicProduct(Db.id(p,"id"))).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("page",page);result.put("size",size);result.put("total",total);
        if(includeFacets)result.put("facets",facets(query,category,collection,color,sizeValue,minPrice,maxPrice,available));
        return result;
    }
    private record Filter(String sql,List<Object> args) {}
    private static void validatePrices(java.math.BigDecimal min,java.math.BigDecimal max) {
        if(min!=null&&min.signum()<0||max!=null&&max.signum()<0||min!=null&&max!=null&&min.compareTo(max)>0)
            throw new Problem(400,"INVALID_PRICE_RANGE","El rango de precios no es válido.");
    }
    private static void validateQuery(String query) {
        if(query!=null&&query.length()>100) throw new Problem(400,"INVALID_QUERY","La búsqueda admite hasta 100 caracteres.");
    }
    private Filter publicFilter(String query,UUID category,UUID collection,String color,String size,
        java.math.BigDecimal min,java.math.BigDecimal max,boolean available) {
        var args=new ArrayList<Object>();var sql=new StringBuilder(" WHERE p.archived=false AND p.published_revision IS NOT NULL");
        validateQuery(query);
        appendClassification(sql,args,category,collection,"r.content");
        var variant=variantFilter(color,size,min,max,available);
        sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.content->'variants') v ")
            .append("JOIN variants vi ON vi.product_id=p.id AND vi.sku=v->>'sku' JOIN inventory i ON i.variant_id=vi.id WHERE ")
            .append(variant.sql());args.addAll(variant.args());
        if(query!=null&&!query.isBlank()) {
            sql.append(" AND (r.content->>'name' ILIKE ? OR v->>'sku' ILIKE ?)");
            args.add("%"+query.trim()+"%");args.add("%"+query.trim()+"%");
        }
        sql.append(')');
        return new Filter(sql.toString(),args);
    }
    private static void appendClassification(StringBuilder sql,List<Object> args,UUID category,UUID collection,String content) {
        if(category!=null) {
            sql.append(" AND ").append(content).append("->>'categoryId' IN (WITH RECURSIVE descendants AS (SELECT id FROM categories WHERE id=? UNION SELECT c.id FROM categories c JOIN descendants d ON c.parent_id=d.id) SELECT id::text FROM descendants)");args.add(category);
        }
        if(collection!=null) {sql.append(" AND COALESCE(").append(content).append("->'collectionIds','[]'::jsonb) @> ?::jsonb");args.add("[\""+collection+"\"]");}
    }
    private Filter variantFilter(String color,String size,java.math.BigDecimal min,java.math.BigDecimal max,boolean available) {
        var sql=new StringBuilder("v->>'enabled'='true'");var args=new ArrayList<Object>();
        if(color!=null&&!color.isBlank()){sql.append(" AND lower(v->'attributes'->>'color')=lower(?)");args.add(color.trim());}
        if(size!=null&&!size.isBlank()){sql.append(" AND lower(v->'attributes'->>'talla')=lower(?)");args.add(size.trim());}
        if(min!=null){sql.append(" AND (v->>'price')::numeric>=?");args.add(min);}
        if(max!=null){sql.append(" AND (v->>'price')::numeric<=?");args.add(max);}
        if(available)sql.append(" AND i.on_hand-i.reserved>0");
        return new Filter(sql.toString(),args);
    }
    private Map<String,Object> facets(String query,UUID category,UUID collection,String color,String size,
        java.math.BigDecimal min,java.math.BigDecimal max,boolean available) {
        var colors=facetValues("color",query,category,collection,null,size,min,max,available);
        var sizes=facetValues("talla",query,category,collection,color,null,min,max,available);
        var base=publicFilter(query,category,collection,color,size,null,null,available);
        var vf=variantFilter(color,size,null,null,available);var args=new ArrayList<Object>(base.args());args.addAll(vf.args());
        var price=db.one("SELECT min((v->>'price')::numeric) AS min,max((v->>'price')::numeric) AS max"+facetJoin()+base.sql()+" AND "+vf.sql(),args.toArray());
        return Map.of("colors",colors,"sizes",sizes,"price",price);
    }
    private String facetJoin() {
        return " FROM products p JOIN product_revisions r ON r.id=p.published_revision CROSS JOIN LATERAL jsonb_array_elements(r.content->'variants') v JOIN variants vi ON vi.product_id=p.id AND vi.sku=v->>'sku' JOIN inventory i ON i.variant_id=vi.id";
    }
    private List<Map<String,Object>> facetValues(String attribute,String query,UUID category,UUID collection,String color,String size,
        java.math.BigDecimal min,java.math.BigDecimal max,boolean available) {
        var base=publicFilter(query,category,collection,color,size,min,max,available);
        var vf=variantFilter(color,size,min,max,available);var args=new ArrayList<Object>(base.args());args.addAll(vf.args());
        String value="v->'attributes'->>'"+attribute+"'";
        return db.rows("SELECT min("+value+") AS value,min("+value+") AS label,count(DISTINCT p.id) AS count"+facetJoin()+base.sql()+" AND "+vf.sql()+" AND nullif("+value+",'') IS NOT NULL GROUP BY lower("+value+") ORDER BY min("+value+")",args.toArray());
    }
    public Map<String,Object> adminList(int page,int size,String query,String status,UUID category,UUID collection) {
        return adminList(page,size,query,status,category,collection,true);
    }
    public Map<String,Object> adminList(int page,int size,String query,String status,UUID category,UUID collection,boolean includeSubcategories) {
        validatePage(page,size);validateQuery(query);var args=new ArrayList<Object>();var sql=new StringBuilder(" WHERE true");
        if(status!=null&&!status.isBlank())switch(status) {
            case "draft" -> sql.append(" AND d.product_id IS NOT NULL AND p.archived=false");
            case "published" -> sql.append(" AND p.published_revision IS NOT NULL AND p.archived=false");
            case "archived" -> sql.append(" AND p.archived=true");
            default -> throw new Problem(400,"INVALID_PRODUCT_STATUS","Estado de producto no válido.");
        }
        String content="COALESCE(d.content,r.content)";
        if(query!=null&&!query.isBlank()) {
            sql.append(" AND (").append(content).append("->>'name' ILIKE ? OR p.slug ILIKE ? OR EXISTS(SELECT 1 FROM variants v WHERE v.product_id=p.id AND v.sku ILIKE ?))");
            for(int n=0;n<3;n++)args.add("%"+query.trim()+"%");
        }
        if(category!=null&&!includeSubcategories){
            sql.append(" AND ").append(content).append("->>'categoryId'=?");
            args.add(category.toString());
        }
        appendClassification(sql,args,includeSubcategories?category:null,collection,content);
        String join=" FROM products p LEFT JOIN product_revisions r ON r.id=p.published_revision LEFT JOIN product_drafts d ON d.product_id=p.id";
        long total=((Number)db.one("SELECT count(*) AS total"+join+sql,args.toArray()).get("total")).longValue();
        args.add(size);args.add(page*size);
        var items=db.rows("SELECT p.id"+join+sql+" ORDER BY lower("+content+"->>'name') NULLS LAST,p.id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->adminProduct(Db.id(row,"id"))).toList();
        var thumbnails=media.thumbnails(items.stream().map(row->(UUID)row.get("id")).toList());
        for(var item:items){item.putAll(thumbnails.getOrDefault(item.get("id"),Map.of()));if(item.get("thumbnailUrl")==null&&item.get("draft") instanceof Map<?,?> draft)item.put("thumbnailUrl",draft.get("imageUrl"));}
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    public Map<String,Object> publicProductDetail(UUID id) {
        var product=publicProduct(id);
        var colors=db.rows("""
            SELECT DISTINCT a.value, opt.swatch
            FROM products p JOIN product_revisions r ON r.id=p.published_revision
            CROSS JOIN LATERAL jsonb_array_elements(r.content->'variants') v
            CROSS JOIN LATERAL jsonb_each_text(v->'attributes') a
            JOIN attribute_definitions d ON d.code='color'
            JOIN attribute_options opt ON opt.attribute_id=d.id AND opt.value=a.value
            WHERE p.id=? AND lower(a.key)='color' AND opt.swatch IS NOT NULL
            """,id);
        var swatches=new LinkedHashMap<String,Object>();
        for(var color:colors) swatches.put(color.get("value").toString(),color.get("swatch"));
        product.put("colorSwatches",swatches);
        return product;
    }
    public Map<String,Object> publicProduct(UUID id) {
        var p=db.one("SELECT p.*,r.content FROM products p JOIN product_revisions r ON r.id=p.published_revision WHERE p.id=? AND p.archived=false AND EXISTS (SELECT 1 FROM variants v JOIN inventory i ON i.variant_id=v.id CROSS JOIN LATERAL jsonb_array_elements(r.content->'variants') rv WHERE v.product_id=p.id AND v.sku=rv->>'sku' AND (rv->>'enabled')::boolean AND i.on_hand-i.reserved>0)",id);
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
        var assets=db.rows("SELECT id,alt_text,is_primary,color_value FROM media_assets WHERE product_id=? AND status='READY' ORDER BY is_primary DESC,display_order,created_at,id",productId);
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

