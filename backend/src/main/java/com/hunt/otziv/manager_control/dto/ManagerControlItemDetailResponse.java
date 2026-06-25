package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ManagerControlItemDetailResponse(
        Long itemId,
        String itemKey,
        String itemType,
        String reasonCode,
        String reasonLabel,
        String sectionCode,
        String label,
        String targetUrl,
        long count,
        String severity,
        String group,
        String itemStatus,
        String actionType,
        String comment,
        List<ManagerControlConcreteItemResponse> examples,
        long hiddenExampleCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime resolvedAt
) {
}
