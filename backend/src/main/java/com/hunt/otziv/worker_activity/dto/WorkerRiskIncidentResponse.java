package com.hunt.otziv.worker_activity.dto;

import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskRollbackStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import java.time.LocalDateTime;

public record WorkerRiskIncidentResponse(
        Long id,
        LocalDateTime createdAt,
        WorkerRiskIncidentStatus status,
        WorkerRiskIncidentLevel level,
        String ruleCode,
        int score,
        Long workerUserId,
        String workerUsername,
        String workerName,
        Long activityEventId,
        String action,
        String entityType,
        Long entityId,
        Long orderId,
        Long reviewId,
        String title,
        String message,
        String details,
        LocalDateTime explanationRequestedAt,
        LocalDateTime explanationPromptedAt,
        String workerExplanation,
        LocalDateTime workerExplanationAt,
        Long workerExplanationByUserId,
        WorkerRiskResolutionAction resolutionAction,
        LocalDateTime resolvedAt,
        Long resolvedByUserId,
        String resolvedByUsername,
        int penaltyPoints,
        WorkerRiskRollbackStatus rollbackStatus,
        LocalDateTime rolledBackAt,
        Long rolledBackByUserId,
        String rolledBackByUsername,
        String rollbackMessage,
        boolean canRollback
) {

    public static WorkerRiskIncidentResponse from(WorkerRiskIncident incident) {
        return new WorkerRiskIncidentResponse(
                incident.getId(),
                incident.getCreatedAt(),
                incident.getStatus(),
                incident.getLevel(),
                incident.getRuleCode(),
                incident.getScore(),
                incident.getWorkerUserId(),
                incident.getWorkerUsername(),
                incident.getWorkerName(),
                incident.getActivityEventId(),
                incident.getAction(),
                incident.getEntityType(),
                incident.getEntityId(),
                incident.getOrderId(),
                incident.getReviewId(),
                incident.getTitle(),
                incident.getMessage(),
                incident.getDetails(),
                incident.getExplanationRequestedAt(),
                incident.getExplanationPromptedAt(),
                incident.getWorkerExplanation(),
                incident.getWorkerExplanationAt(),
                incident.getWorkerExplanationByUserId(),
                incident.getResolutionAction(),
                incident.getResolvedAt(),
                incident.getResolvedByUserId(),
                incident.getResolvedByUsername(),
                incident.getPenaltyPoints(),
                incident.getRollbackStatus(),
                incident.getRolledBackAt(),
                incident.getRolledBackByUserId(),
                incident.getRolledBackByUsername(),
                incident.getRollbackMessage(),
                canRollback(incident)
        );
    }

    private static boolean canRollback(WorkerRiskIncident incident) {
        if (incident.getStatus() != WorkerRiskIncidentStatus.VIOLATION || incident.getRollbackStatus() != null) {
            return false;
        }
        return "BAD_TASK_COMPLETE".equals(incident.getAction())
                || "RECOVERY_TASK_COMPLETE".equals(incident.getAction());
    }
}
