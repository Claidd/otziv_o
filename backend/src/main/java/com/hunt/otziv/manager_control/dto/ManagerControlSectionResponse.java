package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;

public record ManagerControlSectionResponse(
        String code,
        String label,
        long count,
        String severity,
        String group,
        String targetUrl,
        Long itemId,
        String itemStatus,
        String actionType,
        String comment,
        LocalDateTime firstObservedAt,
        LocalDateTime targetDeadlineAt,
        LocalDateTime hardDeadlineAt,
        String slaState
) {
    public ManagerControlSectionResponse(
            String code,
            String label,
            long count,
            String severity,
            String group,
            String targetUrl
    ) {
        this(code, label, count, severity, group, targetUrl, null, null, null, null, null, null, null, null);
    }
}
