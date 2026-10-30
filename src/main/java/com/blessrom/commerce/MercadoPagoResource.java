package com.blessrom.commerce;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.UUID;

@Path("/api/v1/payments/mercado-pago") @Produces(MediaType.APPLICATION_JSON) @PermitAll
public class MercadoPagoResource {
    @Inject MercadoPagoService payments;
    @POST @Path("/orders/{id}/checkout") @Consumes("*/*") public Object checkout(@PathParam("id") UUID id,@HeaderParam("X-Cart-Token") String token){return payments.checkout(id,token);}
    @POST @Path("/webhook") @Consumes(MediaType.APPLICATION_JSON)
    public Response webhook(@HeaderParam("x-signature") String signature,@HeaderParam("x-request-id") String requestId,@QueryParam("data.id") String dataId){payments.webhook(signature,requestId,dataId);return Response.ok().build();}
}
