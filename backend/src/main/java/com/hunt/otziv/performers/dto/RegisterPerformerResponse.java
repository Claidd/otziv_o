package com.hunt.otziv.performers.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record RegisterPerformerResponse(
        Long userId,
        Long performerId,
        String username,
        String temporaryPassword,
        String telegramLinkToken,
        String telegramLinkUrl,
        String status,
        LocalDateTime registrationExpiresAt,
        boolean requiresAdminApproval
) {
}
