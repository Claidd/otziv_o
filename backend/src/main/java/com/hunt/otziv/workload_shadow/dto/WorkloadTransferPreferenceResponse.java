package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDateTime;

public record WorkloadTransferPreferenceResponse(
        Long workerId,
        boolean acceptsCompanyTransfers,
        LocalDateTime changedAt
) {
}
