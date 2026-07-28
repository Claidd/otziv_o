package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowMonitorRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRunRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowMonitorServiceTest {

    @Mock private WorkloadShadowMonitorRepository monitorRepository;
    @Mock private WorkloadShadowRunRepository runRepository;
    @Mock private WorkloadShadowSettingsService settingsService;

    private WorkloadShadowMonitorService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadShadowMonitorService(
                monitorRepository,
                runRepository,
                settingsService
        );
    }

    @Test
    void summaryUsesFourFixedDataQueries() {
        var settings = mock(
                com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse.class
        );
        var totals = mock(
                WorkloadShadowMonitorRepository.SummaryTotalsProjection.class
        );
        when(settings.observationEnabled()).thenReturn(true);
        when(settings.walkMinutesPerCard()).thenReturn(4);
        when(settings.walkMinimumMinutesPerCard()).thenReturn(3);
        when(settingsService.current()).thenReturn(settings);
        when(monitorRepository.summaryTotals()).thenReturn(totals);
        when(monitorRepository.managerSummaries()).thenReturn(List.of());
        when(monitorRepository.nagulEstimate()).thenReturn(Optional.empty());
        when(runRepository.latestRun()).thenReturn(Optional.empty());

        var result = service.summary();

        assertThat(result.observationEnabled()).isTrue();
        assertThat(result.walkEstimate().defaultMinutes()).isEqualTo(4);
        assertThat(result.walkEstimate().minimumMinutes()).isEqualTo(3);
        verify(monitorRepository).summaryTotals();
        verify(monitorRepository).managerSummaries();
        verify(monitorRepository).nagulEstimate();
        verify(runRepository).latestRun();
        verifyNoMoreInteractions(monitorRepository, runRepository);
    }

    @Test
    void transferCasesUseExactlyTwoQueriesAndKeepGraphDiagnostics() {
        var transfer = mock(
                WorkloadShadowMonitorRepository.TransferCaseProjection.class
        );
        when(transfer.getId()).thenReturn(11L);
        when(transfer.getManagerId()).thenReturn(21L);
        when(transfer.getManagerName()).thenReturn("Менеджер");
        when(transfer.getSourceWorkerId()).thenReturn(31L);
        when(transfer.getSourceWorkerName()).thenReturn("Источник");
        when(transfer.getCompanyId()).thenReturn(41L);
        when(transfer.getCompanyTitle()).thenReturn("Компания");
        when(transfer.getFailureNumber()).thenReturn(4);
        when(transfer.getTransferPercent()).thenReturn(15);
        when(transfer.getSelectionRank()).thenReturn(1);
        when(transfer.getProblemUnits()).thenReturn(50L);
        when(transfer.getEstimatedMinutes()).thenReturn(200L);
        when(transfer.getActiveOrderCount()).thenReturn(3L);
        when(transfer.getNewUnitCount()).thenReturn(5L);
        when(transfer.getCorrectionCount()).thenReturn(1L);
        when(transfer.getNagulCount()).thenReturn(20L);
        when(transfer.getPublishCount()).thenReturn(7L);
        when(transfer.getRecoveryCount()).thenReturn(2L);
        when(transfer.getBadCount()).thenReturn(4L);
        when(transfer.getGraphWarningCount()).thenReturn(2);
        when(transfer.getGraphErrorCount()).thenReturn(1);
        when(transfer.getGraphWarningCodes()).thenReturn("SHARED_WORKER");
        when(transfer.getGraphErrorCodes()).thenReturn("MISSING_OWNER");
        when(transfer.getStaffingRequired()).thenReturn(true);
        when(transfer.getFallbackWorkerId()).thenReturn(51L);
        when(transfer.getFallbackWorkerName()).thenReturn("Резерв");
        when(transfer.getFallbackReviewId()).thenReturn(61L);
        when(transfer.getStatus()).thenReturn("SHADOW_PENDING");
        when(transfer.getFirstDetectedAt()).thenReturn(
                LocalDateTime.of(2026, 7, 27, 12, 0)
        );
        when(transfer.getLastSeenAt()).thenReturn(
                LocalDateTime.of(2026, 7, 27, 12, 10)
        );

        var candidate = mock(
                WorkloadShadowMonitorRepository.TransferCandidateProjection.class
        );
        when(candidate.getTransferCaseId()).thenReturn(11L);
        when(candidate.getWorkerId()).thenReturn(71L);
        when(candidate.getWorkerName()).thenReturn("Получатель");
        when(candidate.getSequenceNumber()).thenReturn(1);
        when(candidate.getRating()).thenReturn(new BigDecimal("96.50"));
        when(candidate.getHundredPercentDays()).thenReturn(18);
        when(candidate.getFailureDays()).thenReturn(1);
        when(candidate.getCurrentEstimatedMinutes()).thenReturn(80L);
        when(candidate.getWorkerGroupConnected()).thenReturn(true);
        when(candidate.getSimulatedOfferStatus()).thenReturn("WAITING");

        when(monitorRepository.transferCases(21L)).thenReturn(List.of(transfer));
        when(monitorRepository.transferCandidates(List.of(11L)))
                .thenReturn(List.of(candidate));

        var result = service.transferCases(21L);

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.graph().activeOrders()).isEqualTo(3);
            assertThat(response.graph().newUnits()).isEqualTo(5);
            assertThat(response.graph().correction()).isEqualTo(1);
            assertThat(response.graph().nagul()).isEqualTo(20);
            assertThat(response.graph().publish()).isEqualTo(7);
            assertThat(response.graph().recovery()).isEqualTo(2);
            assertThat(response.graph().bad()).isEqualTo(4);
            assertThat(response.graphWarningCount()).isEqualTo(2);
            assertThat(response.graphErrorCount()).isEqualTo(1);
            assertThat(response.graphWarningCodes()).isEqualTo("SHARED_WORKER");
            assertThat(response.graphErrorCodes()).isEqualTo("MISSING_OWNER");
            assertThat(response.candidates()).singleElement().satisfies(value -> {
                assertThat(value.workerId()).isEqualTo(71L);
                assertThat(value.rating()).isEqualByComparingTo("96.50");
            });
        });
        verify(monitorRepository).transferCases(21L);
        verify(monitorRepository).transferCandidates(List.of(11L));
        verifyNoMoreInteractions(monitorRepository);
    }

    @Test
    void workersKeepAllWorkClassificationsAndLastDayReached100() {
        var row = mock(WorkloadShadowMonitorRepository.WorkerProjection.class);
        when(row.getWorkerId()).thenReturn(1L);
        when(row.getWorkerUserId()).thenReturn(2L);
        when(row.getManagerId()).thenReturn(3L);
        when(row.getManagerName()).thenReturn("Менеджер");
        when(row.getWorkerName()).thenReturn("Специалист");
        when(row.getProgressDate()).thenReturn(LocalDate.of(2026, 7, 27));
        when(row.getSnapshotAt()).thenReturn(
                LocalDateTime.of(2026, 7, 27, 22, 30)
        );
        when(row.getProgressPercent()).thenReturn(new BigDecimal("100.00"));
        when(row.getCompletedUnits()).thenReturn(28L);
        when(row.getActiveUnits()).thenReturn(50L);
        when(row.getEligibleUnits()).thenReturn(28L);
        when(row.getLateExcludedUnits()).thenReturn(22L);
        when(row.getFeasibleUnits()).thenReturn(28L);
        when(row.getEstimatedRemainingMinutes()).thenReturn(88L);
        when(row.getPlannedUnits()).thenReturn(10L);
        when(row.getIncomingUnits()).thenReturn(12L);
        when(row.getUrgentUnits()).thenReturn(6L);
        when(row.getExternalBlockedUnits()).thenReturn(1L);
        when(row.getClientDeferredUnits()).thenReturn(2L);
        when(row.getManagerDeferredUnits()).thenReturn(3L);
        when(row.getBlockedUnits()).thenReturn(6L);
        when(row.getNewUnits()).thenReturn(5L);
        when(row.getCorrectionUnits()).thenReturn(1L);
        when(row.getNagulUnits()).thenReturn(10L);
        when(row.getPublishUnits()).thenReturn(4L);
        when(row.getRecoveryUnits()).thenReturn(2L);
        when(row.getBadUnits()).thenReturn(6L);
        when(row.getRating()).thenReturn(new BigDecimal("93.00"));
        when(row.getHundredPercentDays()).thenReturn(20);
        when(row.getFailureDays()).thenReturn(2);
        when(row.getEvaluatedDays()).thenReturn(22);
        when(row.getFreezeCredits()).thenReturn(1);
        when(row.getTransferStage()).thenReturn(0);
        when(row.getLastDayReached100()).thenReturn(true);
        when(row.getAcceptsCompanyTransfers()).thenReturn(true);
        when(row.getRecipientEligible()).thenReturn(true);
        when(row.getWorkerGroupConnected()).thenReturn(true);
        when(row.getDiagnosticStatus()).thenReturn("OK");
        when(row.getLastAvailableAt()).thenReturn(
                LocalDateTime.of(2026, 7, 27, 22, 25)
        );
        when(monitorRepository.workers(3L)).thenReturn(List.of(row));

        var result = service.workers(3L).getFirst();

        assertThat(result.plannedUnits()).isEqualTo(10);
        assertThat(result.incomingUnits()).isEqualTo(12);
        assertThat(result.urgentUnits()).isEqualTo(6);
        assertThat(result.externalBlockedUnits()).isEqualTo(1);
        assertThat(result.clientDeferredUnits()).isEqualTo(2);
        assertThat(result.managerDeferredUnits()).isEqualTo(3);
        assertThat(result.blockedUnits()).isEqualTo(6);
        assertThat(result.newUnits()).isEqualTo(5);
        assertThat(result.correctionUnits()).isEqualTo(1);
        assertThat(result.nagulUnits()).isEqualTo(10);
        assertThat(result.publishUnits()).isEqualTo(4);
        assertThat(result.recoveryUnits()).isEqualTo(2);
        assertThat(result.badUnits()).isEqualTo(6);
        assertThat(result.lastDayReached100()).isTrue();
        verify(monitorRepository).workers(3L);
        verifyNoMoreInteractions(monitorRepository);
    }
}
