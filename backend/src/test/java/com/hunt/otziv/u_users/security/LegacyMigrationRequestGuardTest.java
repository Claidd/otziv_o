package com.hunt.otziv.u_users.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMigrationRequestGuardTest {

    @Test
    void closedMigrationWindowFailsClosedWithGone() {
        LegacyMigrationRequestGuard guard = guard(false, true, 5, 100);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> guard.enforce(requestFrom("198.51.100.25"), "legacy-user")
        );

        assertEquals(HttpStatus.GONE, error.getStatusCode());
        assertEquals("Legacy migration is no longer available.", error.getReason());
        assertEquals(0, guard.bucketCount());
    }

    @Test
    void limitsTrustedClientIpAndNormalizedUsernameWithoutKeepingRawUsernameInKey() {
        WebhookClientIpResolver resolver = new WebhookClientIpResolver("10.0.0.0/8", 16, 2_048);
        LegacyMigrationRequestGuard guard = new LegacyMigrationRequestGuard(
                resolver,
                true,
                true,
                2,
                Duration.ofMinutes(15),
                100,
                Ticker.systemTicker()
        );
        MockHttpServletRequest request = requestFrom("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.20");
        Instant now = Instant.parse("2026-08-01T00:00:00Z");

        guard.enforce(request, " Alice ", now);
        guard.enforce(request, "ÀＬＩＣＥ", now.plusSeconds(1));
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> guard.enforce(request, "alice", now.plusSeconds(2))
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
        String key = guard.rateLimitKey(request, "Alice");
        assertTrue(key.startsWith("198.51.100.20:"));
        assertFalse(key.toLowerCase().contains("alice"));
        assertEquals(64, key.substring(key.indexOf(':') + 1).length());
    }

    @Test
    void limiterDoesNotCoupleDifferentClientOrAccountPairs() {
        LegacyMigrationRequestGuard guard = guard(true, true, 1, 100);
        Instant now = Instant.parse("2026-08-01T00:00:00Z");

        guard.enforce(requestFrom("198.51.100.1"), "first", now);

        assertDoesNotThrow(() -> guard.enforce(requestFrom("198.51.100.2"), "first", now));
        assertDoesNotThrow(() -> guard.enforce(requestFrom("198.51.100.1"), "second", now));
    }

    @Test
    void limiterAllowsAnotherAttemptAfterWindow() {
        LegacyMigrationRequestGuard guard = guard(true, true, 1, 100);
        MockHttpServletRequest request = requestFrom("198.51.100.25");
        Instant now = Instant.parse("2026-08-01T00:00:00Z");

        guard.enforce(request, "legacy-user", now);
        assertThrows(
                ResponseStatusException.class,
                () -> guard.enforce(request, "legacy-user", now.plusSeconds(899))
        );

        assertDoesNotThrow(() -> guard.enforce(request, "legacy-user", now.plusSeconds(900)));
    }

    @Test
    void disabledRateLimiterLeavesMigrationAvailable() {
        LegacyMigrationRequestGuard guard = guard(true, false, 1, 100);
        MockHttpServletRequest request = requestFrom("198.51.100.25");

        for (int attempt = 0; attempt < 20; attempt++) {
            guard.enforce(request, "legacy-user");
        }

        assertEquals(0, guard.bucketCount());
    }

    @Test
    void cacheStaysBoundedAndExpiresInactiveBuckets() {
        AtomicLong tickerNanos = new AtomicLong();
        LegacyMigrationRequestGuard guard = new LegacyMigrationRequestGuard(
                new WebhookClientIpResolver("", 16, 2_048),
                true,
                true,
                1,
                Duration.ofMinutes(1),
                8,
                tickerNanos::get
        );
        MockHttpServletRequest request = requestFrom("198.51.100.25");
        Instant now = Instant.parse("2026-08-01T00:00:00Z");

        for (int index = 0; index < 100; index++) {
            guard.enforce(request, "user-" + index, now);
        }

        assertTrue(guard.bucketCount() <= 8);
        tickerNanos.addAndGet(Duration.ofMinutes(3).toNanos());
        assertEquals(0, guard.bucketCount());
    }

    private static LegacyMigrationRequestGuard guard(
            boolean migrationEnabled,
            boolean rateLimitEnabled,
            int maxAttempts,
            int maxBuckets
    ) {
        return new LegacyMigrationRequestGuard(
                new WebhookClientIpResolver("", 16, 2_048),
                migrationEnabled,
                rateLimitEnabled,
                maxAttempts,
                Duration.ofMinutes(15),
                maxBuckets,
                Ticker.systemTicker()
        );
    }

    private static MockHttpServletRequest requestFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
