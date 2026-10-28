package com.blessrom.commerce;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.util.*;
@Path("/api/v1") @Produces(MediaType.APPLICATION_JSON) @Tag(name="Catálogo público")
public class CatalogResource {
    @Inject CatalogService catalog;
    @Inject ShippingService shipping;
    @GET @Path("/categories") public Object categories() { return catalog.categories(); }
    @GET @Path("/products") public Object products(@QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("20") int size,@QueryParam("q") String query,@QueryParam("category") UUID category) {
        return catalog.list(false,page,size,query,category);
    }
    @GET @Path("/products/{id}") public Object product(@PathParam("id") UUID id) { return catalog.publicProduct(id); }
    @GET @Path("/shipping-rates") public Object rates() { return shipping.list(false); }
}

