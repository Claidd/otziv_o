package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

public interface ManagerReportReviewSessionRepository extends JpaRepository<ManagerReportReviewSession, Long> {

    /** Current locking read, without hydrating a possibly stale managed session. */
    @Query(value = """
            SELECT review_id AS id, deadline_started_at AS deadlineStartedAt,
                   ai_unavailable_started_at AS aiUnavailableStartedAt,
                   ai_unavailable_seconds AS aiUnavailableSeconds,
                   completed_at AS completedAt, status AS status
              FROM manager_report_review_sessions WHERE review_id = :reviewId FOR UPDATE
            """, nativeQuery = true)
    Optional<Timing> findTimingForUpdate(@Param("reviewId") Long reviewId);

    interface Timing {
        Long getId();
        LocalDateTime getDeadlineStartedAt();
        LocalDateTime getAiUnavailableStartedAt();
        long getAiUnavailableSeconds();
        LocalDateTime getCompletedAt();
        String getStatus();
    }

    @Modifying
    @Query(value = """
            UPDATE manager_report_review_sessions SET ai_unavailable_started_at = :now, updated_at = :now
             WHERE review_id = :reviewId AND completed_at IS NULL AND status <> 'COMPLETED'
               AND ai_unavailable_started_at IS NULL
            """, nativeQuery = true)
    int pauseIfActive(@Param("reviewId") Long reviewId, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE manager_report_review_sessions
               SET ai_unavailable_started_at = NULL, ai_unavailable_seconds = :seconds,
                   recovery_attempts = 0, recovery_next_attempt_at = NULL, recovery_awaiting_context = false,
                   deadline_started_at = :deadline, updated_at = :now
             WHERE review_id = :reviewId AND completed_at IS NULL AND status <> 'COMPLETED'
               AND ai_unavailable_started_at = :expectedPause
            """, nativeQuery = true)
    int resumeIfPaused(@Param("reviewId") Long reviewId,
                       @Param("expectedPause") LocalDateTime expectedPause,
                       @Param("seconds") long seconds, @Param("deadline") LocalDateTime deadline,
                       @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT review_id FROM manager_report_review_sessions
             WHERE test_mode = false AND completed_at IS NULL
               AND recovery_awaiting_context = false
               AND status IN ('QUESTION_PENDING', 'PLAN_PENDING')
               AND ai_unavailable_started_at <= :cutoff
               AND (recovery_next_attempt_at IS NULL OR recovery_next_attempt_at <= :now)
             ORDER BY review_id DESC LIMIT 3
            """, nativeQuery = true)
    List<Long> findRecoveryCandidates(@Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);

    @Query(value = """
            SELECT review_id FROM manager_report_review_sessions
             WHERE test_mode = false AND completed_at IS NULL AND status = 'QUESTION_PENDING'
               AND review_id > :afterId
             ORDER BY review_id LIMIT 20
            """, nativeQuery = true)
    List<Long> findSourceRefreshCandidates(@Param("afterId") long afterId);

