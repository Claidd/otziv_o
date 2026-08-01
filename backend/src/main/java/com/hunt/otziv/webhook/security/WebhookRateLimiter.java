package com.hunt.otziv.webhook.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class WebhookRateLimiter {

    private static final int DEFAULT_MAX_BUCKETS = 10_000;

    private final boolean enabled;
    private final int maxRequests;
    private final Duration window;
    private final Cache<String, Bucket> buckets;

    @Autowired
    public WebhookRateLimiter(
            @Value("${webhook.rate-limit.enabled:true}") boolean enabled,
            @Value("${webhook.rate-limit.max-requests:120}") int maxRequests,
            @Value("${webhook.rate-limit.window:PT1M}") Duration window,
            @Value("${webhook.rate-limit.max-buckets:10000}") int maxBuckets
    ) {
        this(enabled, maxRequests, window, maxBuckets, Ticker.systemTicker());
    }

    public WebhookRateLimiter(boolean enabled, int maxRequests, Duration window) {
        this(enabled, maxRequests, window, DEFAULT_MAX_BUCKETS, Ticker.systemTicker());
    }

    WebhookRateLimiter(boolean enabled, int maxRequests, Duration window, int maxBuckets, Ticker ticker) {
        this.enabled = enabled;
        this.maxRequests = Math.max(1, maxRequests);
        this.window = window == null || window.isNegative() || window.isZero() ? Duration.ofMinutes(1) : window;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxBuckets))
                .expireAfterAccess(expirationTtl(this.window))
                .ticker(ticker == null ? Ticker.systemTicker() : ticker)
                .build();
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, Instant.now());
    }

    boolean tryAcquire(String key, Instant now) {
        if (!enabled) {
            return true;
        }

        String safeKey = hasText(key) ? key : "unknown";
        Bucket bucket = buckets.getIfPresent(safeKey);
        if (bucket == null) {
            bucket = buckets.get(safeKey, ignored -> new Bucket(now));
            // Caffeine performs eviction incrementally. Explicit maintenance after a new key
            // keeps the configured maximum deterministic even during a high-cardinality flood.
            buckets.cleanUp();
        }
        return bucket.tryAcquire(now, maxRequests, window);
    }

    long bucketCount() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    private static Duration expirationTtl(Duration window) {
        try {
            return window.multipliedBy(2);
        } catch (ArithmeticException ignored) {
            return window;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class Bucket {
        private Instant windowStart;
        private int count;

        private Bucket(Instant windowStart) {
            this.windowStart = windowStart;
        }

        private synchronized boolean tryAcquire(Instant now, int maxRequests, Duration window) {
            if (!now.isBefore(windowStart.plus(window))) {
                windowStart = now;
                count = 0;
            }
            count++;
            return count <= maxRequests;
        }

    }
}
