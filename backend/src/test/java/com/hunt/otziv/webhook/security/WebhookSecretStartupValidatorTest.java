package com.hunt.otziv.webhook.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSecretStartupValidatorTest {

    @Test
    void failsWhenProductionWebhookSecretsAreMissing() {
        WebhookSecretStartupValidator validator = new WebhookSecretStartupValidator("", true, "max-secret");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                validator::afterSingletonsInstantiated
        );

        assertTrue(error.getMessage().contains("WHATSAPP_WEBHOOK_SECRET"));
    }

    @Test
    void acceptsConfiguredProductionWebhookSecrets() {
        WebhookSecretStartupValidator validator = new WebhookSecretStartupValidator("wa-secret", true, "max-secret");

        assertDoesNotThrow(validator::afterSingletonsInstantiated);
    }

    @Test
    void failsWhenProductionWebhookHmacIsDisabled() {
        WebhookSecretStartupValidator validator = new WebhookSecretStartupValidator("wa-secret", false, "max-secret");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                validator::afterSingletonsInstantiated
        );

        assertTrue(error.getMessage().contains("WHATSAPP_WEBHOOK_HMAC_REQUIRED=true"));
    }
}
