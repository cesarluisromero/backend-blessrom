package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

@Path("/api/v1/storefront-menu")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public class StorefrontMenuResource {
    @Inject Db db;
    @Inject Json json;
    @Inject CatalogService catalog;

    @RegisterForReflection public record Group(UUID id, String title, List<UUID> categoryIds) {}
    @RegisterForReflection public record Save(long version, List<Group> groups) {}
    @RegisterForReflection public record Version(long version) {}

    @GET @PermitAll
    public List<Group> published() {
        var row=db.one("SELECT published FROM storefront_menu WHERE id=1");
        var groups=row.get("published")==null?defaults():read(row.get("published"));
        var eligible=new HashSet<UUID>();
        for(var category:catalog.categories())eligible.add(Db.id(category,"id"));
        var result=new ArrayList<Group>();
        for(var group:groups) {
            var ids=group.categoryIds().stream().filter(eligible::contains).distinct().toList();
            if(!ids.isEmpty())result.add(new Group(group.id(),group.title(),ids));
        }
        return result;
    }

    @GET @Path("/admin") @RolesAllowed("admin")
    public Map<String,Object> admin() {
        var row=db.one("SELECT version,draft,published FROM storefront_menu WHERE id=1");
        return Map.of("version",row.get("version"),"draft",row.get("draft")==null?defaults():read(row.get("draft")),
            "published",row.get("published")==null?defaults():read(row.get("published")));
    }

    @PUT @Path("/admin/draft") @RolesAllowed("admin")
    public Map<String,Object> save(Save input) {
        if(input==null)throw new Problem(400,"MENU_REQUIRED","Indica los grupos del menú.");
        validate(input.groups(),false);
        var row=db.one("SELECT version FROM storefront_menu WHERE id=1 FOR UPDATE");
        Problem.version(Db.version(row),input.version());
        db.execute("UPDATE storefront_menu SET draft=?::jsonb,version=version+1,updated_at=now() WHERE id=1",json.write(input.groups()));
        return admin();
    }

    @POST @Path("/admin/publish") @RolesAllowed("admin")
    public Map<String,Object> publish(Version input) {
        if(input==null)throw new Problem(400,"MENU_VERSION_REQUIRED","Indica la versión del menú.");
        var row=db.one("SELECT version,draft FROM storefront_menu WHERE id=1 FOR UPDATE");
        Problem.version(Db.version(row),input.version());
        if(row.get("draft")==null)throw new Problem(400,"MENU_DRAFT_REQUIRED","Guarda un borrador antes de publicar.");
        validate(read(row.get("draft")),true);
        db.execute("UPDATE storefront_menu SET published=draft,version=version+1,updated_at=now() WHERE id=1");
        return admin();
    }

    private List<Group> read(Object value) { return Arrays.asList(json.read(value,Group[].class)); }

    private List<Group> defaults() {
        var categories=catalog.adminCategories();
        var groups=new ArrayList<Group>();
        for(var category:categories) {
            if(category.get("parent_id")!=null||!Boolean.TRUE.equals(category.get("active")))continue;
            var root=Db.id(category,"id");
            var ids=new ArrayList<UUID>();
            ids.add(root);
            for(var child:categories)if(root.equals(child.get("parent_id"))&&Boolean.TRUE.equals(child.get("active")))ids.add(Db.id(child,"id"));
            groups.add(new Group(root,category.get("name").toString(),ids));
        }
        return groups;
    }

    private void validate(List<Group> groups,boolean publishing) {
        if(groups==null||groups.size()>8)throw new Problem(400,"MENU_GROUP_LIMIT","El menú admite hasta ocho grupos.");
        var groupIds=new HashSet<UUID>();
        var categoryIds=new HashSet<UUID>();
        for(var group:groups) {
            if(group==null||group.id()==null||!groupIds.add(group.id())||group.title()==null||group.title().length()>80
                ||group.categoryIds()==null||group.categoryIds().size()>30||group.categoryIds().contains(null))
                throw new Problem(400,"MENU_GROUP_INVALID","Revisa los grupos del menú.");
            if(publishing&&(group.title().isBlank()||group.categoryIds().isEmpty()))
                throw new Problem(400,"MENU_GROUP_INCOMPLETE","Cada grupo necesita un nombre y al menos una categoría.");
            for(var id:group.categoryIds()) {
                if(!categoryIds.add(id))throw new Problem(400,"MENU_CATEGORY_DUPLICATE","Una categoría aparece más de una vez en el menú.");
                if(publishing&&db.rows("SELECT id FROM categories WHERE id=? AND active=true",id).isEmpty())
                    throw new Problem(400,"MENU_CATEGORY_UNAVAILABLE","Una categoría del menú está inactiva o ya no existe.");
            }
        }
    }
}
