package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Provider identity compatibility and opaque tokens, independent of queries or workflows. */
final class CommonInvoicePaymentIdentity {
    private static final SecureRandom RANDOM = new SecureRandom();

    private CommonInvoicePaymentIdentity() {}

    static String providerOrderId(CommonInvoicePaymentRef ref) {
        String value = normalize(ref == null ? null : ref.getProviderOrderId());
        return value.isBlank() ? normalize(ref == null ? null : ref.getTbankOrderId()) : value;
    }

    static String providerMerchantId(CommonInvoicePaymentRef ref) {
        String value = normalize(ref == null ? null : ref.getProviderMerchantId());
        return value.isBlank() ? normalize(ref == null ? null : ref.getTbankTerminalKey()) : value;
    }

    static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String maskPaymentId(String paymentId) {
        String clean = normalize(paymentId);
        if (clean.isBlank()) return "—";
        if (clean.length() <= 4) return "****";
        return "****" + clean.substring(clean.length() - 4);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
