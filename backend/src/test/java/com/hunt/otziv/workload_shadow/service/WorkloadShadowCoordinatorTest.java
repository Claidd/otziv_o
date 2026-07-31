package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowRunResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.lock.WorkloadShadowLeaseLostException;
import com.hunt.otziv.workload_shadow.lock.WorkloadShadowRecalculationLease;
import com.hunt.otziv.workload_shadow.lock.WorkloadShadowRecalculationLockService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowCoordinatorTest {

    @Mock private WorkloadShadowSettingsService settingsService;
    @Mock private WorkloadShadowProjectionService projectionService;
    @Mock private WorkloadShadowTransferSimulationService transferSimulationService;
    @Mock private WorkloadShadowRunService runService;
    @Mock private WorkloadShadowRefreshSignal refreshSignal;
    @Mock private WorkloadShadowRecalculationLockService recalculationLockService;
    @Mock private WorkloadShadowRecalculationLease lease;

    private WorkloadShadowSettingsResponse settings;
    private WorkloadShadowCoordinator coordinator;

    @BeforeEach
    void setUp() {
        settings = settings();
        coordinator = new WorkloadShadowCoordinator(
                settingsService,
                projectionService,
                transferSimulationService,
                runService,
                refreshSignal,
                recalculationLockService
        );
        when(settingsService.current()).thenReturn(settings);
    }

    @Test
    void distributedLeaseConflictStopsBeforeRunRegistration() {
        when(projectionService.instanceId()).thenReturn("node-a");
        when(recalculationLockService.tryAcquire("node-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.recalculate("SCHEDULED"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).contains("другим экземпляром");
                });

        assertThat(coordinator.isRunning()).isFalse();
        verifyNoInteractions(runService, transferSimulationService);
        verify(projectionService, never()).recalculate(
                org.mockito.ArgumentMatchers.anyLong(),
                any(LocalDateTime.class)
        );
    }

    @Test
    void successfulRunKeepsLeaseAcrossProjectionAndTransferThenReleasesIt() {
        when(settingsService.zone(settings)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(projectionService.instanceId()).thenReturn("node-a");
        when(recalculationLockService.tryAcquire("node-a")).thenReturn(Optional.of(lease));
        when(runService.start(
                eq("MANUAL"),
                eq("node-a"),
                any(LocalDateTime.class)
        )).thenReturn(42L);
        when(projectionService.recalculate(eq(42L), any(LocalDateTime.class)))
                .thenReturn(new WorkloadShadowRunService.RunResult(2, 8, 0, 3, 1));
        when(transferSimulationService.rebuild(eq(42L), any(LocalDateTime.class)))
                .thenReturn(new WorkloadShadowTransferSimulationService.SimulationResult(4, 2));

        WorkloadShadowRunResponse response = coordinator.recalculate("manual");

        assertThat(response.runId()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.transferCaseCount()).isEqualTo(4);
        assertThat(response.eventCount()).isEqualTo(5);
        assertThat(coordinator.isRunning()).isFalse();

        InOrder ordered = inOrder(
                runService,
                lease,
                projectionService,
                transferSimulationService,
                refreshSignal
        );
        ordered.verify(refreshSignal).beginRefresh();
        ordered.verify(runService).start(
                eq("MANUAL"),
                eq("node-a"),
                any(LocalDateTime.class)
        );
        ordered.verify(lease).attachRun(42L);
        ordered.verify(projectionService).recalculate(eq(42L), any(LocalDateTime.class));
        ordered.verify(lease).checkpoint("AFTER_PROJECTION");
        ordered.verify(transferSimulationService).rebuild(eq(42L), any(LocalDateTime.class));
        ordered.verify(lease).checkpoint("AFTER_TRANSFER_SIMULATION");
        ordered.verify(runService).complete(
                eq(42L),
                any(WorkloadShadowRunService.RunResult.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
        ordered.verify(refreshSignal).completeRefresh(any());
        ordered.verify(lease).close();
    }

    @Test
    void leaseLossAfterProjectionFailsRunAndNeverStartsTransferSimulation() {
        when(settingsService.zone(settings)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(projectionService.instanceId()).thenReturn("node-a");
        when(recalculationLockService.tryAcquire("node-a")).thenReturn(Optional.of(lease));
        when(runService.start(
                eq("EVENT_DIRTY"),
                eq("node-a"),
                any(LocalDateTime.class)
        )).thenReturn(77L);
        when(projectionService.recalculate(eq(77L), any(LocalDateTime.class)))
                .thenReturn(new WorkloadShadowRunService.RunResult(1, 3, 0, 0, 0));
        WorkloadShadowLeaseLostException lost =
                new WorkloadShadowLeaseLostException("ownership lost");
        doThrow(lost).when(lease).checkpoint("AFTER_PROJECTION");

        assertThatThrownBy(() -> coordinator.recalculate("EVENT_DIRTY"))
                .isSameAs(lost);

        verifyNoInteractions(transferSimulationService);
        verify(runService).fail(
                eq(77L),
                eq(lost),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
        verify(refreshSignal).failRefresh();
        verify(lease).close();
        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    void runRegistrationFailureReleasesLeaseAndResetsLocalFastGuard() {
        when(settingsService.zone(settings)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(projectionService.instanceId()).thenReturn("node-a");
        when(recalculationLockService.tryAcquire("node-a")).thenReturn(Optional.of(lease));
        IllegalStateException failure = new IllegalStateException("run storage unavailable");
        when(runService.start(
                eq("MANUAL"),
                eq("node-a"),
                any(LocalDateTime.class)
        )).thenThrow(failure);

        assertThatThrownBy(() -> coordinator.recalculate("MANUAL"))
                .isSameAs(failure);

        verify(runService, never()).fail(
                org.mockito.ArgumentMatchers.anyLong(),
                any(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
        verify(lease).close();
        verify(refreshSignal).failRefresh();
        assertThat(coordinator.isRunning()).isFalse();
    }

    private WorkloadShadowSettingsResponse settings() {
        return new WorkloadShadowSettingsResponse(
                "SHADOW",
                false,
                true,
                true,
                -100L,
                10,
                5,
                120,
                "Asia/Irkutsk",
                "10:00",
                "22:00",
                4,
                3,
                5,
                10,
                3,
                10,
                10,
                true,
                30,
                30,
                3,
                85,
                80,
                2,
                15,
                1,
                25,
                2,
                30,
                3,
                14,
                2,
                60,
                30,
                400,
                90,
                60,
                30,
                10,
                8,
                5,
                1,
                1000,
                1
        );
    }
}
