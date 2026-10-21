package com.hunt.otziv.t_telegrambot.api;

/** Sends one dispute notice; the caller persists intent before invoking Telegram. */
public interface SpecialistPaymentReviewNotice {
    boolean sendPaymentReviewNoticeOnce(long chatId, String text, String buttonText, String callbackData);
}
