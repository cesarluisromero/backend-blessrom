package com.blessrom.commerce;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/v1/admin/ai-studio") @RolesAllowed("admin") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class AiStudioResource {
    @Inject AiStudioService studio;@Inject SecurityIdentity identity;
    @Inject ProductSuggestionService suggestions;
    private String actor(){return identity.getPrincipal().getName();}
    @GET @Path("/status") public Object status(){return studio.status();}
    @POST @Path("/uploads") public Response upload(@NotNull @Valid AiStudioService.UploadRequest input){return Response.status(201).entity(studio.startUpload(input,actor())).build();}
    @POST @Path("/uploads/{id}/complete") public Object complete(@PathParam("id") UUID id,@Valid AiStudioService.CompleteSource input){return studio.completeUpload(id,input,actor());}
    @POST @Path("/uploads/{id}/suggest-product") public Object suggestProduct(@PathParam("id") UUID id){return suggestions.suggest(id,actor());}
    @GET @Path("/jobs/{id}") public Object job(@PathParam("id") UUID id){return studio.job(id,actor());}
    @POST @Path("/jobs/{id}/results/{resultId}/approve") public Object approve(@PathParam("id") UUID id,@PathParam("resultId") UUID result,@NotNull @Valid AiStudioService.Review input){return studio.approve(id,result,input,actor());}
    @POST @Path("/jobs/{id}/results/{resultId}/reject") public Object reject(@PathParam("id") UUID id,@PathParam("resultId") UUID result,@NotNull @Valid AiStudioService.Review input){return studio.reject(id,result,input,actor());}
    @PUT @Path("/jobs/{id}/results/{resultId}/copy") public Object copy(@PathParam("id") UUID id,@PathParam("resultId") UUID result,@NotNull @Valid AiStudioService.CopyUpdate input){return studio.updateCopy(id,result,input,actor());}
    @POST @Path("/jobs/{id}/results/{resultId}/regenerate-copy") public Object rewrite(@PathParam("id") UUID id,@PathParam("resultId") UUID result,@NotNull @Valid AiStudioService.Review input){return studio.reject(id,result,new AiStudioService.Review("COPY",input.feedback()),actor());}
    @POST @Path("/jobs/{id}/apply") public Object apply(@PathParam("id") UUID id,@NotNull @Valid AiStudioService.ApplyRequest input){return studio.apply(id,input,actor());}
    @GET @Path("/products/{id}/jobs") public Object productJobs(@PathParam("id") UUID id){return studio.productJobs(id,actor());}
    @GET @Path("/home/jobs") public Object homeJobs(){return studio.homeJobs(actor());}
    @GET @Path("/eligible-media") public Object eligible(){return studio.eligibleMedia();}
}
