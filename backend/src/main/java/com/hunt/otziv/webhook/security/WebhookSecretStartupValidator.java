package com.hunt.otziv.webhook.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("prod")
public class WebhookSecretStartupValidator implements SmartInitializingSingleton {

    private final String whatsappWebhookSecret;
    private final boolean whatsappWebhookHmacRequired;
    private final String maxBotWebhookSecret;

    public WebhookSecretStartupValidator(
            @Value("${whatsapp.webhook.secret:}") String whatsappWebhookSecret,
            @Value("${whatsapp.webhook.hmac-required:true}") boolean whatsappWebhookHmacRequired,
            @Value("${max.bot.webhook-secret:}") String maxBotWebhookSecret
    ) {
        this.whatsappWebhookSecret = whatsappWebhookSecret;
        this.whatsappWebhookHmacRequired = whatsappWebhookHmacRequired;
        this.maxBotWebhookSecret = maxBotWebhookSecret;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = new ArrayList<>();
        if (!hasText(whatsappWebhookSecret)) {
            missing.add("WHATSAPP_WEBHOOK_SECRET");
        }
        if (!hasText(maxBotWebhookSecret)) {
            missing.add("MAX_BOT_WEBHOOK_SECRET");
        }
        if (!whatsappWebhookHmacRequired) {
            missing.add("WHATSAPP_WEBHOOK_HMAC_REQUIRED=true");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Production webhook secrets are required: " + String.join(", ", missing));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
