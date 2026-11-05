package com.hunt.otziv.client_messages.api;

/**
 * Operator-initiated client messages (manager control: reply to a client, manual delivery) in the
 * sending account's interval journal that the autoresponder family and offer campaigns share (MSG-5).
 *
 * <p>The journal never delays, refuses or reorders the manual message. It records the message right
 * before the provider call, in its own transaction, so the background lanes keep the channel interval
 * after it on the same account. Recording is best effort: the caller sends even when it fails.
 */
public interface ManualDispatchJournal {

    /** The route of one manual message: {@code WHATSAPP} with its client id, or the single Telegram/MAX bot. */
    record Sender(String platform, String clientId) {
    }

    void recordManualDispatch(Sender sender);
}
