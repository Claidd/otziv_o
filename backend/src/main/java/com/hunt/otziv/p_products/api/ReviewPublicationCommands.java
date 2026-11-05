package com.hunt.otziv.p_products.api;

import org.springframework.security.core.Authentication;

public interface ReviewPublicationCommands {
    void publishWorker(Long reviewId,Authentication actor);
    void publishManager(Long orderId,Long reviewId,Authentication actor,String sourceDetails) throws Exception;
    LegacyOutcome publishLegacy(Long companyId,Long orderId,Long reviewId,Authentication actor) throws Exception;
    /** Counter/archive/client-progress book-keeping for a review a performer reported as published. */
    void synchronizeExternalPublication(Long reviewId);
    record LegacyOutcome(boolean published,String blockedSection,String message) {}
}
