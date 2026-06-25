package com.hunt.otziv.manager_control.model;

import com.hunt.otziv.u_users.model.Manager;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "manager_daily_controls",
        uniqueConstraints = @UniqueConstraint(name = "uk_manager_daily_control_day", columnNames = {"control_date", "manager_id"})
)
public class ManagerDailyControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_control_id")
    private Long id;

    @Column(name = "control_date", nullable = false)
    private LocalDate controlDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id", nullable = false)
    private Manager manager;

    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_status", nullable = false, length = 30)
    private ManagerDailyControlStatus status = ManagerDailyControlStatus.NOT_STARTED;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "morning_started_at")
    private LocalDateTime morningStartedAt;

    @Column(name = "morning_completed_at")
    private LocalDateTime morningCompletedAt;

    @Column(name = "day_checked_at")
    private LocalDateTime dayCheckedAt;

    @Column(name = "final_checked_at")
    private LocalDateTime finalCheckedAt;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "quality_score", nullable = false)
    private int qualityScore;

    @Column(name = "quality_grade", length = 20)
    private String qualityGrade;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "fast_click_risk", nullable = false)
    private boolean fastClickRisk;

    @Column(name = "morning_notification_sent_at")
    private LocalDateTime morningNotificationSentAt;

    @Column(name = "day_notification_sent_at")
    private LocalDateTime dayNotificationSentAt;

    @Column(name = "evening_notification_sent_at")
    private LocalDateTime eveningNotificationSentAt;

    @Column(name = "owner_notification_sent_at")
    private LocalDateTime ownerNotificationSentAt;

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
            status = ManagerDailyControlStatus.NOT_STARTED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
