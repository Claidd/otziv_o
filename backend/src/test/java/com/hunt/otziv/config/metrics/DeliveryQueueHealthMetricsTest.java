package com.hunt.otziv.config.metrics;

import com.hunt.otziv.client_messages.api.DeliveryQueueHealth;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryQueueHealthMetricsTest {
    @Test void oneSnapshotServesAllGaugesWithoutExposingOperationIdentifiers() {
        var owner = mock(DeliveryQueueHealth.class);
        when(owner.queueName()).thenReturn("manager_client");
        when(owner.deliveryQueueHealth()).thenReturn(List.of(new DeliveryQueueHealth.Row(DeliveryQueueHealth.State.UNKNOWN, 3, 900, 1)));
        var registry = new SimpleMeterRegistry();
        try {
            new DeliveryQueueHealthMetrics(List.of(owner)).bindTo(registry);
            assertThat(registry.get("otziv.delivery.queue.jobs").tag("state", "UNKNOWN").gauge().value()).isEqualTo(3);
            assertThat(registry.get("otziv.delivery.queue.jobs").tag("state", "FAILED").gauge().value()).isZero();
            assertThat(registry.get("otziv.delivery.queue.oldest.seconds").tag("state", "UNKNOWN").gauge().value()).isEqualTo(900);
            assertThat(registry.get("otziv.delivery.queue.expired.leases").gauge().value()).isEqualTo(1);
            assertThat(registry.get("otziv.delivery.queue.collector.healthy").gauge().value()).isEqualTo(1);
            verify(owner, times(1)).deliveryQueueHealth();
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("queue", "state")));
        } finally { registry.close(); }
    }

    @Test void databaseFailureCannotMasqueradeAsAnEmptyQueueAndTheCollectorRecovers() {
        var owner = mock(DeliveryQueueHealth.class);
        when(owner.deliveryQueueHealth()).thenReturn(List.of(new DeliveryQueueHealth.Row(DeliveryQueueHealth.State.QUEUED, 2, 60, 0)))
                .thenThrow(new IllegalStateException("database unavailable")).thenReturn(List.of());
        var time = new AtomicLong();
        var cache = new DeliveryQueueHealthMetrics.SnapshotCache(owner, time::get);
        assertThat(cache.jobs(DeliveryQueueHealth.State.QUEUED)).isEqualTo(2);
        time.set(30_000_000_000L);
        assertThat(cache.jobs(DeliveryQueueHealth.State.QUEUED)).isNaN();
        assertThat(cache.healthy()).isZero();
        time.set(60_000_000_000L);
        assertThat(cache.jobs(DeliveryQueueHealth.State.QUEUED)).isZero();
        assertThat(cache.healthy()).isEqualTo(1);
        verify(owner, times(3)).deliveryQueueHealth();
    }
}
