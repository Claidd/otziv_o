package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Immutable event-type registry with typed payload deserialization. */
@Component
class IntegrationOutboxHandlerRegistry {

    private final Map<String, IntegrationOutboxHandler<?>> handlers;
    private final ObjectMapper objectMapper;

    IntegrationOutboxHandlerRegistry(
            List<IntegrationOutboxHandler<?>> discoveredHandlers,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Map<String, IntegrationOutboxHandler<?>> registered = new HashMap<>();
        for (IntegrationOutboxHandler<?> handler : discoveredHandlers) {
            if (handler == null || handler.payloadType() == null) {
                throw new IllegalStateException("Outbox handler and payload type are required");
            }
            String eventType = IntegrationOutboxNames.requiredType(
                    handler.eventType(),
                    160,
                    "Outbox handler event type"
            );
            if (registered.putIfAbsent(eventType, handler) != null) {
                throw new IllegalStateException(
                        "Duplicate outbox handler for event type " + eventType
                );
            }
        }
        handlers = Map.copyOf(registered);
    }

    int handlerCount() {
        return handlers.size();
    }

    Set<String> registeredEventTypes() {
        return handlers.keySet();
    }

    void dispatch(IntegrationOutboxRepository.Claim claim) throws DispatchException {
        final UUID eventId;
        try {
            eventId = UUID.fromString(claim.eventId());
            IntegrationOutboxNames.requiredType(
                    claim.aggregateType(),
                    100,
                    "Outbox aggregate type"
            );
            IntegrationOutboxNames.requiredIdentifier(
                    claim.aggregateId(),
                    160,
                    "Outbox aggregate id"
            );
            IntegrationOutboxNames.requiredType(
                    claim.eventType(),
                    160,
                    "Outbox event type"
            );
        } catch (IllegalArgumentException exception) {
            throw DispatchException.permanent("INVALID_ENVELOPE", exception);
        }

        IntegrationOutboxHandler<?> handler = handlers.get(claim.eventType());
        if (handler == null) {
            throw DispatchException.permanent("HANDLER_NOT_REGISTERED", null);
        }
        dispatchTyped(handler, claim, eventId);
    }

    private <T> void dispatchTyped(
            IntegrationOutboxHandler<T> handler,
            IntegrationOutboxRepository.Claim claim,
            UUID eventId
    ) throws DispatchException {
        final T payload;
        try {
            payload = objectMapper.readValue(claim.payloadJson(), handler.payloadType());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw DispatchException.permanent("INVALID_PAYLOAD", exception);
        }

        IntegrationOutboxEvent<T> event = new IntegrationOutboxEvent<>(
                eventId,
                claim.aggregateType(),
                claim.aggregateId(),
                claim.aggregateVersion(),
                claim.eventType(),
                claim.attemptCount(),
                claim.maxAttempts(),
                payload
        );
        try {
            handler.handle(event);
        } catch (Exception exception) {
            boolean retryable;
            try {
                retryable = handler.isRetryable(exception);
            } catch (RuntimeException classificationFailure) {
                retryable = true;
            }
            throw new DispatchException(
                    retryable ? "HANDLER_RETRYABLE_FAILURE" : "HANDLER_PERMANENT_FAILURE",
                    retryable,
                    exception
            );
        }
    }

    static final class DispatchException extends Exception {

        private final String reasonCode;
        private final boolean retryable;

        private DispatchException(
                String reasonCode,
                boolean retryable,
                Throwable cause
        ) {
            super(reasonCode, cause);
            this.reasonCode = reasonCode;
            this.retryable = retryable;
        }

        static DispatchException permanent(String reasonCode, Throwable cause) {
            return new DispatchException(reasonCode, false, cause);
        }

        static DispatchException retryable(String reasonCode, Throwable cause) {
            return new DispatchException(reasonCode, true, cause);
        }

        String reasonCode() {
            return reasonCode;
        }

        boolean retryable() {
            return retryable;
        }
    }
}
