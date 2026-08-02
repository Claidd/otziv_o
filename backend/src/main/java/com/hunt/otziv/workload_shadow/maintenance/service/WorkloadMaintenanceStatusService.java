package com.hunt.otziv.workload_shadow.maintenance.service;

import com.hunt.otziv.workload_shadow.health.dto.WorkloadMaintenanceHealthSnapshot;
import com.hunt.otziv.workload_shadow.repository.WorkloadMaintenanceStatusRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadMaintenanceStatusRepository.StatusProjection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkloadMaintenanceStatusService {

    public static final String TASK_REPAIR = "REPAIR";
    public static final String TASK_RETENTION = "RETENTION";
    private static final Duration REPAIR_MAX_AGE = Duration.ofMinutes(15);
    private static final Duration RETENTION_MAX_AGE = Duration.ofHours(36);
    private static final Duration STARTED_MAX_AGE = Duration.ofMinutes(10);

    private final WorkloadMaintenanceStatusRepository repository;

    public WorkloadMaintenanceStatusService(WorkloadMaintenanceStatusRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStarted(String task, LocalDateTime startedAt) {
        repository.markStarted(task, startedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSucceeded(String task, LocalDateTime succeededAt) {
        repository.markSucceeded(task, succeededAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(
            String task,
            LocalDateTime failedAt,
            RuntimeException exception
    ) {
        String code = exception == null
                ? "UNKNOWN"
                : exception.getClass().getSimpleName();
        String message = bounded(exception == null ? null : exception.getMessage());
        repository.markFailed(task, failedAt, code, message);
    }

    @Transactional(readOnly = true)
    public WorkloadMaintenanceHealthSnapshot health(LocalDateTime now) {
        List<StatusProjection> rows = repository.findRuntimeStatuses();
        StatusProjection repair = row(rows, TASK_REPAIR);
        StatusProjection retention = row(rows, TASK_RETENTION);
        String repairStatus = status(repair, now, REPAIR_MAX_AGE, false);
        String retentionStatus = status(retention, now, RETENTION_MAX_AGE, true);
        StatusProjection latestFailure = latestFailure(repair, retention);
        return new WorkloadMaintenanceHealthSnapshot(
                "UP".equals(repairStatus)
                        && ("UP".equals(retentionStatus)
                                || "INITIALIZING".equals(retentionStatus)),
                repairStatus,
                retentionStatus,
                value(repair, StatusProjection::getLastStartedAt),
                value(repair, StatusProjection::getLastSucceededAt),
                value(repair, StatusProjection::getLastFailedAt),
                value(retention, StatusProjection::getLastStartedAt),
                value(retention, StatusProjection::getLastSucceededAt),
                value(retention, StatusProjection::getLastFailedAt),
                integer(repair),
                integer(retention),
                latestFailure == null ? null : latestFailure.getLastErrorCode(),
                latestFailure == null ? null : latestFailure.getLastErrorMessage()
        );
    }

    private String status(
            StatusProjection row,
            LocalDateTime now,
            Duration maximumAge,
            boolean initialGrace
    ) {
        if (row == null) {
            return "MISSING";
        }
        LocalDateTime succeededAt = row.getLastSucceededAt();
        LocalDateTime failedAt = row.getLastFailedAt();
        LocalDateTime startedAt = row.getLastStartedAt();
        if (failedAt != null && (succeededAt == null || failedAt.isAfter(succeededAt))) {
            return "FAILED";
        }
        if (startedAt != null
                && (succeededAt == null || startedAt.isAfter(succeededAt))
                && (failedAt == null || startedAt.isAfter(failedAt))
                && olderThan(startedAt, now, STARTED_MAX_AGE)) {
            return "STALE";
        }
        if (succeededAt == null) {
            if (initialGrace
                    && row.getCreatedAt() != null
                    && !olderThan(row.getCreatedAt(), now, maximumAge)) {
                return "INITIALIZING";
            }
            return "NEVER_RUN";
        }
        return olderThan(succeededAt, now, maximumAge) ? "STALE" : "UP";
    }

    private boolean olderThan(
            LocalDateTime timestamp,
            LocalDateTime now,
            Duration maximumAge
    ) {
        return timestamp.isBefore(now.minus(maximumAge));
    }

    private StatusProjection row(List<StatusProjection> rows, String task) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .filter(value -> value != null && task.equals(value.getTask()))
                .findFirst()
                .orElse(null);
    }

    private StatusProjection latestFailure(
            StatusProjection left,
            StatusProjection right
    ) {
        if (left == null || left.getLastFailedAt() == null) {
            return right != null && right.getLastFailedAt() != null ? right : null;
        }
        if (right == null || right.getLastFailedAt() == null) {
            return left;
        }
        return right.getLastFailedAt().isAfter(left.getLastFailedAt()) ? right : left;
    }

    private int integer(StatusProjection row) {
        return row == null || row.getConsecutiveFailures() == null
                ? 0
                : row.getConsecutiveFailures();
    }

    private LocalDateTime value(
            StatusProjection row,
            java.util.function.Function<StatusProjection, LocalDateTime> extractor
    ) {
        return row == null ? null : extractor.apply(row);
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

}
