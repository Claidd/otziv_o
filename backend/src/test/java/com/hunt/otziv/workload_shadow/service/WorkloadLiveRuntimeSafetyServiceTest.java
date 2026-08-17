package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.health.service.WorkloadShadowHealthService;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveRuntimeSafetyRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveRuntimeSafetyRepository.RuntimeStateProjection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadLiveRuntimeSafetyServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Irkutsk");

    @Mock private WorkloadLiveRuntimeSafetyRepository repository;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;
    @Mock private WorkloadShadowHealthService healthService;
    @Mock private WorkloadShadowSettingsResponse settings;
    @Mock private RuntimeStateProjection state;

    private WorkloadLiveRuntimeSafetyService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadLiveRuntimeSafetyService(
                repository,
                shadowSettingsService,
                healthService
        );
        when(shadowSettingsService.current()).thenReturn(settings);
        when(settings.observationEnabled()).thenReturn(true);
        when(settings.revision()).thenReturn(9L);
        lenient().when(settings.schedulerIntervalMinutes()).thenReturn(10);
        lenient().when(shadowSettingsService.zone(settings)).thenReturn(ZONE);
    }

    @Test
    void allowsOnlyOneFreshCompleteCurrentRevision() {
        completeState(LocalDateTime.now(ZONE).minusMinutes(1));
        when(healthService.snapshot()).thenReturn(healthy());

        var decision = service.evaluate();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.code()).isEqualTo("SAFE");
        assertThat(decision.runId()).isEqualTo(77L);
        assertThat(decision.shadowSettingsRevision()).isEqualTo(9L);
    }

    @Test
    void failsClosedForRevisionChangeRunningCalculationAndPartialProjection() {
        completeState(LocalDateTime.now(ZONE).minusMinutes(1));
        when(state.getLatestSettingsRevision()).thenReturn(8L);
        assertThat(service.evaluate().code()).isEqualTo("SETTINGS_REVISION_STALE");

        when(state.getLatestSettingsRevision()).thenReturn(9L);
        when(state.getRunningRunCount()).thenReturn(1L);
        assertThat(service.evaluate().code()).isEqualTo("RECALCULATION_RUNNING");

        when(state.getRunningRunCount()).thenReturn(0L);
        when(state.getMismatchedCurrentSnapshotCount()).thenReturn(1L);
        assertThat(service.evaluate().code()).isEqualTo("PARTIAL_OR_STALE_RUN");
    }

    @Test
    void failsClosedForStaleSnapshotAndUnavailableHealth() {
        completeState(LocalDateTime.now(ZONE).minusMinutes(21));
        assertThat(service.evaluate().code()).isEqualTo("SNAPSHOT_STALE");

        completeState(LocalDateTime.now(ZONE).minusMinutes(1));
        when(healthService.snapshot()).thenThrow(new IllegalStateException("db"));
        assertThat(service.evaluate().code()).isEqualTo("HEALTH_UNAVAILABLE");
    }

    private void completeState(LocalDateTime finishedAt) {
        when(repository.runtimeState()).thenReturn(Optional.of(state));
        when(state.getLatestSuccessfulRunId()).thenReturn(77L);
        when(state.getLatestSuccessfulFinishedAt()).thenReturn(finishedAt);
        when(state.getLatestSettingsRevision()).thenReturn(9L);
        when(state.getRunningRunCount()).thenReturn(0L);
        when(state.getCurrentSnapshotCount()).thenReturn(16L);
        when(state.getMismatchedCurrentSnapshotCount()).thenReturn(0L);
        lenient().when(state.getMismatchedActiveCaseCount()).thenReturn(0L);
    }

    private WorkloadShadowHealthSnapshot healthy() {
        return new WorkloadShadowHealthSnapshot(
                "UP",
                LocalDateTime.now(ZONE),
                true,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                30,
                0,
                null,
                LocalDateTime.now(ZONE).minusMinutes(1),
                LocalDateTime.now(ZONE).minusSeconds(30)
        );
    }
}
