package com.hunt.otziv.contractor_payments.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ContractorLegacyRewardReconciliationResponse(
        Long runId,
        LocalDate startDate,
        String status,
        String snapshotHash,
        int autoOrderCount,
        int autoRowCount,
        int autoRemainingRows,
        int manualOrderCount,
        int manualRowCount,
        int manualRemainingOrders,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        List<ContractorLegacyRewardManualGroupResponse> manualGroups
) {
    public static ContractorLegacyRewardReconciliationResponse empty() {
        return new ContractorLegacyRewardReconciliationResponse(
                null, null, "NOT_PREPARED", null, 0, 0, 0, 0, 0, 0, null, null, List.of()
        );
    }
}
