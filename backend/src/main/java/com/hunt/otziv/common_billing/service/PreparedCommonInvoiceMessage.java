package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;

/** Immutable value snapshot; no managed entities may cross the delivery boundary. */
record PreparedCommonInvoiceMessage(Long invoiceId, ClientMessageDelivery.Target chatCompany,
        String managerClientId, String groupId, String message, String telegramCopyTransferNumber,
        boolean reminder, boolean manual, boolean paymentRouteChanged, String operationId,
        boolean alreadyConfirmed, String confirmedChannel, long remainingKopecks) {
    PreparedCommonInvoiceMessage asPaymentRouteChanged(String replacement) {
        return new PreparedCommonInvoiceMessage(invoiceId, chatCompany, managerClientId, groupId,
                replacement, telegramCopyTransferNumber, reminder, manual, true, operationId, alreadyConfirmed, confirmedChannel, remainingKopecks);
    }
}
