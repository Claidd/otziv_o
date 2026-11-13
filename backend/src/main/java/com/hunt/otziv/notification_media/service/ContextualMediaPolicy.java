package com.hunt.otziv.notification_media.service;

import java.util.Locale;
import java.net.URI;
import java.util.Set;
import com.hunt.otziv.notification_media.api.StaffPictureContext;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;

import static com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog.*;
import com.hunt.otziv.r_review.utils.ReviewTextPolicy;
import java.util.Map;

/** Facts, not filename keywords, decide which image pool is eligible. */
public final class ContextualMediaPolicy {
    private ContextualMediaPolicy() {}

    public static NotificationMediaEventCatalog resolve(StaffMediaSignal signal,
            Map<String, Object> card, long publicationsToday) {
        if (card.isEmpty()) return null;
        boolean review = "review".equals(signal.entityType());
        boolean recovery = "recovery_task".equals(signal.entityType());
        boolean bad = "bad_review_task".equals(signal.entityType());
        String status = String.valueOf(card.get("status"));
        return switch (signal.action()) {
            case "REVIEW_NAGUL" -> review && flag(card.get("walked")) ? WORKER_NAGUL_DONE : null;
            case "REVIEW_PUBLISH" -> review && flag(card.get("published"))
                    ? publicationsToday == 3 ? WORKER_THREE_PUBLICATIONS : WORKER_PUBLICATION_DONE : null;
            case "REVIEW_TEXT_UPDATE" -> review && !ReviewTextPolicy.isBlankOrPlaceholder((String) card.get("text"))
                    ? WORKER_TEXT_SAVED : null;
            case "BAD_TASK_COMPLETE" -> bad && "DONE".equals(status) ? WORKER_RATING_DONE : null;
            case "RECOVERY_TASK_UPDATE" -> recovery && "PLANNED".equals(status) ? WORKER_RECOVERY_FINISH : null;
            case "REVIEW_BOT_CHANGE", "RECOVERY_TASK_BOT_CHANGE" ->
                    (review && !flag(card.get("published"))) || (recovery && "PLANNED".equals(status))
                            ? WORKER_ACCOUNT_CHANGED : null;
            case "REVIEW_COPY_PASSWORD" -> review || recovery || bad ? WORKER_ACCOUNT_LOGIN_GUIDE : null;
            case "UNSAVED_CHANGES" -> WORKER_UNSAVED_CHANGES;
            case "EMPTY_TEXT" -> WORKER_TEXT_PENDING;
            case "BOARD" -> board(signal.section(), card, review, recovery, bad, status);
            default -> null;
        };
    }

    static Set<StaffPictureContext> pictureFacts(NotificationMediaEventCatalog event, Map<String, Object> card) {
        return switch (event) {
            case WORKER_TEXT_GUIDE -> "Коррекция".equals(card.get("status"))
                    ? Set.of(StaffPictureContext.TEXT_CORRECTION, StaffPictureContext.TEXT_PROOFREADING)
                    : Set.of(StaffPictureContext.TEXT_PROOFREADING);
            case WORKER_FRESH_ACCOUNT_GUIDE -> Set.of(StaffPictureContext.NEW_PROFILE);
            case WORKER_ACCOUNT_SHORTAGE -> Set.of(StaffPictureContext.ACCOUNT_SHORTAGE);
            case WORKER_RATING_GUIDE -> Set.of(StaffPictureContext.BAD_TASK_TEXT);
            case WORKER_PUBLICATION_PENDING ->
                    isVk(card) ? Set.of(StaffPictureContext.VK_PUBLICATION) : Set.of();
            default -> Set.of();
        };
    }

    private static boolean isVk(Map<String, Object> card) {
        String platform = String.valueOf(card.get("platform"));
        if ("VK".equalsIgnoreCase(platform) || "ВКонтакте".equalsIgnoreCase(platform)) return true;
        try {
            String host = URI.create(String.valueOf(card.get("branch_url"))).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru");
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    private static NotificationMediaEventCatalog board(String section, Map<String, Object> card,
            boolean review, boolean recovery, boolean bad, String status) {
        if (section == null) return null;
        return switch (section) {
            case "nagul" -> {
                if (!review || flag(card.get("walked")) || flag(card.get("published"))) yield null;
                if (flag(card.get("shortage_stub"))) yield WORKER_ACCOUNT_SHORTAGE;
                yield ((Number) card.getOrDefault("account_count", 2)).intValue() <= 1
                        ? WORKER_FRESH_ACCOUNT_GUIDE : WORKER_NAGUL_GUIDE;
            }
            case "publish" -> review && flag(card.get("walked")) && !flag(card.get("published"))
                    ? WORKER_PUBLICATION_PENDING : null;
            case "recovery" -> recovery && "PLANNED".equals(status) ? WORKER_RECOVERY_GUIDE : null;
            case "bad" -> bad && "NEW".equals(status) ? WORKER_RATING_GUIDE : null;
            case "new", "correct" -> !review && !recovery && !bad
                    && ("Новый".equals(status) || "Коррекция".equals(status)) ? WORKER_TEXT_GUIDE : null;
            default -> null;
        };
    }

    static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }
}
