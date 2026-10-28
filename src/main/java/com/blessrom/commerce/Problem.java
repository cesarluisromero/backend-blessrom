package com.blessrom.commerce;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;
import java.util.Map;
public class Problem extends RuntimeException {
    final int status; final String code;
    public Problem(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    static Problem missing() { return new Problem(404,"NOT_FOUND","Recurso no encontrado."); }
    static Problem conflict(String code,String message) { return new Problem(409,code,message); }
    static void version(long actual,long expected) {
        if(actual!=expected) throw conflict("VERSION_CONFLICT","La información cambió; vuelve a consultarla.");
    }
    @Provider public static class Mapper implements ExceptionMapper<Problem> {
        public Response toResponse(Problem e) { return Response.status(e.status).entity(Map.of("code",e.code,"message",e.getMessage())).build(); }
    }
}

