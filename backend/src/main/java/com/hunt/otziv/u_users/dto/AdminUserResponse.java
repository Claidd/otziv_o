package com.hunt.otziv.u_users.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hunt.otziv.u_users.images.SignedImageUrls;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Builder
public record AdminUserResponse(
        Long id,
        String keycloakId,
        boolean keycloakLinked,
        String authProvider,
        String username,
        String email,
        String fio,
        String phoneNumber,
        BigDecimal coefficient,
        String workerChatUrl,
        boolean personalTelegramLinked,
        Long workerTelegramGroupChatId,
        String workerTelegramBotInviteUrl,
        String managerAuditChatUrl,
        Long managerAuditTelegramGroupChatId,
        String managerAuditTelegramBotInviteUrl,
        Long imageId,
        boolean active,
        LocalDate createTime,
        LocalDateTime lastLoginAt,
        Set<String> roles
) {
    /** Short-lived signed link to the user's photo, relative to the backend base URL. */
    @JsonProperty(value = "imageUrl", access = JsonProperty.Access.READ_ONLY)
    public String imageUrl() {
        return SignedImageUrls.pathFor(imageId);
    }
}
