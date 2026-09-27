package com.hunt.otziv.r_review.api;

/** Read-only account readiness policy shared with the pool availability monitor. */
public interface ReviewAccountWalkingPolicy {
    int walkedCounterThreshold();
}
