package com.hunt.otziv.logs.util;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

public final class LogMasking {

    private LogMasking() {
    }

    public static String maskPhone(String value) {
        if (!hasText(value)) {
            return "";
        }
        String digits = value.replaceAll("\\D+", "");
        if (digits.length() < 4) {
            return "***";
        }
        return "***" + digits.substring(digits.length() - 4);
    }

    public static String maskEmail(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return "***";
        }
        return trimmed.charAt(0) + "***@" + trimmed.substring(at + 1);
    }

    public static String maskToken(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "***";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    public static String maskPaymentId(String value) {
        return maskToken(value);
    }

    public static String maskPhones(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(LogMasking::maskPhone)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    public static int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
