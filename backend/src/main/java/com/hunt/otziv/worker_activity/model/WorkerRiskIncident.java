package com.hunt.otziv.worker_activity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "worker_risk_incidents",
        indexes = {
                @Index(name = "idx_worker_risk_worker_status_created", columnList = "worker_user_id, status, created_at"),
                @Index(name = "idx_worker_risk_rule_created", columnList = "rule_code, created_at"),
                @Index(name = "idx_worker_risk_event", columnList = "activity_event_id")
        }
)
public class WorkerRiskIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "incident_id")
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorkerRiskIncidentStatus status = WorkerRiskIncidentStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 30)
    private WorkerRiskIncidentLevel level;

    @Column(name = "rule_code", nullable = false, length = 80)
    private String ruleCode;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "worker_user_id", nullable = false)
    private Long workerUserId;

    @Column(name = "worker_username", nullable = false, length = 150)
    private String workerUsername;

    @Column(name = "worker_name", length = 200)
    private String workerName;

    @Column(name = "activity_event_id")
    private Long activityEventId;

    @Column(name = "action", length = 60)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "title", nullable = false, length = 180)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "explanation_requested_at")
    private LocalDateTime explanationRequestedAt;

    @Column(name = "explanation_prompted_at")
    private LocalDateTime explanationPromptedAt;

    @Column(name = "worker_explanation", columnDefinition = "TEXT")
    private String workerExplanation;

    @Column(name = "worker_explanation_at")
    private LocalDateTime workerExplanationAt;

    @Column(name = "worker_explanation_by_user_id")
    private Long workerExplanationByUserId;

    @Column(name = "telegram_notification_chat_id")
    private Long telegramNotificationChatId;

    @Column(name = "telegram_notification_message_id")
    private Integer telegramNotificationMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_action", length = 40)
    private WorkerRiskResolutionAction resolutionAction;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_by_username", length = 150)
    private String resolvedByUsername;

    @Column(name = "penalty_points", nullable = false)
    private int penaltyPoints;

    @Enumerated(EnumType.STRING)
    @Column(name = "rollback_status", length = 30)
    private WorkerRiskRollbackStatus rollbackStatus;

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    @Column(name = "rolled_back_by_user_id")
    private Long rolledBackByUserId;

    @Column(name = "rolled_back_by_username", length = 150)
    private String rolledBackByUsername;

    @Column(name = "rollback_message", columnDefinition = "TEXT")
    private String rollbackMessage;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = WorkerRiskIncidentStatus.OPEN;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
