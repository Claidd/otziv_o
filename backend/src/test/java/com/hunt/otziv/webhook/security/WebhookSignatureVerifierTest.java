package com.hunt.otziv.webhook.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

    @Test
    void verifiesHeaderSecretInConstantTimeCompatibleWay() {
        assertTrue(verifier.verifyHeaderSecret("secret-123", "secret-123"));
        assertFalse(verifier.verifyHeaderSecret("secret-123", "wrong"));
        assertFalse(verifier.verifyHeaderSecret("secret-123", ""));
    }

    @Test
    void verifiesSha256HmacWithOrWithoutPrefix() {
        String body = "{\"message\":\"hello\"}";
        String signature = verifier.hmacSha256Hex(body, "secret-123");

        assertTrue(verifier.verifyHmacSha256(body, "secret-123", signature));
        assertTrue(verifier.verifyHmacSha256(body, "secret-123", "sha256=" + signature.toUpperCase()));
        assertFalse(verifier.verifyHmacSha256(body, "secret-123", signature.substring(1) + "0"));
    }

    @Test
    void optionalHmacIsOnlyRequiredWhenConfigured() {
        assertTrue(verifier.verifyOptionalHmacSha256("{}", "secret-123", null, false));
        assertFalse(verifier.verifyOptionalHmacSha256("{}", "secret-123", null, true));
    }
}
