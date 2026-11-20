package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;

/** Immutable value snapshot; no managed entities may cross the delivery boundary. */
record PreparedCommonInvoiceMessage(Long invoiceId, ClientMessageDelivery.Target chatCompany,
        String managerClientId, String groupId, String message, String telegramCopyTransferNumber,
        boolean reminder, boolean manual, boolean paymentRouteChanged, String operationId,
        boolean alreadyConfirmed, String confirmedChannel, long remainingKopecks, boolean reviewedUpdate) {
    PreparedCommonInvoiceMessage(Long invoiceId, ClientMessageDelivery.Target chatCompany, String managerClientId,
            String groupId, String message, String telegramCopyTransferNumber, boolean reminder, boolean manual,
            boolean paymentRouteChanged, String operationId, boolean alreadyConfirmed, String confirmedChannel, long remainingKopecks) {
        this(invoiceId, chatCompany, managerClientId, groupId, message, telegramCopyTransferNumber, reminder, manual,
                paymentRouteChanged, operationId, alreadyConfirmed, confirmedChannel, remainingKopecks, false);
    }
    boolean badReviewNotice() {
        return operationId != null && operationId.startsWith("bad-review-invoice:");
    }

    boolean automaticRecovery() {
        return operationId != null && operationId.startsWith("invoice-recovery:");
    }

    PreparedCommonInvoiceMessage asReviewedUpdate() {
        return new PreparedCommonInvoiceMessage(invoiceId, chatCompany, managerClientId, groupId,
                "Обновлённый общий счёт. Предыдущая сумма больше не актуальна.\n\n" + message, telegramCopyTransferNumber,
                reminder, manual, paymentRouteChanged, operationId, alreadyConfirmed, confirmedChannel, remainingKopecks, true);
    }

    PreparedCommonInvoiceMessage asPaymentRouteChanged(String replacement) {
        return new PreparedCommonInvoiceMessage(invoiceId, chatCompany, managerClientId, groupId,
                replacement, telegramCopyTransferNumber, reminder, manual, true, operationId, alreadyConfirmed, confirmedChannel, remainingKopecks);
    }
}
