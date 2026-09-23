package com.hunt.otziv.notification_media.service;

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

    private static NotificationMediaEventCatalog board(String section, Map<String, Object> card,
            boolean review, boolean recovery, boolean bad, String status) {
        if (section == null) return null;
        return switch (section) {
            case "nagul" -> review && !flag(card.get("walked")) && !flag(card.get("published"))
                    ? ((Number) card.getOrDefault("account_count", 2)).intValue() <= 1
                            ? WORKER_FRESH_ACCOUNT_GUIDE : WORKER_NAGUL_GUIDE : null;
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
