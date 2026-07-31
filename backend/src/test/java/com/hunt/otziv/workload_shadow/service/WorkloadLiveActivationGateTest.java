package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.health.WorkloadMaintenanceHealthSnapshot;
import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthService;
import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveReadinessRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveReadinessRepository.ManagerCapacityProjection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadLiveActivationGateTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Irkutsk");

    @Mock private WorkloadLiveReadinessRepository repository;
    @Mock private WorkloadShadowHealthService healthService;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;

    private WorkloadLiveActivationGate gate;
    private WorkloadShadowSettingsResponse shadow;

    @BeforeEach
    void setUp() {
        gate = new WorkloadLiveActivationGate(
                repository,
                healthService,
                shadowSettingsService
        );
        shadow = mock(WorkloadShadowSettingsResponse.class);
        when(shadowSettingsService.current()).thenReturn(shadow);
        when(shadowSettingsService.zone(shadow)).thenReturn(ZONE);
        when(shadow.observationEnabled()).thenReturn(true);
        when(shadow.groupNotificationsEnabled()).thenReturn(true);
        lenient().when(shadow.notificationGroupChatId())
                .thenReturn(-5_181_415_104L);
        when(shadow.schedulerIntervalMinutes()).thenReturn(10);
    }

    @Test
    void canaryIsReadyOnlyWhenEverySafetyCheckPassesForItsManagers() {
        LocalDate today = LocalDate.now(ZONE);
        WorkloadLiveSettingsResponse settings = settings(
                today.minusDays(20),
                List.of(7L)
        );
        when(healthService.snapshot()).thenReturn(healthy());
        when(repository.countFinalizedDates(today.minusDays(20), today))
                .thenReturn(14L);
        when(repository.countFailedRunsSince(any())).thenReturn(0L);
        when(repository.maximumSuccessfulRunGapMinutes(any(), any()))
                .thenReturn(15L);
        when(repository.lastSuccessfulRunAt())
                .thenReturn(Optional.of(LocalDateTime.now(ZONE).minusMinutes(1)));
        ManagerCapacityProjection capacity = capacity(7L, 3L);
        when(repository.managerCapacity()).thenReturn(List.of(capacity));
        when(repository.countGraphErrorCases(false, List.of(7L))).thenReturn(0L);
        when(repository.countInFlightExecutions()).thenReturn(0L);

        var result = gate.readiness(" canary ", settings);

        assertThat(result.ready()).isTrue();
        assertThat(result.targetMode()).isEqualTo("CANARY");
        assertThat(result.checks()).hasSize(10).allMatch(check -> check.passed());
    }

    @Test
    void assertionReportsEveryFailedGuardAndDoesNotHideMissingCanaryManager() {
        LocalDate today = LocalDate.now(ZONE);
        WorkloadLiveSettingsResponse settings = settings(
                today.minusDays(20),
                List.of(7L, 8L)
        );
        when(healthService.snapshot()).thenReturn(unhealthy());
        when(repository.countFinalizedDates(today.minusDays(20), today))
                .thenReturn(3L);
        when(repository.countFailedRunsSince(any())).thenReturn(2L);
        when(repository.maximumSuccessfulRunGapMinutes(any(), any()))
                .thenReturn(180L);
        when(repository.lastSuccessfulRunAt()).thenReturn(Optional.empty());
        ManagerCapacityProjection capacity = capacity(7L, 1L);
        when(repository.managerCapacity()).thenReturn(List.of(capacity));
        when(repository.countGraphErrorCases(false, List.of(7L, 8L)))
                .thenReturn(4L);
        when(repository.countInFlightExecutions()).thenReturn(1L);

        var result = gate.readiness("CANARY", settings);
        Set<String> failed = result.checks().stream()
                .filter(check -> !check.passed())
                .map(check -> check.code())
                .collect(Collectors.toSet());

        assertThat(result.ready()).isFalse();
        assertThat(failed).containsExactlyInAnyOrder(
                "SHADOW_HEALTH",
                "MAINTENANCE_HEALTH",
                "FINALIZED_HISTORY",
                "STABLE_RUNS",
                "FRESH_SNAPSHOT",
                "RECIPIENT_CAPACITY",
                "GRAPH_ERRORS",
                "NO_IN_FLIGHT_EXECUTIONS"
        );
        assertThatThrownBy(() -> gate.assertReady("CANARY", settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RECIPIENT_CAPACITY")
                .hasMessageContaining("NO_IN_FLIGHT_EXECUTIONS");
    }

    @Test
    void readinessRejectsPausedAuditRoutingAndAHistoryGap() {
        LocalDate today = LocalDate.now(ZONE);
        WorkloadLiveSettingsResponse settings = settings(
                today.minusDays(20),
                List.of(7L)
        );
        when(shadow.groupNotificationsEnabled()).thenReturn(false);
        when(healthService.snapshot()).thenReturn(healthy());
        when(repository.countFinalizedDates(today.minusDays(20), today))
                .thenReturn(14L);
        when(repository.countFailedRunsSince(any())).thenReturn(0L);
        when(repository.maximumSuccessfulRunGapMinutes(any(), any()))
                .thenReturn(61L);
        when(repository.lastSuccessfulRunAt())
                .thenReturn(Optional.of(LocalDateTime.now(ZONE).minusMinutes(1)));
        ManagerCapacityProjection managerCapacity = capacity(7L, 3L);
        when(repository.managerCapacity()).thenReturn(List.of(managerCapacity));
        when(repository.countGraphErrorCases(false, List.of(7L))).thenReturn(0L);
        when(repository.countInFlightExecutions()).thenReturn(0L);

        var result = gate.readiness("CANARY", settings);

        assertThat(result.ready()).isFalse();
        assertThat(result.checks().stream()
                .filter(check -> !check.passed())
                .map(check -> check.code())
                .toList()).containsExactly(
                        "AUDIT_GROUP_ROUTING",
                        "STABLE_RUNS"
                );
    }

    @Test
    void canaryWithoutSelectedManagersExplainsThatPilotManagerIsMissing() {
        LocalDate today = LocalDate.now(ZONE);
        WorkloadLiveSettingsResponse settings = settings(
                today.minusDays(20),
                List.of()
        );
        when(healthService.snapshot()).thenReturn(healthy());
        when(repository.countFinalizedDates(today.minusDays(20), today))
                .thenReturn(14L);
        when(repository.countFailedRunsSince(any())).thenReturn(0L);
        when(repository.maximumSuccessfulRunGapMinutes(any(), any()))
                .thenReturn(15L);
        when(repository.lastSuccessfulRunAt())
                .thenReturn(Optional.of(LocalDateTime.now(ZONE).minusMinutes(1)));
        when(repository.managerCapacity()).thenReturn(List.of());

        var result = gate.readiness("CANARY", settings);

        var capacityCheck = result.checks().stream()
                .filter(check -> "RECIPIENT_CAPACITY".equals(check.code()))
                .findFirst()
                .orElseThrow();
        assertThat(capacityCheck.passed()).isFalse();
        assertThat(capacityCheck.message())
                .isEqualTo("Для пилотного режима не выбран ни один менеджер");
    }

    private WorkloadLiveSettingsResponse settings(
            LocalDate historyStart,
            List<Long> managers
    ) {
        return new WorkloadLiveSettingsResponse(
                "SHADOW",
                false,
                historyStart.toString(),
                14,
                168,
                2,
                managers,
                15,
                "10:00",
                "21:00",
                1,
                3,
                30,
                5,
                true,
                1
        );
    }

    private ManagerCapacityProjection capacity(long managerId, long eligible) {
        ManagerCapacityProjection value = mock(ManagerCapacityProjection.class);
        when(value.getManagerId()).thenReturn(managerId);
        when(value.getEligibleRecipientCount()).thenReturn(eligible);
        return value;
    }

    private WorkloadShadowHealthSnapshot healthy() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        return new WorkloadShadowHealthSnapshot(
                "UP",
                now,
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
                0,
                0,
                null,
                now.minusMinutes(1),
                now.minusMinutes(1)
        );
    }

    private WorkloadShadowHealthSnapshot unhealthy() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        return new WorkloadShadowHealthSnapshot(
                "STALE",
                now,
                true,
                0,
                0,
                1,
                1,
                1,
                0,
                1,
                0,
                0,
                1,
                0,
                0,
                null,
                null,
                null,
                new WorkloadMaintenanceHealthSnapshot(
                        false,
                        "FAILED",
                        "UP",
                        now.minusMinutes(2),
                        now.minusMinutes(10),
                        now.minusMinutes(1),
                        now.minusHours(2),
                        now.minusHours(2),
                        null,
                        1,
                        0,
                        "InvalidDataAccessApiUsageException",
                        "No active transaction"
                )
        );
    }
}
