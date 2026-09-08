package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository.OperationalSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class UserSessionRevocationMetricsTest {
    @Test
    void scrapingDoesNotQuerySessionDataAndFailedRefreshRetainsLastSnapshot() {
        var state = mock(AuthSessionStateRepository.class);
        var registry = new SimpleMeterRegistry();
        var beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        var metrics = new UserSessionRevocationMetrics(state, beans.getBeanProvider(MeterRegistry.class));
        assertThat(registry.get("otziv.security.session_revocations.snapshot_available").gauge().value()).isZero();
        when(state.operationalSnapshot()).thenReturn(new OperationalSnapshot(4, 1, 1, 2, 60, 30))
                .thenThrow(new IllegalStateException("database unavailable"));

        metrics.refresh();
        for (int i = 0; i < 3; i++) {
            assertThat(registry.get("otziv.security.session_revocations.pending").gauge().value()).isEqualTo(4);
            assertThat(registry.get("otziv.security.session_revocations.unknown").gauge().value()).isEqualTo(1);
        }
        verify(state).operationalSnapshot();
        metrics.refresh();
        assertThat(registry.get("otziv.security.session_revocations.pending").gauge().value()).isEqualTo(4);
        assertThat(registry.get("otziv.security.session_revocations.snapshot.errors").counter().count()).isEqualTo(1);
        metrics.error();
        metrics.reconciled();
        assertThat(registry.get("otziv.security.session_revocations.dispatch.errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("otziv.security.session_revocations.dispatch.reconciled_attempts").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }
}
