package com.hunt.otziv.performers.dto;

import java.math.BigDecimal;

public record AdminPerformerResponse(
        Long id,
        Long userId,
        String username,
        String fio,
        String phoneNumber,
        String cityTitle,
        String gender,
        String status,
        BigDecimal rating,
        BigDecimal reliabilityScore,
        int completedCount,
        int cancelledCount,
        int expiredOfferCount,
        int failedCheckCount,
        Long telegramChatId
) {
}
