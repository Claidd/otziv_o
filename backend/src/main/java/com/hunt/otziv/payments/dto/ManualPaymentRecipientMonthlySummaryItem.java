package com.hunt.otziv.payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import java.time.LocalDateTime;

public record ManualPaymentRecipientMonthlySummaryItem(
        String manualRecipientName,
        String manualPhone,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        String paymentProfileName,
        ManualPaymentSource manualSource,
        ManualPaymentType manualPaymentType,
        String accountingRecipientKey,
        String accountingRecipientLabel,
        ContractorCashDestinationKind accountingDestinationKind,
        ContractorRecipientType accountingRecipientType,
        Long accountingRecipientProfileId,
        Long manualPaymentTaskId,
        Long manualPaymentTaskGeneration,
        ManualPaymentTaskAccountingTargetKind manualPaymentTaskTargetKind,
        boolean attributionKnown,
        long paymentCount,
        long amountKopecks,
        LocalDateTime firstConfirmedAt,
        LocalDateTime lastConfirmedAt
) {
}
