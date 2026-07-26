package com.hunt.otziv.manager_daily_summary.model;

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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "manager_report_review_disputes")
public class ManagerReportReviewDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dispute_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private ManagerReportReviewIssue issue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ManagerReportReviewDisputeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_issue_status", nullable = false, length = 32)
    private ManagerReportReviewIssueStatus previousIssueStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_session_status", nullable = false, length = 32)
    private ManagerReportReviewStatus previousSessionStatus;

    @Column(name = "manager_text", length = 2000)
    private String managerText;

    @Column(name = "owner_comment", length = 2000)
    private String ownerComment;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (status == null) status = ManagerReportReviewDisputeStatus.DRAFT;
        if (requestedAt == null) requestedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
