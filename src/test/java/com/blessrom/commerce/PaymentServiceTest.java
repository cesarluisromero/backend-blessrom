package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentServiceTest {
    @Test void digitalMethodsRequireProof() {
        assertTrue(PaymentService.requiresProof("YAPE"));
        assertTrue(PaymentService.requiresProof("PLIN"));
        assertTrue(PaymentService.requiresProof("BANK_TRANSFER"));
    }

    @Test void cashOnDeliveryDoesNotRequireProof() {
        assertFalse(PaymentService.requiresProof("CASH_ON_DELIVERY"));
    }
}
