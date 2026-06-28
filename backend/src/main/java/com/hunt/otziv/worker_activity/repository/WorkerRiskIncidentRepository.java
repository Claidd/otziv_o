package com.hunt.otziv.worker_activity.repository;

import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkerRiskIncidentRepository extends JpaRepository<WorkerRiskIncident, Long> {

    boolean existsByWorkerUserIdAndRuleCodeAndStatusAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            String ruleCode,
            WorkerRiskIncidentStatus status,
            LocalDateTime since
    );

    boolean existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            String ruleCode,
            WorkerRiskIncidentStatus status,
            Long reviewId,
            LocalDateTime since
    );

    boolean existsByWorkerUserIdAndRuleCodeAndStatusAndOrderIdAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            String ruleCode,
            WorkerRiskIncidentStatus status,
            Long orderId,
            LocalDateTime since
    );

    boolean existsByWorkerUserIdAndRuleCodeAndStatusAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            String ruleCode,
            WorkerRiskIncidentStatus status,
            String entityType,
            Long entityId,
            LocalDateTime since
    );

    Page<WorkerRiskIncident> findByStatusOrderByCreatedAtDesc(WorkerRiskIncidentStatus status, Pageable pageable);

    Page<WorkerRiskIncident> findByWorkerUserIdInAndStatusOrderByCreatedAtDesc(
            Collection<Long> workerUserIds,
            WorkerRiskIncidentStatus status,
            Pageable pageable
    );

    long countByWorkerUserIdInAndStatus(
            Collection<Long> workerUserIds,
            WorkerRiskIncidentStatus status
    );

    Optional<WorkerRiskIncident> findFirstByWorkerUserIdAndStatusAndResolutionActionAndWorkerExplanationAtIsNullAndExplanationPromptedAtIsNotNullOrderByExplanationPromptedAtDescCreatedAtDesc(
            Long workerUserId,
            WorkerRiskIncidentStatus status,
            WorkerRiskResolutionAction resolutionAction
    );

    @Query("""
            SELECT i
            FROM WorkerRiskIncident i
            WHERE i.status = :status
              AND i.resolutionAction = :resolutionAction
              AND i.workerExplanationAt IS NULL
              AND i.explanationPromptedAt IS NOT NULL
              AND EXISTS (
                    SELECT 1
                    FROM com.hunt.otziv.u_users.model.User u
                    WHERE u.id = i.workerUserId
                      AND u.active = true
                      AND u.workerTelegramGroupChatId = :chatId
              )
            ORDER BY i.explanationPromptedAt DESC, i.createdAt DESC
            """)
    List<WorkerRiskIncident> findPendingExplanationByWorkerGroupChatId(
            @Param("chatId") Long chatId,
            @Param("status") WorkerRiskIncidentStatus status,
            @Param("resolutionAction") WorkerRiskResolutionAction resolutionAction,
            Pageable pageable
    );

    @Modifying
    long deleteByStatusNotAndCreatedAtBefore(WorkerRiskIncidentStatus status, LocalDateTime cutoff);
}
