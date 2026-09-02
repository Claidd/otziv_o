package com.hunt.otziv.payments.dto;

import java.time.LocalDateTime;

public record PaymentReturnManualResolutionResponse(
        Long paymentLinkId,
        Long orderId,
        PaymentReturnManualResolutionOutcome outcome,
        LocalDateTime resolvedAt,
        String resolvedBy,
        String reason,
        Long paymentCheckId
) {
}
