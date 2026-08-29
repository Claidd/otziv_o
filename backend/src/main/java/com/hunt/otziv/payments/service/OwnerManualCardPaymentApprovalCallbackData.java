package com.hunt.otziv.payments.service;

final class OwnerManualCardPaymentApprovalCallbackData {

    private static final String PREFIX = "ompa:a:";

    private OwnerManualCardPaymentApprovalCallbackData() {
    }

    static String encode(Long approvalId, String token) {
        return PREFIX + approvalId + ":" + token;
    }

    static Parsed parse(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = value.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            return new Parsed(null, "");
        }
        try {
            return new Parsed(Long.parseLong(parts[0]), parts[1]);
        } catch (NumberFormatException exception) {
            return new Parsed(null, "");
        }
    }

    record Parsed(Long approvalId, String token) {
    }
}
