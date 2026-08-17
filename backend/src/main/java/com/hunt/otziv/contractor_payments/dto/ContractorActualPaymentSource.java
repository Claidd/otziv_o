package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import java.time.LocalDateTime;

public record ContractorActualPaymentSource(
        ContractorActualPaymentSourceKind sourceKind,
        Long sourceId,
        Long evidenceId,
        Long orderId,
        Long commonInvoiceId,
        Long originalAllocationId,
        Long clientFacingAllocationId,
        ContractorRecipientType clientFacingRecipientType,
        Long clientFacingRecipientProfileId,
        String clientFacingRecipientName,
        Long currentWorkerId,
        Long currentManagerId,
        LocalDateTime effectiveAt,
        String reason,
        String evidenceReference,
        String receiptUrl,
        String actor,
        ContractorCashDestinationKind clientFacingCashDestinationKind,
        Long clientFacingManualPaymentTaskId,
        Long clientFacingManualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind clientFacingManualPaymentTaskTargetKind
) {
    public ContractorActualPaymentSource(
            ContractorActualPaymentSourceKind sourceKind, Long sourceId, Long evidenceId,
            Long orderId, Long commonInvoiceId, Long originalAllocationId,
            Long clientFacingAllocationId, ContractorRecipientType clientFacingRecipientType,
            Long clientFacingRecipientProfileId, String clientFacingRecipientName,
            Long currentWorkerId, Long currentManagerId, LocalDateTime effectiveAt,
            String reason, String evidenceReference, String receiptUrl, String actor
    ) {
        this(sourceKind, sourceId, evidenceId, orderId, commonInvoiceId, originalAllocationId,
                clientFacingAllocationId, clientFacingRecipientType, clientFacingRecipientProfileId,
                clientFacingRecipientName, currentWorkerId, currentManagerId, effectiveAt, reason,
                evidenceReference, receiptUrl, actor,
                clientFacingRecipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null, null, null);
    }

    /** Compatibility constructor for callers without a distinct historical allocation. */
    public ContractorActualPaymentSource(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Long evidenceId,
            Long orderId,
            Long commonInvoiceId,
            Long originalAllocationId,
            ContractorRecipientType clientFacingRecipientType,
            Long clientFacingRecipientProfileId,
            String clientFacingRecipientName,
            Long currentWorkerId,
            Long currentManagerId,
            LocalDateTime effectiveAt,
            String reason,
            String evidenceReference,
            String receiptUrl,
            String actor
    ) {
        this(
                sourceKind,
                sourceId,
                evidenceId,
                orderId,
                commonInvoiceId,
                originalAllocationId,
                originalAllocationId,
                clientFacingRecipientType,
                clientFacingRecipientProfileId,
                clientFacingRecipientName,
                currentWorkerId,
                currentManagerId,
                effectiveAt,
                reason,
                evidenceReference,
                receiptUrl,
                actor,
                clientFacingRecipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null,
                null,
                null
        );
    }
}
