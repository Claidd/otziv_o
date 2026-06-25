package com.hunt.otziv.manager_control.dto;

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
        String comment
) {
    public ManagerControlSectionResponse(
            String code,
            String label,
            long count,
            String severity,
            String group,
            String targetUrl
    ) {
        this(code, label, count, severity, group, targetUrl, null, null, null, null);
    }
}
