package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the relay's short, independent database transactions. */
@Service
public class IntegrationOutboxTransactionService {

    private static final String FINAL_LEASE_ERROR = "FINAL_ATTEMPT_PROCESSING_LEASE_EXPIRED";
    private static final int MAX_ALLOWED_EVENT_TYPES = 256;

    private final IntegrationOutboxRepository repository;
    private final IntegrationOutboxProperties properties;
    private final Supplier<String> tokenSupplier;
    private final String processingOwner;

    @Autowired
    IntegrationOutboxTransactionService(
            IntegrationOutboxRepository repository,
            IntegrationOutboxProperties properties
    ) {
        this(
                repository,
                properties,
                () -> UUID.randomUUID().toString(),
                "outbox-" + UUID.randomUUID()
        );
    }

    IntegrationOutboxTransactionService(
            IntegrationOutboxRepository repository,
            IntegrationOutboxProperties properties,
            Supplier<String> tokenSupplier,
            String processingOwner
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.processingOwner = IntegrationOutboxNames.requiredIdentifier(
                processingOwner,
                128,
                "Outbox processing owner"
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 10
    )
    public Optional<IntegrationOutboxRepository.Claim> claimNext(
            Collection<String> allowedEventTypes
    ) {
        List<String> normalizedEventTypes = normalizeAllowedEventTypes(allowedEventTypes);
        if (!properties.isRelayEnabled() || normalizedEventTypes.isEmpty()) {
            return Optional.empty();
        }
        String token = requireUuid(tokenSupplier.get());
        return repository.claimNext(
                token,
                processingOwner,
                toMicros(properties.getLeaseDuration()),
                normalizedEventTypes
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markSucceeded(IntegrationOutboxRepository.Claim claim) {
        return repository.markSucceeded(claim.outboxId(), claim.processingToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markRetry(
            IntegrationOutboxRepository.Claim claim,
            Duration delay,
            String reasonCode
    ) {
        return repository.markRetry(
                claim.outboxId(),
                claim.processingToken(),
                toMicros(delay),
                safeReason(reasonCode)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markDead(
            IntegrationOutboxRepository.Claim claim,
            String reasonCode
    ) {
        return repository.markDead(
                claim.outboxId(),
                claim.processingToken(),
                safeReason(reasonCode)
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 10
    )
    public int markExpiredFinalAttemptsDead(Collection<String> allowedEventTypes) {
        List<String> normalizedEventTypes = normalizeAllowedEventTypes(allowedEventTypes);
        if (!properties.isRelayEnabled() || normalizedEventTypes.isEmpty()) {
            return 0;
        }
        return repository.markExpiredExhaustedDead(
                properties.getBatchSize(),
                normalizedEventTypes,
                FINAL_LEASE_ERROR
        );
    }

    private List<String> normalizeAllowedEventTypes(Collection<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() > MAX_ALLOWED_EVENT_TYPES) {
            throw new IllegalArgumentException("Too many outbox event types are enabled");
        }
        return candidates.stream()
                .map(candidate -> IntegrationOutboxNames.requiredType(
                        candidate,
                        160,
                        "Outbox allowed event type"
                ))
                .distinct()
                .sorted()
                .toList();
    }

    private long toMicros(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Outbox duration must be positive");
        }
        return duration.toNanos() / 1_000L;
    }

    private String requireUuid(String candidate) {
        try {
            return UUID.fromString(candidate).toString();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Outbox processing token supplier returned invalid UUID");
        }
    }

    private String safeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "UNCLASSIFIED_FAILURE";
        }
        String sanitized = reasonCode.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return sanitized.substring(0, Math.min(512, sanitized.length()));
    }
}
