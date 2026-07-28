package com.hunt.otziv.workload_shadow.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Status;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowHealthIndicatorTest {

    @Mock private WorkloadShadowHealthService healthService;

    @Test
    void reportsUpButDegradedForRetainedDeadDiagnosticEvents() {
        when(healthService.snapshot()).thenReturn(snapshot("DEGRADED", 0, 0, 2, 1));

        var health = new WorkloadShadowHealthIndicator(healthService).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("degraded", true);
        assertThat(health.getDetails()).containsEntry("deadEvents", 2L);
        assertThat(health.getDetails()).containsEntry("missingGroupBindings", 1L);
    }

    @Test
    void reportsStaleDetailsWithoutDowngradingTheProductionSiteHealth() {
        when(healthService.snapshot()).thenReturn(snapshot("STALE", 1, 1, 0, 0));

        var health = new WorkloadShadowHealthIndicator(healthService).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "STALE");
        assertThat(health.getDetails()).containsEntry("degraded", true);
        assertThat(health.getDetails()).containsEntry("staleProcessingEvents", 1L);
        assertThat(health.getDetails()).containsEntry("staleRunningRuns", 1L);
    }

    @Test
    void reportsUnavailableDiagnosticsWithoutDowngradingTheProductionSiteHealth() {
        when(healthService.snapshot()).thenThrow(new IllegalStateException("database unavailable"));

        var health = new WorkloadShadowHealthIndicator(healthService).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("diagnosticAvailable", false);
        assertThat(health.getDetails()).containsEntry("status", "HEALTH_CHECK_FAILED");
    }

    @Test
    void exposesGraphAndDistributedLockDiagnostics() {
        WorkloadShadowHealthSnapshot snapshot = new WorkloadShadowHealthSnapshot(
                "STALE",
                LocalDateTime.of(2026, 7, 27, 12, 0),
                true,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                2,
                1,
                1,
                45,
                0,
                null,
                null,
                LocalDateTime.of(2026, 7, 27, 11, 59, 15)
        );
        when(healthService.snapshot()).thenReturn(snapshot);

        var health = new WorkloadShadowHealthIndicator(healthService).health();

        assertThat(health.getDetails()).containsEntry("graphWarningCases", 2L);
        assertThat(health.getDetails()).containsEntry("graphErrorCases", 1L);
        assertThat(health.getDetails()).containsEntry("expiredRecalculationLocks", 1L);
        assertThat(health.getDetails()).containsEntry("snapshotAgeSeconds", 45L);
    }

    private WorkloadShadowHealthSnapshot snapshot(
            String status,
            long staleProcessing,
            long staleRuns,
            long dead,
            long missingGroups
    ) {
        return new WorkloadShadowHealthSnapshot(
                status,
                LocalDateTime.of(2026, 7, 27, 12, 0),
                true,
                0,
                0,
                staleProcessing,
                dead,
                missingGroups,
                0,
                staleRuns,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                null
        );
    }
}
