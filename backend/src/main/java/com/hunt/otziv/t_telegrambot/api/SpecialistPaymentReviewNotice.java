package com.hunt.otziv.t_telegrambot.api;

/** Sends one dispute notice; the caller persists intent before invoking Telegram. */
public interface SpecialistPaymentReviewNotice {
    /** Telegram may already have shown the actionable notice to the recipient. */
    final class DeliveryUncertain extends IllegalStateException {
        public DeliveryUncertain(Throwable cause) {
            super("Telegram payment notice outcome is uncertain", cause);
        }
    }

    boolean sendPaymentReviewNoticeOnce(long chatId, String text, String buttonText, String callbackData);
}
