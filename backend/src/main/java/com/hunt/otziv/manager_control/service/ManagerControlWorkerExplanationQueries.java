package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.dto.ManagerControlWorkerExplanationStatsResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Read-only explanation SLA aggregation for a control already authorized by the application.
 * Notification timestamps identify the original recipient; a risk is answered only after acceptance.
 */
@Component
@RequiredArgsConstructor
public class ManagerControlWorkerExplanationQueries {
    static final int WORKER_TASK_FOLLOW_UP_HOURS = 3;
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final UserRepository userRepository;
    private final ManagerControlWorkerTaskLookup workerTaskLookup;

    List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats(ManagerDailyControl control) {
        return workerExplanationStats(control, LocalDateTime.now());
    }

    List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats(
            ManagerDailyControl control, LocalDateTime now) {
        if (control == null || control.getId() == null) {
            return List.of();
        }
        Map<Long, WorkerExplanationAccumulator> stats = new LinkedHashMap<>();
        dailyControlConcreteItemRepository.findByControl(control).stream()
                .filter(this::isWorkerExplanationTrackedConcrete)
                .filter(this::hasWorkerExplanationRequest)
                .forEach(item -> {
                    User worker = workerUserForStats(item);
                    if (worker == null || worker.getId() == null) {
                        return;
                    }
                    WorkerExplanationAccumulator accumulator = stats.computeIfAbsent(
                            worker.getId(),
                            userId -> new WorkerExplanationAccumulator(worker.getId(), workerTaskLookup.userDisplayName(worker))
                    );
                    accumulator.add(
                            now,
                            workerExplanationStartedAt(item),
                            workerAcceptedExplanationAtForStats(item)
                    );
                });
        return stats.values().stream()
                .map(WorkerExplanationAccumulator::response)
                .sorted(Comparator
                        .comparingLong(ManagerControlWorkerExplanationStatsResponse::overdueCount).reversed()
                        .thenComparing(ManagerControlWorkerExplanationStatsResponse::unansweredCount, Comparator.reverseOrder())
                        .thenComparing(ManagerControlWorkerExplanationStatsResponse::workerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean isWorkerExplanationTrackedConcrete(ManagerDailyControlConcreteItem item) {
        return workerTaskLookup.isSpecialistActionConcrete(item);
    }

    private boolean hasWorkerExplanationRequest(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return false;
        }
        if (item.getWorkerExplanationRequestedAt() != null
                || item.getWorkerNotificationAttemptedAt() != null
                || item.getWorkerNotificationSentAt() != null
                || item.getWorkerExplanationPromptedAt() != null
                || item.getWorkerExplanationAt() != null) {
            return true;
        }
        if (!workerTaskLookup.isWorkerRiskConcrete(item) || item.getEntityId() == null) {
            return false;
        }
        WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
        return incident != null
                && (incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                || incident.getExplanationRequestedAt() != null
                || incident.getExplanationPromptedAt() != null
                || incident.getWorkerExplanationAt() != null);
    }

    private User workerUserForStats(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return null;
        }
        if (item.getWorkerNotificationUserId() != null) {
            return userRepository.findById(item.getWorkerNotificationUserId()).orElse(null);
        }
        return workerTaskLookup.workerUserForTask(item);
    }

    private LocalDateTime workerExplanationStartedAt(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return null;
        }
        if (item.getWorkerNotificationSentAt() != null) {
            return item.getWorkerNotificationSentAt();
        }
        if (workerTaskLookup.isWorkerRiskConcrete(item) && item.getEntityId() != null) {
            WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
            if (incident != null
                    && (incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                    || incident.getWorkerExplanationAt() != null
                    || incident.getExplanationPromptedAt() != null)) {
                return firstNonNullTime(
                        incident.getExplanationRequestedAt(),
                        incident.getExplanationPromptedAt(),
                        incident.getCreatedAt(),
                        item.getWorkerNotificationAttemptedAt()
                );
            }
        }
        return item.getWorkerNotificationAttemptedAt();
    }

    private LocalDateTime workerAcceptedExplanationAtForStats(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return null;
        }
        if (!workerTaskLookup.isWorkerRiskConcrete(item) && item.getWorkerExplanationAt() != null) {
            return item.getWorkerExplanationAt();
        }
        if (!workerTaskLookup.isWorkerRiskConcrete(item) || item.getEntityId() == null) {
            return null;
        }
        return riskIncidentRepository.findById(item.getEntityId())
                .map(WorkerRiskIncident::getExplanationAcceptedAt)
                .orElse(null);
    }

    private static class WorkerExplanationAccumulator {
        private final Long workerUserId;
        private final String workerName;
        private long requestCount;
        private long unansweredCount;
        private long overdueCount;
        private long answeredCount;
        private long responseMinutesTotal;

        private WorkerExplanationAccumulator(Long workerUserId, String workerName) {
            this.workerUserId = workerUserId;
            this.workerName = workerName;
        }

        private void add(
                LocalDateTime now,
                LocalDateTime startedAt,
                LocalDateTime explanationAt
        ) {
            requestCount++;
            if (explanationAt == null) {
                unansweredCount++;
                if (startedAt != null && Duration.between(startedAt, now).toHours() >= WORKER_TASK_FOLLOW_UP_HOURS) {
                    overdueCount++;
                }
                return;
            }
            if (startedAt != null) {
                answeredCount++;
                responseMinutesTotal += Math.max(0, Duration.between(startedAt, explanationAt).toMinutes());
            }
        }

        private ManagerControlWorkerExplanationStatsResponse response() {
            double averageResponseMinutes = answeredCount == 0
                    ? 0
                    : Math.round((responseMinutesTotal / (double) answeredCount) * 10.0) / 10.0;
            return new ManagerControlWorkerExplanationStatsResponse(
                    workerUserId,
                    workerName,
                    requestCount,
                    unansweredCount,
                    overdueCount,
                    averageResponseMinutes
            );
        }
    }

    private LocalDateTime firstNonNullTime(LocalDateTime... values) {
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
