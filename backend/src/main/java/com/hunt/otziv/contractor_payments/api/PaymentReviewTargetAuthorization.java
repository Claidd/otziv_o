package com.hunt.otziv.contractor_payments.api;

/** Immutable recipient authorization for specialist payment review administration. */
public interface PaymentReviewTargetAuthorization {
    boolean canManageUser(Long userId);
    void requireCanManageUser(Long userId);
    void requireCanManagePaymentLink(Long paymentLinkId);
}
