package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WorkloadLiveReadinessResponse(
        boolean ready,
        String targetMode,
        LocalDateTime checkedAt,
        List<Check> checks
) {
    public record Check(
            String code,
            String status,
            String message,
            Long actual,
            Long required
    ) {
        public boolean passed() {
            return "PASS".equals(status);
        }
    }
}
