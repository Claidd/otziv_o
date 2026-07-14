package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;

public record ManagerControlProblemResponse(
        String code,
        String label,
        long count,
        String severity,
        String group,
        String icon,
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
    public ManagerControlProblemResponse(
            String code,
            String label,
            long count,
            String severity,
            String group,
            String icon,
            String targetUrl
    ) {
        this(code, label, count, severity, group, icon, targetUrl, null, null, null, null, null, null, null, null);
    }
}
