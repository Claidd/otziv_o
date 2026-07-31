package com.hunt.otziv.notification_media.dto;

import com.hunt.otziv.notification_media.model.NotificationRecipientType;

public record NotificationMediaEventResponse(
        String code,
        NotificationRecipientType recipientType,
        String label,
        String description,
        boolean serious
) {
}
