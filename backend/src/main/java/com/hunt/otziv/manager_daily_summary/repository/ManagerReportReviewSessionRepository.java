package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagerReportReviewSessionRepository extends JpaRepository<ManagerReportReviewSession, Long> {

    Optional<ManagerReportReviewSession> findBySummaryDateAndManager_IdAndTestModeFalse(
            LocalDate date,
            Long managerId
    );

    Optional<ManagerReportReviewSession> findFirstByManagerUserIdAndRecipientChatIdAndStatusInOrderByCreatedAtDesc(
            Long managerUserId,
            Long recipientChatId,
            Collection<ManagerReportReviewStatus> statuses
    );

    List<ManagerReportReviewSession> findBySummaryDateOrderByManagerNameAsc(LocalDate date);

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
            SELECT review
            FROM ManagerReportReviewSession review
            WHERE review.status IN :statuses
              AND review.testMode = false
              AND review.completedAt IS NULL
              AND review.deadlineStartedAt IS NOT NULL
              AND review.deadlineStartedAt <= :cutoff
            ORDER BY review.deadlineStartedAt
            """)
    List<ManagerReportReviewSession> findPendingForReminder(
            @Param("statuses") Collection<ManagerReportReviewStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff
    );
}
