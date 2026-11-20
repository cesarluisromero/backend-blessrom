package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

@Path("/api/v1/data-deletion-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public class DataDeletionResource {
    @Inject Db db;

    @RegisterForReflection public record Submit(String fullName,String documentType,String documentNumber,String whatsapp,String email,String reason,boolean confirmed) {}
    @RegisterForReflection public record Update(String status,String note) {}

    @POST @PermitAll
    public Map<String,Object> submit(Submit input) {
        if(input==null||!input.confirmed()||blank(input.fullName())||input.fullName().length()>200
            ||!("DNI".equals(input.documentType())||"CE".equals(input.documentType())||"PASAPORTE".equals(input.documentType())||"OTRO".equals(input.documentType()))
            ||blank(input.documentNumber())||input.documentNumber().length()>30
            ||blank(input.whatsapp())||!input.whatsapp().matches("\\+[1-9][0-9 ]{7,18}")
            ||blank(input.email())||input.email().length()>254||!input.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
            ||input.reason()!=null&&input.reason().length()>2000)
            throw new Problem(400,"INVALID_DATA_DELETION_REQUEST","Revisa los datos de la solicitud.");
        UUID id=UUID.randomUUID();
        var row=db.one("INSERT INTO data_deletion_requests(id,full_name,document_type,document_number,whatsapp,email,reason) VALUES(?,?,?,?,?,?,?) RETURNING number,created_at",
            id,input.fullName().trim(),input.documentType(),input.documentNumber().trim(),input.whatsapp().trim(),input.email().trim(),input.reason()==null?null:input.reason().trim());
        String number="BLS-DAT-%06d".formatted(((Number)row.get("number")).longValue());
        String body="Solicitud de eliminación de datos "+number+"\nFecha: "+row.get("created_at")
            +"\nTitular: "+input.fullName().trim()+"\nDocumento: "+input.documentType()+" "+input.documentNumber().trim()
            +"\nWhatsApp: "+input.whatsapp().trim()+"\nCorreo: "+input.email().trim()
            +"\nMotivo: "+(blank(input.reason())?"No indicado":input.reason().trim())
            +"\n\nBlessrom verificará la identidad y evaluará la solicitud. Algunos datos pueden conservarse cuando exista una obligación legal aplicable.";
        queue(id,input.email().trim(),"Constancia de solicitud "+number,body);
        return Map.of("number",number,"createdAt",row.get("created_at"),"receipt",body);
    }

    @GET @Path("/admin") @RolesAllowed("admin")
    public Map<String,Object> list(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) {
        if(page<0||size<1||size>50)throw new Problem(400,"INVALID_PAGE","Página inválida.");
        return Map.of("items",db.rows("SELECT id,number,full_name,status,created_at FROM data_deletion_requests ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",size,page*size),
            "page",page,"size",size,"total",db.one("SELECT count(*) AS total FROM data_deletion_requests").get("total"));
    }

    @GET @Path("/admin/{id}") @RolesAllowed("admin")
    public Map<String,Object> get(@PathParam("id") UUID id) {
        return db.one("SELECT id,number,full_name,document_type,document_number,whatsapp,email,reason,status,admin_note,created_at,resolved_at FROM data_deletion_requests WHERE id=?",id);
    }

    @POST @Path("/admin/{id}/status") @RolesAllowed("admin")
    public Map<String,Object> update(@PathParam("id") UUID id,Update input) {
        if(input==null||!("EN_REVISION".equals(input.status())||"RESUELTA".equals(input.status())||"RECHAZADA".equals(input.status()))
            ||("RECHAZADA".equals(input.status())&&blank(input.note()))||(input.note()!=null&&input.note().length()>5000))
            throw new Problem(400,"INVALID_STATUS","Indica un estado y una explicación válida.");
        var current=db.one("SELECT status,email,number FROM data_deletion_requests WHERE id=? FOR UPDATE",id);
        if("RESUELTA".equals(current.get("status"))||"RECHAZADA".equals(current.get("status")))
            throw Problem.conflict("REQUEST_CLOSED","La solicitud ya fue cerrada.");
        db.execute("UPDATE data_deletion_requests SET status=?,admin_note=?,resolved_at=CASE WHEN ?='EN_REVISION' THEN NULL ELSE now() END WHERE id=?",
            input.status(),input.note()==null?null:input.note().trim(),input.status(),id);
        if(!"EN_REVISION".equals(input.status())){
            String number="BLS-DAT-%06d".formatted(((Number)current.get("number")).longValue());
            queue(id,current.get("email").toString(),"Respuesta a solicitud "+number,
                "Solicitud "+number+"\nEstado: "+input.status()+"\n"+(input.note()==null?"":input.note().trim()));
        }
        return get(id);
    }

    private void queue(UUID id,String recipient,String subject,String body) {
        db.execute("INSERT INTO data_deletion_email_outbox(id,request_id,recipient,subject,body) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,recipient,subject,body);
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
}
