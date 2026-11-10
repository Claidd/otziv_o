package com.hunt.otziv.common_billing.api;

import java.security.Principal;

public interface ManualReceiptInvoiceDebt {
    void reopen(long invoiceId, String evidence, long amountKopecks, Principal actor, String reason);
}
