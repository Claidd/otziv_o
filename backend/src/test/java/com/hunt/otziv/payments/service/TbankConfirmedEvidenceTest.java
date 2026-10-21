package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TbankConfirmedEvidenceTest {

    @Test
    void confirmedWebhookRequiresConsistentSuccessPaymentIdentityAndPositiveAmount() {
        Map<String, String> valid = new LinkedHashMap<>(Map.of(
                "Status", "CONFIRMED", "Success", "true", "ErrorCode", "0",
                "PaymentId", "bank-123", "Amount", "12500"));
        assertDoesNotThrow(() -> TbankConfirmedEvidence.requireValidWebhook(valid));

        for (Map.Entry<String, String> invalid : Map.of(
                "Success", "false", "ErrorCode", "5", "PaymentId", "  ", "Amount", "0").entrySet()) {
            Map<String, String> payload = new LinkedHashMap<>(valid);
            payload.put(invalid.getKey(), invalid.getValue());
            assertThrows(ResponseStatusException.class,
                    () -> TbankConfirmedEvidence.requireValidWebhook(payload), invalid.getKey());
        }
        for (String missing : new String[]{"Success", "ErrorCode", "PaymentId", "Amount"}) {
            Map<String, String> payload = new LinkedHashMap<>(valid);
            payload.remove(missing);
            assertThrows(ResponseStatusException.class,
                    () -> TbankConfirmedEvidence.requireValidWebhook(payload), missing);
        }
    }

    @Test
    void getStateConfirmationRequiresFullProviderEvidence() {
        assertTrue(TbankConfirmedEvidence.validGetStateConfirmation(state(true, "0", "123", 12500L)));
        assertFalse(TbankConfirmedEvidence.validGetStateConfirmation(state(false, "0", "123", 12500L)));
        assertFalse(TbankConfirmedEvidence.validGetStateConfirmation(state(true, "5", "123", 12500L)));
        assertFalse(TbankConfirmedEvidence.validGetStateConfirmation(state(true, "0", null, 12500L)));
        assertFalse(TbankConfirmedEvidence.validGetStateConfirmation(state(true, "0", "123", null)));
        assertFalse(TbankConfirmedEvidence.validGetStateConfirmation(state(true, "0", "123", 0L)));
    }

    private TbankGetStateResponse state(boolean success, String errorCode, String paymentId, Long amount) {
        return new TbankGetStateResponse(success, errorCode, null, null,
                "terminal", "CONFIRMED", paymentId, "order-123", amount);
    }
}
