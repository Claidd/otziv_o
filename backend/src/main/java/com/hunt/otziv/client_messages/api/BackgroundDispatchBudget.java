package com.hunt.otziv.client_messages.api;

import java.time.LocalDateTime;

/**
 * Messaging-owned admission for automatic client messages that are not autoresponder states
 * (common/paper invoice reminders, offer campaigns). Runs in its own short transaction under
 * the autoresponder's dispatch-budget lock, re-reads the switches fresh and, when it allows a
 * send, records a reservation counted by every background sender.
 */
public interface BackgroundDispatchBudget {

    enum Purpose {
        /** Automatic payment reminder: master switch, pause, reminder toggle, business windows,
         *  shared daily limit and per-channel interval. */
        PAYMENT_REMINDER,
        BAD_REVIEW_INVOICE,
        /** Offer campaign message: master switch, pause and the shared per-channel interval;
         *  the campaign keeps its own schedule and daily quota. */
        OFFER_CAMPAIGN
    }

    record Admission(boolean allowed, String code, String reason, LocalDateTime nextAttemptAt) {
        public static Admission allow() {
            return new Admission(true, null, null, null);
        }
    }

    Admission reserve(Purpose purpose, String sourceKind, long sourceId, ClientMessageDelivery.Target target);

    /** Same admission when the caller knows only the destination platform: WHATSAPP, TELEGRAM or MAX. */
    Admission reserveOnPlatform(Purpose purpose, String sourceKind, long sourceId, String platform);

    /**
     * Same admission plus the interval of the sending account (the WhatsApp client id; the single
     * Telegram or MAX bot), shared by the autoresponder family and offer campaigns. Without a known
     * account only the per-channel rules apply.
     */
    default Admission reserveForSender(Purpose purpose, String sourceKind, long sourceId,
                                       ClientMessageDelivery.Target target, String senderClientId) {
        return reserve(purpose, sourceKind, sourceId, target);
    }

    /** {@link #reserveForSender(Purpose, String, long, ClientMessageDelivery.Target, String)} by platform. */
    default Admission reserveForSender(Purpose purpose, String sourceKind, long sourceId,
                                       String platform, String senderClientId) {
        return reserveOnPlatform(purpose, sourceKind, sourceId, platform);
    }
}
