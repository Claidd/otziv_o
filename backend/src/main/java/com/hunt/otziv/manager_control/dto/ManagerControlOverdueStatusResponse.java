package com.hunt.otziv.manager_control.dto;

public record ManagerControlOverdueStatusResponse(
        String status,
        long count,
        long maxDays,
        String targetUrl,
        Long itemId,
        String itemStatus,
        String actionType,
        String comment
) {
    public ManagerControlOverdueStatusResponse(
            String status,
            long count,
            long maxDays,
            String targetUrl
    ) {
        this(status, count, maxDays, targetUrl, null, null, null, null);
    }
}
