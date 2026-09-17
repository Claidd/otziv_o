package com.hunt.otziv.client_campaigns;

import java.util.Locale;

/** Uses company board statuses; online presence does not define this audience. */
final class CampaignAudience {
    private CampaignAudience() {}
    static String group(String status) {
        return switch (status == null ? "" : status) {
            case "В работе" -> "ACTIVE";
            case "На стопе" -> "STOPPED";
            case "Бан" -> "BANNED";
            default -> null;
        };
    }
    static int priority(String audience) {
        return switch (audience) { case "ACTIVE" -> 1; case "STOPPED" -> 2; default -> 3; };
    }
    static boolean included(String audience, CampaignModels.Settings settings) {
        return switch (audience) {
            case "ACTIVE" -> settings.includeActive();
            case "STOPPED" -> settings.includeStopped();
            case "BANNED" -> settings.includeBanned();
            default -> false;
        };
    }
    static String destination(String url, String clientId, String groupId, Long telegramId, Long maxId) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (value.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+") && hasText(clientId) && hasText(groupId)) {
            try { return "WHATSAPP:" + com.hunt.otziv.whatsapp.dto.WhatsAppDestination.normalize("send-group",groupId); }
            catch (IllegalArgumentException invalid) { return null; }
        }
        if ((value.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || value.startsWith("tg://resolve?")) && telegramId != null && telegramId != 0)
            return "TELEGRAM:" + telegramId;
        if (value.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+") && maxId != null && maxId != 0)
            return "MAX:" + maxId;
        return null;
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
