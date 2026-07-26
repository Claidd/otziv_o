package com.hunt.otziv.manager_daily_summary.model;

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
        name = "manager_report_review_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_manager_report_review_run",
                columnNames = {"summary_date", "manager_id", "test_run_id"}
        )
)
public class ManagerReportReviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_id", nullable = false)
    private Manager manager;

    @Column(name = "manager_user_id", nullable = false)
    private Long managerUserId;

    @Column(name = "manager_name", nullable = false, length = 220)
    private String managerName;

    @Column(name = "test_mode", nullable = false)
    private boolean testMode;

    @Column(name = "test_owner_user_id")
    private Long testOwnerUserId;

    @Column(name = "test_run_id", nullable = false)
    private long testRunId;

    @Column(name = "recipient_chat_id", nullable = false)
    private Long recipientChatId;

    @Column(name = "telegram_message_id")
    private Integer telegramMessageId;

    @Column(name = "question_message_id")
    private Integer questionMessageId;

    @Column(name = "reply_prompt_message_id")
    private Integer replyPromptMessageId;

    @Column(name = "question_sent_at")
    private LocalDateTime questionSentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ManagerReportReviewStatus status;

    @Column(name = "report_snapshot", nullable = false, columnDefinition = "LONGTEXT")
    private String reportSnapshot;

    @Column(name = "report_rich_snapshot", columnDefinition = "LONGTEXT")
    private String reportRichSnapshot;

    @Column(name = "questions_json", columnDefinition = "LONGTEXT")
    private String questionsJson;

    @Column(name = "questions_source", length = 24)
    private String questionsSource;

    @Column(name = "answers_json", columnDefinition = "LONGTEXT")
    private String answersJson;

    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    @Column(name = "issue_count", nullable = false)
    private int issueCount;

    @Column(name = "minimum_read_seconds", nullable = false)
    private int minimumReadSeconds;

    @Column(name = "read_seconds", nullable = false)
    private long readSeconds;

    @Column(name = "answer_quality", length = 32)
    private String answerQuality;

    @Column(name = "answer_quality_reason", length = 1000)
    private String answerQualityReason;

    @Column(name = "suspicious_answer_count", nullable = false)
    private int suspiciousAnswerCount;

    @Column(name = "action_plan", length = 2000)
    private String actionPlan;

    @Column(name = "dispute_text", length = 2000)
    private String disputeText;

    @Column(name = "audit_required", nullable = false)
    private boolean auditRequired;

    @Column(name = "auto_completed", nullable = false)
    private boolean autoCompleted;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "reading_confirmed_at")
    private LocalDateTime readingConfirmedAt;

    @Column(name = "deadline_started_at")
    private LocalDateTime deadlineStartedAt;

    @Column(name = "ai_unavailable_started_at")
    private LocalDateTime aiUnavailableStartedAt;

    @Column(name = "ai_unavailable_seconds", nullable = false)
    private long aiUnavailableSeconds;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    @Column(name = "reminder_one_sent_at")
    private LocalDateTime reminderOneSentAt;

    @Column(name = "reminder_three_sent_at")
    private LocalDateTime reminderThreeSentAt;

    @Column(name = "restricted_at")
    private LocalDateTime restrictedAt;

    @Column(name = "restriction_released_at")
    private LocalDateTime restrictionReleasedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = ManagerReportReviewStatus.DELIVERED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
