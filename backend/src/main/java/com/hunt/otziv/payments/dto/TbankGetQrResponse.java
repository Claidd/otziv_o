package com.hunt.otziv.payments.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TbankGetQrResponse(
        @JsonProperty("Success") boolean success,
        @JsonProperty("ErrorCode") String errorCode,
        @JsonProperty("Message") String message,
        @JsonProperty("Details") String details,
        @JsonProperty("TerminalKey") String terminalKey,
        @JsonProperty("PaymentId") String paymentId,
        @JsonProperty("Data") String data
) {
    public String errorText() {
        String cleanMessage = clean(message);
        String cleanDetails = clean(details);
        String cleanErrorCode = clean(errorCode);
        StringBuilder result = new StringBuilder();
        if (!cleanMessage.isBlank()) {
            result.append(cleanMessage);
        }
        if (!cleanDetails.isBlank() && !cleanDetails.equals(cleanMessage)) {
            appendPart(result, cleanDetails);
        }
        if (!cleanErrorCode.isBlank()) {
            appendPart(result, "ErrorCode: " + cleanErrorCode);
        }
        return result.isEmpty() ? "T-Bank GetQr failed" : result.toString();
    }

    private static void appendPart(StringBuilder result, String value) {
        if (!result.isEmpty()) {
            result.append(" ");
        }
        result.append(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
