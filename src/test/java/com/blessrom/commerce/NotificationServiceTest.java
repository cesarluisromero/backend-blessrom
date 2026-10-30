package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {
    @Test void providesCustomerFacingTitlesForEveryOrderEvent() {
        for(String event:new String[]{"ORDER_CREATED","PAYMENT_CONFIRMED","PAYMENT_REJECTED","PREPARING","READY_TO_SHIP","SHIPPED","DELIVERED","CANCELLED","EXPIRED"})
            assertFalse(NotificationService.title(event).isBlank());
    }

    @Test void sanitizesDeliveryErrorsForStorage() {
        String value=NotificationDelivery.error(new RuntimeException("línea uno\nlínea dos"));
        assertFalse(value.contains("\n"));
        assertTrue(value.length()<=500);
    }

    @Test void rendersBrandedMultipartContentForEveryOrderEvent() {
        UUID orderId=UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        for(String event:new String[]{"ORDER_CREATED","PAYMENT_CONFIRMED","PAYMENT_REJECTED","PREPARING","READY_TO_SHIP","SHIPPED","DELIVERED","CANCELLED","EXPIRED"}) {
            var email=OrderEmailTemplate.render(event,"César",orderId,new BigDecimal("109.90"),null);
            assertTrue(email.subject().startsWith("Blessrom · "));
            assertTrue(email.text().contains(orderId.toString()));
            assertTrue(email.html().contains("BLESSROM"));
            assertTrue(email.html().contains("S/"));
            assertTrue(email.html().contains("soporte@blessrom.com"));
        }
    }

    @Test void escapesCustomerAndOperatorSuppliedContent() {
        var email=OrderEmailTemplate.render("PAYMENT_REJECTED","<script>alert(1)</script>",UUID.randomUUID(),BigDecimal.TEN,"<img src=x onerror=alert(1)>");
        assertFalse(email.html().contains("<script>"));
        assertFalse(email.html().contains("<img src=x"));
        assertTrue(email.html().contains("&lt;script&gt;"));
        assertTrue(email.html().contains("&lt;img src=x"));
    }
}
