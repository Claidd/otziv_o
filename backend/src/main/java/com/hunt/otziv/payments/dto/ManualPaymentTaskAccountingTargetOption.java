package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;

public record ManualPaymentTaskAccountingTargetOption(
        String key,
        ManualPaymentTaskAccountingTargetKind kind,
        Long profileId,
        Long userId,
        String role,
        String label,
        boolean enabled,
        long currentAvailableKopecks,
        long targetAmountKopecks,
        long projectedOverrunKopecks,
        boolean needsAcknowledgement,
        boolean recommended
) {
}
