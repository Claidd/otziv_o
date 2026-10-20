package com.hunt.otziv.payments.api;

/** Read-only settlement evidence used to resolve stale payment alerts. */
public interface PaymentIssueEvidenceOperations {

    /** Lock the one order before inspecting its payment state in the caller's transaction. */
    boolean lockOrder(long orderId);

    Evidence confirmedPaidOrder(long orderId);

    record Evidence(boolean confirmed, String reason, Long paymentLinkId) {
        public static Evidence unresolved(String reason) {
            return new Evidence(false, reason, null);
        }

        public static Evidence confirmed(Long paymentLinkId) {
            return new Evidence(true, "confirmed_paid_order", paymentLinkId);
        }
    }
}
