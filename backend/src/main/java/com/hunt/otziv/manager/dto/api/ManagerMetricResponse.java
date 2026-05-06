package com.hunt.otziv.manager.dto.api;

public record ManagerMetricResponse(
        String label,
        int value,
        String icon,
        String tone,
        String section,
        String status,
        int delta
) {
    public ManagerMetricResponse(
            String label,
            int value,
            String icon,
            String tone,
            String section,
            String status
    ) {
        this(label, value, icon, tone, section, status, 0);
    }

    public ManagerMetricResponse withDelta(int delta) {
        return new ManagerMetricResponse(
                label,
                value,
                icon,
                tone,
                section,
                status,
                Math.max(0, delta)
        );
    }
}
