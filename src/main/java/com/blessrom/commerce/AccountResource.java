package com.blessrom.commerce;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.util.UUID;

@Path("/api/v1/account") @Authenticated @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @Tag(name="Cuenta del cliente")
public class AccountResource {
    @Inject CustomerService customers;
    @Inject SecurityIdentity identity;
    private String subject() { return identity.getPrincipal().getName(); }

    @GET public Object profile() { return customers.profile(subject()); }
    @PUT public Object update(@NotNull @Valid Api.CustomerProfile input) { return customers.updateProfile(subject(),input); }
    @GET @Path("/addresses") public Object addresses() { return customers.addresses(subject()); }
    @POST @Path("/addresses") public Response createAddress(@NotNull @Valid Api.CustomerAddress input) { return Response.status(201).entity(customers.createAddress(subject(),input)).build(); }
    @PUT @Path("/addresses/{id}") public Object updateAddress(@PathParam("id") UUID id,@NotNull @Valid Api.CustomerAddress input) { return customers.updateAddress(subject(),id,input); }
    @DELETE @Path("/addresses/{id}") public void deleteAddress(@PathParam("id") UUID id,@QueryParam("version") @Min(0) long version) { customers.deleteAddress(subject(),id,version); }
    @GET @Path("/orders") public Object orders(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) { return customers.orderList(subject(),page,size); }
    @GET @Path("/orders/{id}") public Object order(@PathParam("id") UUID id) { return customers.order(subject(),id); }
}
