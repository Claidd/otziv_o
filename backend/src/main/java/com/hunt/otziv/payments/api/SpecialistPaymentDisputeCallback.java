package com.hunt.otziv.payments.api;

import java.util.Optional;

/** Telegram transport passes scalar callback identity; payment owner decides the dispute. */
public interface SpecialistPaymentDisputeCallback {
    Optional<String> handle(String data, Long fromUserId, Long chatId);
}
