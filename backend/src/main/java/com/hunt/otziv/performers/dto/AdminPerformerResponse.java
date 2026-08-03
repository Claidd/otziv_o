package com.hunt.otziv.performers.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        Long telegramChatId,
        LocalDateTime telegramLinkedAt,
        LocalDateTime registrationExpiresAt,
        LocalDateTime phoneVerifiedAt,
        String phoneVerificationMethod,
        String personalDataConsentVersion,
        String rulesConsentVersion,
        String honestReviewConsentVersion,
        boolean activationReady,
        boolean legacyApprovedBeforeSecureLifecycle,
        String activationWarning
) {
}
