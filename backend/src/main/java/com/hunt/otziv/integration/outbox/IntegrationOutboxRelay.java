package com.hunt.otziv.integration.outbox;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers at most one configured batch. External I/O is always outside the
 * claim/finalization transactions; a stable event id makes retries idempotent.
 */
@Component
class IntegrationOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(IntegrationOutboxRelay.class);

    private final IntegrationOutboxTransactionService transactions;
    private final IntegrationOutboxHandlerRegistry handlers;
    private final IntegrationOutboxBackoffPolicy backoff;
    private final IntegrationOutboxProperties properties;
    private final IntegrationOutboxMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    IntegrationOutboxRelay(
            IntegrationOutboxTransactionService transactions,
            IntegrationOutboxHandlerRegistry handlers,
            IntegrationOutboxBackoffPolicy backoff,
            IntegrationOutboxProperties properties,
            IntegrationOutboxMetrics metrics
    ) {
        this.transactions = transactions;
        this.handlers = handlers;
        this.backoff = backoff;
        this.properties = properties;
        this.metrics = metrics;
    }

    RelayReport runOnce() {
        if (!properties.isRelayEnabled()) {
            return RelayReport.disabled();
        }
        if (!running.compareAndSet(false, true)) {
            metrics.recordDelivery(
                    IntegrationOutboxMetrics.DeliveryOutcome.OVERLAPPING_CYCLE
            );
            return RelayReport.overlappingReport();
        }

        int recoveredFinalLeases = 0;
        int claimed = 0;
        int succeeded = 0;
        int retried = 0;
        int dead = 0;
        int fenced = 0;
        boolean cycleFailed = false;
        try {
            Set<String> allowedEventTypes = handlers.registeredEventTypes();
            if (!allowedEventTypes.isEmpty()) {
                recoveredFinalLeases = transactions.markExpiredFinalAttemptsDead(
                        allowedEventTypes
                );
                if (recoveredFinalLeases > 0) {
                    metrics.recordDelivery(
                            IntegrationOutboxMetrics.DeliveryOutcome.STALE_FINAL_LEASE,
                            recoveredFinalLeases
                    );
                }

                for (int index = 0; index < properties.getBatchSize(); index++) {
                    Optional<IntegrationOutboxRepository.Claim> next = transactions.claimNext(
                            allowedEventTypes
                    );
                    if (next.isEmpty()) {
                        break;
                    }
                    claimed++;
                    metrics.recordDelivery(IntegrationOutboxMetrics.DeliveryOutcome.CLAIMED);
                    DeliveryResult result = deliver(next.get());
                    switch (result) {
                        case SUCCEEDED -> succeeded++;
                        case RETRIED -> retried++;
                        case DEAD -> dead++;
                        case FENCED -> fenced++;
                    }
                }
            }
        } catch (RuntimeException exception) {
            cycleFailed = true;
            metrics.recordDelivery(IntegrationOutboxMetrics.DeliveryOutcome.CYCLE_FAILED);
            log.warn(
                    "Integration outbox relay cycle failed; errorType={}",
                    safeClassName(exception)
            );
        } finally {
            running.set(false);
            metrics.recordCycleCompleted();
        }

        return new RelayReport(
                true,
                false,
                recoveredFinalLeases,
                claimed,
                succeeded,
                retried,
                dead,
                fenced,
                cycleFailed
        );
    }

    private DeliveryResult deliver(IntegrationOutboxRepository.Claim claim) {
        long startedNanos = System.nanoTime();
        try {
            handlers.dispatch(claim);
            if (transactions.markSucceeded(claim)) {
                metrics.recordDelivery(IntegrationOutboxMetrics.DeliveryOutcome.SUCCEEDED);
                return DeliveryResult.SUCCEEDED;
            }
            return fenced(claim, "SUCCESS_FENCE_LOST");
        } catch (IntegrationOutboxHandlerRegistry.DispatchException exception) {
            if (!exception.retryable() || claim.attemptCount() >= claim.maxAttempts()) {
                if (transactions.markDead(claim, exception.reasonCode())) {
                    metrics.recordDelivery(IntegrationOutboxMetrics.DeliveryOutcome.DEAD);
                    log.warn(
                            "Integration outbox event moved to DEAD; eventId={} eventType={} attempt={} reasonCode={} errorType={}",
                            safeEventId(claim.eventId()),
                            IntegrationOutboxNames.safeForLog(claim.eventType(), 160),
                            claim.attemptCount(),
                            exception.reasonCode(),
                            safeClassName(exception.getCause())
                    );
                    return DeliveryResult.DEAD;
                }
                return fenced(claim, "DEAD_FENCE_LOST");
            }

            Duration delay = backoff.delayForAttempt(claim.attemptCount());
            if (transactions.markRetry(claim, delay, exception.reasonCode())) {
                metrics.recordDelivery(IntegrationOutboxMetrics.DeliveryOutcome.RETRIED);
                log.warn(
                        "Integration outbox event scheduled for retry; eventId={} eventType={} attempt={} reasonCode={} errorType={}",
                        safeEventId(claim.eventId()),
                        IntegrationOutboxNames.safeForLog(claim.eventType(), 160),
                        claim.attemptCount(),
                        exception.reasonCode(),
                        safeClassName(exception.getCause())
                );
                return DeliveryResult.RETRIED;
            }
            return fenced(claim, "RETRY_FENCE_LOST");
        } finally {
            metrics.recordHandlerDuration(Duration.ofNanos(System.nanoTime() - startedNanos));
        }
    }

    private DeliveryResult fenced(
            IntegrationOutboxRepository.Claim claim,
            String transition
    ) {
        metrics.recordDelivery(IntegrationOutboxMetrics.DeliveryOutcome.FENCED);
        log.warn(
                "Integration outbox transition fenced; eventId={} eventType={} transition={}",
                safeEventId(claim.eventId()),
                IntegrationOutboxNames.safeForLog(claim.eventType(), 160),
                transition
        );
        return DeliveryResult.FENCED;
    }

    private String safeEventId(String candidate) {
        try {
            return UUID.fromString(candidate).toString();
        } catch (RuntimeException exception) {
            return "invalid";
        }
    }

    private String safeClassName(Throwable exception) {
        if (exception == null) {
            return "none";
        }
        return IntegrationOutboxNames.safeForLog(
                exception.getClass().getSimpleName(),
                160
        );
    }

    enum DeliveryResult {
        SUCCEEDED,
        RETRIED,
        DEAD,
        FENCED
    }

    record RelayReport(
            boolean enabled,
            boolean overlapping,
            int recoveredFinalLeases,
            int claimed,
            int succeeded,
            int retried,
            int dead,
            int fenced,
            boolean cycleFailed
    ) {
        static RelayReport disabled() {
            return new RelayReport(false, false, 0, 0, 0, 0, 0, 0, false);
        }

        static RelayReport overlappingReport() {
            return new RelayReport(true, true, 0, 0, 0, 0, 0, 0, false);
        }
    }
}