    /** Committed separately from the provider call, so restarts and failed transactions retain the lease. */
    @Modifying
    @Query(value = """
            UPDATE manager_report_review_sessions
               SET recovery_attempts = recovery_attempts + 1, recovery_next_attempt_at = :leaseUntil
             WHERE review_id = :reviewId AND test_mode = false AND completed_at IS NULL
               AND recovery_awaiting_context = false
               AND status IN ('QUESTION_PENDING', 'PLAN_PENDING')
               AND ai_unavailable_started_at <= :cutoff
               AND (recovery_next_attempt_at IS NULL OR recovery_next_attempt_at <= :now)
            """, nativeQuery = true)
    int claimRecovery(@Param("reviewId") Long reviewId, @Param("now") LocalDateTime now,
                      @Param("cutoff") LocalDateTime cutoff, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Query(value = """
            SELECT review_id FROM manager_report_review_sessions
             WHERE manager_user_id = :userId AND test_mode = false AND completed_at IS NULL
             ORDER BY created_at DESC, review_id DESC LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findLatestIncompleteId(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            UPDATE manager_report_review_sessions SET deadline_started_at = :now, updated_at = :now
             WHERE review_id = :reviewId AND completed_at IS NULL AND status <> 'COMPLETED'
               AND test_mode = false AND deadline_started_at IS NULL
            """, nativeQuery = true)
    int startDeadlineIfAbsent(@Param("reviewId") Long reviewId, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE manager_report_review_sessions SET restricted_at = :now, updated_at = :now
             WHERE review_id = :reviewId AND completed_at IS NULL AND test_mode = false
               AND status IN ('DELIVERED','READING','QUESTION_PENDING','PLAN_PENDING','DISPUTE_PENDING')
               AND ai_unavailable_started_at IS NULL AND deadline_started_at <= :cutoff
               AND restricted_at IS NULL
            """, nativeQuery = true)
    int restrictIfDue(@Param("reviewId") Long reviewId, @Param("now") LocalDateTime now,
                      @Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query(value = """
            UPDATE manager_report_review_sessions
               SET reminder_one_sent_at = CASE WHEN :repeated = false THEN :now ELSE reminder_one_sent_at END,
                   reminder_three_sent_at = CASE WHEN :repeated = true THEN :now ELSE reminder_three_sent_at END,
                   updated_at = :now
             WHERE review_id = :reviewId AND completed_at IS NULL AND test_mode = false
               AND status IN ('DELIVERED','READING','QUESTION_PENDING','PLAN_PENDING','DISPUTE_PENDING')
               AND ai_unavailable_started_at IS NULL AND deadline_started_at <= :cutoff
               AND ((:repeated = false AND reminder_one_sent_at IS NULL)
                    OR (:repeated = true AND reminder_three_sent_at IS NULL))
            """, nativeQuery = true)
    int markReminderSentIfDue(@Param("reviewId") Long reviewId, @Param("now") LocalDateTime now,
                              @Param("cutoff") LocalDateTime cutoff, @Param("repeated") boolean repeated);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT review FROM ManagerReportReviewSession review WHERE review.id = :reviewId")
    Optional<ManagerReportReviewSession> findForUpdateById(@Param("reviewId") Long reviewId);

    Optional<ManagerReportReviewSession> findBySummaryDateAndManager_IdAndTestModeFalse(
            LocalDate date,
            Long managerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ManagerReportReviewSession> findFirstByManagerUserIdAndRecipientChatIdAndStatusInOrderByCreatedAtDesc(
            Long managerUserId,
            Long recipientChatId,
            Collection<ManagerReportReviewStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ManagerReportReviewSession> findFirstByManagerUserIdAndRecipientChatIdAndReplyPromptMessageId(
            Long managerUserId, Long recipientChatId, Integer replyPromptMessageId);

    List<ManagerReportReviewSession> findBySummaryDateOrderByManagerNameAsc(LocalDate date);

    List<ManagerReportReviewSession> findByCompletedAtIsNull();

    List<ManagerReportReviewSession> findByManager_IdAndCompletedAtIsNull(Long managerId);

    Optional<ManagerReportReviewSession> findTopByOrderBySummaryDateDesc();

    Optional<ManagerReportReviewSession>
    findFirstByManagerUserIdAndTestModeFalseAndCompletedAtIsNullOrderByCreatedAtDesc(
            Long managerUserId
    );

    Optional<ManagerReportReviewSession>
    findFirstByManagerUserIdAndTestModeFalseAndDeadlineStartedAtLessThanEqualAndAiUnavailableStartedAtIsNullAndStatusNotInAndCompletedAtIsNullOrderByDeadlineStartedAtAsc(
            Long managerUserId,
            LocalDateTime cutoff,
            Collection<ManagerReportReviewStatus> excludedStatuses
    );

    @Query("""
            SELECT review.id
            FROM ManagerReportReviewSession review
            WHERE review.status IN :statuses
              AND review.testMode = false
              AND review.completedAt IS NULL
              AND review.deadlineStartedAt IS NOT NULL
              AND review.deadlineStartedAt <= :cutoff
            ORDER BY review.deadlineStartedAt
            """)
    List<Long> findPendingIdsForReminder(
            @Param("statuses") Collection<ManagerReportReviewStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff
    );
}
