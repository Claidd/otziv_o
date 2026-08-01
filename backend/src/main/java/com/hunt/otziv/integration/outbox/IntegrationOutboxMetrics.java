package com.hunt.otziv.integration.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics; no payload, aggregate id, token or error message is tagged. */
@Component
class IntegrationOutboxMetrics {

    private static final String DELIVERY_COUNTER = "otziv.integration.outbox.delivery";
    private static final String ENQUEUE_COUNTER = "otziv.integration.outbox.enqueue";

    private final Map<DeliveryOutcome, Counter> deliveryCounters =
            new EnumMap<>(DeliveryOutcome.class);
    private final Counter enqueueCreated;
    private final Counter enqueueDeduplicated;
    private final Timer handlerDuration;
    private final AtomicLong lastCycleCompletedEpochSeconds = new AtomicLong(0);
    private final AtomicLong lastSuccessfulDeliveryEpochSeconds = new AtomicLong(0);

    IntegrationOutboxMetrics(
            MeterRegistry meterRegistry,
            IntegrationOutboxProperties properties
    ) {
        for (DeliveryOutcome outcome : DeliveryOutcome.values()) {
            deliveryCounters.put(outcome, Counter.builder(DELIVERY_COUNTER)
                    .description("Transactional outbox delivery outcomes")
                    .tag("outcome", outcome.metricValue)
                    .register(meterRegistry));
        }
        enqueueCreated = enqueueCounter(meterRegistry, "created");
        enqueueDeduplicated = enqueueCounter(meterRegistry, "deduplicated");
        handlerDuration = Timer.builder("otziv.integration.outbox.handler.duration")
                .description("Transactional outbox handler duration")
                .register(meterRegistry);

        Gauge.builder(
                        "otziv.integration.outbox.relay.enabled",
                        properties,
                        candidate -> candidate.isRelayEnabled() ? 1.0d : 0.0d
                )
                .description("Whether the transactional outbox relay is enabled")
                .register(meterRegistry);
        Gauge.builder(
                        "otziv.integration.outbox.cycle.last.completed.epoch.seconds",
                        lastCycleCompletedEpochSeconds,
                        AtomicLong::get
                )
                .description("Epoch second of the last completed relay cycle")
                .register(meterRegistry);
        Gauge.builder(
                        "otziv.integration.outbox.delivery.last.success.epoch.seconds",
                        lastSuccessfulDeliveryEpochSeconds,
                        AtomicLong::get
                )
                .description("Epoch second of the last successful outbox delivery")
                .register(meterRegistry);
    }

    void recordEnqueue(boolean created) {
        (created ? enqueueCreated : enqueueDeduplicated).increment();
    }

    void recordDelivery(DeliveryOutcome outcome) {
        recordDelivery(outcome, 1);
    }

    void recordDelivery(DeliveryOutcome outcome, long count) {
        if (count <= 0) {
            return;
        }
        deliveryCounters.get(outcome).increment(count);
        if (outcome == DeliveryOutcome.SUCCEEDED) {
            lastSuccessfulDeliveryEpochSeconds.set(Instant.now().getEpochSecond());
        }
    }

    void recordHandlerDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            handlerDuration.record(duration);
        }
    }

    void recordCycleCompleted() {
        lastCycleCompletedEpochSeconds.set(Instant.now().getEpochSecond());
    }

    long lastCycleCompletedEpochSeconds() {
        return lastCycleCompletedEpochSeconds.get();
    }

    long lastSuccessfulDeliveryEpochSeconds() {
        return lastSuccessfulDeliveryEpochSeconds.get();
    }

    private Counter enqueueCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(ENQUEUE_COUNTER)
                .description("Transactional outbox enqueue outcomes after transaction commit")
                .tag("outcome", outcome)
                .register(registry);
    }

    enum DeliveryOutcome {
        CLAIMED("claimed"),
        SUCCEEDED("succeeded"),
        RETRIED("retried"),
        DEAD("dead"),
        FENCED("fenced"),
        STALE_FINAL_LEASE("stale_final_lease"),
        CYCLE_FAILED("cycle_failed"),
        OVERLAPPING_CYCLE("overlapping_cycle");

        private final String metricValue;

        DeliveryOutcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }
}
