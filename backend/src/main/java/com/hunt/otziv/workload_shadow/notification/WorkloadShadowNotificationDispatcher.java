package com.hunt.otziv.workload_shadow.notification;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowBusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@Slf4j
public class WorkloadShadowNotificationDispatcher {

    public static final String GROUP_NOTIFICATIONS_ENABLED =
            "workload.shadow.group-notifications-enabled";
    public static final String NOTIFICATION_GROUP_CHAT_ID =
            "workload.shadow.notification-group-chat-id";
    public static final String TARGET_ADMIN_OWNER_MONITORING =
            "ADMIN_OWNER_MONITORING";
    public static final String ERROR_MISSING_GROUP_BINDING = "MISSING_GROUP_BINDING";
    public static final String ERROR_TELEGRAM_SEND_FAILED = "TELEGRAM_SEND_FAILED";

    private static final String BATCH_SIZE = "workload.shadow.notification-batch-size";
    private static final String MAX_ATTEMPTS = "workload.shadow.notification-max-attempts";
    private static final String LEASE_MINUTES = "workload.shadow.notification-lease-minutes";
    private static final String RETRY_BASE_MINUTES = "workload.shadow.notification-retry-base-minutes";

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final int DEFAULT_LEASE_MINUTES = 5;
    private static final int DEFAULT_RETRY_BASE_MINUTES = 1;
    private static final int MAX_BACKOFF_MINUTES = 24 * 60;

    private final WorkloadShadowNotificationStore store;
    private final AppSettingService settings;
    private final TelegramService telegramService;
    private final WorkloadShadowMetrics metrics;
    private final Clock clock;

    @Autowired
    public WorkloadShadowNotificationDispatcher(
            WorkloadShadowNotificationStore store,
            AppSettingService settings,
            TelegramService telegramService,
            WorkloadShadowMetrics metrics
    ) {
        this(store, settings, telegramService, metrics, Clock.systemDefaultZone());
    }

    WorkloadShadowNotificationDispatcher(
            WorkloadShadowNotificationStore store,
            AppSettingService settings,
            TelegramService telegramService,
            WorkloadShadowMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.settings = settings;
        this.telegramService = telegramService;
        this.metrics = metrics;
        this.clock = clock;
    }

    public DispatchSummary dispatchDue() {
        if (!settings.getBoolean(GROUP_NOTIFICATIONS_ENABLED, false)) {
            return DispatchSummary.disabledSummary();
        }
        Long notificationGroupChatId = notificationGroupChatId();
        if (notificationGroupChatId == null) {
            return DispatchSummary.disabledSummary();
        }

        LocalDateTime now = WorkloadShadowBusinessTime.now(settings, clock);
        int batchSize = bounded(settings.getInt(BATCH_SIZE, DEFAULT_BATCH_SIZE), 1, 25);
        int leaseMinutes = bounded(settings.getInt(LEASE_MINUTES, DEFAULT_LEASE_MINUTES), 1, 30);
        int maxAttempts = bounded(settings.getInt(MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS), 1, 20);
        int retryBaseMinutes =
                bounded(settings.getInt(RETRY_BASE_MINUTES, DEFAULT_RETRY_BASE_MINUTES), 1, 60);

        List<Long> dueIds = store.findDueEventIds(now, batchSize);
        MutableSummary summary = new MutableSummary(dueIds.size());
        if (dueIds.isEmpty()) {
            return summary.toImmutable();
        }

        LocalDateTime leaseUntil = now.plusMinutes(leaseMinutes);
        summary.claimed = store.claim(dueIds, now, leaseUntil);
        List<WorkloadShadowClaimedNotification> claimed =
                store.findClaimed(dueIds, now, leaseUntil);
        if (claimed.size() != summary.claimed) {
            log.warn(
                    "Workload shadow claim/fetch mismatch claimed={} fetched={}",
                    summary.claimed,
                    claimed.size()
            );
        }

        List<WorkloadShadowDeliveryOutcome> outcomes =
                new ArrayList<>(claimed.size());
        for (WorkloadShadowClaimedNotification notification : claimed) {
            dispatchClaimed(
                    notification,
                    notificationGroupChatId,
                    now,
                    maxAttempts,
                    retryBaseMinutes,
                    summary,
                    outcomes
            );
        }
        store.applyDeliveryOutcomes(outcomes, now, leaseUntil);
        recordMetrics(summary);
        return summary.toImmutable();
    }

