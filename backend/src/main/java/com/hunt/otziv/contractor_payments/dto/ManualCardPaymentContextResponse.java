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
        String preparedReceiptUrl,
        String contractVersion,
        String routeRevision
) {
    public ManualCardPaymentContextResponse(
            Long orderId, long amountKopecks, ManualCardPaymentRecipientResponse originalRecipient,
            List<ManualCardPaymentRecipientResponse> candidates, String anomalyWarning,
            boolean recipientSelectionFrozen, ManualCardPaymentRecipientResponse preparedRecipient,
            String preparedReason, String preparedReceiptUrl
    ) {
        this(orderId, amountKopecks, originalRecipient, candidates, anomalyWarning,
                recipientSelectionFrozen, preparedRecipient, preparedReason, preparedReceiptUrl,
                "TASK_RECIPIENT_V1", null);
    }
}
