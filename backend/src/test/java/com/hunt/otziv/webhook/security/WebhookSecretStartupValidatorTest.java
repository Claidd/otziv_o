package com.hunt.otziv.webhook.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSecretStartupValidatorTest {

    private static final String WA_SECRET = "whatsapp-webhook-secret-at-least-32-bytes";
    private static final String MAX_SECRET = "max-webhook-secret-at-least-32-bytes-long";

    @Test
    void failsWhenProductionWebhookSecretsAreMissing() {
        WebhookSecretStartupValidator validator = new WebhookSecretStartupValidator("", true, MAX_SECRET);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                validator::afterSingletonsInstantiated
        );

        assertTrue(error.getMessage().contains("WHATSAPP_WEBHOOK_SECRET"));
    }

    @Test
    void acceptsConfiguredProductionWebhookSecrets() {
        WebhookSecretStartupValidator validator = new WebhookSecretStartupValidator(WA_SECRET, true, MAX_SECRET);

        assertDoesNotThrow(validator::afterSingletonsInstantiated);
    }

    @Test
    void failsWhenProductionWebhookHmacIsDisabled() {
        WebhookSecretStartupValidator validator = new WebhookSecretStartupValidator(WA_SECRET, false, MAX_SECRET);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                validator::afterSingletonsInstantiated
        );

        assertTrue(error.getMessage().contains("WHATSAPP_WEBHOOK_HMAC_REQUIRED=true"));
    }
}
