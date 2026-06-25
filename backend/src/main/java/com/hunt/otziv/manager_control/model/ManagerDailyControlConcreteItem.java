package com.hunt.otziv.manager_control.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "manager_daily_control_concrete_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_manager_control_concrete_key",
                columnNames = {"control_id", "parent_item_id", "entity_key"}
        )
)
public class ManagerDailyControlConcreteItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "control_entity_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id", nullable = false)
    private ManagerDailyControl control;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_item_id", nullable = false)
    private ManagerDailyControlItem parentItem;

    @Column(name = "entity_key", nullable = false, length = 180)
    private String entityKey;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "title", nullable = false, length = 220)
    private String title;

    @Column(name = "subtitle", length = 500)
    private String subtitle;

    @Column(name = "status_label", length = 120)
    private String statusLabel;

    @Column(name = "age_days")
    private Long ageDays;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "target_url", length = 500)
    private String targetUrl;

    @Column(name = "order_details_id", length = 36)
    private String orderDetailsId;

    @Column(name = "chat_url", length = 500)
    private String chatUrl;

    @Column(name = "follow_up_at")
    private LocalDateTime followUpAt;

    @Column(name = "last_manual_touch_at")
    private LocalDateTime lastManualTouchAt;

    @Column(name = "worker_notification_attempted_at")
    private LocalDateTime workerNotificationAttemptedAt;

    @Column(name = "worker_notification_sent_at")
    private LocalDateTime workerNotificationSentAt;

    @Column(name = "worker_notification_accepted_at")
    private LocalDateTime workerNotificationAcceptedAt;

    @Column(name = "worker_notification_accepted_by_user_id")
    private Long workerNotificationAcceptedByUserId;

    @Column(name = "worker_notification_failure_reason", length = 500)
    private String workerNotificationFailureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 30)
    private ManagerDailyControlItemStatus status = ManagerDailyControlItemStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 40)
    private ManagerDailyControlActionType actionType;

    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = ManagerDailyControlItemStatus.OPEN;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
