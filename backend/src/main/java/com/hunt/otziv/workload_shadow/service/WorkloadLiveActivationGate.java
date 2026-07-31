package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveReadinessResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveReadinessResponse.Check;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthService;
import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveReadinessRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkloadLiveActivationGate {

    private final WorkloadLiveReadinessRepository repository;
    private final WorkloadShadowHealthService healthService;
    private final WorkloadShadowSettingsService shadowSettingsService;

    public WorkloadLiveReadinessResponse readiness(
            String targetMode,
            WorkloadLiveSettingsResponse settings
    ) {
        String mode = normalizedMode(targetMode);
        var shadowSettings = shadowSettingsService.current();
        LocalDateTime now = LocalDateTime.now(shadowSettingsService.zone(shadowSettings));
        LocalDate today = now.toLocalDate();
        LocalDate historyStart = LocalDate.parse(settings.historyStartDate());
        List<Check> checks = new ArrayList<>();

        checks.add(check(
                "OBSERVATION_ENABLED",
                shadowSettings.observationEnabled(),
                "Сбор актуальных наблюдений включён",
                shadowSettings.observationEnabled() ? 1L : 0L,
                1L
        ));

        WorkloadShadowHealthSnapshot health = healthService.snapshot();
        boolean auditRoutingSafe = shadowSettings.groupNotificationsEnabled()
                && health.groupNotificationsEnabled()
                && shadowSettings.notificationGroupChatId() != null
                && shadowSettings.notificationGroupChatId() < 0;
        checks.add(check(
                "AUDIT_GROUP_ROUTING",
                auditRoutingSafe,
                auditRoutingSafe
                        ? "Предупреждения направляются в общую группу администраторов и владельцев"
                        : "Для CANARY/LIVE нужно включить групповые уведомления и указать корректный chat ID общей группы",
                auditRoutingSafe ? 1L : 0L,
                1L
        ));
        boolean healthSafe = !health.stale()
                && health.deadEvents() == 0
                && health.missingGroupBindings() == 0;
        checks.add(check(
                "SHADOW_HEALTH",
                healthSafe,
                healthSafe
                        ? "Контур наблюдения не имеет просроченных узлов и мёртвых событий"
                        : "Самодиагностика SHADOW требует внимания",
                healthSafe ? 1L : 0L,
                1L
        ));
        boolean maintenanceHealthy = health.maintenanceHealthy();
        checks.add(check(
                "MAINTENANCE_HEALTH",
                maintenanceHealthy,
                maintenanceHealthy
                        ? "Самовосстановление и регламентное хранение работают штатно"
                        : "Обслуживание требует внимания: repair="
                                + health.maintenance().repairStatus()
                                + ", retention="
                                + health.maintenance().retentionStatus(),
                maintenanceHealthy ? 1L : 0L,
                1L
        ));

        long finalizedDates = repository.countFinalizedDates(historyStart, today);
        checks.add(check(
                "FINALIZED_HISTORY",
                finalizedDates >= settings.minFinalizedDays(),
                "Завершённых чистых дней после " + historyStart + ": " + finalizedDates,
                finalizedDates,
                (long) settings.minFinalizedDays()
        ));

        LocalDateTime stableSince = now.minusHours(settings.stableHours());
        long failedRuns = repository.countFailedRunsSince(stableSince);
        long maximumStableGapMinutes = Math.max(
                5L,
                shadowSettings.schedulerIntervalMinutes() * 2L
        );
        long observedMaximumGapMinutes =
                repository.maximumSuccessfulRunGapMinutes(stableSince, now);
        boolean stableRuns = failedRuns == 0
                && observedMaximumGapMinutes <= maximumStableGapMinutes;
        checks.add(check(
                "STABLE_RUNS",
                stableRuns,
                "Ошибок расчёта: "
                        + failedRuns
                        + "; максимальный перерыв между успешными расчётами: "
                        + observedMaximumGapMinutes
                        + " мин.",
                observedMaximumGapMinutes,
                maximumStableGapMinutes
        ));

        LocalDateTime lastSuccess = repository.lastSuccessfulRunAt().orElse(null);
        long ageMinutes = lastSuccess == null
                ? Long.MAX_VALUE
                : Math.max(0, Duration.between(lastSuccess, now).toMinutes());
        long maximumAge = Math.max(
                5L,
                shadowSettings.schedulerIntervalMinutes() * 2L
        );
        checks.add(check(
                "FRESH_SNAPSHOT",
                lastSuccess != null && ageMinutes <= maximumAge,
                lastSuccess == null
                        ? "Нет успешного расчёта"
                        : "Возраст последнего успешного расчёта: " + ageMinutes + " мин.",
                lastSuccess == null ? null : ageMinutes,
                maximumAge
        ));

        List<WorkloadLiveReadinessRepository.ManagerCapacityProjection> capacities =
                repository.managerCapacity();
        List<Long> targetManagers = targetManagers(mode, settings, capacities);
        Set<Long> targetSet = new HashSet<>(targetManagers);
        List<WorkloadLiveReadinessRepository.ManagerCapacityProjection> selected =
                WorkloadLiveSettingsService.MODE_LIVE.equals(mode)
                        ? capacities
                        : capacities.stream()
                                .filter(value -> targetSet.contains(value.getManagerId()))
                                .toList();
        boolean allManagersPresent = WorkloadLiveSettingsService.MODE_LIVE.equals(mode)
                || selected.size() == targetSet.size();
        long insufficientManagers = selected.stream()
                .filter(value -> value.getEligibleRecipientCount() == null
                        || value.getEligibleRecipientCount()
                                < settings.minCandidatesPerManager())
                .count();
        boolean capacitySafe = !selected.isEmpty()
                && allManagersPresent
                && insufficientManagers == 0;
        String capacityMessage;
        if (capacitySafe) {
            capacityMessage = "У каждого выбранного менеджера достаточно получателей";
        } else if (WorkloadLiveSettingsService.MODE_CANARY.equals(mode)
                && targetManagers.isEmpty()) {
            capacityMessage = "Для пилотного режима не выбран ни один менеджер";
        } else if (!allManagersPresent && selected.isEmpty()) {
            capacityMessage = "Не найдены данные по выбранным пилотным менеджерам";
        } else if (!allManagersPresent) {
            capacityMessage = "Часть выбранных пилотных менеджеров не найдена; "
                    + "менеджеров с недостаточным числом получателей: "
                    + insufficientManagers;
        } else if (selected.isEmpty()) {
            capacityMessage = "Нет менеджеров с данными для проверки получателей";
        } else {
            capacityMessage = "Менеджеров без требуемого числа получателей: "
                    + insufficientManagers;
        }
        checks.add(check(
                "RECIPIENT_CAPACITY",
                capacitySafe,
                capacityMessage,
                insufficientManagers,
                0L
        ));

        List<Long> queryManagers = targetManagers.isEmpty()
                ? List.of(-1L)
                : targetManagers;
        long graphErrors = repository.countGraphErrorCases(
                WorkloadLiveSettingsService.MODE_LIVE.equals(mode),
                queryManagers
        );
        checks.add(check(
                "GRAPH_ERRORS",
                graphErrors == 0,
                "Активных рекомендаций с ошибками графа: " + graphErrors,
                graphErrors,
                0L
        ));

        long inFlight = repository.countInFlightExecutions();
        checks.add(check(
                "NO_IN_FLIGHT_EXECUTIONS",
                inFlight == 0,
                "Незавершённых применений или откатов: " + inFlight,
                inFlight,
                0L
        ));

        boolean ready = checks.stream().allMatch(Check::passed);
        return new WorkloadLiveReadinessResponse(ready, mode, now, List.copyOf(checks));
    }

    public void assertReady(
            String targetMode,
            WorkloadLiveSettingsResponse settings
    ) {
        WorkloadLiveReadinessResponse readiness = readiness(targetMode, settings);
        if (readiness.ready()) {
            return;
        }
        String failed = readiness.checks().stream()
                .filter(check -> !check.passed())
                .map(Check::code)
                .reduce((left, right) -> left + ", " + right)
                .orElse("UNKNOWN");
        throw new ResponseStatusException(
                HttpStatus.PRECONDITION_FAILED,
                "Боевой режим не включён. Не пройдены проверки: " + failed
        );
    }

    private List<Long> targetManagers(
            String mode,
            WorkloadLiveSettingsResponse settings,
            List<WorkloadLiveReadinessRepository.ManagerCapacityProjection> capacities
    ) {
        if (WorkloadLiveSettingsService.MODE_CANARY.equals(mode)) {
            if (settings.canaryManagerIds().isEmpty()) {
                return List.of();
            }
            return settings.canaryManagerIds();
        }
        return capacities.stream()
                .map(WorkloadLiveReadinessRepository.ManagerCapacityProjection::getManagerId)
                .filter(value -> value != null)
                .toList();
    }

    private String normalizedMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!WorkloadLiveSettingsService.MODE_CANARY.equals(normalized)
                && !WorkloadLiveSettingsService.MODE_LIVE.equals(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Проверка доступна только для CANARY или LIVE"
            );
        }
        return normalized;
    }

    private Check check(
            String code,
            boolean passed,
            String message,
            Long actual,
            Long required
    ) {
        return new Check(code, passed ? "PASS" : "FAIL", message, actual, required);
    }
}
