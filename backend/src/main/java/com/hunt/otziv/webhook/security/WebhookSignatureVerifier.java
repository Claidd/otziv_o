package com.hunt.otziv.webhook.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA256_PREFIX = "sha256=";

    public boolean verifyHeaderSecret(String expectedSecret, String providedSecret) {
        if (!hasText(expectedSecret) || !hasText(providedSecret)) {
            return false;
        }
        return constantTimeEquals(expectedSecret.trim(), providedSecret.trim());
    }

    public boolean verifyOptionalHmacSha256(String body, String secret, String providedSignature, boolean required) {
        if (!hasText(providedSignature)) {
            return !required;
        }
        return verifyHmacSha256(body, secret, providedSignature);
    }

    public boolean verifyHmacSha256(String body, String secret, String providedSignature) {
        if (!hasText(secret) || !hasText(providedSignature)) {
            return false;
        }

        String expected = hmacSha256Hex(body == null ? "" : body, secret.trim());
        String provided = normalizeSignature(providedSignature);
        return constantTimeEquals(expected, provided);
    }

    public String hmacSha256Hex(String body, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate webhook HMAC-SHA256", e);
        }
    }

    private static String normalizeSignature(String providedSignature) {
        String value = providedSignature.trim();
        if (value.regionMatches(true, 0, SHA256_PREFIX, 0, SHA256_PREFIX.length())) {
            value = value.substring(SHA256_PREFIX.length());
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
