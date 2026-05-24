package com.hunt.otziv.payments;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TbankTokenSignerTest {

    private final TbankTokenSigner signer = new TbankTokenSigner();

    @Test
    void signsOnlyRootFieldsAndSkipsTokenAndNestedValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("TerminalKey", "Test");
        values.put("Amount", 1000);
        values.put("OrderId", "1");
        values.put("Receipt", Map.of("Email", "client@example.ru"));
        values.put("DATA", Map.of("Email", "client@example.ru"));
        values.put("Items", List.of(Map.of("Name", "Service")));
        values.put("Token", "old-token");

        assertEquals(
                "bb385a652fced918567bdb7e692fab14dd43bf85171244c5ea73ccbb8c07a9dc",
                signer.sign(values, "secret")
        );
    }

    @Test
    void matchesOfficialNotificationTokenExample() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("TerminalKey", "1234567890DEMO");
        values.put("OrderId", "000000");
        values.put("Success", "true");
        values.put("Status", "AUTHORIZED");
        values.put("PaymentId", "0000000");
        values.put("ErrorCode", "0");
        values.put("Amount", "1111");
        values.put("CardId", "000000");
        values.put("Pan", "200000******0000");
        values.put("ExpDate", "1111");
        values.put("RebillId", "000000");

        assertTrue(signer.matches(
                values,
                "11111111111",
                "1c0964277d0213349243065a0d5b838b8e90d2d25f740d0f2767836e710e80c8"
        ));
    }
}
