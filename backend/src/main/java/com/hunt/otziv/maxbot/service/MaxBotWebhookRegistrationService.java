package com.hunt.otziv.maxbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
@Profile("prod")
@Slf4j
public class MaxBotWebhookRegistrationService {

    private static final String DEFAULT_WEBHOOK_PATH = "/webhook/max";
    private static final String SECRET_PATTERN = "^[A-Za-z0-9_-]{5,256}$";

    private final MaxBotClient maxBotClient;
    private final boolean autoRegisterEnabled;
    private final String appBaseUrl;
    private final String webhookUrl;
    private final String webhookSecret;
    private final String updateTypes;

    public MaxBotWebhookRegistrationService(
            MaxBotClient maxBotClient,
            @Value("${max.bot.webhook.auto-register-enabled:false}") boolean autoRegisterEnabled,
            @Value("${otziv.app-base-url:}") String appBaseUrl,
            @Value("${max.bot.webhook.url:}") String webhookUrl,
            @Value("${max.bot.webhook-secret:}") String webhookSecret,
            @Value("${max.bot.webhook.update-types:bot_started,bot_added,message_created}") String updateTypes
    ) {
        this.maxBotClient = maxBotClient;
        this.autoRegisterEnabled = autoRegisterEnabled;
        this.appBaseUrl = appBaseUrl;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.updateTypes = updateTypes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhookOnStartup() {
        if (!autoRegisterEnabled) {
            log.info("MAX webhook auto-registration disabled");
            return;
        }

        if (!maxBotClient.isConfigured()) {
            log.warn("MAX webhook auto-registration skipped: MAX_BOT_TOKEN is empty");
            return;
        }

        String url = resolvedWebhookUrl();
        if (!hasText(url)) {
            log.warn("MAX webhook auto-registration skipped: set OTZIV_APP_BASE_URL or MAX_BOT_WEBHOOK_URL");
            return;
        }

        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            log.warn("MAX webhook auto-registration skipped: MAX requires HTTPS webhook on port 443, got {}", url);
            return;
        }

        String secret = trimmed(webhookSecret);
        if (!hasText(secret) || !secret.matches(SECRET_PATTERN)) {
            log.warn("MAX webhook auto-registration skipped: MAX_BOT_WEBHOOK_SECRET must match {}", SECRET_PATTERN);
            return;
        }

        List<String> types = parsedUpdateTypes();
        if (types.isEmpty()) {
            log.warn("MAX webhook auto-registration skipped: update types are empty");
            return;
        }

        maxBotClient.registerWebhook(url, types, secret);
    }

    String resolvedWebhookUrl() {
        String explicitUrl = trimmed(webhookUrl);
        if (hasText(explicitUrl)) {
            return explicitUrl;
        }

        String baseUrl = trimmed(appBaseUrl);
        if (!hasText(baseUrl)) {
            return "";
        }

        return baseUrl.replaceAll("/+$", "") + DEFAULT_WEBHOOK_PATH;
    }

    /**
     * Tracked chat messages also need their edits (client chat control keeps the original text and the
     * edit); the deploy configuration lists only the older types, so message_edited follows message_created.
     */
    List<String> parsedUpdateTypes() {
        List<String> configured = Arrays.stream(trimmed(updateTypes).split(","))
                .map(String::trim)
                .filter(MaxBotWebhookRegistrationService::hasText)
                .distinct()
                .toList();
        if (!configured.contains(MESSAGE_CREATED) || configured.contains(MaxBotUpdateService.TYPE_MESSAGE_EDITED)) {
            return configured;
        }
        List<String> withEdits = new java.util.ArrayList<>(configured);
        withEdits.add(MaxBotUpdateService.TYPE_MESSAGE_EDITED);
        return List.copyOf(withEdits);
    }

    private static final String MESSAGE_CREATED = "message_created";

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
