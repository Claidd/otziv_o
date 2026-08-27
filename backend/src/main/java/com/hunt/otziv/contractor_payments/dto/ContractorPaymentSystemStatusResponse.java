package com.hunt.otziv.contractor_payments.dto;

import java.time.LocalDate;
import java.util.List;

public record ContractorPaymentSystemStatusResponse(
        String mode,
        boolean systemEnabled,
        boolean legacyBehavior,
        boolean irreversible,
        boolean routingRequested,
        boolean completionAccountingEffective,
        boolean liveRoutingEffective,
        boolean completionBacklogReady,
        boolean activationAvailable,
        List<String> activationBlockedReasons,
        List<String> runtimeWarnings,
        LocalDate attributionStartDate,
        long revision,
        boolean liveRoutingMasterEnabled,
        boolean rewardAttributionMasterEnabled
) {
}
