package com.hunt.otziv.u_users.images;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Short-lived signed links for {@code GET /images/{id}}.
 *
 * <p>The API returns {@code /images/{id}?exp=...&sig=...} next to every {@code imageId} it already shows
 * to the caller, so image bytes are no longer enumerable by guessing sequential ids. The expiry is
 * rounded up to a {@link #BUCKET} boundary: a link stays byte-identical inside that window, and the
 * browser can reuse its cached copy instead of downloading the photo on every API refresh.
 *
 * <p>Installed mobile versions (Android 73 and older) build {@code /images/{id}} themselves. While
 * {@code otziv.images.unsigned-access-enabled} stays {@code true} those requests, and links signed by a
 * previous key, are still served; switch it off once the old clients are retired.
 */
@Slf4j
@Component
public class SignedImageUrls {

    public static final long DEFAULT_IMAGE_ID = 1L;
    static final Duration BUCKET = Duration.ofMinutes(15);
    static final Duration MAX_BROWSER_CACHE = Duration.ofDays(1);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_DOMAIN = "otziv-image-url-signing-key-v1";
    private static final String PAYLOAD_PREFIX = "otziv-image-v1:";
    private static final int MIN_SECRET_LENGTH = 32;
    private static final Pattern EXPIRY = Pattern.compile("[0-9]{1,12}");
    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** One per JVM: test contexts and a restarted bean agree on links when no secret is configured. */
    private static final byte[] PROCESS_KEY = randomKey();

    private static volatile SignedImageUrls current;

    private final byte[] key;
    private final Duration ttl;
    private final boolean unsignedAccessEnabled;
    private final boolean configuredSecret;
    private final Clock clock;

    @Autowired
    public SignedImageUrls(
            @Value("${otziv.images.signing-secret:}") String secret,
            @Value("${otziv.images.signed-url-ttl:PT1H}") Duration ttl,
            @Value("${otziv.images.unsigned-access-enabled:true}") boolean unsignedAccessEnabled
    ) {
        this(secret, ttl, unsignedAccessEnabled, Clock.systemUTC());
    }

    SignedImageUrls(String secret, Duration ttl, boolean unsignedAccessEnabled, Clock clock) {
        if (ttl == null || ttl.compareTo(Duration.ofMinutes(5)) < 0 || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("otziv.images.signed-url-ttl must be between PT5M and PT24H");
        }
        String normalized = secret == null ? "" : secret.trim();
        if (!normalized.isEmpty() && normalized.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("otziv.images.signing-secret must contain at least "
                    + MIN_SECRET_LENGTH + " characters");
        }
        this.configuredSecret = !normalized.isEmpty();
        this.key = configuredSecret
                ? hmac(normalized.getBytes(StandardCharsets.UTF_8), KEY_DOMAIN)
                : PROCESS_KEY.clone();
        this.ttl = ttl;
        this.unsignedAccessEnabled = unsignedAccessEnabled;
        this.clock = clock;
    }

    @PostConstruct
    void publish() {
        current = this;
        if (!configuredSecret) {
            log.warn("Image links are signed with a per-process key: links issued before a restart stop working "
                    + "when unsigned access is disabled. Set OTZIV_IMAGE_URL_SIGNING_SECRET (32+ characters).");
        }
        if (unsignedAccessEnabled) {
            log.info("Unsigned /images/{{id}} requests remain allowed for installed older clients");
        }
    }

    @PreDestroy
    void unpublish() {
        if (current == this) {
            current = null;
        }
    }

    /**
     * Signed path for DTO serialization. {@code null} when the id is absent or the signer is not running
     * (plain unit tests); clients then fall back to the legacy path.
     */
    public static String pathFor(Long imageId) {
        SignedImageUrls signer = current;
        return signer == null || imageId == null || imageId <= 0 ? null : signer.signedPath(imageId);
    }

    /** Same as {@link #pathFor(Long)}, but uses the shared default avatar when a profile has no photo. */
    public static String pathForOrDefault(Long imageId) {
        return pathFor(imageId == null || imageId <= 0 ? DEFAULT_IMAGE_ID : imageId);
    }

    public String signedPath(long imageId) {
        if (imageId <= 0) {
            throw new IllegalArgumentException("imageId must be positive");
        }
        long expiresAt = expiryFor(clock.instant());
        return "/images/" + imageId + "?exp=" + expiresAt + "&sig=" + signature(imageId, expiresAt);
    }

    public Access authorize(long imageId, String expiry, String signature) {
        if (expiry == null && signature == null) {
            return unsignedAccessEnabled ? Access.unsigned() : Access.denied();
        }
        Instant validUntil = verifiedExpiry(imageId, expiry, signature);
        if (validUntil != null) {
            return Access.signed(validUntil);
        }
        // During the transition an expired or old-key link still loads like an unsigned request.
        return unsignedAccessEnabled ? Access.unsigned() : Access.denied();
    }

    public Duration browserCacheFor(Access access) {
        if (access.kind() != AccessKind.SIGNED) {
            return MAX_BROWSER_CACHE;
        }
        Duration remaining = Duration.between(clock.instant(), access.validUntil());
        if (remaining.isNegative()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(MAX_BROWSER_CACHE) > 0 ? MAX_BROWSER_CACHE : remaining;
    }

    public boolean unsignedAccessEnabled() {
        return unsignedAccessEnabled;
    }

    long expiryFor(Instant now) {
        long bucketSeconds = BUCKET.toSeconds();
        long earliest = now.plus(ttl).getEpochSecond();
        return ((earliest + bucketSeconds - 1) / bucketSeconds) * bucketSeconds;
    }

    private Instant verifiedExpiry(long imageId, String expiry, String signature) {
        if (imageId <= 0 || expiry == null || signature == null
                || !EXPIRY.matcher(expiry).matches() || !SIGNATURE.matcher(signature).matches()) {
            return null;
        }
        long expiresAt = Long.parseLong(expiry);
        Instant now = clock.instant();
        Instant validUntil = Instant.ofEpochSecond(expiresAt);
        if (!validUntil.isAfter(now)
                || validUntil.isAfter(now.plus(ttl).plus(BUCKET).plus(CLOCK_SKEW))) {
            return null;
        }
        byte[] expected = signature(imageId, expiresAt).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual) ? validUntil : null;
    }

    private String signature(long imageId, long expiresAt) {
        byte[] mac = hmac(key, PAYLOAD_PREFIX + imageId + ":" + expiresAt);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private static byte[] hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public enum AccessKind { SIGNED, UNSIGNED, DENIED }

    public record Access(AccessKind kind, Instant validUntil) {
        static Access signed(Instant validUntil) {
            return new Access(AccessKind.SIGNED, validUntil);
        }

        static Access unsigned() {
            return new Access(AccessKind.UNSIGNED, null);
        }

        static Access denied() {
            return new Access(AccessKind.DENIED, null);
        }

        public boolean allowed() {
            return kind != AccessKind.DENIED;
        }
    }
}
