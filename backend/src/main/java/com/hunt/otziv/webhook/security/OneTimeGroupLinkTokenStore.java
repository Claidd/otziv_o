package com.hunt.otziv.webhook.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OneTimeGroupLinkTokenStore {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Duration ttl;
    private final Cache<String, LinkTarget> links;
    private final Cache<String, String> latestTokens;

    public OneTimeGroupLinkTokenStore(@Value("${group-links.token-ttl:PT15M}") Duration configuredTtl) {
        this.ttl = bound(configuredTtl);
        this.links = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(100_000)
                .build();
        this.latestTokens = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(100_000)
                .build();
    }

    public OneTimeGroupLinkTokenStore() {
        this(Duration.ofMinutes(15));
    }

    public synchronized String issue(String scope, Long targetId, String secret) {
        requireInputs(scope, targetId, secret);
        String targetKey = targetKey(scope, targetId);
        String existing = latestTokens.getIfPresent(targetKey);
        if (existing != null && links.getIfPresent(existing) != null) {
            return existing;
        }
        byte[] random = new byte[18];
        SECURE_RANDOM.nextBytes(random);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String signature = signature(scope, targetId, nonce, secret);
        String token = nonce + "." + signature;
        links.put(token, new LinkTarget(scope, targetId));
        String previous = latestTokens.asMap().put(targetKey, token);
        if (previous != null) {
            links.invalidate(previous);
        }
        return token;
    }

    /** Verifies and atomically removes a token. At most one caller receives the target id. */
    public synchronized Optional<Long> consume(String token, String expectedScope, String secret) {
        if (!hasText(token) || !hasText(expectedScope) || !isStrongSecret(secret)) {
            return Optional.empty();
        }
        int separator = token.lastIndexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }
        String nonce = token.substring(0, separator);
        String providedSignature = token.substring(separator + 1);
        AtomicReference<Long> consumed = new AtomicReference<>();
        links.asMap().computeIfPresent(token, (key, target) -> {
            if (!expectedScope.equals(target.scope())) {
                return target;
            }
            String expectedSignature = signature(target.scope(), target.id(), nonce, secret);
            if (!constantTimeEquals(expectedSignature, providedSignature)) {
                return target;
            }
            consumed.set(target.id());
            latestTokens.asMap().remove(targetKey(target.scope(), target.id()), token);
            return null;
        });
        return Optional.ofNullable(consumed.get());
    }

    public Duration ttl() {
        return ttl;
    }

    public static boolean isStrongSecret(String secret) {
        return hasText(secret) && secret.trim().getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private void requireInputs(String scope, Long targetId, String secret) {
        if (!hasText(scope) || targetId == null || targetId <= 0 || !isStrongSecret(secret)) {
            throw new IllegalStateException("A dedicated group-link secret of at least 32 bytes is required");
        }
    }

    private String signature(String scope, Long targetId, String nonce, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal((scope + "\n" + targetId + "\n" + nonce).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign group-link token", exception);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static Duration bound(Duration value) {
        Duration safe = value == null || value.isNegative() || value.isZero() ? Duration.ofMinutes(15) : value;
        if (safe.compareTo(Duration.ofMinutes(1)) < 0) {
            return Duration.ofMinutes(1);
        }
        return safe.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : safe;
    }

    private static String targetKey(String scope, Long targetId) {
        return scope + "\n" + targetId;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record LinkTarget(String scope, Long id) {
    }
}
