package com.hunt.otziv.performers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.hunt.otziv.performers.repository.PerformerNotificationRepository;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository.OperationalSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PerformerNotificationMetricsTest {
    @Test
    void scrapingUsesCachedSnapshotAndReportsAnUnavailableRefresh() {
        var repository = mock(PerformerNotificationRepository.class);
        var registry = new SimpleMeterRegistry();
        var beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        var metrics = new PerformerNotificationMetrics(repository, beans.getBeanProvider(MeterRegistry.class));
        assertThat(registry.get("otziv.performers.notifications.snapshot_available").gauge().value()).isZero();
        when(repository.operationalSnapshot()).thenReturn(new OperationalSnapshot(3, 2, 1, 4, 5, 60, 90, 2))
                .thenThrow(new IllegalStateException("database unavailable"));

        metrics.refresh();
        for (int i = 0; i < 3; i++) {
            assertThat(registry.get("otziv.performers.notifications.pending").gauge().value()).isEqualTo(3);
            assertThat(registry.get("otziv.performers.notifications.unknown").gauge().value()).isEqualTo(1);
        }
        verify(repository).operationalSnapshot();
        metrics.refresh();
        assertThat(registry.get("otziv.performers.notifications.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("otziv.performers.notifications.snapshot.errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("otziv.performers.notifications.snapshot_available").gauge().value()).isEqualTo(1);
        metrics.expired(2);
        metrics.fenced();
        assertThat(registry.get("otziv.performers.notifications.claims.expired").counter().count()).isEqualTo(2);
        assertThat(registry.get("otziv.performers.notifications.completion.fenced").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }
}
