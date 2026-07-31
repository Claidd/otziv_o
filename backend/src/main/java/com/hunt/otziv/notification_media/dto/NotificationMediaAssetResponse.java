package com.hunt.otziv.notification_media.dto;

import java.time.LocalDateTime;

public record NotificationMediaAssetResponse(
        Long id,
        String imageUrl,
        String originalFilename,
        String contentType,
        boolean active,
        int sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
