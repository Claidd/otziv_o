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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "worker_activity_events",
        indexes = {
                @Index(name = "idx_worker_activity_worker_created", columnList = "worker_user_id, created_at"),
                @Index(name = "idx_worker_activity_action_created", columnList = "action, created_at"),
                @Index(name = "idx_worker_activity_review_created", columnList = "review_id, created_at"),
                @Index(name = "idx_worker_activity_entity_created", columnList = "entity_type, entity_id, created_at")
        }
)
public class WorkerActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "worker_user_id", nullable = false)
    private Long workerUserId;

    @Column(name = "worker_username", nullable = false, length = 150)
    private String workerUsername;

    @Column(name = "worker_name", length = 200)
    private String workerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 60)
    private WorkerActivityAction action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "section", length = 40)
    private String section;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
