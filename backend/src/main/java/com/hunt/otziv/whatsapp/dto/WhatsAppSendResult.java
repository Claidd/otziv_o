package com.hunt.otziv.whatsapp.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public record WhatsAppSendResult(String status, String code, String error, String rawBody) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static WhatsAppSendResult ok(String rawBody) {
        return new WhatsAppSendResult("ok", null, null, rawBody);
    }

    public static WhatsAppSendResult error(String code, String error) {
        return new WhatsAppSendResult("error", code, error, null);
    }

    public static WhatsAppSendResult parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return error("empty_response", "WhatsApp API вернул пустой ответ");
        }

        String trimmed = rawBody.trim();
        if ("ok".equalsIgnoreCase(trimmed)) {
            return ok(trimmed);
        }

        try {
            JsonNode node = MAPPER.readTree(trimmed);
            String status = text(node, "status");
            String code = text(node, "code");
            String error = text(node, "error");

            if ("ok".equalsIgnoreCase(status)) {
                return new WhatsAppSendResult("ok", code, error, rawBody);
            }
            if (status != null && !status.isBlank()) {
                return new WhatsAppSendResult(status, code, error, rawBody);
            }
        } catch (JsonProcessingException ignored) {
            if (trimmed.toLowerCase().contains("\"status\":\"ok\"")) {
                return ok(rawBody);
            }
        }

        return new WhatsAppSendResult("unknown", "unknown_response", trimmed, rawBody);
    }

    public boolean isOk() {
        return "ok".equalsIgnoreCase(status);
    }

    public boolean hasStatus(String expected) {
        return expected != null && expected.equalsIgnoreCase(status);
    }

    public String displayError() {
        if (error != null && !error.isBlank()) {
            return error;
        }
        if (rawBody != null && !rawBody.isBlank()) {
            return rawBody;
        }
        return "Неизвестная ошибка WhatsApp";
    }

    public String toJson() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", status);
        if (code != null && !code.isBlank()) {
            body.put("code", code);
        }
        if (error != null && !error.isBlank()) {
            body.put("error", error);
        }

        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException ignored) {
            return "{\"status\":\"error\",\"code\":\"json_error\",\"error\":\"Не удалось собрать ответ WhatsApp\"}";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
