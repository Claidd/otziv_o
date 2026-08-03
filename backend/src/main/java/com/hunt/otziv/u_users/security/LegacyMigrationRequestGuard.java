package com.hunt.otziv.u_users.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class LegacyMigrationRequestGuard {

    private static final int HARD_MAX_ATTEMPTS = 1_000;
    private static final int HARD_MAX_BUCKETS = 100_000;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    private static final Duration MIN_WINDOW = Duration.ofSeconds(1);
    private static final Duration MAX_WINDOW = Duration.ofDays(1);

    private final WebhookClientIpResolver clientIpResolver;
    private final boolean migrationEnabled;
    private final boolean rateLimitEnabled;
    private final int maxAttempts;
    private final Duration window;
    private final Cache<String, Bucket> buckets;

    @Autowired
    public LegacyMigrationRequestGuard(
            WebhookClientIpResolver clientIpResolver,
            @Value("${otziv.auth.legacy-migration.enabled:false}") boolean migrationEnabled,
            @Value("${otziv.auth.legacy-migration.rate-limit.enabled:true}") boolean rateLimitEnabled,
            @Value("${otziv.auth.legacy-migration.rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${otziv.auth.legacy-migration.rate-limit.window:PT15M}") Duration window,
            @Value("${otziv.auth.legacy-migration.rate-limit.max-buckets:10000}") int maxBuckets
    ) {
        this(
                clientIpResolver,
                migrationEnabled,
                rateLimitEnabled,
                maxAttempts,
                window,
                maxBuckets,
                Ticker.systemTicker()
        );
    }

    LegacyMigrationRequestGuard(
            WebhookClientIpResolver clientIpResolver,
            boolean migrationEnabled,
            boolean rateLimitEnabled,
            int maxAttempts,
            Duration window,
            int maxBuckets,
            Ticker ticker
    ) {
        this.clientIpResolver = clientIpResolver;
        this.migrationEnabled = migrationEnabled;
        this.rateLimitEnabled = rateLimitEnabled;
        this.maxAttempts = clamp(maxAttempts, 1, HARD_MAX_ATTEMPTS);
        this.window = validWindow(window);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(clamp(maxBuckets, 1, HARD_MAX_BUCKETS))
                .expireAfterAccess(expirationTtl(this.window))
                .ticker(ticker == null ? Ticker.systemTicker() : ticker)
                .build();
    }

    public void enforce(HttpServletRequest request, String username) {
        enforce(request, username, Instant.now());
    }

    void enforce(HttpServletRequest request, String username, Instant now) {
        if (!migrationEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    "Legacy migration is no longer available."
            );
        }
        if (!rateLimitEnabled) {
            return;
        }

        String key = rateLimitKey(request, username);
        Bucket bucket = buckets.getIfPresent(key);
        if (bucket == null) {
            bucket = buckets.get(key, ignored -> new Bucket(now));
            buckets.cleanUp();
        }
        if (!bucket.tryAcquire(now, maxAttempts, window)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many legacy migration attempts."
            );
        }
    }

    String rateLimitKey(HttpServletRequest request, String username) {
        String clientIp = clientIpResolver.resolve(request);
        String safeClientIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        return safeClientIp + ':' + hashNormalizedUsername(username);
    }

    int bucketCount() {
        buckets.cleanUp();
        return Math.toIntExact(buckets.estimatedSize());
    }

    static String hashNormalizedUsername(String username) {
        String decomposed = Normalizer.normalize(
                username == null ? "" : username.strip(),
                Normalizer.Form.NFKD
        );
        StringBuilder folded = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(codePoint -> !isCombiningMark(codePoint))
                .forEach(folded::appendCodePoint);
        String normalized = folded.toString().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static Duration validWindow(Duration configured) {
        if (configured == null || configured.isNegative() || configured.isZero()) {
            return DEFAULT_WINDOW;
        }
        if (configured.compareTo(MIN_WINDOW) < 0) {
            return MIN_WINDOW;
        }
        return configured.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : configured;
    }

    private static Duration expirationTtl(Duration window) {
        try {
            return window.multipliedBy(2);
        } catch (ArithmeticException ignored) {
            return window;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static final class Bucket {
        private Instant windowStart;
        private int attempts;

        private Bucket(Instant windowStart) {
            this.windowStart = windowStart;
        }

        private synchronized boolean tryAcquire(Instant now, int maxAttempts, Duration window) {
            if (!now.isBefore(windowStart.plus(window))) {
                windowStart = now;
                attempts = 0;
            }
            attempts++;
            return attempts <= maxAttempts;
        }
    }
}
