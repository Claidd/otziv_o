package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferExecutionEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Set-based storage and guarded mutations for one-card emergency assignments.
 */
public interface WorkloadEmergencyAssignmentRepository
        extends Repository<WorkloadTransferExecutionEntity, Long> {

    @Query(value = """
            SELECT transfer_case.workload_shadow_transfer_case_id AS shadowCaseId,
                   transfer_case.manager_id AS sourceManagerId,
                   transfer_case.source_worker_id AS sourceWorkerId,
                   transfer_case.company_id AS companyId,
                   transfer_case.company_title AS companyTitle,
                   transfer_case.fallback_review_id AS reviewId,
                   (
                       SELECT MIN(workflow.workload_transfer_workflow_id)
                       FROM workload_transfer_workflows workflow
                       WHERE workflow.shadow_case_id =
                             transfer_case.workload_shadow_transfer_case_id
                         AND workflow.active = TRUE
                         AND workflow.status = 'STAFFING_REQUIRED'
                   ) AS exhaustedWorkflowId
            FROM workload_shadow_transfer_cases transfer_case
            JOIN reviews review
              ON review.review_id = transfer_case.fallback_review_id
             AND review.review_worker = transfer_case.source_worker_id
             AND COALESCE(review.review_publish, 0) = 0
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders orders
              ON orders.order_id = detail.order_detail_order
             AND orders.order_company = transfer_case.company_id
             AND COALESCE(orders.order_complete, 0) = 0
            WHERE transfer_case.active = TRUE
              AND transfer_case.graph_error_count = 0
              AND transfer_case.fallback_review_id IS NOT NULL
              AND (
                    transfer_case.staffing_required = TRUE
                    OR EXISTS (
                        SELECT 1
                        FROM workload_transfer_workflows exhausted
                        WHERE exhausted.shadow_case_id =
                              transfer_case.workload_shadow_transfer_case_id
                          AND exhausted.active = TRUE
                          AND exhausted.status = 'STAFFING_REQUIRED'
                    )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_emergency_assignments existing
                  WHERE existing.shadow_case_id =
                        transfer_case.workload_shadow_transfer_case_id
                    AND existing.review_id =
                        transfer_case.fallback_review_id
              )
            ORDER BY transfer_case.failure_number DESC,
                     transfer_case.selection_rank,
                     transfer_case.workload_shadow_transfer_case_id
            """, nativeQuery = true)
    List<EmergencyCaseProjection> findReadyCases();

    @Query(value = """
            SELECT current.worker_id AS workerId,
                   current.manager_id AS managerId,
                   current.rating AS rating,
                   (
                       SELECT COUNT(*)
                       FROM workload_transfer_emergency_assignments recent
                       WHERE recent.target_worker_id = current.worker_id
                         AND recent.decision_date = :today
                         AND recent.status IN (
                               'APPLIED',
                               'NOTIFYING',
                               'NOTIFIED',
                               'NOTIFY_RETRY',
                               'NOTIFY_FAILED'
                         )
                   ) AS emergencyAssignmentsToday,
                   user.worker_telegram_group_chat_id AS targetGroupChatId,
                   COALESCE(
                       NULLIF(TRIM(user.fio), ''),
                       NULLIF(TRIM(user.username), ''),
                       CONCAT('Специалист #', current.worker_id)
                   ) AS workerName
            FROM workload_shadow_worker_current current
            JOIN workers worker ON worker.worker_id = current.worker_id
            JOIN users user ON user.id = worker.user_id
            WHERE current.recipient_eligible = TRUE
              AND current.accepts_company_transfers = TRUE
              AND current.worker_group_connected = TRUE
              AND current.rating >= :minimumRating
              AND user.worker_telegram_group_chat_id < 0
            ORDER BY current.worker_id
            """, nativeQuery = true)
    List<EmergencyRecipientProjection> findEligibleRecipients(
            @Param("minimumRating") BigDecimal minimumRating,
            @Param("today") LocalDate today
    );

    @Query(value = """
            SELECT workflow.shadow_case_id AS shadowCaseId,
                   candidate.worker_id AS workerId
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workflow_id =
                 workflow.workload_transfer_workflow_id
            WHERE workflow.shadow_case_id IN (:shadowCaseIds)
            GROUP BY workflow.shadow_case_id,
                     candidate.worker_id
            """, nativeQuery = true)
    List<WorkflowCandidatePairProjection> findWorkflowCandidatePairs(
            @Param("shadowCaseIds") Collection<Long> shadowCaseIds
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_emergency_assignments (
                assignment_key,
                shadow_case_id,
                exhausted_workflow_id,
                source_manager_id,
                source_worker_id,
                company_id,
                review_id,
                target_manager_id,
                target_worker_id,
                target_group_chat_id,
                audit_group_chat_id,
                mode,
                status,
                reason,
                review_bot_id,
                review_vigul_before,
                review_text_ready_at_before,
                review_text_hash_before,
                target_notification_status,
                audit_notification_status,
                notification_next_attempt_at,
                decision_date,
                rollback_deadline_at,
                created_at,
                updated_at
            )
            SELECT :assignmentKey,
                   transfer_case.workload_shadow_transfer_case_id,
                   :exhaustedWorkflowId,
                   transfer_case.manager_id,
                   transfer_case.source_worker_id,
                   transfer_case.company_id,
                   review.review_id,
                   target_current.manager_id,
                   target_current.worker_id,
                   target_user.worker_telegram_group_chat_id,
                   :auditGroupChatId,
                   :mode,
                   'PREPARED',
                   :reason,
                   review.review_bot,
                   COALESCE(review.review_vigul, 0),
                   review.review_text_ready_at,
                   SHA2(COALESCE(review.review_text, ''), 256),
                   'PENDING',
                   'PENDING',
                   :now,
                   :decisionDate,
                   :rollbackDeadline,
                   :now,
                   :now
            FROM workload_shadow_transfer_cases transfer_case
            JOIN reviews review
              ON review.review_id = :reviewId
             AND review.review_worker = transfer_case.source_worker_id
             AND COALESCE(review.review_publish, 0) = 0
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders orders
              ON orders.order_id = detail.order_detail_order
             AND orders.order_company = transfer_case.company_id
             AND COALESCE(orders.order_complete, 0) = 0
            JOIN workload_shadow_worker_current target_current
              ON target_current.worker_id = :targetWorkerId
             AND target_current.recipient_eligible = TRUE
             AND target_current.accepts_company_transfers = TRUE
             AND target_current.worker_group_connected = TRUE
             AND target_current.rating >= :minimumRating
            JOIN workers target_worker
              ON target_worker.worker_id = target_current.worker_id
            JOIN users target_user ON target_user.id = target_worker.user_id
             AND target_user.worker_telegram_group_chat_id =
                 :targetGroupChatId
             AND target_user.worker_telegram_group_chat_id < 0
            WHERE transfer_case.workload_shadow_transfer_case_id = :shadowCaseId
              AND transfer_case.active = TRUE
              AND transfer_case.graph_error_count = 0
              AND transfer_case.fallback_review_id = :reviewId
              AND (
                    (
                        :exhaustedWorkflowId IS NULL
                        AND transfer_case.staffing_required = TRUE
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM workload_transfer_workflows exhausted
                        WHERE exhausted.workload_transfer_workflow_id =
                              :exhaustedWorkflowId
                          AND exhausted.shadow_case_id =
                              transfer_case.workload_shadow_transfer_case_id
                          AND exhausted.active = TRUE
                          AND exhausted.status = 'STAFFING_REQUIRED'
                    )
              )
              AND target_current.worker_id <> transfer_case.source_worker_id
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflows prior_workflow
                  JOIN workload_transfer_workflow_candidates prior_candidate
                    ON prior_candidate.workflow_id =
                       prior_workflow.workload_transfer_workflow_id
                  WHERE prior_workflow.shadow_case_id =
                        transfer_case.workload_shadow_transfer_case_id
                    AND prior_candidate.worker_id = :targetWorkerId
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_emergency_assignments existing
                  WHERE existing.shadow_case_id =
                        transfer_case.workload_shadow_transfer_case_id
                    AND existing.review_id = review.review_id
              )
            """, nativeQuery = true)
    int insertPrepared(
            @Param("assignmentKey") String assignmentKey,
            @Param("shadowCaseId") long shadowCaseId,
            @Param("exhaustedWorkflowId") Long exhaustedWorkflowId,
            @Param("reviewId") long reviewId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("targetGroupChatId") long targetGroupChatId,
            @Param("auditGroupChatId") long auditGroupChatId,
            @Param("minimumRating") BigDecimal minimumRating,
            @Param("mode") String mode,
            @Param("reason") String reason,
            @Param("decisionDate") LocalDate decisionDate,
            @Param("rollbackDeadline") LocalDateTime rollbackDeadline,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT assignment.workload_transfer_emergency_assignment_id AS assignmentId,
                   assignment.source_worker_id AS sourceWorkerId,
                   assignment.target_worker_id AS targetWorkerId,
                   assignment.company_id AS companyId,
                   assignment.review_id AS reviewId,
                   assignment.exhausted_workflow_id AS exhaustedWorkflowId
            FROM workload_transfer_emergency_assignments assignment
            WHERE assignment.assignment_key = :assignmentKey
              AND assignment.status = 'PREPARED'
            """, nativeQuery = true)
    Optional<PreparedProjection> findPrepared(
            @Param("assignmentKey") String assignmentKey
    );

    @Modifying
    @Query(value = """
            UPDATE reviews review
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders orders
              ON orders.order_id = detail.order_detail_order
            SET review.review_worker = :targetWorkerId,
                review.row_version = review.row_version + 1
            WHERE review.review_id = :reviewId
              AND review.review_worker = :sourceWorkerId
              AND COALESCE(review.review_publish, 0) = 0
              AND COALESCE(orders.order_complete, 0) = 0
              AND orders.order_company = :companyId
            """, nativeQuery = true)
    int transferReview(
            @Param("reviewId") long reviewId,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments assignment
            SET assignment.status = 'APPLIED',
                assignment.target_company_link_added = :linkAdded,
                assignment.applied_at = :now,
                assignment.updated_at = :now
            WHERE assignment.workload_transfer_emergency_assignment_id =
                  :assignmentId
              AND assignment.status = 'PREPARED'
            """, nativeQuery = true)
    int markApplied(
            @Param("assignmentId") long assignmentId,
            @Param("linkAdded") boolean linkAdded,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows
            SET status = 'EMERGENCY_APPLIED',
                active = FALSE,
                current_offer_id = NULL,
                last_transition_at = :now,
                resolved_at = :now,
                updated_at = :now
            WHERE workload_transfer_workflow_id = :workflowId
              AND active = TRUE
              AND status = 'STAFFING_REQUIRED'
            """, nativeQuery = true)
    int markExhaustedWorkflowEmergencyApplied(
            @Param("workflowId") long workflowId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET status = 'NOTIFYING',
                notification_processing_token = :processingToken,
                notification_lease_until = :leaseUntil,
                notification_attempts = notification_attempts + 1,
                updated_at = :now
            WHERE status IN ('APPLIED', 'NOTIFY_RETRY')
              AND notification_next_attempt_at <= :now
              AND (
                    notification_lease_until IS NULL
                    OR notification_lease_until < :now
              )
              AND (
                    target_notification_status <> 'SENT'
                    OR audit_notification_status <> 'SENT'
              )
            ORDER BY workload_transfer_emergency_assignment_id
            LIMIT :rowLimit
            """, nativeQuery = true)
    int claimNotifications(
            @Param("processingToken") String processingToken,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("rowLimit") int rowLimit
    );

    @Query(value = """
            SELECT assignment.workload_transfer_emergency_assignment_id AS assignmentId,
                   assignment.target_group_chat_id AS targetGroupChatId,
                   assignment.audit_group_chat_id AS auditGroupChatId,
                   assignment.target_notification_status AS targetNotificationStatus,
                   assignment.audit_notification_status AS auditNotificationStatus,
                   assignment.notification_attempts AS notificationAttempts,
                   assignment.review_id AS reviewId,
                   company.company_title AS companyTitle,
                   COALESCE(
                       NULLIF(TRIM(source_user.fio), ''),
                       source_user.username,
                       CONCAT('Специалист #', assignment.source_worker_id)
                   ) AS sourceWorkerName,
                   COALESCE(
                       NULLIF(TRIM(target_user.fio), ''),
                       target_user.username,
                       CONCAT('Специалист #', assignment.target_worker_id)
                   ) AS targetWorkerName
            FROM workload_transfer_emergency_assignments assignment
            JOIN companies company ON company.company_id = assignment.company_id
            JOIN workers source_worker
              ON source_worker.worker_id = assignment.source_worker_id
            JOIN users source_user ON source_user.id = source_worker.user_id
            JOIN workers target_worker
              ON target_worker.worker_id = assignment.target_worker_id
            JOIN users target_user ON target_user.id = target_worker.user_id
            WHERE assignment.notification_processing_token = :processingToken
              AND assignment.status = 'NOTIFYING'
            ORDER BY assignment.workload_transfer_emergency_assignment_id
            """, nativeQuery = true)
    List<NotificationProjection> findClaimedNotifications(
            @Param("processingToken") String processingToken
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET target_notification_status = 'SENT',
                updated_at = :now
            WHERE workload_transfer_emergency_assignment_id = :assignmentId
              AND notification_processing_token = :processingToken
              AND status = 'NOTIFYING'
            """, nativeQuery = true)
    int markTargetNotificationSent(
            @Param("assignmentId") long assignmentId,
            @Param("processingToken") String processingToken,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET audit_notification_status = 'SENT',
                updated_at = :now
            WHERE workload_transfer_emergency_assignment_id = :assignmentId
              AND notification_processing_token = :processingToken
              AND status = 'NOTIFYING'
            """, nativeQuery = true)
    int markAuditNotificationSent(
            @Param("assignmentId") long assignmentId,
            @Param("processingToken") String processingToken,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET status = CASE
                    WHEN target_notification_status = 'SENT'
                     AND audit_notification_status = 'SENT'
                    THEN 'NOTIFIED'
                    WHEN notification_attempts >= :maxAttempts
                    THEN 'NOTIFY_FAILED'
                    ELSE 'NOTIFY_RETRY'
                END,
                target_notification_status = CASE
                    WHEN target_notification_status = 'SENT'
                    THEN 'SENT'
                    WHEN notification_attempts >= :maxAttempts
                    THEN 'FAILED'
                    ELSE 'RETRY'
                END,
                audit_notification_status = CASE
                    WHEN audit_notification_status = 'SENT'
                    THEN 'SENT'
                    WHEN notification_attempts >= :maxAttempts
                    THEN 'FAILED'
                    ELSE 'RETRY'
                END,
                notification_processing_token = NULL,
                notification_lease_until = NULL,
                notification_next_attempt_at = :nextAttemptAt,
                last_error = :lastError,
                updated_at = :now
            WHERE workload_transfer_emergency_assignment_id = :assignmentId
              AND notification_processing_token = :processingToken
              AND status = 'NOTIFYING'
            """, nativeQuery = true)
    int finishNotificationAttempt(
            @Param("assignmentId") long assignmentId,
            @Param("processingToken") String processingToken,
            @Param("maxAttempts") int maxAttempts,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET status = 'ROLLING_BACK',
                notification_processing_token = NULL,
                notification_lease_until = NULL,
                updated_at = :now
            WHERE workload_transfer_emergency_assignment_id = :assignmentId
              AND status IN (
                    'APPLIED',
                    'NOTIFIED',
                    'NOTIFY_RETRY',
                    'NOTIFY_FAILED'
              )
              AND rollback_deadline_at >= :now
            """, nativeQuery = true)
    int claimRollback(
            @Param("assignmentId") long assignmentId,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT emergency.workload_transfer_emergency_assignment_id
                       AS assignmentId,
                   emergency.source_worker_id AS sourceWorkerId,
                   emergency.target_worker_id AS targetWorkerId,
                   emergency.company_id AS companyId,
                   emergency.review_id AS reviewId,
                   emergency.target_company_link_added AS targetCompanyLinkAdded,
                   CASE
                       WHEN review.review_worker = emergency.target_worker_id
                        AND COALESCE(review.review_publish, 0) = 0
                        AND COALESCE(review.review_vigul, 0) =
                            emergency.review_vigul_before
                        AND review.review_text_ready_at <=>
                            emergency.review_text_ready_at_before
                        AND SHA2(COALESCE(review.review_text, ''), 256) <=>
                            emergency.review_text_hash_before
                        AND review.review_bot <=> emergency.review_bot_id
                       THEN TRUE
                       ELSE FALSE
                   END AS rollbackSafe
            FROM workload_transfer_emergency_assignments emergency
            JOIN reviews review ON review.review_id = emergency.review_id
            WHERE emergency.workload_transfer_emergency_assignment_id =
                  :assignmentId
              AND emergency.status = 'ROLLING_BACK'
            """, nativeQuery = true)
    Optional<EmergencyRollbackProjection> findRollbackContext(
            @Param("assignmentId") long assignmentId
    );

    @Modifying
    @Query(value = """
            UPDATE reviews review
            JOIN workload_transfer_emergency_assignments emergency
              ON emergency.review_id = review.review_id
            SET review.review_worker = emergency.source_worker_id,
                review.row_version = review.row_version + 1
            WHERE emergency.workload_transfer_emergency_assignment_id =
                  :assignmentId
              AND emergency.status = 'ROLLING_BACK'
              AND review.review_worker = emergency.target_worker_id
              AND COALESCE(review.review_publish, 0) = 0
              AND COALESCE(review.review_vigul, 0) =
                  emergency.review_vigul_before
              AND review.review_text_ready_at <=>
                  emergency.review_text_ready_at_before
              AND SHA2(COALESCE(review.review_text, ''), 256) <=>
                  emergency.review_text_hash_before
              AND review.review_bot <=> emergency.review_bot_id
            """, nativeQuery = true)
    int rollbackReview(@Param("assignmentId") long assignmentId);

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET status = 'ROLLED_BACK',
                rolled_back_at = :now,
                updated_at = :now
            WHERE workload_transfer_emergency_assignment_id = :assignmentId
              AND status = 'ROLLING_BACK'
            """, nativeQuery = true)
    int markRolledBack(
            @Param("assignmentId") long assignmentId,
            @Param("now") LocalDateTime now
    );

    interface EmergencyCaseProjection {
        Long getShadowCaseId();
        Long getSourceManagerId();
        Long getSourceWorkerId();
        Long getCompanyId();
        String getCompanyTitle();
        Long getReviewId();
        Long getExhaustedWorkflowId();
    }

    interface EmergencyRecipientProjection {
        Long getWorkerId();
        Long getManagerId();
        BigDecimal getRating();
        Long getEmergencyAssignmentsToday();
        Long getTargetGroupChatId();
        String getWorkerName();
    }

    interface WorkflowCandidatePairProjection {
        Long getShadowCaseId();
        Long getWorkerId();
    }

    interface PreparedProjection {
        Long getAssignmentId();
        Long getSourceWorkerId();
        Long getTargetWorkerId();
        Long getCompanyId();
        Long getReviewId();
        Long getExhaustedWorkflowId();
    }

    interface NotificationProjection {
        Long getAssignmentId();
        Long getTargetGroupChatId();
        Long getAuditGroupChatId();
        String getTargetNotificationStatus();
        String getAuditNotificationStatus();
        Integer getNotificationAttempts();
        Long getReviewId();
        String getCompanyTitle();
        String getSourceWorkerName();
        String getTargetWorkerName();
    }

    interface EmergencyRollbackProjection {
        Long getAssignmentId();
        Long getSourceWorkerId();
        Long getTargetWorkerId();
        Long getCompanyId();
        Long getReviewId();
        Boolean getTargetCompanyLinkAdded();
        Boolean getRollbackSafe();
    }
}
