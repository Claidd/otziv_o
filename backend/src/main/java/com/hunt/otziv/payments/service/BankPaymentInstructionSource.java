package com.hunt.otziv.payments.service;

import java.util.Locale;
import java.util.Set;

/** Provider-neutral parser for the client payment-instruction setting. */
public final class BankPaymentInstructionSource {

    public static final String MANAGER_TEXT = "MANAGER_TEXT";
    public static final String TBANK_LINK = "TBANK_LINK";
    public static final String TOCHKA_LINK = "TOCHKA_LINK";
    public static final String BANK_LINK = "BANK_LINK";

    private static final Set<String> BANK_LINK_SOURCES = Set.of(
            TBANK_LINK,
            TOCHKA_LINK,
            BANK_LINK
    );

    private BankPaymentInstructionSource() {
    }

    public static boolean isBankLink(String value) {
        return BANK_LINK_SOURCES.contains(clean(value));
    }

    public static String normalize(String value, String fallback) {
        String clean = clean(value);
        if (MANAGER_TEXT.equals(clean) || BANK_LINK_SOURCES.contains(clean)) {
            return clean;
        }
        String cleanFallback = clean(fallback);
        return MANAGER_TEXT.equals(cleanFallback) || BANK_LINK_SOURCES.contains(cleanFallback)
                ? cleanFallback
                : MANAGER_TEXT;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
