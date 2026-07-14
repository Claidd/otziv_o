package com.hunt.otziv.manager_control.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ManagerQueueStateResponse(
        boolean enabled,
        LocalDate date,
        String state,
        long openActionCount,
        long withinTargetCount,
        long targetMissedCount,
        long overdueCount,
        long hardBreachCount,
        long controlledSeconds,
        long cleanQueueSeconds,
        long currentControlledStreakSeconds,
        int controlTargetHours,
        int controlPercent,
        LocalDateTime observedAt
) {
}
