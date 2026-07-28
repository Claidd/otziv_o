package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRunRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadShadowRunService {

    private final WorkloadShadowRunRepository runRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(String triggerType, String instanceId, LocalDateTime startedAt) {
        LocalDateTime effectiveStartedAt =
                startedAt == null ? LocalDateTime.now() : startedAt;
        int inserted = runRepository.startRun(
                safe(triggerType, "SCHEDULED"),
                effectiveStartedAt,
                trim(instanceId, 120)
        );
        Long runId = inserted == 1 ? runRepository.lastInsertedRunId() : null;
        if (runId == null || runId <= 0) {
            throw new IllegalStateException(
                    "Не удалось зарегистрировать запуск workload shadow"
            );
        }
        return runId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            long runId,
            RunResult result,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
        LocalDateTime effectiveFinishedAt =
                finishedAt == null ? LocalDateTime.now() : finishedAt;
        long durationMs = Math.max(
                0,
                Duration.between(startedAt, effectiveFinishedAt).toMillis()
        );
        runRepository.complete(
                runId,
                effectiveFinishedAt,
                durationMs,
                result.managerCount(),
                result.workerCount(),
                result.transferCaseCount(),
                result.eventCount(),
                result.selfHealActionCount()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            long runId,
            Throwable error,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
        LocalDateTime effectiveFinishedAt =
                finishedAt == null ? LocalDateTime.now() : finishedAt;
        long durationMs = Math.max(
                0,
                Duration.between(startedAt, effectiveFinishedAt).toMillis()
        );
        runRepository.fail(
                runId,
                effectiveFinishedAt,
                durationMs,
                error == null
                        ? "UNKNOWN"
                        : trim(error.getClass().getSimpleName(), 80),
                error == null
                        ? "Неизвестная ошибка"
                        : trim(rootMessage(error), 1000)
        );
    }

    @Transactional(readOnly = true)
    public LocalDateTime lastSuccessfulFinishedAt() {
        return runRepository.lastSuccessfulFinishedAt().orElse(null);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private String safe(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return normalized.isBlank() ? fallback : trim(normalized, 32);
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record RunResult(
            int managerCount,
            int workerCount,
            int transferCaseCount,
            int eventCount,
            int selfHealActionCount
    ) {
    }
}
