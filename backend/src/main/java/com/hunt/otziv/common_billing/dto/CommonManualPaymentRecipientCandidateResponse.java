package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;

/** A server-authorized actual recipient that may be selected for this invoice. */
public record CommonManualPaymentRecipientCandidateResponse(
        String key,
        ContractorRecipientType recipientType,
        Long recipientProfileId,
        Long recipientUserId,
        String label,
        boolean originalRecipient,
        boolean currentParticipant,
        boolean profileEnabled,
        Long availableKopecks,
        ContractorCashDestinationKind cashDestinationKind,
        Long manualPaymentTaskId,
        Long manualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind taskTargetKind,
        String taskRecipientName,
        String accountingTargetLabel,
        String effectText
) {
    public CommonManualPaymentRecipientCandidateResponse(
            String key, ContractorRecipientType recipientType, Long recipientProfileId,
            Long recipientUserId, String label, boolean originalRecipient,
            boolean currentParticipant, boolean profileEnabled, Long availableKopecks
    ) {
        this(key, recipientType, recipientProfileId, recipientUserId, label, originalRecipient,
                currentParticipant, profileEnabled, availableKopecks,
                recipientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                null, null, null, null, label,
                recipientType == ContractorRecipientType.OWNER
                        ? "Сумма будет учтена владельцу" : "Сумма будет учтена выбранному работнику");
    }
}
