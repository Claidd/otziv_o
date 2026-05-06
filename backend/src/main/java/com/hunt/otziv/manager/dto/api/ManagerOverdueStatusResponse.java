package com.hunt.otziv.manager.dto.api;

public record ManagerOverdueStatusResponse(
        String status,
        long count,
        long maxDays
) {
}
