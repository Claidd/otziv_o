package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.health.service.WorkloadShadowHealthService;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveRuntimeSafetyRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runtime circuit breaker for every mutating CANARY/LIVE entry point.
 *
 * <p>The activation readiness gate is intentionally not enough: collection can
 * stop or a projection can commit while the following transfer simulation fails.
 * This service therefore proves on every live transition that all current rows
 * belong to the last fully successful run and to the current algorithm revision.
 */
@Service
@RequiredArgsConstructor
public class WorkloadLiveRuntimeSafetyService {

    private final WorkloadLiveRuntimeSafetyRepository repository;
    private final WorkloadShadowSettingsService shadowSettingsService;
    private final WorkloadShadowHealthService healthService;

    public Decision evaluate() {
        var settings = shadowSettingsService.current();
        if (!settings.observationEnabled()) {
            return Decision.blocked("OBSERVATION_DISABLED", "Сбор наблюдений выключен");
        }
        var state = repository.runtimeState().orElse(null);
        if (state == null
                || state.getLatestSuccessfulRunId() == null
                || state.getLatestSuccessfulFinishedAt() == null) {
            return Decision.blocked("NO_SUCCESSFUL_RUN", "Нет полного успешного расчёта");
        }
        if (value(state.getLatestSettingsRevision()) != settings.revision()) {
            return Decision.blocked(
                    "SETTINGS_REVISION_STALE",
                    "Последний расчёт выполнен на другой ревизии алгоритма"
            );
        }
        if (value(state.getRunningRunCount()) > 0) {
            return Decision.blocked("RECALCULATION_RUNNING", "Пересчёт ещё выполняется");
        }
        if (value(state.getCurrentSnapshotCount()) == 0) {
            return Decision.blocked("EMPTY_CURRENT_SNAPSHOT", "Текущий снимок пуст");
        }
        if (value(state.getMismatchedCurrentSnapshotCount()) > 0
                || value(state.getMismatchedActiveCaseCount()) > 0) {
            return Decision.blocked(
                    "PARTIAL_OR_STALE_RUN",
                    "Текущие данные не принадлежат последнему полному успешному расчёту"
            );
        }
        LocalDateTime now = LocalDateTime.now(shadowSettingsService.zone(settings));
        long ageMinutes = Math.max(
                0,
                Duration.between(state.getLatestSuccessfulFinishedAt(), now).toMinutes()
        );
        long maximumAgeMinutes = Math.max(5L, settings.schedulerIntervalMinutes() * 2L);
        if (ageMinutes > maximumAgeMinutes) {
            return Decision.blocked(
                    "SNAPSHOT_STALE",
                    "Возраст последнего полного расчёта превышает "
                            + maximumAgeMinutes + " мин."
            );
        }
        WorkloadShadowHealthSnapshot health;
        try {
            health = healthService.snapshot();
        } catch (RuntimeException exception) {
            return Decision.blocked(
                    "HEALTH_UNAVAILABLE",
                    "Самодиагностика SHADOW недоступна"
            );
        }
        if (health.stale()
                || health.deadEvents() > 0
                || health.missingGroupBindings() > 0
                || !health.maintenanceHealthy()) {
            return Decision.blocked(
                    "SHADOW_UNHEALTHY",
                    "Самодиагностика или обслуживание SHADOW требуют внимания"
            );
        }
        return Decision.allowed(
                state.getLatestSuccessfulRunId(),
                settings.revision(),
                state.getLatestSuccessfulFinishedAt()
        );
    }

    private long value(Number value) {
        return value == null ? 0 : value.longValue();
    }

    public record Decision(
            boolean allowed,
            String code,
            String message,
            Long runId,
            Long shadowSettingsRevision,
            LocalDateTime completedAt
    ) {
        static Decision allowed(long runId, long revision, LocalDateTime completedAt) {
            return new Decision(true, "SAFE", "Боевые данные актуальны", runId, revision, completedAt);
        }

        static Decision blocked(String code, String message) {
            return new Decision(false, code, message, null, null, null);
        }
    }
}
