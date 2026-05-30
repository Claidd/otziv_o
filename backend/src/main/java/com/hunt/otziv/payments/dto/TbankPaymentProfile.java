package com.hunt.otziv.payments.dto;

public record TbankPaymentProfile(
        Long id,
        String code,
        String name,
        boolean enabled,
        String terminalKey,
        String password,
        boolean testMode
) {
    public static final String PRIMARY_CODE = "primary";
    public static final String SECONDARY_CODE = "secondary";

    public TbankPaymentProfile {
        code = safe(code);
        name = safe(name);
        terminalKey = safe(terminalKey);
        password = safe(password);
    }

    public boolean hasCredentials() {
        return !terminalKey.isBlank() && !password.isBlank();
    }

    public boolean matchesTerminalKey(String value) {
        String clean = safe(value);
        return !clean.isBlank() && clean.equals(terminalKey);
    }

    public String displayName() {
        if (!name.isBlank()) {
            return name;
        }
        return code.isBlank() ? "T-Bank" : code;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
