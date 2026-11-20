package com.blessrom.commerce;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.*;

@Path("/api/v1/complaints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public class ComplaintResource {
    @Inject Db db;
    @Inject Json json;

    @RegisterForReflection public record Consumer(String surnames,String names,String documentType,String documentNumber,
        String phone,String email,String address,String department,String city,boolean minor,String representativeName,
        String representativeDocument,String representativePhone,String representativeEmail) {}
    @RegisterForReflection public record Purchase(String description,BigDecimal amount,String orderNumber) {}
    @RegisterForReflection public record Submit(String kind,Consumer consumer,Purchase purchase,String detail,String requestText,boolean confirmed) {}
    @RegisterForReflection public record Answer(String response) {}

    @POST @PermitAll
    public Map<String,Object> submit(Submit input) {
        validate(input);
        var settings=settings();
        if(settings==null||!settings.enabled()||!SiteInformationResource.complete(settings))
            throw new Problem(503,"COMPLAINTS_UNAVAILABLE","El Libro de Reclamaciones no está configurado. Comunícate con la tienda.");
        UUID id=UUID.randomUUID();
        var provider=Map.of("businessName",settings.businessName(),"ruc",settings.ruc(),"address",settings.address());
        var row=db.one("INSERT INTO complaints_book(id,kind,consumer,purchase,detail,request_text,provider) VALUES(?,?,?::jsonb,?::jsonb,?, ?,?::jsonb) RETURNING number,created_at",
            id,input.kind(),json.write(input.consumer()),json.write(input.purchase()),input.detail().trim(),input.requestText().trim(),json.write(provider));
        String number="BLS-LR-%06d".formatted(((Number)row.get("number")).longValue());
        String body="Hoja de reclamación "+number+"\nFecha: "+row.get("created_at")+"\nProveedor: "+settings.businessName()+" · RUC "+settings.ruc()+" · "+settings.address()
            +"\nTipo: "+input.kind()+"\nConsumidor: "+input.consumer().names()+" "+input.consumer().surnames()
            +"\nDocumento: "+input.consumer().documentType()+" "+input.consumer().documentNumber()
            +"\nContacto: "+input.consumer().phone()+" · "+input.consumer().email()
            +"\nDomicilio: "+input.consumer().address()+", "+input.consumer().city()+", "+input.consumer().department()
            +(input.consumer().minor()?"\nRepresentante: "+input.consumer().representativeName()+" · Documento "+input.consumer().representativeDocument()+" · "+input.consumer().representativePhone()+" · "+input.consumer().representativeEmail():"")
            +"\nProducto o servicio: "+input.purchase().description()+"\nMonto: S/ "+input.purchase().amount()
            +(blank(input.purchase().orderNumber())?"":"\nNúmero de pedido: "+input.purchase().orderNumber().trim())
            +"\nDetalle: "+input.detail().trim()+"\nPedido: "+input.requestText().trim()
            +"\n\nLa presentación de esta hoja no impide acudir a otras vías de solución. Responderemos en un plazo máximo de 15 días hábiles.";
        queue(id,input.consumer().email(),"Constancia del Libro de Reclamaciones "+number,body);
        if(!settings.email().equalsIgnoreCase(input.consumer().email()))queue(id,settings.email(),"Nuevo "+input.kind().toLowerCase(Locale.ROOT)+" "+number,body);
        return Map.of("number",number,"createdAt",row.get("created_at"),"receipt",body);
    }

    @GET @Path("/admin") @RolesAllowed("admin")
    public Map<String,Object> list(@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("20") int size) {
        if(page<0||size<1||size>50)throw new Problem(400,"INVALID_PAGE","Página inválida.");
        var items=db.rows("SELECT id,number,kind,status,consumer->>'names' AS names,consumer->>'surnames' AS surnames,created_at FROM complaints_book ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",size,page*size);
        return Map.of("items",items,"page",page,"size",size,"total",db.one("SELECT count(*) AS total FROM complaints_book").get("total"));
    }

    @GET @Path("/admin/{id}") @RolesAllowed("admin")
    public Map<String,Object> get(@PathParam("id") UUID id) {
        var row=db.one("SELECT id,number,kind,status,consumer,purchase,detail,request_text,provider,response_text,response_at,created_at FROM complaints_book WHERE id=?",id);
        for(var field:List.of("consumer","purchase","provider"))row.put(field,json.read(row.get(field),Map.class));
        return row;
    }

    @POST @Path("/admin/{id}/answer") @RolesAllowed("admin")
    public Map<String,Object> answer(@PathParam("id") UUID id,Answer input) {
        if(input==null||blank(input.response())||input.response().length()>10000)throw new Problem(400,"RESPONSE_REQUIRED","Escribe una respuesta de hasta 10000 caracteres.");
        var row=db.one("SELECT number,status,consumer FROM complaints_book WHERE id=? FOR UPDATE",id);
        if("RESPONDIDO".equals(row.get("status")))throw Problem.conflict("ALREADY_ANSWERED","La hoja ya tiene respuesta.");
        db.execute("UPDATE complaints_book SET status='RESPONDIDO',response_text=?,response_at=now() WHERE id=?",input.response().trim(),id);
        @SuppressWarnings("unchecked") var consumer=json.read(row.get("consumer"),Map.class);
        queue(id,String.valueOf(consumer.get("email")),"Respuesta a hoja BLS-LR-%06d".formatted(((Number)row.get("number")).longValue()),input.response().trim());
        return get(id);
    }

    private SiteInformationResource.ComplaintSettings settings() {
        var content=json.read(db.one("SELECT published FROM site_information WHERE id=1").get("published"),SiteInformationResource.Content.class);
        return content.complaints();
    }
    private void queue(UUID id,String recipient,String subject,String body) {
        db.execute("INSERT INTO complaint_email_outbox(id,complaint_id,recipient,subject,body) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,recipient,subject,body);
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static void validate(Submit x) {
        if(x==null||!("RECLAMO".equals(x.kind())||"QUEJA".equals(x.kind()))||!x.confirmed()||x.consumer()==null||x.purchase()==null)
            throw new Problem(400,"INVALID_COMPLAINT","Completa y confirma la hoja de reclamación.");
        var c=x.consumer();var p=x.purchase();
        if(blank(c.surnames())||c.surnames().length()>120||blank(c.names())||c.names().length()>120
            ||!("DNI".equals(c.documentType())||"CE".equals(c.documentType())||"PASAPORTE".equals(c.documentType())||"OTRO".equals(c.documentType()))||blank(c.documentNumber())||c.documentNumber().length()>30
            ||blank(c.phone())||c.phone().length()>30||blank(c.email())||c.email().length()>254||!c.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
            ||blank(c.address())||c.address().length()>300||blank(c.department())||c.department().length()>100||blank(c.city())||c.city().length()>100
            ||(c.minor()&&(blank(c.representativeName())||c.representativeName().length()>200
                ||blank(c.representativeDocument())||c.representativeDocument().length()>30
                ||blank(c.representativePhone())||c.representativePhone().length()>30
                ||blank(c.representativeEmail())||c.representativeEmail().length()>254
                ||!c.representativeEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")))
            ||blank(p.description())||p.description().length()>1000||p.amount()==null||p.amount().signum()<0||p.amount().compareTo(new BigDecimal("99999999"))>0
            ||(p.orderNumber()!=null&&p.orderNumber().length()>40)||blank(x.detail())||x.detail().length()>5000||blank(x.requestText())||x.requestText().length()>5000)
            throw new Problem(400,"INVALID_COMPLAINT","Revisa los datos de la hoja de reclamación.");
    }
}
