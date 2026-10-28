package com.blessrom.system;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/system")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sistema", description = "Información pública del servicio Blessrom")
public class SystemResource {
    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @GET
    @Operation(summary = "Consultar la información del backend")
    public SystemInfo information() {
        return new SystemInfo("Blessrom", "backend-blessrom", version, "PE", "PEN");
    }

    public record SystemInfo(String brand, String service, String version,
                             String country, String currency) {}
}
