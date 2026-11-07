package com.blessrom.commerce;
import io.quarkus.security.identity.SecurityIdentity;import jakarta.annotation.security.RolesAllowed;import jakarta.inject.Inject;import jakarta.validation.Valid;import jakarta.validation.constraints.NotNull;import jakarta.ws.rs.*;import jakarta.ws.rs.core.*;import java.util.UUID;
@Path("/api/v1/admin/products/{id}/ai-cutouts") @RolesAllowed("admin") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class AiProductStudioResource { @Inject AiStudioService studio;@Inject SecurityIdentity identity;
 @POST public Response create(@PathParam("id") UUID id,@HeaderParam("Idempotency-Key") String key,@NotNull @Valid AiStudioService.CutoutRequest input){return Response.status(202).entity(studio.createCutout(id,input,key,identity.getPrincipal().getName())).build();}}
