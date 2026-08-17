package com.hunt.otziv.contractor_payments.dto;

import java.time.LocalDate;

public record ContractorLegacyRewardManualGroupResponse(
        Long orderId,
        String groupHash,
        String evidenceCategory,
        String status,
        int rowCount,
        LocalDate completedOn,
        String evidenceReference
) {
}
