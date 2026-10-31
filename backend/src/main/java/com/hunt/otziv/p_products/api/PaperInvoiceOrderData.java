package com.hunt.otziv.p_products.api;

/** Order-owned scalar read and canonical lock. Caller must authorize the order before reading company data. */
public interface PaperInvoiceOrderData {
    long companyId(long orderId);
    void lockCompletedUnpaid(long orderId);
}
