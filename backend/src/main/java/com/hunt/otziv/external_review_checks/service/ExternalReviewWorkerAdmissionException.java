package com.hunt.otziv.external_review_checks.service;

/** Positive evidence that the trusted worker rejected admission before work began. */
public final class ExternalReviewWorkerAdmissionException extends RuntimeException {
    public ExternalReviewWorkerAdmissionException() { super("External review worker admission refused"); }
}
