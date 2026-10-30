package com.blessrom.commerce;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.util.*;
@Path("/api/v1/admin") @RolesAllowed("admin") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @Tag(name="Administración comercial")
public class AdminResource {
    @Inject CatalogService catalog;
    @Inject InventoryService inventory;
    @Inject ShippingService shipping;
    @Inject OrderService orders;
    @Inject PaymentService payments;
    @Inject SecurityIdentity identity;
    @POST @Path("/categories") public Response category(@NotNull @Valid Api.Category input) { return Response.status(201).entity(catalog.createCategory(input)).build(); }
    @GET @Path("/products") public Object products(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) { return catalog.list(true,page,size,null,null); }
    @POST @Path("/products") public Response create(@NotNull @Valid Api.CreateProduct input) { return Response.status(201).entity(catalog.create(input)).build(); }
    @GET @Path("/products/{id}") public Object get(@PathParam("id") UUID id) { return catalog.adminProduct(id); }
    @PUT @Path("/products/{id}/draft") public Object edit(@PathParam("id") UUID id,@NotNull @Valid Api.UpdateDraft input) { return catalog.edit(id,input); }
    @POST @Path("/products/{id}/publish") public Object publish(@PathParam("id") UUID id,@NotNull @Valid Api.Version input) { return catalog.publish(id,input.version()); }
    @DELETE @Path("/products/{id}") public void archive(@PathParam("id") UUID id,@QueryParam("version") Long version) {
        if(version==null||version<0) throw new Problem(400,"VERSION_REQUIRED","Indica la versión actual.");
        catalog.archive(id,version);
    }
    @GET @Path("/inventory/{id}") public Object stock(@PathParam("id") UUID id) { return inventory.get(id); }
    @POST @Path("/inventory/{id}/adjustments") public Object adjust(@PathParam("id") UUID id,@NotNull @Valid Api.Adjustment input) { return inventory.adjust(id,input,identity.getPrincipal().getName()); }
    @GET @Path("/inventory/{id}/movements") public Object movements(@PathParam("id") UUID id,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) { return inventory.movements(id,page,size); }
    @GET @Path("/shipping-rates") public Object rates() { return shipping.list(true); }
    @POST @Path("/shipping-rates") public Response rate(@NotNull @Valid Api.Rate input) { return Response.status(201).entity(shipping.create(input)).build(); }
    @PUT @Path("/shipping-rates/{id}") public Object rate(@PathParam("id") UUID id,@NotNull @Valid Api.UpdateRate input) { return shipping.update(id,input); }
    @GET @Path("/orders") public Object orders(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) { return orders.list(page,size); }
    @GET @Path("/orders/{id}") public Object order(@PathParam("id") UUID id) { return orders.adminGet(id); }
    @PUT @Path("/orders/{id}/shipment") public Object shipment(@PathParam("id") UUID id,@NotNull @Valid Api.Shipment input) { return orders.saveShipment(id,input,identity.getPrincipal().getName()); }
    @POST @Path("/orders/{id}/status") public Object status(@PathParam("id") UUID id,@NotNull @Valid Api.OrderStatus input) { return orders.transition(id,input,identity.getPrincipal().getName()); }
    @POST @Path("/orders/{id}/payment/review") public Object review(@PathParam("id") UUID id,@NotNull @Valid Api.PaymentReview input) { payments.review(id,input,identity.getPrincipal().getName());return orders.adminGet(id); }
    @GET @Path("/orders/{id}/payment/proof") @Produces({"image/jpeg","image/png","image/webp"}) public Response proof(@PathParam("id") UUID id) { var proof=payments.proof(id);return Response.ok(proof.data(),proof.contentType()).header("Content-Disposition","inline").build(); }
}

