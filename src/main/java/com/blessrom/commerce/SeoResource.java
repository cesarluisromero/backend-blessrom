package com.blessrom.commerce;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/v1/seo")
public class SeoResource {
    @Inject Db db;

    @GET @Path("/sitemap.xml") @Produces(MediaType.APPLICATION_XML)
    public String sitemap() {
        var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        url(xml,"https://blessrom.com/","1.0");url(xml,"https://blessrom.com/catalogo","0.9");
        for(Map<String,Object> row:db.rows("SELECT p.id FROM products p JOIN product_revisions r ON r.id=p.published_revision WHERE p.archived=false ORDER BY r.created_at DESC"))
            url(xml,"https://blessrom.com/producto/"+row.get("id"),"0.8");
        for(Map<String,Object> row:db.rows("SELECT slug FROM categories WHERE active=true ORDER BY display_order,name"))
            url(xml,"https://blessrom.com/categoria/"+row.get("slug"),"0.7");
        for(Map<String,Object> row:db.rows("SELECT slug FROM collections WHERE active=true AND (starts_at IS NULL OR starts_at<=now()) AND (ends_at IS NULL OR ends_at>now()) ORDER BY display_order,name"))
            url(xml,"https://blessrom.com/coleccion/"+row.get("slug"),"0.7");
        return xml.append("</urlset>").toString();
    }
    private void url(StringBuilder xml,String location,String priority) {
        xml.append("<url><loc>").append(escape(location)).append("</loc><priority>").append(priority).append("</priority></url>");
    }
    private String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
}
