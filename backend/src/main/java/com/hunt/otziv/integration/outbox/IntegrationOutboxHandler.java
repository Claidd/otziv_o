package com.hunt.otziv.integration.outbox;

/**
 * A typed, bounded and idempotent outbox side-effect handler.
 * Implementations must not retain the payload or log credentials/PII.
 */
public interface IntegrationOutboxHandler<T> {

    String eventType();

    Class<T> payloadType();

    void handle(IntegrationOutboxEvent<T> event) throws Exception;

    /**
     * Override only for failures which are known to be permanent. Unknown
     * transport failures should remain retryable.
     */
    default boolean isRetryable(Exception failure) {
        return true;
    }
}
