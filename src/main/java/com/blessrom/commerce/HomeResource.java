package com.blessrom.commerce;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.util.UUID;

@Path("/api/v1/admin/storefront/home")
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name="Portada de la tienda")
public class HomeResource {
    @Inject HomeService home;
    @Inject MediaService media;
    @Inject SecurityIdentity identity;
    private String actor(){return identity.getPrincipal().getName();}

    @GET public Object get(){return home.admin();}
    @PUT @Path("/draft") public Object save(@NotNull @Valid HomeService.SaveDraft input){return home.saveDraft(input,actor());}
    @POST @Path("/publish") public Object publish(@NotNull @Valid Api.Version input){return home.publish(input.version(),actor());}
    @POST @Path("/media/uploads") public Response start(@NotNull @Valid MediaService.StartUpload input){
        return Response.status(201).entity(media.startHome(input)).build();
    }
    @POST @Path("/media/{id}/complete") public Object complete(@PathParam("id") UUID id,@NotNull @Valid MediaService.CompleteUpload input){
        return media.completeHome(id,input);
    }
}
