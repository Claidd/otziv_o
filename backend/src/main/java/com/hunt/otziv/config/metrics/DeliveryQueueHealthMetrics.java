package com.hunt.otziv.config.metrics;

import com.hunt.otziv.client_messages.api.DeliveryQueueHealth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** One bounded query per queue per 30 seconds, independent of the number of scraped gauges. */
@Component
public class DeliveryQueueHealthMetrics implements MeterBinder {
    private final List<DeliveryQueueHealth> queues;
    public DeliveryQueueHealthMetrics(List<DeliveryQueueHealth> queues) { this.queues = List.copyOf(queues); }

    @Override public void bindTo(MeterRegistry registry) {
        for (var queue : queues) {
            var cache = new SnapshotCache(queue, System::nanoTime);
            for (var state : DeliveryQueueHealth.State.values()) {
                Gauge.builder("otziv.delivery.queue.jobs", cache, c -> c.jobs(state))
                        .description("Durable messages by outcome; SENT history excluded")
                        .tag("queue", queue.queueName()).tag("state", state.name()).strongReference(true).register(registry);
                Gauge.builder("otziv.delivery.queue.oldest.seconds", cache, c -> c.age(state))
                        .description("Age of the oldest durable message in this state")
                        .tag("queue", queue.queueName()).tag("state", state.name()).strongReference(true).register(registry);
            }
            Gauge.builder("otziv.delivery.queue.expired.leases", cache, SnapshotCache::expired)
                    .tag("queue", queue.queueName()).strongReference(true).register(registry);
            Gauge.builder("otziv.delivery.queue.collector.healthy", cache, SnapshotCache::healthy)
                    .description("Zero means queue inspection failed; missing metrics must never mean an empty queue")
                    .tag("queue", queue.queueName()).strongReference(true).register(registry);
        }
    }

    static final class SnapshotCache {
        private final DeliveryQueueHealth source;
        private final LongSupplier clock;
        private Map<DeliveryQueueHealth.State, DeliveryQueueHealth.Row> rows = Map.of();
        private boolean initialized, valid;
        private long refreshed;
        SnapshotCache(DeliveryQueueHealth source, LongSupplier clock) { this.source = source; this.clock = clock; }
        private synchronized void refresh() {
            long now = clock.getAsLong();
            if (initialized && now - refreshed < 30_000_000_000L) return;
            initialized = true; refreshed = now;
            try {
                var next = new EnumMap<DeliveryQueueHealth.State, DeliveryQueueHealth.Row>(DeliveryQueueHealth.State.class);
                for (var row : source.deliveryQueueHealth()) {
                    if (row.jobs() < 0 || row.oldestSeconds() < 0 || row.expiredLeases() < 0 || next.put(row.state(), row) != null)
                        throw new IllegalStateException("Invalid queue health projection");
                }
                rows = Map.copyOf(next); valid = true;
            } catch (RuntimeException unavailable) { valid = false; }
        }
        synchronized double jobs(DeliveryQueueHealth.State state) { refresh(); return valid ? rows.containsKey(state) ? rows.get(state).jobs() : 0 : Double.NaN; }
        synchronized double age(DeliveryQueueHealth.State state) { refresh(); return valid ? rows.containsKey(state) ? rows.get(state).oldestSeconds() : 0 : Double.NaN; }
        synchronized double expired() { refresh(); return valid ? rows.values().stream().mapToLong(DeliveryQueueHealth.Row::expiredLeases).sum() : Double.NaN; }
        synchronized double healthy() { refresh(); return valid ? 1 : 0; }
    }
}
