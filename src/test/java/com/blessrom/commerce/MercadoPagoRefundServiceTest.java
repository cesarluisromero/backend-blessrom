package com.blessrom.commerce;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoRefundServiceTest {
    @Test
    void parsesProcessedRefund() {
        var response=Map.<String,Object>of("transactions",Map.of("refunds",List.of(Map.of(
            "id","REF01TEST","status","processed","amount","59.90"
        ))));
        var result=MercadoPagoRefundService.parse(response,new BigDecimal("59.90"));
        assertEquals("REF01TEST",result.id());
        assertEquals("processed",result.status());
        assertEquals(new BigDecimal("59.90"),result.amount());
    }

    @Test
    void rejectsUnexpectedAmountOrStatus() {
        var wrongAmount=Map.<String,Object>of("transactions",Map.of("refunds",List.of(Map.of(
            "id","REF01TEST","status","processed","amount","10.00"
        ))));
        var pending=Map.<String,Object>of("transactions",Map.of("refunds",List.of(Map.of(
            "id","REF01TEST","status","pending","amount","59.90"
        ))));
        assertThrows(Problem.class,()->MercadoPagoRefundService.parse(wrongAmount,new BigDecimal("59.90")));
        assertThrows(Problem.class,()->MercadoPagoRefundService.parse(pending,new BigDecimal("59.90")));
    }
}
