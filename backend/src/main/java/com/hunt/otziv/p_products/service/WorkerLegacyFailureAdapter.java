package com.hunt.otziv.p_products.service;

import org.springframework.web.server.ResponseStatusException;

/** Compatibility for existing domain services; inspect only after their transaction proxy has returned. */
public final class WorkerLegacyFailureAdapter {
    private WorkerLegacyFailureAdapter() {}

    public static StatusFailure describe(Exception failure) {
        if (failure instanceof ResponseStatusException statusFailure) {
            return new StatusFailure(statusFailure.getStatusCode().value(), statusFailure.getReason());
        }
        return null;
    }

    public record StatusFailure(int statusCode, String message) {}
}
