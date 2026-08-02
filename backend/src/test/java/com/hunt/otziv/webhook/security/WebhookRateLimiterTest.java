package com.hunt.otziv.webhook.security;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookRateLimiterTest {

    @Test
    void exposesConservativeRetryAfterFromConfiguredWindow() {
        assertEquals(90L, new WebhookRateLimiter(true, 1, Duration.ofSeconds(90)).retryAfterSeconds());
        assertEquals(1L, new WebhookRateLimiter(true, 1, Duration.ofMillis(250)).retryAfterSeconds());
    }

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

    @Test
    void boundsHighCardinalityBucketsAndExpiresInactiveEntries() {
        AtomicLong tickerNanos = new AtomicLong();
        Ticker ticker = tickerNanos::get;
        WebhookRateLimiter limiter = new WebhookRateLimiter(
                true,
                1,
                Duration.ofMinutes(1),
                128,
                ticker
        );
        Instant now = Instant.parse("2026-06-06T00:00:00Z");

        for (int index = 0; index < 1_000; index++) {
            assertTrue(limiter.tryAcquire("client-" + index, now));
        }

        assertTrue(limiter.bucketCount() <= 128);

        tickerNanos.addAndGet(Duration.ofMinutes(3).toNanos());
        assertEquals(0, limiter.bucketCount());
    }

    @Test
    void customRegistrationWindowIsNotEvictedByShortDefaultWindow() {
        AtomicLong tickerNanos = new AtomicLong();
        Ticker ticker = tickerNanos::get;
        WebhookRateLimiter limiter = new WebhookRateLimiter(
                true,
                120,
                Duration.ofMinutes(1),
                128,
                ticker
        );
        Instant now = Instant.parse("2026-06-06T00:00:00Z");

        assertTrue(limiter.tryAcquireCustom("registration|10.0.0.1", now, 1, Duration.ofMinutes(10)));
        tickerNanos.addAndGet(Duration.ofMinutes(3).toNanos());
        assertFalse(limiter.tryAcquireCustom(
                "registration|10.0.0.1",
                now.plus(Duration.ofMinutes(3)),
                1,
                Duration.ofMinutes(10)
        ));
    }
}
