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
    @Inject HomeSlidePurgeService purges;
    @Inject SecurityIdentity identity;
    private String actor(){return identity.getPrincipal().getName();}

    @GET public Object get(){return home.admin();}
    @PUT @Path("/draft") public Object save(@NotNull @Valid HomeService.SaveDraft input){return home.saveDraft(input,actor());}
    @POST @Path("/publish") public Object publish(@NotNull @Valid Api.Version input){return home.publish(input.version(),actor());}
    @DELETE @Path("/slides/{desktopAssetId}") public Response deleteSlide(@PathParam("desktopAssetId") UUID desktopAssetId,
        @QueryParam("version") Long version,@HeaderParam("Idempotency-Key") String key) {
        if(version==null)throw new Problem(400,"VERSION_REQUIRED","Indica la versión de la portada.");
        return Response.accepted(home.deletePublishedSlide(desktopAssetId,version,key,actor())).build();
    }
    @GET @Path("/purges/{id}") public Object purge(@PathParam("id") UUID id) {return purges.status(id);}
    @POST @Path("/media/uploads") public Response start(@NotNull @Valid MediaService.StartUpload input){
        return Response.status(201).entity(media.startHome(input)).build();
    }
    @POST @Path("/media/{id}/complete") public Object complete(@PathParam("id") UUID id,@NotNull @Valid MediaService.CompleteUpload input){
        return media.completeHome(id,input);
    }
}
