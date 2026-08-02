package com.hunt.otziv.external_review_checks.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Distinguishes a local kill-switch rejection from an upstream worker 503.
 * A local rejection releases the claim without consuming an attempt.
 */
final class ExternalReviewWorkerDisabledException extends ResponseStatusException {

    ExternalReviewWorkerDisabledException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Внешняя проверка отзывов временно отключена");
    }
}
