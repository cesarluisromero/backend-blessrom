package com.blessrom.commerce;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;

final class OrderEmailTemplate {
    private static final Locale PERU=Locale.forLanguageTag("es-PE");

    private OrderEmailTemplate() {}

    record Rendered(String subject,String text,String html) {}

    static Rendered render(String event,String name,UUID orderId,BigDecimal total,String detail) {
        String title=title(event);
        String message=message(event);
        String nextStep=nextStep(event);
        String amount=NumberFormat.getCurrencyInstance(PERU).format(total);
        String safeName=escape(name);
        String safeDetail=detail==null||detail.isBlank()?null:escape(detail);
        String detailText=safeDetail==null?"":"\nObservación: "+detail;
        String text="""
            Hola %s,

            %s
            %s

            %s%s

            Pedido: %s
            Total: %s

            ¿Necesitas ayuda? Escríbenos a soporte@blessrom.com.

            Blessrom · Tarapoto, Perú
            """.formatted(name,title,message,nextStep,detailText,orderId,amount);
        String observation=safeDetail==null?"":"""
            <tr><td style="padding:0 32px 24px">
              <div style="padding:14px 16px;background:#fff8e8;border-left:4px solid #c9a66b;color:#49391f;line-height:1.55">
                <strong>Observación</strong><br>%s
              </div>
            </td></tr>
            """.formatted(safeDetail);
        String html="""
            <!doctype html>
            <html lang="es">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#f3f5f7;font-family:Arial,Helvetica,sans-serif;color:#172b4d">
              <div style="display:none;max-height:0;overflow:hidden;opacity:0">%s · Pedido %s</div>
              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f3f5f7">
                <tr><td align="center" style="padding:28px 12px">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:640px;background:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 8px 30px rgba(19,42,74,.10)">
                    <tr><td style="padding:28px 32px;background:#132a4a;text-align:center">
                      <div style="font-family:Georgia,serif;font-size:30px;font-weight:bold;letter-spacing:2px;color:#ffffff">BLESSROM</div>
                      <div style="margin-top:7px;font-size:11px;letter-spacing:3px;text-transform:uppercase;color:#d9bd8a">Tu estilo · Tu naturaleza</div>
                    </td></tr>
                    <tr><td style="height:5px;background:#c9a66b;font-size:0">&nbsp;</td></tr>
                    <tr><td style="padding:34px 32px 18px">
                      <div style="display:inline-block;padding:7px 12px;border-radius:999px;background:#eaf1fa;color:#174f94;font-size:12px;font-weight:bold;letter-spacing:.6px;text-transform:uppercase">%s</div>
                      <h1 style="margin:20px 0 12px;font-family:Georgia,serif;font-size:30px;line-height:1.2;color:#132a4a">Hola %s</h1>
                      <p style="margin:0;font-size:16px;line-height:1.7;color:#465a73">%s</p>
                    </td></tr>
                    <tr><td style="padding:6px 32px 24px">
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="border:1px solid #dfe5ec;border-radius:12px">
                        <tr>
                          <td style="padding:18px 20px;border-bottom:1px solid #dfe5ec;color:#64748b;font-size:13px">Número de pedido</td>
                          <td align="right" style="padding:18px 20px;border-bottom:1px solid #dfe5ec;color:#132a4a;font-size:13px;font-weight:bold">%s</td>
                        </tr>
                        <tr>
                          <td style="padding:18px 20px;color:#64748b;font-size:14px">Total</td>
                          <td align="right" style="padding:18px 20px;color:#132a4a;font-size:20px;font-weight:bold">%s</td>
                        </tr>
                      </table>
                    </td></tr>
                    %s
                    <tr><td style="padding:0 32px 32px">
                      <div style="padding:18px 20px;border-radius:12px;background:#eef4fb">
                        <div style="margin-bottom:6px;color:#174f94;font-size:12px;font-weight:bold;letter-spacing:.5px;text-transform:uppercase">¿Qué sigue?</div>
                        <div style="color:#334e68;font-size:15px;line-height:1.6">%s</div>
                      </div>
                    </td></tr>
                    <tr><td style="padding:24px 32px;background:#f8fafc;text-align:center;color:#64748b;font-size:12px;line-height:1.7">
                      ¿Necesitas ayuda? <a href="mailto:soporte@blessrom.com" style="color:#174f94;text-decoration:none;font-weight:bold">soporte@blessrom.com</a><br>
                      Blessrom · Tarapoto, San Martín, Perú<br>
                      <span style="color:#94a3b8">Este es un mensaje automático relacionado con tu pedido.</span>
                    </td></tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(escape(title),orderId,statusLabel(event),safeName,escape(message),orderId,escape(amount),observation,escape(nextStep));
        return new Rendered("Blessrom · "+title,text,html);
    }

    static String title(String event) {
        return switch(event) {
            case "ORDER_CREATED" -> "Recibimos tu pedido";
            case "PAYMENT_CONFIRMED" -> "Tu pago fue aprobado";
            case "PAYMENT_REJECTED" -> "Necesitamos que revises tu pago";
            case "PREPARING" -> "Estamos preparando tu pedido";
            case "READY_TO_SHIP" -> "Tu pedido está listo para enviar";
            case "SHIPPED" -> "Tu pedido fue enviado";
            case "DELIVERED" -> "Tu pedido fue entregado";
            case "RETURN_REQUESTED" -> "Recibimos tu solicitud";
            case "RETURN_APPROVED" -> "Aprobamos tu devolución o cambio";
            case "RETURN_REJECTED" -> "Revisamos tu solicitud";
            case "RETURN_RECEIVED" -> "Recibimos los productos devueltos";
            case "RETURN_REFUNDED" -> "Tu reembolso fue registrado";
            case "RETURN_EXCHANGED" -> "Tu cambio fue completado";
            case "CANCELLED" -> "Tu pedido fue cancelado";
            case "EXPIRED" -> "La reserva de tu pedido venció";
            default -> "Actualización de tu pedido";
        };
    }

    private static String statusLabel(String event) {
        return switch(event) {
            case "ORDER_CREATED" -> "Pedido recibido";
            case "PAYMENT_CONFIRMED" -> "Pago aprobado";
            case "PAYMENT_REJECTED" -> "Pago por revisar";
            case "PREPARING" -> "En preparación";
            case "READY_TO_SHIP" -> "Listo para enviar";
            case "SHIPPED" -> "En camino";
            case "DELIVERED" -> "Entregado";
            case "RETURN_REQUESTED" -> "Solicitud recibida";
            case "RETURN_APPROVED" -> "Solicitud aprobada";
            case "RETURN_REJECTED" -> "Solicitud rechazada";
            case "RETURN_RECEIVED" -> "Devolución recibida";
            case "RETURN_REFUNDED" -> "Reembolso registrado";
            case "RETURN_EXCHANGED" -> "Cambio completado";
            case "CANCELLED" -> "Cancelado";
            case "EXPIRED" -> "Reserva vencida";
            default -> "Pedido actualizado";
        };
    }

    private static String message(String event) {
        return switch(event) {
            case "ORDER_CREATED" -> "Tu compra fue registrada correctamente. Hemos reservado las prendas mientras completas el pago.";
            case "PAYMENT_CONFIRMED" -> "Confirmamos tu pago. Tu pedido continuará ahora con la preparación.";
            case "PAYMENT_REJECTED" -> "No pudimos validar la información de pago que recibimos.";
            case "PREPARING" -> "Nuestro equipo ya está preparando cuidadosamente tu pedido.";
            case "READY_TO_SHIP" -> "Terminamos de preparar tu pedido y está listo para salir a reparto.";
            case "SHIPPED" -> "Tu pedido salió para entrega y pronto estará contigo.";
            case "DELIVERED" -> "Registramos la entrega de tu pedido. Gracias por elegir Blessrom.";
            case "RETURN_REQUESTED" -> "Registramos tu solicitud de devolución o cambio y nuestro equipo la revisará.";
            case "RETURN_APPROVED" -> "Tu solicitud fue aprobada. Sigue las indicaciones incluidas en la observación.";
            case "RETURN_REJECTED" -> "Terminamos de revisar la solicitud y encontrarás el motivo en la observación.";
            case "RETURN_RECEIVED" -> "Confirmamos la recepción de los productos asociados a tu solicitud.";
            case "RETURN_REFUNDED" -> "El reembolso fue registrado correctamente.";
            case "RETURN_EXCHANGED" -> "El cambio solicitado fue completado correctamente.";
            case "CANCELLED" -> "El pedido fue cancelado y el inventario reservado quedó liberado.";
            case "EXPIRED" -> "El plazo de reserva terminó antes de confirmarse el pago y el inventario quedó liberado.";
            default -> "Tenemos una actualización sobre el estado de tu pedido.";
        };
    }

    private static String nextStep(String event) {
        return switch(event) {
            case "ORDER_CREATED" -> "Completa o registra el pago dentro del plazo indicado para conservar la reserva.";
            case "PAYMENT_CONFIRMED" -> "Te avisaremos cuando comencemos a preparar tu compra.";
            case "PAYMENT_REJECTED" -> "Revisa los datos enviados o comunícate con soporte para recibir ayuda.";
            case "PREPARING" -> "Te notificaremos cuando terminemos de preparar tu compra.";
            case "READY_TO_SHIP" -> "Te avisaremos nuevamente en cuanto el pedido salga para entrega.";
            case "SHIPPED" -> "Mantente atento al teléfono registrado para coordinar la recepción.";
            case "DELIVERED" -> "Esperamos que disfrutes tu compra. Si necesitas ayuda, estamos para atenderte.";
            case "RETURN_REQUESTED" -> "Te enviaremos otro correo cuando el equipo termine la revisión.";
            case "RETURN_APPROVED" -> "Envía o entrega los productos siguiendo las instrucciones del equipo de Blessrom.";
            case "RETURN_REJECTED" -> "Puedes comunicarte con soporte si necesitas una aclaración adicional.";
            case "RETURN_RECEIVED" -> "Ahora verificaremos los productos y completaremos el reembolso o el cambio.";
            case "RETURN_REFUNDED" -> "Conserva la referencia informada para realizar el seguimiento del abono.";
            case "RETURN_EXCHANGED" -> "Conserva la referencia informada para realizar el seguimiento del cambio.";
            case "CANCELLED", "EXPIRED" -> "Si todavía deseas los productos, puedes realizar un nuevo pedido sujeto a disponibilidad.";
            default -> "Puedes comunicarte con soporte si necesitas más información.";
        };
    }

    static String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
}
