package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Minimum provider evidence required before a T-Bank CONFIRMED observation credits an order. */
public final class TbankConfirmedEvidence {

    private TbankConfirmedEvidence() {
    }

    public static void requireValidWebhook(Map<String, String> payload) {
        if (!"CONFIRMED".equalsIgnoreCase(clean(payload == null ? null : payload.get("Status")))) {
            return;
        }
        if (!"true".equalsIgnoreCase(clean(payload.get("Success")))
                || !"0".equals(clean(payload.get("ErrorCode")))
                || clean(payload.get("PaymentId")).isBlank()
                || !positiveAmount(payload.get("Amount"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CONFIRMED Т-Банка без успешного ответа, PaymentId и положительной суммы требует сверки");
        }
    }

    public static boolean validGetStateConfirmation(TbankGetStateResponse state) {
        return state != null
                && state.success()
                && "0".equals(clean(state.errorCode()))
                && !clean(state.paymentId()).isBlank()
                && state.amount() != null
                && state.amount() > 0;
    }

    private static boolean positiveAmount(String value) {
        try {
            return Long.parseLong(clean(value)) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
