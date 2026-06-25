package com.hunt.otziv.config.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    public static final String NAGUL_COOLDOWN_MINUTES = "nagul.cooldown.minutes";
    public static final String NAGUL_LOOKAHEAD_DAYS = "nagul.lookahead.days";
    public static final String REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD = "review.account.walked-counter-threshold";
    public static final String REVIEW_ACCOUNT_WALK_DELAY_DAYS = "review.account.walk-delay-days";
    public static final String WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED = "worker.publication.special-task-gate.enabled";
    public static final String WORKER_PUBLICATION_SPECIAL_TASK_GATE_ROLLOUT_STARTED_ON = "worker.publication.special-task-gate.rollout-started-on";
    public static final String WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON = "worker.publication.special-task-gate.activate-on";
    public static final String TELEGRAM_REPORTS_MORNING_ENABLED = "telegram.reports.morning.enabled";
    public static final String TELEGRAM_REPORTS_MORNING_TIME = "telegram.reports.morning.time";
    public static final String TELEGRAM_REPORTS_MORNING_LAST_RUN_KEY = "telegram.reports.morning.last-run-key";
    public static final String TELEGRAM_REPORTS_EVENING_ENABLED = "telegram.reports.evening.enabled";
    public static final String TELEGRAM_REPORTS_EVENING_TIME = "telegram.reports.evening.time";
    public static final String TELEGRAM_REPORTS_EVENING_LAST_RUN_KEY = "telegram.reports.evening.last-run-key";
    public static final String TELEGRAM_REPORTS_ZONE = "telegram.reports.zone";
    public static final String WHATSAPP_GROUP_SYNC_ENABLED = "whatsapp.group-sync.enabled";
    public static final String WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES = "whatsapp.group-sync.interval-minutes";
    public static final String WHATSAPP_GROUP_SYNC_LAST_RUN_AT = "whatsapp.group-sync.last-run-at";
    public static final String WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT = "whatsapp.group-sync.last-linked-count";
    public static final String CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED = "client.publication-progress-reports.enabled";
    public static final String CLIENT_PUBLICATION_PROGRESS_REPORT_TEXT = "client.publication-progress-reports.text";
    public static final String ARCHIVE_ORDERS_RETENTION_DAYS = "archive.orders.retention.days";
    public static final String ARCHIVE_ORDERS_BATCH_SIZE = "archive.orders.batch.size";
    public static final String ARCHIVE_ORDERS_APPLY_ENABLED = "archive.orders.apply.enabled";
    public static final String ARCHIVE_ORDERS_SCHEDULE_WORKER_ENABLED = "archive.orders.schedule.worker.enabled";
    public static final String ARCHIVE_ORDERS_SCHEDULE_ENABLED = "archive.orders.schedule.enabled";
    public static final String ARCHIVE_ORDERS_SCHEDULE_CRON = "archive.orders.schedule.cron";
    public static final String ARCHIVE_ORDERS_SCHEDULE_ZONE = "archive.orders.schedule.zone";
    public static final String ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY = "archive.orders.schedule.last-run-key";
    public static final String ARCHIVE_ORDERS_RUN_MODE = "archive.orders.run.mode";
    public static final String ARCHIVE_ORDERS_REASON = "archive.orders.reason";
    public static final String PAYMENT_LINKS_ARCHIVE_ENABLED = "payment.links.archive.enabled";
    public static final String PAYMENT_LINKS_ARCHIVE_PAID_RETENTION_DAYS = "payment.links.archive.paid-retention-days";
    public static final String PAYMENT_LINKS_ARCHIVE_FINAL_RETENTION_DAYS = "payment.links.archive.final-retention-days";
    public static final String PAYMENT_LINKS_ARCHIVE_BATCH_SIZE = "payment.links.archive.batch-size";
    public static final String CLIENT_MESSAGES_WORKER_ENABLED = "client.messages.worker.enabled";
    public static final String CLIENT_MESSAGES_LIVE_ENABLED = "client.messages.live.enabled";
    public static final String CLIENT_MESSAGES_IMMEDIATE_ENABLED = "client.messages.immediate.enabled";
    public static final String CLIENT_MESSAGES_MONITOR_ENABLED = "client.messages.monitor.enabled";
    public static final String CLIENT_MESSAGES_REVIEW_CHECK_ENABLED = "client.messages.review-check.enabled";
    public static final String CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED = "client.messages.review-check.auto-archive.enabled";
    public static final String CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED = "client.messages.client-text-reminder.enabled";
    public static final String CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED = "client.messages.payment-reminder.enabled";
    public static final String CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED = "client.messages.bad-review-invoice.enabled";
    public static final String CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED = "client.messages.bad-review-auto-ban.enabled";
    public static final String CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_ENABLED = "client.messages.review-recovery-notice.enabled";
    public static final String CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED = "client.messages.archive-reorder.enabled";
    public static final String CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED = "client.messages.payment-overdue.enabled";
    public static final String CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED = "client.messages.payment-overdue.live-enabled";
    public static final String CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS = "client.messages.review-check.interval-days";
    public static final String CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_DAYS = "client.messages.review-check.auto-archive-days";
    public static final String CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_INTERVAL_DAYS = "client.messages.client-text-reminder.interval-days";
    public static final String CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS = "client.messages.payment-reminder.interval-days";
    public static final String CLIENT_MESSAGES_REVIEW_CHECK_RETRY_DELAY_HOURS = "client.messages.review-check-retry.delay-hours";
    public static final String CLIENT_MESSAGES_PAYMENT_INVOICE_RETRY_DELAY_HOURS = "client.messages.payment-invoice-retry.delay-hours";
    public static final String CLIENT_MESSAGES_BAD_REVIEW_INVOICE_RETRY_DELAY_HOURS = "client.messages.bad-review-invoice-retry.delay-hours";
    public static final String CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_DELAY_DAYS = "client.messages.bad-review-auto-ban.delay-days";
    public static final String CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_RETRY_DELAY_HOURS = "client.messages.review-recovery-notice.retry-delay-hours";
    public static final String CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS = "client.messages.payment-overdue-days";
    public static final String CLIENT_MESSAGES_ARCHIVE_REORDER_MONTHS = "client.messages.archive-reorder.months";
    public static final String CLIENT_MESSAGES_ARCHIVE_REORDER_JITTER_DAYS = "client.messages.archive-reorder.jitter-days";
    public static final String CLIENT_MESSAGES_RETENTION_DAYS = "client.messages.retention-days";
    public static final String CLIENT_MESSAGES_TICK_BATCH_SIZE = "client.messages.tick.batch-size";
    public static final String CLIENT_MESSAGES_CANDIDATE_LIMIT = "client.messages.candidate-limit";
    public static final String CLIENT_MESSAGES_DEFAULT_GAP_SECONDS = "client.messages.default-gap-seconds";
    public static final String CLIENT_MESSAGES_TELEGRAM_GAP_SECONDS = "client.messages.telegram-gap-seconds";
    public static final String CLIENT_MESSAGES_MAX_GAP_SECONDS = "client.messages.max-gap-seconds";
    public static final String CLIENT_MESSAGES_WHATSAPP_GAP_SECONDS = "client.messages.whatsapp-gap-seconds";
    public static final String CLIENT_MESSAGES_DAILY_LIMIT = "client.messages.daily-limit";
    public static final String CLIENT_MESSAGES_BUSINESS_WINDOWS = "client.messages.business-windows";
    public static final String CLIENT_MESSAGES_REVIEW_CHECK_STATUSES = "client.messages.review-check.statuses";
    public static final String CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES = "client.messages.client-text-reminder.statuses";
    public static final String CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES = "client.messages.payment-reminder.statuses";
    public static final String CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES = "client.messages.payment-overdue.statuses";
    public static final String CLIENT_MESSAGES_CLOSED_ORDER_STATUSES = "client.messages.closed-order.statuses";
    public static final String CLIENT_MESSAGES_PAYMENT_OVERDUE_TARGET_STATUS = "client.messages.payment-overdue.target-status";
    public static final String CLIENT_MESSAGES_ARCHIVE_COMPANY_STATUS = "client.messages.archive.company-status";
    public static final String CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES = "client.messages.archive.inactive-order-statuses";
    public static final String CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES = "client.messages.open-next-order-request-statuses";
    public static final String CLIENT_MESSAGES_REVIEW_LINK_BASE_URL = "client.messages.review-link-base-url";
    public static final String CLIENT_MESSAGES_REVIEW_REMINDER_TEXT = "client.messages.review-reminder-text";
    public static final String CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_TEXT = "client.messages.client-text-reminder-text";
    public static final String CLIENT_MESSAGES_PUBLICATION_STARTED_TEXT = "client.messages.publication-started-text";
    public static final String CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE = "client.messages.payment-instruction-source";
    public static final String CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT = "client.messages.payment-reminder-text";
    public static final String CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT = "client.messages.payment-link-copy-text";
    public static final String CLIENT_MESSAGES_PAYMENT_SUCCESS_TEXT = "client.messages.payment-success-text";
    public static final String CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_TEXT = "client.messages.review-recovery-notice-text";
    public static final String CLIENT_MESSAGES_ARCHIVE_OFFER_TEXT = "client.messages.archive-offer-text";
    public static final String CLIENT_MESSAGES_ERROR_PROTECTION_ENABLED = "client.messages.error-protection.enabled";
    public static final String CLIENT_MESSAGES_ERROR_PROTECTION_THRESHOLD = "client.messages.error-protection.threshold";
    public static final String CLIENT_MESSAGES_ERROR_PROTECTION_WINDOW_MINUTES = "client.messages.error-protection.window-minutes";
    public static final String CLIENT_MESSAGES_ERROR_PROTECTION_COOLDOWN_MINUTES = "client.messages.error-protection.cooldown-minutes";
    public static final String CLIENT_MESSAGES_PAUSED_UNTIL = "client.messages.paused-until";
    public static final String CLIENT_MESSAGES_PAUSE_REASON = "client.messages.pause-reason";
    public static final String CLIENT_MESSAGES_WHATSAPP_AUTH_RETRY_HOURS = "client.messages.whatsapp-auth.retry-hours";
    public static final String CLIENT_MESSAGES_WHATSAPP_AUTH_ALERT_COOLDOWN_HOURS = "client.messages.whatsapp-auth.alert-cooldown-hours";
    public static final String PAYMENTS_TBANK_RUNTIME_MODE = "payments.tbank.runtime-mode";
    public static final String PAYMENTS_TBANK_ENABLED = "payments.tbank.enabled";
    public static final String PAYMENTS_TBANK_PAYMENT_LINKS_ENABLED = "payments.tbank.payment-links-enabled";
    public static final String PAYMENTS_TBANK_MANAGER_UI_ENABLED = "payments.tbank.manager-ui-enabled";
    public static final String PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS = "payments.tbank.apply-confirmed-payments";
    public static final String PAYMENTS_TBANK_PAYMENT_PAGE_MODE = "payments.tbank.payment-page-mode";
    public static final String PAYMENTS_TBANK_TPAY_ENABLED = "payments.tbank.tpay-enabled";
    public static final String PAYMENTS_TBANK_SBERPAY_ENABLED = "payments.tbank.sberpay-enabled";
    public static final String PAYMENTS_TBANK_MIRPAY_ENABLED = "payments.tbank.mirpay-enabled";

    private final AppSettingRepository repository;
    private final Map<String, CachedSetting> cache = new ConcurrentHashMap<>();

    @Value("${otziv.cache.app-settings.ttl:PT5M}")
    private Duration cacheTtl;

    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        String value = getCachedValue(key);
        return value == null ? fallback : parseInt(value, fallback);
    }

    @Transactional
    public int setInt(String key, int value) {
        AppSetting setting = repository.findById(key)
                .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(String.valueOf(value));
        repository.save(setting);
        cache.put(key, CachedSetting.fresh(String.valueOf(value), cacheTtl));
        return value;
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean fallback) {
        String value = getCachedValue(key);
        return value == null ? fallback : parseBoolean(value, fallback);
    }

    @Transactional
    public boolean setBoolean(String key, boolean value) {
        setString(key, String.valueOf(value));
        return value;
    }

    @Transactional(readOnly = true)
    public String getString(String key, String fallback) {
        return trimToNull(getCachedValue(key))
                .filter(value -> !value.isEmpty())
                .orElse(fallback);
    }

    @Transactional
    public String setString(String key, String value) {
        AppSetting setting = repository.findById(key)
                .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(value);
        repository.save(setting);
        cache.put(key, CachedSetting.fresh(value, cacheTtl));
        return value;
    }

    private String getCachedValue(String key) {
        CachedSetting cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return cache.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired()) {
                return existing;
            }
            String value = repository.findById(key)
                    .map(AppSetting::getValue)
                    .orElse(null);
            return CachedSetting.fresh(value, cacheTtl);
        }).value();
    }

    private java.util.Optional<String> trimToNull(String value) {
        return value == null ? java.util.Optional.empty() : java.util.Optional.of(value.trim());
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    private record CachedSetting(String value, long expiresAtNanos) {
        private static CachedSetting fresh(String value, Duration ttl) {
            Duration safeTtl = ttl == null || ttl.isNegative() ? Duration.ZERO : ttl;
            return new CachedSetting(value, System.nanoTime() + safeTtl.toNanos());
        }

        private boolean isExpired() {
            return System.nanoTime() >= expiresAtNanos;
        }
    }
}
