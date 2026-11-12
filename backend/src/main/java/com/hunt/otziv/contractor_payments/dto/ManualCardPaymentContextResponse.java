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
        String routeRevision,
        /**
         * Keys of the candidates offered only because they work in the manager's team: neither the
         * recipient of the invoice nor assigned to the order. The payment window lists them apart,
         * under «Другой получатель».
         */
        List<String> teamRecipientKeys,
        /**
         * The invoice of the order is a bank link. Paying by the link is confirmed by the bank itself:
         * the window names nobody in advance and says what a manual mark means here.
         */
        boolean bankLinkRoute
) {
    public ManualCardPaymentContextResponse {
        teamRecipientKeys = teamRecipientKeys == null ? List.of() : List.copyOf(teamRecipientKeys);
    }

    public ManualCardPaymentContextResponse(
            Long orderId, long amountKopecks, ManualCardPaymentRecipientResponse originalRecipient,
            List<ManualCardPaymentRecipientResponse> candidates, String anomalyWarning,
            boolean recipientSelectionFrozen, ManualCardPaymentRecipientResponse preparedRecipient,
            String preparedReason, String preparedReceiptUrl, String contractVersion, String routeRevision,
            List<String> teamRecipientKeys
    ) {
        this(orderId, amountKopecks, originalRecipient, candidates, anomalyWarning,
                recipientSelectionFrozen, preparedRecipient, preparedReason, preparedReceiptUrl,
                contractVersion, routeRevision, teamRecipientKeys, false);
    }

    /** The same recipients, marked as offered for an invoice that is a bank link. */
    public ManualCardPaymentContextResponse onBankLinkRoute() {
        return new ManualCardPaymentContextResponse(orderId, amountKopecks, originalRecipient, candidates, anomalyWarning,
                recipientSelectionFrozen, preparedRecipient, preparedReason, preparedReceiptUrl,
                contractVersion, routeRevision, teamRecipientKeys, true);
    }

    public ManualCardPaymentContextResponse(
            Long orderId, long amountKopecks, ManualCardPaymentRecipientResponse originalRecipient,
            List<ManualCardPaymentRecipientResponse> candidates, String anomalyWarning,
            boolean recipientSelectionFrozen, ManualCardPaymentRecipientResponse preparedRecipient,
            String preparedReason, String preparedReceiptUrl, String contractVersion, String routeRevision
    ) {
        this(orderId, amountKopecks, originalRecipient, candidates, anomalyWarning,
                recipientSelectionFrozen, preparedRecipient, preparedReason, preparedReceiptUrl,
                contractVersion, routeRevision, List.of());
    }

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
