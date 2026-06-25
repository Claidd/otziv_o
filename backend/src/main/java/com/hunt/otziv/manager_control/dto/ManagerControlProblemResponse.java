package com.hunt.otziv.manager_control.dto;

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
        String comment
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
        this(code, label, count, severity, group, icon, targetUrl, null, null, null, null);
    }
}
