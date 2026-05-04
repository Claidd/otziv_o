package com.hunt.otziv.manager.dto.api;

public record ManagerMetricResponse(
        String label,
        int value,
        String icon,
        String tone,
        String section,
        String status
) {
}
