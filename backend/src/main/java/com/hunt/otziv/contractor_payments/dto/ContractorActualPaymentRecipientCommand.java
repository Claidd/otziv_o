package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;

public record ContractorActualPaymentRecipientCommand(
        String attributionKey,
        ContractorRecipientType actualRecipientType,
        Long actualRecipientProfileId,
        long amountKopecks,
        String actualRecipientName,
        String recipientKey,
        ContractorCashDestinationKind cashDestinationKind,
        Long manualPaymentTaskId,
        Long manualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind manualPaymentTaskTargetKind
) {
    public ContractorActualPaymentRecipientCommand(
            String attributionKey, ContractorRecipientType actualRecipientType,
            Long actualRecipientProfileId, long amountKopecks, String actualRecipientName
    ) {
        this(attributionKey, actualRecipientType, actualRecipientProfileId, amountKopecks,
                actualRecipientName,
                actualRecipientType == ContractorRecipientType.OWNER
                        ? "OWNER" : "PROFILE:" + actualRecipientProfileId,
                actualRecipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null, null, null);
    }

    public ContractorActualPaymentRecipientCommand(
            String attributionKey,
            ContractorRecipientType actualRecipientType,
            Long actualRecipientProfileId,
            long amountKopecks
    ) {
        this(attributionKey, actualRecipientType, actualRecipientProfileId, amountKopecks, null);
    }
}
