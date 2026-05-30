package com.hunt.otziv.payments.model;

import java.util.Locale;

public enum TbankPaymentPageMode {
    SBP_PRIMARY,
    BANK_PRIMARY,
    SBP_PAY_ONLY,
    SBP_ONLY,
    BANK_ONLY;

    public static TbankPaymentPageMode from(String value, TbankPaymentPageMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? SBP_PRIMARY : fallback;
        }
        try {
            return TbankPaymentPageMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback == null ? SBP_PRIMARY : fallback;
        }
    }
}
