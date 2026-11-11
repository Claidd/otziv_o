package com.hunt.otziv.common_billing.api;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Delivered notices about a manually confirmed common-invoice receipt, for the recipient's dispute.
 * Owner: common billing. Caller: the payment dispute callback, which authorizes the Telegram user
 * against the recipient before it marks anything.
 */
public interface CommonInvoiceRecipientReceipts {

    Optional<Receipt> find(long deliveryId);

    /** Records the first dispute in the caller's transaction; false when the receipt is already disputed. */
    boolean markDisputed(long deliveryId, long telegramUserId);

    record Receipt(
            long deliveryId,
            long invoiceId,
            String invoiceTitle,
            Long anchorOrderId,
            String recipientType,
            Long recipientUserId,
            long amountKopecks,
            String confirmedBy,
            LocalDateTime sentAt,
            LocalDateTime disputedAt
    ) {
    }
}
