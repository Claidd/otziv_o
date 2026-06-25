package com.hunt.otziv.webhook.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookRateLimiterTest {

    @Test
    void limitsRequestsPerClientWithinWindow() {
        WebhookRateLimiter limiter = new WebhookRateLimiter(true, 2, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-06-06T00:00:00Z");

        assertTrue(limiter.tryAcquire("10.0.0.1", now));
        assertTrue(limiter.tryAcquire("10.0.0.1", now.plusSeconds(1)));
        assertFalse(limiter.tryAcquire("10.0.0.1", now.plusSeconds(2)));
        assertTrue(limiter.tryAcquire("10.0.0.2", now.plusSeconds(2)));
    }

    @Test
    void resetsCounterAfterWindow() {
        WebhookRateLimiter limiter = new WebhookRateLimiter(true, 1, Duration.ofSeconds(10));
        Instant now = Instant.parse("2026-06-06T00:00:00Z");

        assertTrue(limiter.tryAcquire("10.0.0.1", now));
        assertFalse(limiter.tryAcquire("10.0.0.1", now.plusSeconds(9)));
        assertTrue(limiter.tryAcquire("10.0.0.1", now.plusSeconds(10)));
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        WebhookRateLimiter limiter = new WebhookRateLimiter(false, 1, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-06-06T00:00:00Z");

        assertTrue(limiter.tryAcquire("10.0.0.1", now));
        assertTrue(limiter.tryAcquire("10.0.0.1", now));
    }
}
