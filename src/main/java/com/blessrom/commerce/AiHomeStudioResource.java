package com.blessrom.commerce;
import io.quarkus.security.identity.SecurityIdentity;import jakarta.annotation.security.RolesAllowed;import jakarta.inject.Inject;import jakarta.validation.Valid;import jakarta.validation.constraints.NotNull;import jakarta.ws.rs.*;import jakarta.ws.rs.core.*;
@Path("/api/v1/admin/storefront/home/ai-proposals") @RolesAllowed("admin") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class AiHomeStudioResource { @Inject AiStudioService studio;@Inject SecurityIdentity identity;
 @POST public Response create(@HeaderParam("Idempotency-Key") String key,@NotNull @Valid AiStudioService.CoverRequest input){return Response.status(202).entity(studio.createCovers(input,key,identity.getPrincipal().getName())).build();}}
