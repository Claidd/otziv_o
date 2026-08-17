package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;

public record ManualCardPaymentRecipientResponse(
        ContractorRecipientType recipientType,
        Long recipientProfileId,
        Long recipientUserId,
        String displayName,
        long availableKopecks,
        long projectedOverrunKopecks,
        boolean original,
        String key,
        ContractorCashDestinationKind cashDestinationKind,
        Long manualPaymentTaskId,
        Long manualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind taskTargetKind,
        String taskRecipientName,
        String accountingTargetLabel,
        String effectText
) {
    public ManualCardPaymentRecipientResponse(
            ContractorRecipientType recipientType, Long recipientProfileId, Long recipientUserId,
            String displayName, long availableKopecks, long projectedOverrunKopecks, boolean original
    ) {
        this(recipientType, recipientProfileId, recipientUserId, displayName, availableKopecks,
                projectedOverrunKopecks, original,
                recipientType == ContractorRecipientType.OWNER ? "OWNER" : "PROFILE:" + recipientProfileId,
                recipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null, null, null, null, displayName,
                recipientType == ContractorRecipientType.OWNER
                        ? "Сумма будет учтена владельцу" : "Сумма будет учтена выбранному работнику");
    }
}
