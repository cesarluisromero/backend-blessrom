package com.blessrom.commerce;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped @Transactional
public class PaymentService {
    private static final Set<String> PROOF_TYPES=Set.of("image/jpeg","image/png","image/webp");
    @Inject Db db;
    @Inject OrderService orders;
    @Inject NotificationService notifications;

    public record Proof(String contentType,byte[] data) {}

    public void submit(UUID orderId,String token,Api.PaymentSubmission input) {
        var order=db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",orderId);
        Tokens.authorize(token,order,"access_expires_at");
        if(!"PENDING_PAYMENT".equals(order.get("status")))
            throw Problem.conflict("PAYMENT_CLOSED","El pedido ya no admite información de pago.");
        validate(input);
        var existing=db.rows("SELECT version,status FROM order_payments WHERE order_id=? FOR UPDATE",orderId);
        if(existing.isEmpty()) {
            db.execute("INSERT INTO order_payments(order_id,method,reference,proof_content_type,proof_data) VALUES(?,?,?,?,?)",
                orderId,input.method(),clean(input.reference()),input.proofContentType(),input.proof());
        } else {
            var payment=existing.getFirst();
            if("SUBMITTED".equals(payment.get("status"))) return;
            if(!"REJECTED".equals(payment.get("status")))
                throw Problem.conflict("PAYMENT_ALREADY_SUBMITTED","El comprobante ya está pendiente de revisión.");
            db.execute("UPDATE order_payments SET version=version+1,status='SUBMITTED',method=?,reference=?,proof_content_type=?,proof_data=?,submitted_at=now(),reviewed_at=NULL,reviewed_by=NULL,review_note=NULL WHERE order_id=?",
                input.method(),clean(input.reference()),input.proofContentType(),input.proof(),orderId);
        }
    }

    public void review(UUID orderId,Api.PaymentReview input,String actor) {
        db.one("SELECT id FROM orders WHERE id=? FOR UPDATE",orderId);
        var payment=db.one("SELECT * FROM order_payments WHERE order_id=? FOR UPDATE",orderId);
        Problem.version(Db.version(payment),input.paymentVersion());
        if(!"SUBMITTED".equals(payment.get("status")))
            throw Problem.conflict("PAYMENT_ALREADY_REVIEWED","El pago ya fue revisado.");
        int updated=db.execute("UPDATE order_payments SET version=version+1,status=?,reviewed_at=now(),reviewed_by=?,review_note=? WHERE order_id=? AND version=?",
            input.decision(),actor,clean(input.note()),orderId,input.paymentVersion());
        if(updated!=1) throw Problem.conflict("VERSION_CONFLICT","El pago cambió; vuelve a consultarlo.");
        if("APPROVED".equals(input.decision()))
            orders.transition(orderId,new Api.OrderStatus(input.orderVersion(),"PAYMENT_CONFIRMED"),actor);
        else notifications.enqueue(orderId,"PAYMENT_REJECTED",clean(input.note()));
    }

    public Proof proof(UUID orderId) {
        var row=db.one("SELECT proof_content_type,proof_data FROM order_payments WHERE order_id=? AND proof_data IS NOT NULL",orderId);
        return new Proof(row.get("proof_content_type").toString(),(byte[])row.get("proof_data"));
    }

    static boolean requiresProof(String method) { return !"CASH_ON_DELIVERY".equals(method); }
    private void validate(Api.PaymentSubmission input) {
        String reference=clean(input.reference());
        if(requiresProof(input.method())) {
            if(reference==null) throw new Problem(400,"PAYMENT_REFERENCE_REQUIRED","Ingresa el número de operación o referencia.");
            if(input.proof()==null||input.proof().length==0) throw new Problem(400,"PAYMENT_PROOF_REQUIRED","Adjunta el comprobante del pago.");
            if(!PROOF_TYPES.contains(input.proofContentType())) throw new Problem(400,"INVALID_PAYMENT_PROOF","El comprobante debe ser JPG, PNG o WebP.");
        } else if(input.proof()!=null&&input.proof().length>0&&!PROOF_TYPES.contains(input.proofContentType())) {
            throw new Problem(400,"INVALID_PAYMENT_PROOF","El comprobante debe ser JPG, PNG o WebP.");
        }
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
}
