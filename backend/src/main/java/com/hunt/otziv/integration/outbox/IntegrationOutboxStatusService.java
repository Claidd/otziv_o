package com.hunt.otziv.integration.outbox;

import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Sanitized operational snapshot; never returns payloads, ids, errors or leases. */
@Service
class IntegrationOutboxStatusService {

    private static final Logger log = LoggerFactory.getLogger(
            IntegrationOutboxStatusService.class
    );

    private final IntegrationOutboxRepository repository;
    private final IntegrationOutboxProperties properties;
    private final IntegrationOutboxHandlerRegistry handlers;
    private final IntegrationOutboxMetrics metrics;

    IntegrationOutboxStatusService(
            IntegrationOutboxRepository repository,
            IntegrationOutboxProperties properties,
            IntegrationOutboxHandlerRegistry handlers,
            IntegrationOutboxMetrics metrics
    ) {
        this.repository = repository;
        this.properties = properties;
        this.handlers = handlers;
        this.metrics = metrics;
    }

    IntegrationOutboxStatusResponse snapshot() {
        try {
            IntegrationOutboxRepository.StatusSnapshot snapshot =
                    repository.statusSnapshot(properties.getStatusCountCap());
            return new IntegrationOutboxStatusResponse(
                    properties.isRelayEnabled(),
                    true,
                    null,
                    handlers.handlerCount(),
                    properties.getBatchSize(),
                    properties.getLeaseDuration().toMillis(),
                    toResponse(snapshot.due()),
                    toResponse(snapshot.processing()),
                    toResponse(snapshot.staleProcessing()),
                    toResponse(snapshot.dead()),
                    snapshot.databaseTime(),
                    snapshot.oldestDueAt(),
                    oldestDueAgeSeconds(snapshot.databaseTime(), snapshot.oldestDueAt()),
                    metrics.lastCycleCompletedEpochSeconds(),
                    metrics.lastSuccessfulDeliveryEpochSeconds()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Integration outbox status query failed; errorType={}",
                    safeClassName(exception)
            );
            return new IntegrationOutboxStatusResponse(
                    properties.isRelayEnabled(),
                    false,
                    "STATUS_QUERY_FAILED",
                    handlers.handlerCount(),
                    properties.getBatchSize(),
                    properties.getLeaseDuration().toMillis(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    metrics.lastCycleCompletedEpochSeconds(),
                    metrics.lastSuccessfulDeliveryEpochSeconds()
            );
        }
    }

    private IntegrationOutboxStatusResponse.CountSample toResponse(
            IntegrationOutboxRepository.CountSample sample
    ) {
        return new IntegrationOutboxStatusResponse.CountSample(
                sample.value(),
                sample.capped()
        );
    }

    private Long oldestDueAgeSeconds(LocalDateTime databaseTime, LocalDateTime oldestDueAt) {
        if (databaseTime == null || oldestDueAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(oldestDueAt, databaseTime).toSeconds());
    }

    private String safeClassName(RuntimeException exception) {
        return IntegrationOutboxNames.safeForLog(
                exception.getClass().getSimpleName(),
                160
        );
    }
}
