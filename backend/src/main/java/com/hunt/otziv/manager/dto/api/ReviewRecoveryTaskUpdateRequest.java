package com.hunt.otziv.manager.dto.api;

import java.time.LocalDate;

public record ReviewRecoveryTaskUpdateRequest(
        String recoveryText,
        String recoveryAnswer,
        LocalDate scheduledDate
) {
}
