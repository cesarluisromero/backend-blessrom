package com.blessrom.commerce;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MercadoPagoServiceTest {

    @Test
    void acceptsValidWebhookSignatureAndRejectsTampering() throws Exception {
        MercadoPagoService service = new MercadoPagoService();
        service.webhookSecret = Optional.of("test-webhook-secret");

        String dataId = "ORDTST01M6ZCHAZEXAMPLE123456789";
        String requestId = "request-123";
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        String manifest = "id:" + dataId.toLowerCase(java.util.Locale.ROOT) + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        String signature = "ts=" + timestamp + ",v1=" + hmac(manifest, "test-webhook-secret");

        assertDoesNotThrow(() -> service.validateSignature(signature, requestId, dataId));
        assertThrows(Problem.class,
                () -> service.validateSignature(signature, requestId, dataId + "0"));
    }

    private static String hmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
