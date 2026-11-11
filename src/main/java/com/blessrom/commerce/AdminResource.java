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
    @Inject CatalogManagement management;
    @Inject ProductPurgeService productPurges;
    @DELETE @Path("/products/{id}/permanent") public Response purgeProduct(@PathParam("id") UUID id,@QueryParam("version") Long version,@HeaderParam("Idempotency-Key") String key){return Response.accepted(productPurges.enqueue(id,null,requiredVersion(version),key,identity.getPrincipal().getName())).build();}
    @GET @Path("/purges/{id}") public Object purgeStatus(@PathParam("id") UUID id){return productPurges.status(id);}
    @POST @Path("/purges/{id}/retry") public Object retryPurge(@PathParam("id") UUID id){return productPurges.retry(id);}
    @GET @Path("/categories/page") public Object categoryPage(@QueryParam("q") String q,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("5") int size){return management.page("category",null,q,page,size);}
    @GET @Path("/collections/page") public Object collectionPage(@QueryParam("q") String q,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("5") int size){return management.page("collection",null,q,page,size);}
    @GET @Path("/attributes/{id}/options/page") public Object optionPage(@PathParam("id") UUID id,@QueryParam("q") String q,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("5") int size){return management.page("option",id,q,page,size);}
    @GET @Path("/categories/{id}") public Object categoryDetail(@PathParam("id") UUID id){return management.detail("category",id);}
    @GET @Path("/collections/{id}") public Object collectionDetail(@PathParam("id") UUID id){return management.detail("collection",id);}
    @GET @Path("/attributes/{attribute}/options/{id}") public Object optionDetail(@PathParam("id") UUID id,@PathParam("attribute") UUID attribute){dbOption(attribute,id);return management.detail("option",id);}
    private void dbOption(UUID attribute,UUID id){var row=management.detail("option",id);if(!attribute.equals(row.get("attribute_id")))throw Problem.missing();}
    @PUT @Path("/categories/{id}") public Object editCategory(@PathParam("id") UUID id,@NotNull @Valid CatalogManagement.CategoryEdit input){return management.updateCategory(id,input);}
    @PUT @Path("/attributes/{attribute}/options/{id}") public Object editOption(@PathParam("id") UUID id,@PathParam("attribute") UUID attribute,@NotNull @Valid CatalogManagement.OptionEdit input){dbOption(attribute,id);return management.updateOption(id,input);}
    @DELETE @Path("/categories/{id}") public void deleteCategory(@PathParam("id") UUID id,@QueryParam("version") Long version){management.delete("category",id,requiredVersion(version));}
    @DELETE @Path("/collections/{id}") public void deleteCollection(@PathParam("id") UUID id,@QueryParam("version") Long version){management.delete("collection",id,requiredVersion(version));}
    @DELETE @Path("/attributes/{attribute}/options/{id}") public void deleteOption(@PathParam("id") UUID id,@PathParam("attribute") UUID attribute,@QueryParam("version") Long version){dbOption(attribute,id);management.delete("option",id,requiredVersion(version));}
    private long requiredVersion(Long version){if(version==null||version<0)throw new Problem(400,"VERSION_REQUIRED","Indica la versión actual.");return version;}
    @Inject InventoryService inventory;
    @Inject ShippingService shipping;
    @Inject OrderService orders;
    @Inject PaymentService payments;
    @Inject ReturnService returns;
    @Inject PromotionService promotions;
    @Inject ReportService reports;
    @Inject EmbeddingService embeddings;
    @Inject MediaService media;
    @Inject SecurityIdentity identity;
    @POST @Path("/categories") public Response category(@NotNull @Valid Api.Category input) { return Response.status(201).entity(catalog.createCategory(input)).build(); }
    @GET @Path("/categories") public Object categories() { return catalog.adminCategories(); }
    @GET @Path("/collections") public Object collections() { return catalog.collections(true); }
    @POST @Path("/collections") public Response collection(@NotNull @Valid Api.Collection input) { return Response.status(201).entity(catalog.createCollection(input)).build(); }
    @PUT @Path("/collections/{id}") public Object collection(@PathParam("id") UUID id,@NotNull @Valid CatalogManagement.CollectionEdit input) { return management.updateCollection(id,input); }
    @GET @Path("/attributes") public Object attributes() { return catalog.attributes(true); }
    @POST @Path("/attributes") public Response attribute(@NotNull @Valid Api.AttributeDefinition input) { return Response.status(201).entity(catalog.createAttribute(input)).build(); }
    @POST @Path("/attributes/{id}/options") public Response attributeOption(@PathParam("id") UUID id,@NotNull @Valid Api.AttributeOption input) { return Response.status(201).entity(catalog.createAttributeOption(id,input)).build(); }
    @GET @Path("/products") public Object products(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size,
        @QueryParam("q") String query,@QueryParam("status") String status,@QueryParam("category") UUID category,@QueryParam("collection") UUID collection,@QueryParam("includeSubcategories") @DefaultValue("true") boolean includeSubcategories) { return catalog.adminList(page,size,query,status,category,collection,includeSubcategories); }
    @POST @Path("/products") public Response create(@NotNull @Valid Api.CreateProduct input) { return Response.status(201).entity(catalog.create(input)).build(); }
    @GET @Path("/products/{id}") public Object get(@PathParam("id") UUID id) { return catalog.adminProduct(id); }
    @PUT @Path("/products/{id}/draft") public Object edit(@PathParam("id") UUID id,@NotNull @Valid Api.UpdateDraft input) { return catalog.edit(id,input); }
    @POST @Path("/products/{id}/publish") public Object publish(@PathParam("id") UUID id,@NotNull @Valid Api.Version input) { return catalog.publish(id,input.version()); }
    @DELETE @Path("/products/{id}") public void archive(@PathParam("id") UUID id,@QueryParam("version") Long version) {
        if(version==null||version<0) throw new Problem(400,"VERSION_REQUIRED","Indica la versión actual.");
        catalog.archive(id,version);
    }
    @GET @Path("/products/{id}/media") public Object media(@PathParam("id") UUID id) { return media.list(id); }
    @POST @Path("/products/{id}/media/uploads") public Response startMedia(@PathParam("id") UUID id,@NotNull @Valid MediaService.StartUpload input) {
        return Response.status(201).entity(media.start(id,input)).build();
    }
    @POST @Path("/products/{id}/media/{assetId}/complete") public Object completeMedia(@PathParam("id") UUID id,@PathParam("assetId") UUID assetId,
        @NotNull @Valid MediaService.CompleteUpload input) { return media.complete(id,assetId,input); }
    @PUT @Path("/products/{id}/media/{assetId}") public Object updateMedia(@PathParam("id") UUID id,@PathParam("assetId") UUID assetId,
        @NotNull @Valid MediaService.UpdateMedia input) { return media.update(id,assetId,input); }
    @PUT @Path("/products/{id}/media-order") public Object orderMedia(@PathParam("id") UUID id,
        @NotNull @Valid MediaService.MediaOrder input) { return media.reorder(id,input); }
    @DELETE @Path("/products/{id}/media/{assetId}") public Response deleteMedia(@PathParam("id") UUID id,@PathParam("assetId") UUID assetId,@QueryParam("version") Long version,@HeaderParam("Idempotency-Key") String key) {
        return Response.accepted(productPurges.enqueue(id,assetId,version,key==null?UUID.randomUUID().toString():key,identity.getPrincipal().getName())).build();
    }
    @GET @Path("/inventory/{id}") public Object stock(@PathParam("id") UUID id) { return inventory.get(id); }
    @GET @Path("/inventory") public Object stockList(@QueryParam("q") String query,@QueryParam("stockLevel") @DefaultValue("all") String level,
        @QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) {return inventory.list(query,level,page,size);}
    @POST @Path("/inventory/{id}/adjustments") public Object adjust(@PathParam("id") UUID id,@NotNull @Valid Api.Adjustment input) { return inventory.adjust(id,input,identity.getPrincipal().getName()); }
    @GET @Path("/inventory/{id}/movements") public Object movements(@PathParam("id") UUID id,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) { return inventory.movements(id,page,size); }
    @GET @Path("/shipping-rates") public Object rates() { return shipping.list(true); }
    @POST @Path("/shipping-rates") public Response rate(@NotNull @Valid Api.Rate input) { return Response.status(201).entity(shipping.create(input)).build(); }
    @PUT @Path("/shipping-rates/{id}") public Object rate(@PathParam("id") UUID id,@NotNull @Valid Api.UpdateRate input) { return shipping.update(id,input); }
    @GET @Path("/promotions") public Object promotions() { return promotions.list(); }
    @POST @Path("/promotions") public Response promotion(@NotNull @Valid Api.Promotion input) { return Response.status(201).entity(promotions.create(input)).build(); }
    @PUT @Path("/promotions/{id}") public Object promotion(@PathParam("id") UUID id,@NotNull @Valid Api.UpdatePromotion input) { return promotions.update(id,input); }
    @GET @Path("/reports/dashboard") public Object dashboard(@QueryParam("days") @DefaultValue("30") int days,
        @QueryParam("from") String from,@QueryParam("to") String to) { return reports.dashboard(days,QueryFilters.date(from),QueryFilters.date(to)); }
    @GET @Path("/assistant/embeddings") public Object embeddingStatus() { return embeddings.status(); }
    @POST @Path("/assistant/embeddings/sync") public Object syncEmbeddings(@QueryParam("limit") @DefaultValue("50") int limit) { return embeddings.syncPending(limit); }
    @GET @Path("/orders") public Object orders(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size,
        @QueryParam("q") String query,@QueryParam("status") String status,@QueryParam("paymentStatus") String paymentStatus,
        @QueryParam("paymentMethod") String paymentMethod,@QueryParam("from") String from,@QueryParam("to") String to,
        @QueryParam("paymentException") @DefaultValue("false") boolean paymentException) {
        return orders.list(query,status,paymentStatus,paymentMethod,QueryFilters.date(from),QueryFilters.date(to),paymentException,page,size);
    }
    @GET @Path("/returns") public Object returnList(@QueryParam("status") String status,@QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("20") int size) {return returns.list(status,page,size);}
    @GET @Path("/orders/{id}") public Object order(@PathParam("id") UUID id) { return orders.adminGet(id); }
    @PUT @Path("/orders/{id}/shipment") public Object shipment(@PathParam("id") UUID id,@NotNull @Valid Api.Shipment input) { return orders.saveShipment(id,input,identity.getPrincipal().getName()); }
    @POST @Path("/orders/{id}/status") public Object status(@PathParam("id") UUID id,@NotNull @Valid Api.OrderStatus input) { return orders.transition(id,input,identity.getPrincipal().getName()); }
    @POST @Path("/orders/{id}/payment/review") public Object review(@PathParam("id") UUID id,@NotNull @Valid Api.PaymentReview input) { payments.review(id,input,identity.getPrincipal().getName());return orders.adminGet(id); }
    @GET @Path("/orders/{id}/payment/proof") @Produces({"image/jpeg","image/png","image/webp"}) public Response proof(@PathParam("id") UUID id) { var proof=payments.proof(id);return Response.ok(proof.data(),proof.contentType()).header("Content-Disposition","inline").build(); }
    @PUT @Path("/returns/{id}") public Object returnAction(@PathParam("id") UUID id,@NotNull @Valid Api.ReturnAction input) {
        return orders.adminGet(returns.action(id,input,identity.getPrincipal().getName()));
    }
}

