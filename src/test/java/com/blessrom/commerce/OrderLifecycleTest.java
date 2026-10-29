package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderLifecycleTest {
    @Test void permitsOnlyTheOperationalSequence() {
        assertTrue(OrderService.allowed("PENDING_PAYMENT","PAYMENT_CONFIRMED"));
        assertTrue(OrderService.allowed("PENDING_PAYMENT","CANCELLED"));
        assertTrue(OrderService.allowed("PAYMENT_CONFIRMED","PREPARING"));
        assertTrue(OrderService.allowed("PREPARING","SHIPPED"));
        assertTrue(OrderService.allowed("SHIPPED","DELIVERED"));
    }

    @Test void rejectsSkipsBacktrackingAndTerminalChanges() {
        assertFalse(OrderService.allowed("PENDING_PAYMENT","SHIPPED"));
        assertFalse(OrderService.allowed("PREPARING","PAYMENT_CONFIRMED"));
        assertFalse(OrderService.allowed("DELIVERED","CANCELLED"));
        assertFalse(OrderService.allowed("CANCELLED","PAYMENT_CONFIRMED"));
        assertFalse(OrderService.allowed("EXPIRED","PAYMENT_CONFIRMED"));
    }
}
