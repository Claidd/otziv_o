package com.hunt.otziv.integration.outbox;

/**
 * An event to be persisted in the caller's existing database transaction.
 *
 * <p>The deduplication key must be a stable, non-secret business idempotency
 * key. It is SHA-256 hashed before persistence and is never logged. Payloads
 * containing credential-like fields or values are rejected.</p>
 */
public record IntegrationOutboxEventDraft(
        String aggregateType,
        String aggregateId,
        Long aggregateVersion,
        String eventType,
        String deduplicationKey,
        Object payload,
        Integer maxAttempts
) {
}
