package com.hunt.otziv.workload_shadow.maintenance;

import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.DAILY_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.EVENT_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.INACTIVE_EVENT_CANCELLED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.LATE_BATCH_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.RUN_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.STALE_EVENT_RETRIED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.STALE_RUN_FAILED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.TRANSFER_CASE_DELETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowMaintenanceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 0);

    @Mock private WorkloadShadowNotificationStore store;
    @Mock private AppSettingService settings;
    @Mock private WorkloadShadowMetrics metrics;

    private WorkloadShadowMaintenanceService service;

    @BeforeEach
    void setUp() {
        lenient().when(settings.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = new WorkloadShadowMaintenanceService(
                store,
                settings,
                metrics,
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void repairsOnlyBoundedStaleAndInactiveState() {
        when(store.cancelInactiveDeliveries(NOW, 1000)).thenReturn(2);
        when(store.retryStaleProcessingEvents(NOW, 1000)).thenReturn(3);
        when(store.failStaleRuns(NOW.minusMinutes(30), NOW, 1000)).thenReturn(1);

        WorkloadShadowMaintenanceService.RepairSummary summary = service.repairStaleState();

        assertThat(summary).isEqualTo(
                new WorkloadShadowMaintenanceService.RepairSummary(1, 3, 2)
        );
        verify(metrics).recordMaintenance(INACTIVE_EVENT_CANCELLED, 2);
        verify(metrics).recordMaintenance(STALE_EVENT_RETRIED, 3);
        verify(metrics).recordMaintenance(STALE_RUN_FAILED, 1);
    }

    @Test
    void deletesOnlyDataPastConfiguredRetentionCutoffsInBoundedBatches() {
        when(store.deleteTerminalInactiveEvents(NOW.minusDays(90), 1000)).thenReturn(4);
        when(store.deleteInactiveResolvedTransferCases(NOW.minusDays(90), 1000))
                .thenReturn(8);
        when(store.deleteTerminalRuns(NOW.minusDays(30), 1000)).thenReturn(5);
        when(store.deleteFinalizedDaily(LocalDate.of(2026, 7, 27).minusDays(400), 1000))
                .thenReturn(6);
        when(store.deleteLateBatches(LocalDate.of(2026, 7, 27).minusDays(60), 1000))
                .thenReturn(7);

        WorkloadShadowMaintenanceService.RetentionSummary summary =
                service.cleanupRetention();

        assertThat(summary).isEqualTo(
                new WorkloadShadowMaintenanceService.RetentionSummary(4, 8, 5, 6, 7)
        );
        verify(metrics).recordMaintenance(EVENT_DELETED, 4);
        verify(metrics).recordMaintenance(TRANSFER_CASE_DELETED, 8);
        verify(metrics).recordMaintenance(RUN_DELETED, 5);
        verify(metrics).recordMaintenance(DAILY_DELETED, 6);
        verify(metrics).recordMaintenance(LATE_BATCH_DELETED, 7);
    }
}
