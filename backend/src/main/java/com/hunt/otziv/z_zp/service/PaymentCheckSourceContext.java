package com.hunt.otziv.z_zp.service;

/**
 * Carries the exact payment-link source through the synchronous paid-order
 * transaction without changing legacy/manual OrderTransactionService entry
 * points. The scope is always restored in finally, including nested calls.
 */
public final class PaymentCheckSourceContext {

    private static final ThreadLocal<Long> PAYMENT_LINK_ID = new ThreadLocal<>();

    private PaymentCheckSourceContext() {
    }

    public static Long currentPaymentLinkId() {
        return PAYMENT_LINK_ID.get();
    }

    public static <T> T withPaymentLink(Long paymentLinkId, CheckedOperation<T> operation) throws Exception {
        if (paymentLinkId == null) {
            throw new IllegalArgumentException("Для платежного контекста нужен ID ссылки");
        }
        Long previous = PAYMENT_LINK_ID.get();
        PAYMENT_LINK_ID.set(paymentLinkId);
        try {
            return operation.run();
        } finally {
            if (previous == null) {
                PAYMENT_LINK_ID.remove();
            } else {
                PAYMENT_LINK_ID.set(previous);
            }
        }
    }

    @FunctionalInterface
    public interface CheckedOperation<T> {
        T run() throws Exception;
    }
}
