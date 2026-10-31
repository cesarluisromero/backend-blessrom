package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReturnLifecycleTest {
    @Test void followsReviewReceiptAndResolution() {
        assertTrue(ReturnService.allowed("REQUESTED","APPROVED"));
        assertTrue(ReturnService.allowed("REQUESTED","REJECTED"));
        assertTrue(ReturnService.allowed("APPROVED","RECEIVED"));
        assertTrue(ReturnService.allowed("RECEIVED","REFUNDED"));
        assertTrue(ReturnService.allowed("RECEIVED","EXCHANGED"));
    }
    @Test void rejectsSkippingAndTerminalChanges() {
        assertFalse(ReturnService.allowed("REQUESTED","REFUNDED"));
        assertFalse(ReturnService.allowed("APPROVED","EXCHANGED"));
        assertFalse(ReturnService.allowed("REJECTED","APPROVED"));
        assertFalse(ReturnService.allowed("REFUNDED","RECEIVED"));
    }
}
