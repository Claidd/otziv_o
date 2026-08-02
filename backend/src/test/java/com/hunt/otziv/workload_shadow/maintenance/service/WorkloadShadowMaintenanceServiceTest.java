package com.hunt.otziv.workload_shadow.maintenance.service;

import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.DAILY_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.EVENT_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.INACTIVE_EVENT_CANCELLED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.LATE_BATCH_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.RUN_DELETED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.STALE_EVENT_RETRIED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.STALE_RUN_FAILED;
import static com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics.WorkloadShadowMaintenanceAction.TRANSFER_CASE_DELETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferMaintenanceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowMaintenanceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 0);

    @Mock private WorkloadShadowNotificationStore store;
    @Mock private WorkloadTransferMaintenanceRepository transferMaintenanceRepository;
    @Mock private WorkloadMaintenanceStatusService maintenanceStatusService;
    @Mock private AppSettingService settings;
    @Mock private WorkloadShadowMetrics metrics;

    private WorkloadShadowMaintenanceService service;

    @BeforeEach
    void setUp() {
        lenient().when(settings.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = new WorkloadShadowMaintenanceService(
                store,
                transferMaintenanceRepository,
                maintenanceStatusService,
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
        when(transferMaintenanceRepository.retryStaleOfferDeliveries(NOW, 1000))
                .thenReturn(4);
        when(transferMaintenanceRepository.retryStaleEmergencyNotifications(
                NOW,
                1000
        )).thenReturn(6);
        when(transferMaintenanceRepository.lockOrphanReadyOfferIds(
                NOW.minusMinutes(5),
                1000
        )).thenReturn(List.of(701L, 702L));
        when(transferMaintenanceRepository.cancelOrphanReadyOffers(
                List.of(701L, 702L),
                NOW
        )).thenReturn(2);
        when(transferMaintenanceRepository.lockExpiredWorkflowIds(
                LocalDate.of(2026, 7, 27),
                1000
        )).thenReturn(List.of(801L, 802L, 803L, 804L, 805L));
        when(transferMaintenanceRepository.cancelExpiredWorkflows(
                List.of(801L, 802L, 803L, 804L, 805L),
                LocalDate.of(2026, 7, 27),
                NOW
        )).thenReturn(5);

        WorkloadShadowMaintenanceService.RepairSummary summary = service.repairStaleState();

        assertThat(summary).isEqualTo(
                new WorkloadShadowMaintenanceService.RepairSummary(
                        1,
                        3,
                        2,
                        10,
                        2,
                        5
                )
        );
        var repairOrder = inOrder(transferMaintenanceRepository);
        repairOrder.verify(transferMaintenanceRepository)
                .lockOrphanReadyOfferIds(NOW.minusMinutes(5), 1000);
        repairOrder.verify(transferMaintenanceRepository)
                .closeCandidatesForOrphanReadyOffers(
                        List.of(701L, 702L),
                        NOW
                );
        repairOrder.verify(transferMaintenanceRepository)
                .cancelOrphanReadyOffers(List.of(701L, 702L), NOW);
        repairOrder.verify(transferMaintenanceRepository)
                .lockExpiredWorkflowIds(LocalDate.of(2026, 7, 27), 1000);
        repairOrder.verify(transferMaintenanceRepository)
                .closeOffersForExpiredWorkflows(
                        List.of(801L, 802L, 803L, 804L, 805L),
                        NOW
                );
        repairOrder.verify(transferMaintenanceRepository)
                .closeCandidatesForExpiredWorkflows(
                        List.of(801L, 802L, 803L, 804L, 805L),
                        NOW
                );
        repairOrder.verify(transferMaintenanceRepository)
                .countOpenChildrenForWorkflows(
                        List.of(801L, 802L, 803L, 804L, 805L)
                );
        repairOrder.verify(transferMaintenanceRepository)
                .cancelExpiredWorkflows(
                        List.of(801L, 802L, 803L, 804L, 805L),
                        LocalDate.of(2026, 7, 27),
                        NOW
                );
        verify(metrics).recordMaintenance(INACTIVE_EVENT_CANCELLED, 2);
        verify(metrics).recordMaintenance(STALE_EVENT_RETRIED, 3);
        verify(metrics).recordMaintenance(STALE_RUN_FAILED, 1);
    }

    @Test
    void emptyLifecycleRepairBatchesDoNotRunMutationQueries() {
        when(transferMaintenanceRepository.lockOrphanReadyOfferIds(
                NOW.minusMinutes(5),
                1000
        )).thenReturn(List.of());
        when(transferMaintenanceRepository.lockExpiredWorkflowIds(
                LocalDate.of(2026, 7, 27),
                1000
        )).thenReturn(List.of());

        WorkloadShadowMaintenanceService.RepairSummary summary =
                service.repairStaleState();

        assertThat(summary.repairedOrphanReadyOffers()).isZero();
        assertThat(summary.cancelledExpiredWorkflows()).isZero();
        verify(transferMaintenanceRepository, never())
                .closeCandidatesForOrphanReadyOffers(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()
                );
        verify(transferMaintenanceRepository, never())
                .cancelOrphanReadyOffers(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()
                );
        verify(transferMaintenanceRepository, never())
                .closeOffersForExpiredWorkflows(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()
                );
        verify(transferMaintenanceRepository, never())
                .closeCandidatesForExpiredWorkflows(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any()
                );
        verify(transferMaintenanceRepository, never())
                .cancelExpiredWorkflows(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
    }

    @Test
    void orphanRepairCountMismatchFailsTheWholeMaintenanceTransaction() {
        when(transferMaintenanceRepository.lockOrphanReadyOfferIds(
                NOW.minusMinutes(5),
                1000
        )).thenReturn(List.of(701L));
        when(transferMaintenanceRepository.cancelOrphanReadyOffers(
                List.of(701L),
                NOW
        )).thenReturn(0);

        assertThatThrownBy(service::repairStaleState)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan READY offers")
                .hasMessageContaining("expected=1")
                .hasMessageContaining("changed=0");
        verify(transferMaintenanceRepository, never())
                .lockExpiredWorkflowIds(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt()
                );
    }

    @Test
    void expiredWorkflowIsNotClosedWhileAnyOpenChildRemains() {
        when(transferMaintenanceRepository.lockOrphanReadyOfferIds(
                NOW.minusMinutes(5),
                1000
        )).thenReturn(List.of());
        when(transferMaintenanceRepository.lockExpiredWorkflowIds(
                LocalDate.of(2026, 7, 27),
                1000
        )).thenReturn(List.of(801L));
        when(transferMaintenanceRepository.countOpenChildrenForWorkflows(
                List.of(801L)
        )).thenReturn(1L);

        assertThatThrownBy(service::repairStaleState)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired workflow children")
                .hasMessageContaining("open=1");
        verify(transferMaintenanceRepository, never())
                .cancelExpiredWorkflows(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
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
        when(transferMaintenanceRepository.deleteOldAssignmentAudit(
                NOW.minusDays(400),
                1000
        )).thenReturn(9);
        when(transferMaintenanceRepository.deleteOldExecutions(
                NOW.minusDays(400),
                1000
        )).thenReturn(10);
        when(transferMaintenanceRepository.deleteOldWorkflows(
                NOW.minusDays(400),
                1000
        )).thenReturn(11);
        when(transferMaintenanceRepository.deleteOldEmergencyAssignments(
                NOW.minusDays(400),
                1000
        )).thenReturn(12);

        WorkloadShadowMaintenanceService.RetentionSummary summary =
                service.cleanupRetention();

        assertThat(summary).isEqualTo(
                new WorkloadShadowMaintenanceService.RetentionSummary(
                        4, 8, 5, 6, 7, 9, 10, 11, 12
                )
        );
        verify(metrics).recordMaintenance(EVENT_DELETED, 4);
        verify(metrics).recordMaintenance(TRANSFER_CASE_DELETED, 8);
        verify(metrics).recordMaintenance(RUN_DELETED, 5);
        verify(metrics).recordMaintenance(DAILY_DELETED, 6);
        verify(metrics).recordMaintenance(LATE_BATCH_DELETED, 7);
    }
}
