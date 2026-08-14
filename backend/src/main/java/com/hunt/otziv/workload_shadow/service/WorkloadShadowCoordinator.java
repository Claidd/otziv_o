package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowRunResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.lock.model.WorkloadShadowRecalculationLease;
import com.hunt.otziv.workload_shadow.lock.service.WorkloadShadowRecalculationLockService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowCoordinator {

    private final WorkloadShadowSettingsService settingsService;
    private final WorkloadShadowProjectionService projectionService;
    private final WorkloadShadowTransferSimulationService transferSimulationService;
    private final WorkloadShadowRunService runService;
    private final WorkloadShadowRefreshSignal refreshSignal;
    private final WorkloadShadowRecalculationLockService recalculationLockService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkloadShadowRunResponse recalculate(String requestedTrigger) {
        var settings = settingsService.current();
        String trigger = normalizeTrigger(requestedTrigger);
        if (!settings.observationEnabled()) {
            return new WorkloadShadowRunResponse(
                    null,
                    "DISABLED",
                    trigger,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    "Режим наблюдения отключён в настройках"
            );
        }
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Пересчёт уже выполняется. Дождитесь его завершения."
            );
        }

        try {
            String instanceId = projectionService.instanceId();
            WorkloadShadowRecalculationLease lease = recalculationLockService
                    .tryAcquire(instanceId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Пересчёт уже выполняется другим экземпляром приложения. "
                                    + "Дождитесь освобождения lease."
                    ));
            try (lease) {
                return recalculateWithLease(trigger, instanceId, settings, lease);
            }
        } finally {
            running.set(false);
        }
    }

    private WorkloadShadowRunResponse recalculateWithLease(
            String trigger,
            String instanceId,
            WorkloadShadowSettingsResponse settings,
            WorkloadShadowRecalculationLease lease
    ) {
        // Токен фиксирует ревизию источников на старте. Изменения, которые придут
        // во время расчёта, увеличат ревизию и инициируют следующий запуск.
        WorkloadShadowRefreshSignal.RefreshToken refreshToken = refreshSignal.beginRefresh();
        LocalDateTime startedAt = LocalDateTime.now(settingsService.zone(settings));
        Long runId = null;
        try {
            runId = runService.start(trigger, instanceId, startedAt, settings.revision());
            lease.attachRun(runId);
            WorkloadShadowRunService.RunResult projection =
                    projectionService.recalculate(runId, startedAt);
            lease.checkpoint("AFTER_PROJECTION");
            WorkloadShadowTransferSimulationService.SimulationResult simulation =
                    transferSimulationService.rebuild(runId, startedAt);
            lease.checkpoint("AFTER_TRANSFER_SIMULATION");
            WorkloadShadowRunService.RunResult result = new WorkloadShadowRunService.RunResult(
                    projection.managerCount(),
                    projection.workerCount(),
                    simulation.transferCaseCount(),
                    projection.eventCount() + simulation.eventCount(),
                    projection.selfHealActionCount()
            );
            LocalDateTime finishedAt = LocalDateTime.now(settingsService.zone(settings));
            runService.complete(runId, result, startedAt, finishedAt);
            refreshSignal.completeRefresh(refreshToken);
            return new WorkloadShadowRunResponse(
                    runId,
                    "SUCCEEDED",
                    trigger,
                    startedAt,
                    finishedAt,
                    result.managerCount(),
                    result.workerCount(),
                    result.transferCaseCount(),
                    result.eventCount(),
                    "Снимок режима наблюдения обновлён; назначения и владельцы не изменялись"
            );
        } catch (RuntimeException exception) {
            if (runId != null) {
                try {
                    runService.fail(
                            runId,
                            exception,
                            startedAt,
                            LocalDateTime.now(settingsService.zone(settings))
                    );
                } catch (RuntimeException failException) {
                    exception.addSuppressed(failException);
                    log.error(
                            "Failed to mark workload shadow run as failed, runId={}",
                            runId,
                            failException
                    );
                }
            }
            refreshSignal.failRefresh();
            log.error("Workload shadow recalculation failed, runId={}", runId, exception);
            throw exception;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private String normalizeTrigger(String value) {
        if (value == null || value.isBlank()) {
            return "MANUAL";
        }
        String normalized = value.trim().toUpperCase();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}
