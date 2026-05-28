package com.hunt.otziv.payments;

import java.util.Locale;

public enum TbankRuntimeMode {
    TEST,
    LIVE;

    public boolean isTest() {
        return this == TEST;
    }

    public static TbankRuntimeMode from(String value, TbankRuntimeMode fallback) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("LIVE".equals(clean) || "PROD".equals(clean) || "PRODUCTION".equals(clean)) {
            return LIVE;
        }
        if ("TEST".equals(clean) || "DEMO".equals(clean)) {
            return TEST;
        }
        return fallback == null ? TEST : fallback;
    }
}
