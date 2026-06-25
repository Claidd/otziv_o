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
        name = "manager_daily_control_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_manager_control_item_key", columnNames = {"control_id", "item_key"})
)
public class ManagerDailyControlItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "control_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id", nullable = false)
    private ManagerDailyControl control;

    @Column(name = "item_key", nullable = false, length = 160)
    private String itemKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 40)
    private ManagerDailyControlItemType itemType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "section_code", length = 80)
    private String sectionCode;

    @Column(name = "reason_code", nullable = false, length = 80)
    private String reasonCode;

    @Column(name = "label", nullable = false, length = 160)
    private String label;

    @Column(name = "target_url", length = 500)
    private String targetUrl;

    @Column(name = "item_count", nullable = false)
    private long count;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 30)
    private ManagerDailyControlSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_code", nullable = false, length = 30)
    private ManagerDailyControlGroup group;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 30)
    private ManagerDailyControlItemStatus status = ManagerDailyControlItemStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 40)
    private ManagerDailyControlActionType actionType;

    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "created_reminder_id")
    private Long createdReminderId;

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
