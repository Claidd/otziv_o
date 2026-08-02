package com.hunt.otziv.webhook.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final String telegramLinkSecret;
    private final String maxLinkSecret;

    @Autowired
    public WebhookSecretStartupValidator(
            @Value("${whatsapp.webhook.secret:}") String whatsappWebhookSecret,
            @Value("${whatsapp.webhook.hmac-required:true}") boolean whatsappWebhookHmacRequired,
            @Value("${max.bot.webhook-secret:}") String maxBotWebhookSecret,
            @Value("${telegram.bot.link-secret:}") String telegramLinkSecret,
            @Value("${max.bot.link-secret:}") String maxLinkSecret
    ) {
        this.whatsappWebhookSecret = whatsappWebhookSecret;
        this.whatsappWebhookHmacRequired = whatsappWebhookHmacRequired;
        this.maxBotWebhookSecret = maxBotWebhookSecret;
        this.telegramLinkSecret = telegramLinkSecret;
        this.maxLinkSecret = maxLinkSecret;
    }

    public WebhookSecretStartupValidator(
            String whatsappWebhookSecret,
            boolean whatsappWebhookHmacRequired,
            String maxBotWebhookSecret
    ) {
        this(
                whatsappWebhookSecret,
                whatsappWebhookHmacRequired,
                maxBotWebhookSecret,
                "test-telegram-link-secret-32-bytes-long",
                "test-max-link-secret-distinct-32-bytes"
        );
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = new ArrayList<>();
        if (!isStrongSecret(whatsappWebhookSecret)) {
            missing.add("WHATSAPP_WEBHOOK_SECRET");
        }
        if (!isStrongSecret(maxBotWebhookSecret)) {
            missing.add("MAX_BOT_WEBHOOK_SECRET");
        }
        if (!isStrongSecret(telegramLinkSecret)) {
            missing.add("TELEGRAM_BOT_LINK_SECRET");
        }
        if (!isStrongSecret(maxLinkSecret)) {
            missing.add("MAX_BOT_LINK_SECRET");
        }
        if (!whatsappWebhookHmacRequired) {
            missing.add("WHATSAPP_WEBHOOK_HMAC_REQUIRED=true");
        }
        if (isStrongSecret(telegramLinkSecret)
                && isStrongSecret(maxLinkSecret)
                && telegramLinkSecret.trim().equals(maxLinkSecret.trim())) {
            missing.add("Telegram and MAX link secrets must differ");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Production webhook secrets are required: " + String.join(", ", missing));
        }
    }

    private static boolean isStrongSecret(String value) {
        return OneTimeGroupLinkTokenStore.isStrongSecret(value);
    }
}
