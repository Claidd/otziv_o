package com.hunt.otziv.webhook.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebhookRateLimiter {

    private final boolean enabled;
    private final int maxRequests;
    private final Duration window;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public WebhookRateLimiter(
            @Value("${webhook.rate-limit.enabled:true}") boolean enabled,
            @Value("${webhook.rate-limit.max-requests:120}") int maxRequests,
            @Value("${webhook.rate-limit.window:PT1M}") Duration window
    ) {
        this.enabled = enabled;
        this.maxRequests = Math.max(1, maxRequests);
        this.window = window == null || window.isNegative() || window.isZero() ? Duration.ofMinutes(1) : window;
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, Instant.now());
    }

    boolean tryAcquire(String key, Instant now) {
        if (!enabled) {
            return true;
        }

        String safeKey = hasText(key) ? key : "unknown";
        cleanup(now);
        return buckets
                .computeIfAbsent(safeKey, ignored -> new Bucket(now))
                .tryAcquire(now, maxRequests, window);
    }

    private void cleanup(Instant now) {
        if (buckets.size() < 10_000) {
            return;
        }
        buckets.entrySet().removeIf(entry -> entry.getValue().isExpired(now, window.multipliedBy(2)));
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

        private synchronized boolean isExpired(Instant now, Duration ttl) {
            return !now.isBefore(windowStart.plus(ttl));
        }
    }
}
