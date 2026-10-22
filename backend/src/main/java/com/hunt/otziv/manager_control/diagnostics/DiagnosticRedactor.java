package com.hunt.otziv.manager_control.diagnostics;

import java.util.regex.Pattern;

/** Used before persistence and before every external model request. Never exports request envelopes. */
public final class DiagnosticRedactor {
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization|bearer|api[_-]?key|password|secret|token|cookie)[\\\"']?\\s*[=: ]+\\s*[\\\"']?[^\\s,;\\\"'}]+", Pattern.UNICODE_CASE);
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)\\b(authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]+");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----", Pattern.DOTALL);
    private static final Pattern API_TOKEN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{10,}\\b");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<![\\w-])(?:\\+\\d[\\d ()-]{8,}\\d|\\d{11,15})(?![\\w-])");
    private DiagnosticRedactor() { }

    public static String redact(Object value) {
        String text = value == null ? "неизвестно" : value.toString();
        text = PRIVATE_KEY.matcher(text).replaceAll("[ключ скрыт]");
        text = AUTH_HEADER.matcher(text).replaceAll("$1: [скрыто]");
        text = API_TOKEN.matcher(text).replaceAll("[токен скрыт]");
        text = SECRET.matcher(text).replaceAll("$1=[скрыто]");
        text = URL.matcher(text).replaceAll("[ссылка скрыта]");
        text = EMAIL.matcher(text).replaceAll("[email скрыт]");
        text = PHONE.matcher(text).replaceAll("[номер скрыт]");
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }
}
