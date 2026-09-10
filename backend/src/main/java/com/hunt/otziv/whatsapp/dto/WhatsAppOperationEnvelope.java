package com.hunt.otziv.whatsapp.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned wire identity shared with the gateway's operation ledger. */
public final class WhatsAppOperationEnvelope {
    private WhatsAppOperationEnvelope() {}

    public static String groupHash(String clientId, String groupId, String message) {
        String destination = trimJavaScript(groupId);
        if (destination.matches("[0-9]+(?:-[0-9]+)?@g\\.us")) {
            int userLength = destination.length() - "@g.us".length();
            if (userLength < 8 || userLength > 80) throw invalidEnvelope();
        } else if (destination.length() >= 8 && destination.length() <= 80
                && destination.matches("[0-9]+(?:-[0-9]+)?")) {
            destination += "@g.us";
        } else {
            throw invalidEnvelope();
        }
        return hash(clientId, "send-group", destination, message);
    }

    public static String phoneHash(String clientId, String phone, String message) {
        String destination = WhatsAppDestination.normalize("send", phone) + "@c.us";
        return hash(clientId, "send", destination, message);
    }

    private static String hash(String clientId, String kind, String destination, String message) {
        String normalizedMessage = trimJavaScript(message);
        if (clientId == null || clientId.isEmpty() || normalizedMessage.isEmpty()) throw invalidEnvelope();
        StringBuilder json = new StringBuilder("[");
        for (String value : new String[]{clientId, kind, destination, normalizedMessage}) {
            if (json.length() > 1) json.append(',');
            appendJsonString(json, value);
        }
        json.append(']');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("otziv.whatsapp.envelope.v1".getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(json.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    // Java trim/strip have different Unicode whitespace semantics from ECMAScript.
    private static String trimJavaScript(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptWhitespace(value.charAt(start))) start++;
        while (end > start && isJavaScriptWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isJavaScriptWhitespace(char value) {
        return value >= '\t' && value <= '\r' || value == ' ' || value == '\u00a0'
                || value == '\u1680' || value >= '\u2000' && value <= '\u200a'
                || value == '\u2028' || value == '\u2029' || value == '\u202f'
                || value == '\u205f' || value == '\u3000' || value == '\ufeff';
    }

    // Match JSON.stringify, including well-formed escaping of lone UTF-16 surrogates.
    private static void appendJsonString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\t' -> json.append("\\t");
                case '\n' -> json.append("\\n");
                case '\f' -> json.append("\\f");
                case '\r' -> json.append("\\r");
                default -> {
                    if (Character.isHighSurrogate(ch) && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        json.append(ch).append(value.charAt(++index));
                    } else if (ch < 0x20 || Character.isSurrogate(ch)) {
                        json.append("\\u").append(HexFormat.of().toHexDigits(ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }

    private static IllegalArgumentException invalidEnvelope() {
        return new IllegalArgumentException("Invalid WhatsApp group operation envelope");
    }
}
