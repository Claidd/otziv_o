package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-bound entry point for future payment/Keycloak/S3 dual writes. */
@Service
public class IntegrationOutboxService {

    private static final int MAX_ATTEMPTS_LIMIT = 100;

    private final IntegrationOutboxRepository repository;
    private final IntegrationOutboxPayloadPolicy payloadPolicy;
    private final IntegrationOutboxProperties properties;
    private final IntegrationOutboxMetrics metrics;

    IntegrationOutboxService(
            IntegrationOutboxRepository repository,
            IntegrationOutboxPayloadPolicy payloadPolicy,
            IntegrationOutboxProperties properties,
            IntegrationOutboxMetrics metrics
    ) {
        this.repository = repository;
        this.payloadPolicy = payloadPolicy;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Persists an event atomically with the caller's state change. Calling this
     * method without an already active transaction fails closed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EnqueueResult enqueue(IntegrationOutboxEventDraft draft) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "Outbox enqueue requires an active business transaction"
            );
        }
        if (draft == null) {
            throw new IllegalArgumentException("Outbox event draft is required");
        }

        String aggregateType = IntegrationOutboxNames.requiredType(
                draft.aggregateType(),
                100,
                "Outbox aggregate type"
        );
        String aggregateId = IntegrationOutboxNames.requiredIdentifier(
                draft.aggregateId(),
                160,
                "Outbox aggregate id"
        );
        if (draft.aggregateVersion() != null && draft.aggregateVersion() < 0) {
            throw new IllegalArgumentException("Outbox aggregate version must be non-negative");
        }
        String eventType = IntegrationOutboxNames.requiredType(
                draft.eventType(),
                160,
                "Outbox event type"
        );
        payloadPolicy.validateDeduplicationKey(draft.deduplicationKey());
        String payloadJson = payloadPolicy.serialize(draft.payload());
        int maxAttempts = boundedMaxAttempts(draft.maxAttempts());

        String proposedEventId = UUID.randomUUID().toString();
        byte[] deduplicationKeyHash = deduplicationHash(
                aggregateType,
                aggregateId,
                eventType,
                draft.deduplicationKey()
        );
        IntegrationOutboxRepository.EnqueueResult stored = repository.enqueue(
                proposedEventId,
                aggregateType,
                aggregateId,
                draft.aggregateVersion(),
                eventType,
                deduplicationKeyHash,
                payloadJson,
                maxAttempts
        );
        validateStoredEnvelope(
                stored,
                aggregateType,
                aggregateId,
                draft.aggregateVersion(),
                eventType,
                payloadJson,
                maxAttempts
        );
        recordEnqueueAfterCommit(stored.created());
        return new EnqueueResult(UUID.fromString(stored.eventId()), stored.created());
    }

    private int boundedMaxAttempts(Integer requested) {
        int value = requested == null ? properties.getDefaultMaxAttempts() : requested;
        if (value < 1 || value > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("Outbox max attempts must be between 1 and 100");
        }
        return value;
    }

    private void validateStoredEnvelope(
            IntegrationOutboxRepository.EnqueueResult stored,
            String aggregateType,
            String aggregateId,
            Long aggregateVersion,
            String eventType,
            String payloadJson,
            int maxAttempts
    ) {
        boolean sameEnvelope = Objects.equals(stored.aggregateType(), aggregateType)
                && Objects.equals(stored.aggregateId(), aggregateId)
                && Objects.equals(stored.aggregateVersion(), aggregateVersion)
                && Objects.equals(stored.eventType(), eventType)
                && stored.maxAttempts() == maxAttempts;
        if (!sameEnvelope) {
            throw new IllegalStateException(
                    "Outbox deduplication key conflicts with a different event envelope"
            );
        }
        if (!payloadPolicy.semanticallyEquals(stored.payloadJson(), payloadJson)) {
            throw new IllegalStateException(
                    "Outbox deduplication key conflicts with a different payload"
            );
        }
    }

    private byte[] deduplicationHash(
            String aggregateType,
            String aggregateId,
            String eventType,
            String deduplicationKey
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, "otziv-outbox-v1");
            updateLengthPrefixed(digest, eventType);
            updateLengthPrefixed(digest, aggregateType);
            updateLengthPrefixed(digest, aggregateId);
            updateLengthPrefixed(digest, deduplicationKey);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private void recordEnqueueAfterCommit(boolean created) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        metrics.recordEnqueue(created);
                    }
                }
        );
    }

    public record EnqueueResult(UUID eventId, boolean created) {
    }
}
