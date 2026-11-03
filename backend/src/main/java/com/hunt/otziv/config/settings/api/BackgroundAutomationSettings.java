package com.hunt.otziv.config.settings.api;

/** Fresh safety switches used by background automation; reads must bypass cached values. */
public interface BackgroundAutomationSettings {
    String CLIENT_MESSAGES_WORKER_ENABLED = "client.messages.worker.enabled";
    String CLIENT_MESSAGES_PAUSED_UNTIL = "client.messages.paused-until";
    String CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED = "client.messages.client-text-reminder.enabled";
    String CLIENT_MESSAGES_REVIEW_CHECK_ENABLED = "client.messages.review-check.enabled";
    String CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED = "client.messages.review-check.auto-archive.enabled";
    String CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED = "client.messages.archive-reorder.enabled";
    String CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED = "client.messages.payment-reminder.enabled";
    String CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED = "client.messages.payment-overdue.enabled";
    String CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED = "client.messages.bad-review-invoice.enabled";
    String CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED = "client.messages.bad-review-auto-ban.enabled";
    String CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_ENABLED = "client.messages.review-recovery-notice.enabled";

    boolean getBooleanFreshFailClosed(String key, boolean fallbackWhenMissing);
    String getStringFresh(String key, String fallbackWhenMissing);
}
