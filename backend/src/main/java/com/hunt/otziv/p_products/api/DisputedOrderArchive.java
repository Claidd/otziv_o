package com.hunt.otziv.p_products.api;

/** Order-owned payment guard and archive transition for a disputed invoice member. */
public interface DisputedOrderArchive {
    boolean hasPaymentState(Long orderId);
    boolean archive(Long orderId) throws Exception;
}
