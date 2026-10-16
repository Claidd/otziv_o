package com.hunt.otziv.review_recovery.api;

/** Scalar check for an active recovery task while the caller owns order status. */
public interface ReviewRecoveryMessageHold {
    boolean shouldPauseActiveOrder(Long orderId);
}
