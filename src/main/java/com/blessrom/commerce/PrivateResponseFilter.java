package com.blessrom.commerce;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
@Provider
public class PrivateResponseFilter implements ContainerResponseFilter {
    @Override public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        String path=request.getUriInfo().getPath().replaceFirst("^/","");
        if(path.startsWith("api/v1/carts")||path.startsWith("api/v1/orders")||path.startsWith("api/v1/payments")||path.startsWith("api/v1/admin")||path.startsWith("api/v1/account")) {
            response.getHeaders().putSingle("Cache-Control","no-store");
            response.getHeaders().putSingle("Pragma","no-cache");
        }
    }
}

