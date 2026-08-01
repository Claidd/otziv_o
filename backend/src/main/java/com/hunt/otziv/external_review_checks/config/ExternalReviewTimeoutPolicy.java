package com.hunt.otziv.external_review_checks.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Single source of truth for external-review I/O timeouts and the minimum
 * processing lease that must cover those sequential operations.
 */
public final class ExternalReviewTimeoutPolicy {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_UPLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SAFETY_MARGIN = Duration.ofSeconds(30);
    private static final Duration DEFAULT_PROCESSING_LEASE = Duration.ofMinutes(5);

    private ExternalReviewTimeoutPolicy() {
    }

    public static Duration workerConnectTimeout(ExternalReviewCheckProperties properties) {
        return positiveOrDefault(properties.getWorkerConnectTimeout(), DEFAULT_CONNECT_TIMEOUT);
    }

    public static Duration workerReadTimeout(ExternalReviewCheckProperties properties) {
        return positiveOrDefault(properties.getWorkerReadTimeout(), DEFAULT_READ_TIMEOUT);
    }

    public static Duration screenshotUploadTimeout(ExternalReviewCheckProperties properties) {
        return positiveOrDefault(properties.getScreenshotUploadTimeout(), DEFAULT_UPLOAD_TIMEOUT);
    }

    public static Duration processingLease(ExternalReviewCheckProperties properties) {
        Objects.requireNonNull(properties, "properties");
        Duration configured = positiveOrDefault(
                properties.getProcessingLease(),
                DEFAULT_PROCESSING_LEASE
        );
        Duration minimum = safeSum(
                workerConnectTimeout(properties),
                workerReadTimeout(properties),
                screenshotUploadTimeout(properties),
                positiveOrDefault(
                        properties.getProcessingLeaseSafetyMargin(),
                        DEFAULT_SAFETY_MARGIN
                )
        );
        return configured.compareTo(minimum) >= 0 ? configured : minimum;
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration safeSum(Duration first, Duration... remaining) {
        Duration total = first;
        try {
            for (Duration value : remaining) {
                total = total.plus(value);
            }
            return total;
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                    "External review timeout sum exceeds supported lease",
                    overflow
            );
        }
    }
}
