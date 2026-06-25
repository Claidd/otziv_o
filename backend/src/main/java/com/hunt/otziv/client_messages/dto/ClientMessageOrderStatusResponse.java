package com.hunt.otziv.client_messages.dto;

import java.time.LocalDateTime;

public record ClientMessageOrderStatusResponse(
        String state,
        String label,
        String tone,
        String scenario,
        String errorCode,
        String errorMessage,
        LocalDateTime lastAttemptAt,
        LocalDateTime lastSuccessAt,
        LocalDateTime nextAttemptAt,
        int consecutiveFailures,
        int sentCount
) {
}
