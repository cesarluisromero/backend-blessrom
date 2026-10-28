package com.blessrom.commerce;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.util.UUID;
@Path("/api/v1") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @Tag(name="Carrito y pedidos")
public class CheckoutResource {
    @Inject CartService carts;
    @Inject OrderService orders;
    @POST @Path("/carts") @Consumes("*/*") public Response create() { return Response.status(201).entity(carts.create()).build(); }
    @GET @Path("/carts/{id}") public Object cart(@PathParam("id") UUID id,@HeaderParam("X-Cart-Token") String token) { return carts.get(id,token); }
    @PUT @Path("/carts/{id}/items/{variantId}") public Object set(@PathParam("id") UUID id,@PathParam("variantId") UUID variant,@HeaderParam("X-Cart-Token") String token,@NotNull @Valid Api.Quantity input) { return carts.set(id,token,variant,input.quantity()); }
    @DELETE @Path("/carts/{id}/items/{variantId}") public void remove(@PathParam("id") UUID id,@PathParam("variantId") UUID variant,@HeaderParam("X-Cart-Token") String token) { carts.remove(id,token,variant); }
    @GET @Path("/carts/{id}/quote") public Object quote(@PathParam("id") UUID id,@HeaderParam("X-Cart-Token") String token,@QueryParam("district") String district) { return carts.quote(id,token,district); }
    @POST @Path("/carts/{id}/checkout") public Response checkout(@PathParam("id") UUID id,@HeaderParam("X-Cart-Token") String token,@HeaderParam("Idempotency-Key") String key,@NotNull @Valid Api.Checkout input) {
        var result=orders.checkout(id,token,key,input);
        return Response.status(result.replayed()?200:201).entity(result.order()).build();
    }
    @GET @Path("/orders/{id}") public Object get(@PathParam("id") UUID id,@HeaderParam("X-Cart-Token") String token) { return orders.get(id,token); }
    @POST @Path("/orders/{id}/cancel") @Consumes("*/*") public Object cancel(@PathParam("id") UUID id,@HeaderParam("X-Cart-Token") String token) { return orders.cancel(id,token); }
}


