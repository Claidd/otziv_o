package com.hunt.otziv.integration.outbox;

import java.util.UUID;

/**
 * Typed delivery envelope. Handlers must use {@code eventId} as the
 * idempotency key at the remote boundary because delivery is at least once.
 */
public record IntegrationOutboxEvent<T>(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        Long aggregateVersion,
        String eventType,
        int attempt,
        int maxAttempts,
        T payload
) {
}
