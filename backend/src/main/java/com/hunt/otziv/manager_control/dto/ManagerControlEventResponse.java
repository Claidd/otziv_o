package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;

public record ManagerControlEventResponse(
        Long eventId,
        Long itemId,
        String itemLabel,
        Long actorUserId,
        String eventType,
        String actionType,
        String comment,
        LocalDateTime createdAt
) {
}
