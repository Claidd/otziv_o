package com.hunt.otziv.manager_control.dto;

import java.util.List;

public record ManagerControlCloseResponse(
        boolean closed,
        String status,
        int qualityScore,
        String qualityGrade,
        int riskScore,
        boolean fastClickRisk,
        List<String> blockers
) {
}
