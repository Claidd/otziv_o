package com.hunt.otziv.p_products.api;

import java.security.Principal;

/** Accounting reversal of the exact manual receipt inside the administrator's transaction. */
public interface ManualReceiptOrderDebt {
    void reopen(long orderId, Long evidenceLinkId, long amountKopecks, boolean commonInvoice, Principal actor, String reason);

    /**
     * Reverses an order closed by a paper invoice payment that was confirmed by mistake (no bank receipt): the same
     * accounting reversal, and the untouched next order the payment created is deleted, as when a single order's
     * payment is cancelled.
     */
    void reopenPaperInvoiceOrder(long orderId, long amountKopecks, Principal actor, String reason);
}
