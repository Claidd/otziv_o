package com.hunt.otziv.p_products.api;

/** Order-owned scalar read and canonical lock. Caller must authorize the order before reading company data. */
public interface PaperInvoiceOrderData {
    long companyId(long orderId);
    void lockCompletedUnpaid(long orderId);
    void lockForDelivery(long orderId);
    void markDelivered(long orderId);
    void holdAutomation(long orderId,boolean hold);
    void cancelled(long orderId,boolean commonMember);
    void requireUnpaidForCancellation(long orderId);
    void holdForPaymentReview(long orderId);
    record StaffContext(Long managerId,String company,String filial) {}
    StaffContext staffContext(long orderId);
}
