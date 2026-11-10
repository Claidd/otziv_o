package com.hunt.otziv.p_products.api;

import java.security.Principal;

/** Accounting reversal of the exact manual receipt inside the administrator's transaction. */
public interface ManualReceiptOrderDebt {
    void reopen(long orderId, Long evidenceLinkId, long amountKopecks, boolean commonInvoice, Principal actor, String reason);
}
