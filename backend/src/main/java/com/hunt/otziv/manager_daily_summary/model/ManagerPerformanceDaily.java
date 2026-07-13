package com.hunt.otziv.manager_daily_summary.model;

import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "manager_performance_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_manager_performance_daily", columnNames = {"summary_date", "manager_id"})
)
public class ManagerPerformanceDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_id")
    private Long id;

    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_id", nullable = false)
    private Manager manager;

    @Column(name = "manager_user_id") private Long managerUserId;
    @Column(name = "manager_name", length = 220) private String managerName;
    @Column(name = "base_score", nullable = false) private int baseScore;
    @Column(name = "adjusted_score", nullable = false) private int adjustedScore;
    @Column(name = "grade", nullable = false, length = 4) private String grade;
    @Column(name = "formula_version", nullable = false, length = 40) private String formulaVersion;
    @Column(name = "task_total", nullable = false) private long taskTotal;
    @Column(name = "task_completed", nullable = false) private long taskCompleted;
    @Column(name = "task_open", nullable = false) private long taskOpen;
    @Column(name = "task_progress_percent", nullable = false, precision = 5, scale = 2) private BigDecimal taskProgressPercent;
    @Column(name = "overdue_count", nullable = false) private long overdueCount;
    @Column(name = "risk_count", nullable = false) private long riskCount;
    @Column(name = "unanswered_count", nullable = false) private long unansweredCount;
    @Column(name = "first_reply_count", nullable = false) private long firstReplyCount;
    @Column(name = "first_reply_total_seconds", nullable = false) private long firstReplyTotalSeconds;
    @Column(name = "first_reply_average_seconds", nullable = false) private long firstReplyAverageSeconds;
    @Column(name = "first_reply_median_seconds", nullable = false) private long firstReplyMedianSeconds;
    @Column(name = "first_reply_p90_seconds", nullable = false) private long firstReplyP90Seconds;
    @Column(name = "all_reply_count", nullable = false) private long allReplyCount;
    @Column(name = "all_reply_total_seconds", nullable = false) private long allReplyTotalSeconds;
    @Column(name = "all_reply_average_seconds", nullable = false) private long allReplyAverageSeconds;
    @Column(name = "all_reply_median_seconds", nullable = false) private long allReplyMedianSeconds;
    @Column(name = "all_reply_p90_seconds", nullable = false) private long allReplyP90Seconds;
    @Column(name = "replies_in_sla", nullable = false) private long repliesInSla;
    @Column(name = "reply_histogram", length = 500) private String replyHistogram;
    @Column(name = "problem_count", nullable = false) private long problemCount;
    @Column(name = "problem_resolved_count", nullable = false) private long problemResolvedCount;
    @Column(name = "problem_resolution_total_seconds", nullable = false) private long problemResolutionTotalSeconds;
    @Column(name = "problem_resolution_average_seconds", nullable = false) private long problemResolutionAverageSeconds;
    @Column(name = "site_active_seconds", nullable = false) private long siteActiveSeconds;
    @Column(name = "messenger_active_seconds", nullable = false) private long messengerActiveSeconds;
    @Column(name = "confirmed_active_seconds", nullable = false) private long confirmedActiveSeconds;
    @Column(name = "aggregation_status", nullable = false, length = 24) private String aggregationStatus;
    @Column(name = "finalized_at") private LocalDateTime finalizedAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (grade == null) grade = "J";
        if (formulaVersion == null) formulaVersion = "manager-v2";
        if (aggregationStatus == null) aggregationStatus = "CALCULATED";
        if (taskProgressPercent == null) taskProgressPercent = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
