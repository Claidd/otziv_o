package com.hunt.otziv.contractor_payments.dto;

import java.util.List;

public record ContractorAccrualAdjustmentHistoryPageResponse(
        List<ContractorAccrualAdjustmentResponse> items,
        boolean hasMore
) {
}
