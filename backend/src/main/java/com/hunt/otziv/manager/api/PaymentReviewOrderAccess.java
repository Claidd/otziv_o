package com.hunt.otziv.manager.api;

import org.springframework.security.core.Authentication;

/** Reuses the order's manager/owner authorization policy for payment review. */
public interface PaymentReviewOrderAccess {
    void requireOrderAccess(Long orderId, Authentication authentication);
}