    private void dispatchClaimed(
            WorkloadShadowClaimedNotification claimed,
            long notificationGroupChatId,
            LocalDateTime now,
            int maxAttempts,
            int retryBaseMinutes,
            MutableSummary summary,
            List<WorkloadShadowDeliveryOutcome> outcomes
    ) {
        WorkloadShadowNotificationEvent event = claimed.event();
        if (!TARGET_ADMIN_OWNER_MONITORING.equals(event.targetGroupType())) {
            outcomes.add(WorkloadShadowDeliveryOutcome.dead(
                    event,
                    event.deliveryAttempts(),
                    ERROR_MISSING_GROUP_BINDING,
                    "MISSING_GROUP_BINDING: shadow-событие не направлено в общую группу администраторов и владельцев"
            ));
            summary.missingGroups++;
            summary.dead++;
            log.warn(
                    "Workload shadow notification blocked: invalid target type eventId={} managerId={} targetType={}",
                    event.id(),
                    event.managerId(),
                    event.targetGroupType()
            );
            return;
        }

        if (event.deliveryAttempts() >= maxAttempts) {
            outcomes.add(WorkloadShadowDeliveryOutcome.dead(
                    event,
                    event.deliveryAttempts(),
                    ERROR_TELEGRAM_SEND_FAILED,
                    "TELEGRAM_SEND_FAILED: превышен лимит попыток до очередной отправки"
            ));
            summary.dead++;
            return;
        }

        boolean sent;
        String error = "TelegramService вернул false";
        try {
            sent = telegramService.sendMessage(
                    notificationGroupChatId,
                    shadowMessage(event),
                    "HTML"
            );
        } catch (RuntimeException exception) {
            sent = false;
            error = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
            log.warn(
                    "Workload shadow Telegram send threw eventId={} groupChatId={}: {}",
                    event.id(),
                    notificationGroupChatId,
                    error
            );
        }

        int attempt = event.deliveryAttempts() + 1;
        if (sent) {
            outcomes.add(WorkloadShadowDeliveryOutcome.sent(event, now));
            summary.sent++;
            return;
        }
        if (attempt >= maxAttempts) {
            outcomes.add(WorkloadShadowDeliveryOutcome.dead(
                    event,
                    attempt,
                    ERROR_TELEGRAM_SEND_FAILED,
                    "TELEGRAM_SEND_FAILED: " + error
            ));
            summary.dead++;
            return;
        }

        LocalDateTime nextAttemptAt = now.plus(retryBackoff(attempt, retryBaseMinutes));
        outcomes.add(WorkloadShadowDeliveryOutcome.retry(
                event,
                nextAttemptAt,
                ERROR_TELEGRAM_SEND_FAILED,
                "TELEGRAM_SEND_FAILED: " + error
        ));
        summary.retried++;
    }

    private Long notificationGroupChatId() {
        String configured = settings.getStringAllowEmpty(
                NOTIFICATION_GROUP_CHAT_ID,
                ""
        );
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            long chatId = Long.parseLong(configured.trim());
            return chatId < 0 ? chatId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void recordMetrics(MutableSummary summary) {
        for (int index = 0; index < summary.sent; index++) {
            metrics.recordSent();
        }
        for (int index = 0; index < summary.retried; index++) {
            metrics.recordRetry();
        }
        for (int index = 0; index < summary.dead; index++) {
            metrics.recordDead();
        }
        for (int index = 0; index < summary.missingGroups; index++) {
            metrics.recordMissingGroup();
        }
    }

    private String shadowMessage(WorkloadShadowNotificationEvent event) {
        String severity = escaped(event.severity());
        String title = escaped(event.title());
        String message = escaped(event.message());
        String eventType = escaped(event.eventType());
        return "🟣 <b>SHADOW · РЕЖИМ НАБЛЮДЕНИЯ</b>\n"
                + "<i>Система ничего не передаёт и не меняет назначения.</i>\n\n"
                + (severity.isBlank() ? "" : "<b>Уровень:</b> " + severity + "\n")
                + "<b>" + title + "</b>\n"
                + message
                + (eventType.isBlank() ? "" : "\n\n<code>" + eventType + "</code>");
    }

    private Duration retryBackoff(int attempt, int baseMinutes) {
        int[] multipliers = {1, 5, 15, 60, 180, 360, 720, 1440};
        int index = Math.max(0, Math.min(attempt - 1, multipliers.length - 1));
        long minutes = Math.min(
                MAX_BACKOFF_MINUTES,
                (long) baseMinutes * multipliers[index]
        );
        return Duration.ofMinutes(Math.max(1, minutes));
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String escaped(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "без описания";
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    public record DispatchSummary(
            int scanned,
            int claimed,
            int sent,
            int retried,
            int dead,
            boolean disabled
    ) {
        static DispatchSummary disabledSummary() {
            return new DispatchSummary(0, 0, 0, 0, 0, true);
        }
    }

    private static final class MutableSummary {
        private final int scanned;
        private int claimed;
        private int sent;
        private int retried;
        private int dead;
        private int missingGroups;

        private MutableSummary(int scanned) {
            this.scanned = scanned;
        }

        private DispatchSummary toImmutable() {
            return new DispatchSummary(scanned, claimed, sent, retried, dead, false);
        }
    }
}
