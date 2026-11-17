package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

@Path("/api/v1/home-sections")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public class HomeSectionsResource {
    @Inject Db db;
    @Inject Json json;
    @Inject CatalogService catalog;

    @RegisterForReflection public record Section(UUID id,String title,String kind,UUID sourceId,int limit,boolean enabled) {}
    @RegisterForReflection public record Save(long version,List<Section> sections) {}
    @RegisterForReflection public record Version(long version) {}

    @GET @PermitAll
    public List<Map<String,Object>> published() {
        var sections=read(db.one("SELECT published FROM home_product_sections WHERE id=1").get("published"));
        var result=new ArrayList<Map<String,Object>>();
        for(var section:sections) {
            if(!section.enabled())continue;
            List<?> products;
            if("BEST_SELLERS".equals(section.kind())) products=bestSellers(section.limit());
            else {
                UUID category="CATEGORY".equals(section.kind())?section.sourceId():null;
                UUID collection="COLLECTION".equals(section.kind())?section.sourceId():null;
                products=(List<?>)catalog.list(false,0,section.limit(),null,category,collection,null,null,null,null,true,"newest").get("items");
            }
            if(!products.isEmpty())result.add(Map.of("id",section.id(),"title",section.title(),"kind",section.kind(),"products",products));
        }
        return result;
    }

    @GET @Path("/admin") @RolesAllowed("admin")
    public Map<String,Object> admin() {
        var row=db.one("SELECT version,draft,published FROM home_product_sections WHERE id=1");
        return Map.of("version",row.get("version"),"draft",read(row.get("draft")),"published",read(row.get("published")));
    }

    @PUT @Path("/admin/draft") @RolesAllowed("admin")
    public Map<String,Object> save(Save input) {
        if(input==null)throw new Problem(400,"SECTIONS_REQUIRED","Indica las secciones.");
        validate(input.sections());
        var row=db.one("SELECT version FROM home_product_sections WHERE id=1 FOR UPDATE");
        Problem.version(Db.version(row),input.version());
        db.execute("UPDATE home_product_sections SET draft=?::jsonb,version=version+1,updated_at=now() WHERE id=1",json.write(input.sections()));
        return admin();
    }

    @POST @Path("/admin/publish") @RolesAllowed("admin")
    public Map<String,Object> publish(Version input) {
        if(input==null)throw new Problem(400,"SECTION_VERSION_REQUIRED","Indica la versión.");
        var row=db.one("SELECT version,draft FROM home_product_sections WHERE id=1 FOR UPDATE");
        Problem.version(Db.version(row),input.version());
        var sections=read(row.get("draft"));validate(sections);
        for(var section:sections) {
            if(!section.enabled()||section.sourceId()==null)continue;
            String table="CATEGORY".equals(section.kind())?"categories":"collections";
            if(db.rows("SELECT id FROM "+table+" WHERE id=? AND active=true",section.sourceId()).isEmpty())
                throw new Problem(400,"SECTION_SOURCE_UNAVAILABLE","Una sección usa una categoría o colección inactiva.");
        }
        db.execute("UPDATE home_product_sections SET published=draft,version=version+1,updated_at=now() WHERE id=1");
        return admin();
    }

    private List<Map<String,Object>> bestSellers(int limit) {
        var rows=db.rows("""
            SELECT p.id,sum(oi.quantity) AS units
            FROM order_items oi JOIN orders o ON o.id=oi.order_id
            JOIN order_payments pay ON pay.order_id=o.id AND pay.status='APPROVED'
            JOIN variants sold ON sold.id=oi.variant_id JOIN products p ON p.id=sold.product_id
            JOIN product_revisions r ON r.id=p.published_revision
            WHERE p.archived=false AND EXISTS (
              SELECT 1 FROM variants v JOIN inventory i ON i.variant_id=v.id
              JOIN LATERAL jsonb_array_elements(r.content->'variants') rv ON rv->>'sku'=v.sku AND rv->>'enabled'='true'
              WHERE v.product_id=p.id AND i.on_hand-i.reserved>0)
            GROUP BY p.id ORDER BY units DESC,p.id LIMIT ?
            """,limit);
        var products=new ArrayList<Map<String,Object>>();
        for(var row:rows)products.add(catalog.publicProduct(Db.id(row,"id")));
        return products;
    }

    private List<Section> read(Object value) { return Arrays.asList(json.read(value,Section[].class)); }
    private static void validate(List<Section> sections) {
        if(sections==null||sections.size()>8)throw new Problem(400,"INVALID_HOME_SECTIONS","El inicio admite hasta ocho secciones.");
        var ids=new HashSet<UUID>();
        for(var section:sections) {
            if(section==null||section.id()==null||!ids.add(section.id())||section.title()==null||section.title().isBlank()
                ||section.title().length()>100||section.limit()<1||section.limit()>12
                ||section.kind()==null||!Set.of("CATEGORY","COLLECTION","BEST_SELLERS").contains(section.kind())
                ||("BEST_SELLERS".equals(section.kind())?section.sourceId()!=null:section.sourceId()==null))
                throw new Problem(400,"INVALID_HOME_SECTION","Revisa el título, el criterio y la cantidad de cada sección.");
        }
    }
}
