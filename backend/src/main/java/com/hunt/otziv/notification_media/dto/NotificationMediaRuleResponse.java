package com.hunt.otziv.notification_media.dto;

import com.hunt.otziv.notification_media.model.NotificationRecipientType;
import java.time.LocalDateTime;
import java.util.List;

public record NotificationMediaRuleResponse(
        Long id,
        String eventCode,
        NotificationRecipientType recipientType,
        String eventLabel,
        String eventDescription,
        boolean serious,
        boolean enabled,
        int imageProbabilityPercent,
        int cooldownMinutes,
        List<NotificationMediaAssetResponse> images,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
