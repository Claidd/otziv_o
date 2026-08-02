package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
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
