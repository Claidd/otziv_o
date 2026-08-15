package com.hunt.otziv.contractor_payments.dto;

import java.util.List;

public record ManualCardPaymentContextResponse(
        Long orderId,
        long amountKopecks,
        ManualCardPaymentRecipientResponse originalRecipient,
        List<ManualCardPaymentRecipientResponse> candidates,
        String anomalyWarning,
        boolean recipientSelectionFrozen,
        ManualCardPaymentRecipientResponse preparedRecipient,
        String preparedReason,
        String preparedReceiptUrl
) {
}
