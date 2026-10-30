package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {
    @Test void providesCustomerFacingTitlesForEveryOrderEvent() {
        for(String event:new String[]{"ORDER_CREATED","PAYMENT_CONFIRMED","PAYMENT_REJECTED","PREPARING","SHIPPED","DELIVERED","CANCELLED","EXPIRED"})
            assertFalse(NotificationService.title(event).isBlank());
    }

    @Test void sanitizesDeliveryErrorsForStorage() {
        String value=NotificationDelivery.error(new RuntimeException("línea uno\nlínea dos"));
        assertFalse(value.contains("\n"));
        assertTrue(value.length()<=500);
    }
}
