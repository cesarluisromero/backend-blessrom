package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

@Path("/api/v1/site-information")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Transactional
public class SiteInformationResource {
    @Inject Db db;
    @Inject Json json;

    @RegisterForReflection public record Page(String title, String body) {}
    @RegisterForReflection public record Content(String address, List<String> phones, Map<String,Page> pages) {}
    @RegisterForReflection public record Save(long version, Content content) {}
    @RegisterForReflection public record Version(long version) {}
    private static final Set<String> PAGE_KEYS=Set.of("quienes-somos","privacidad","terminos","eliminar-datos");

    @GET @PermitAll
    public Content published() {
        return json.read(db.one("SELECT published FROM site_information WHERE id=1").get("published"),Content.class);
    }

    @GET @Path("/admin") @RolesAllowed("admin")
    public Map<String,Object> admin() {
        var row=db.one("SELECT version,draft,published FROM site_information WHERE id=1");
        return Map.of("version",row.get("version"),"draft",json.read(row.get("draft"),Content.class),
            "published",json.read(row.get("published"),Content.class));
    }

    @PUT @Path("/admin/draft") @RolesAllowed("admin")
    public Map<String,Object> save(Save input) {
        if(input==null)throw new Problem(400,"SITE_CONTENT_REQUIRED","Indica el contenido del sitio.");
        validate(input.content());
        var row=db.one("SELECT version FROM site_information WHERE id=1 FOR UPDATE");
        Problem.version(Db.version(row),input.version());
        db.execute("UPDATE site_information SET draft=?::jsonb,version=version+1,updated_at=now() WHERE id=1",json.write(input.content()));
        return admin();
    }

    @POST @Path("/admin/publish") @RolesAllowed("admin")
    public Map<String,Object> publish(Version input) {
        if(input==null)throw new Problem(400,"SITE_VERSION_REQUIRED","Indica la versión del contenido.");
        var row=db.one("SELECT version,draft FROM site_information WHERE id=1 FOR UPDATE");
        Problem.version(Db.version(row),input.version());
        db.execute("UPDATE site_information SET published=draft,version=version+1,updated_at=now() WHERE id=1");
        return admin();
    }

    private static void validate(Content content) {
        if(content==null||content.address()==null||content.address().length()>300||content.phones()==null
            ||content.phones().size()>5||content.phones().stream().anyMatch(p->p==null||!p.matches("[+0-9 ()-]{0,30}"))
            ||content.pages()==null||!content.pages().keySet().equals(PAGE_KEYS)
            ||content.pages().values().stream().anyMatch(p->p==null||p.title()==null||p.title().isBlank()
                ||p.title().length()>100||p.body()==null||p.body().length()>20000))
            throw new Problem(400,"INVALID_SITE_CONTENT","Revisa el contacto y los títulos o textos de las páginas.");
    }
}
