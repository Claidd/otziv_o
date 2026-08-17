package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;

public record ManualPaymentTaskSourceRef(
        ManualPaymentTaskLedgerSourceKind sourceKind,
        Long sourceId,
        String sourceGeneration
) {
}
