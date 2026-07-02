package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentSource;
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
        long paymentCount,
        long amountKopecks,
        LocalDateTime firstConfirmedAt,
        LocalDateTime lastConfirmedAt
) {
}
