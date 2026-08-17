package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import java.time.LocalDateTime;

/** Append-only audit row for an already recorded common-invoice receipt. */
public record CommonManualPaymentAttributionResponse(
        Long id,
        String attributionKey,
        ContractorAllocationMode accountingMode,
        ContractorRecipientType originalRecipientType,
        Long originalRecipientProfileId,
        String originalRecipientLabel,
        ContractorRecipientType actualRecipientType,
        Long actualRecipientProfileId,
        String actualRecipientLabel,
        long amountKopecks,
        Long availableBeforeKopecks,
        long projectedOverrunKopecks,
        LocalDateTime effectiveAt,
        String reason,
        String evidenceReference,
        String actor,
        LocalDateTime createdAt,
        ContractorCashDestinationKind originalCashDestinationKind,
        Long originalManualPaymentTaskId,
        Long originalManualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind originalTaskTargetKind,
        ContractorCashDestinationKind actualCashDestinationKind,
        Long actualManualPaymentTaskId,
        Long actualManualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind actualTaskTargetKind
) {
    public CommonManualPaymentAttributionResponse(
            Long id, String attributionKey, ContractorAllocationMode accountingMode,
            ContractorRecipientType originalRecipientType, Long originalRecipientProfileId,
            String originalRecipientLabel, ContractorRecipientType actualRecipientType,
            Long actualRecipientProfileId, String actualRecipientLabel, long amountKopecks,
            Long availableBeforeKopecks, long projectedOverrunKopecks, LocalDateTime effectiveAt,
            String reason, String evidenceReference, String actor, LocalDateTime createdAt
    ) {
        this(id, attributionKey, accountingMode, originalRecipientType, originalRecipientProfileId,
                originalRecipientLabel, actualRecipientType, actualRecipientProfileId, actualRecipientLabel,
                amountKopecks, availableBeforeKopecks, projectedOverrunKopecks, effectiveAt, reason,
                evidenceReference, actor, createdAt,
                originalRecipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null, null, null,
                actualRecipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null, null, null);
    }
}
