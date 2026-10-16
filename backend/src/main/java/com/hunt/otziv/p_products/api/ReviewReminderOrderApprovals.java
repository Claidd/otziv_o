package com.hunt.otziv.p_products.api;

/** Order-owned admission and mutation under the caller's transaction. */
public interface ReviewReminderOrderApprovals {
    boolean lockEligible(Long orderId, String reviewCycleKey);
    void approve(Long orderId, String auditDetails);
}
