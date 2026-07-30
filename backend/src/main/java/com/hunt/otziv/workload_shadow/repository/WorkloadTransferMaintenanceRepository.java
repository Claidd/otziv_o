package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferExecutionEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Bounded self-healing and retention for the protected transfer contour.
 */
public interface WorkloadTransferMaintenanceRepository
        extends Repository<WorkloadTransferExecutionEntity, Long> {

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'RETRY',
                processing_token = NULL,
                processing_lease_until = NULL,
                next_attempt_at = :now,
                last_error_code = 'STALE_DELIVERY_LEASE',
                last_error = 'Просроченная блокировка доставки восстановлена самодиагностикой',
                updated_at = :now
            WHERE status = 'SENDING'
              AND processing_lease_until < :now
            ORDER BY workload_transfer_offer_id
            LIMIT :rowLimit
            """, nativeQuery = true)
    int retryStaleOfferDeliveries(
            @Param("now") LocalDateTime now,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_emergency_assignments
            SET status = 'NOTIFY_RETRY',
                notification_processing_token = NULL,
                notification_lease_until = NULL,
                notification_next_attempt_at = :now,
                last_error = 'Просроченная блокировка уведомления восстановлена самодиагностикой',
                updated_at = :now
            WHERE status = 'NOTIFYING'
              AND notification_lease_until < :now
            ORDER BY workload_transfer_emergency_assignment_id
            LIMIT :rowLimit
            """, nativeQuery = true)
    int retryStaleEmergencyNotifications(
            @Param("now") LocalDateTime now,
            @Param("rowLimit") int rowLimit
    );

    @Query(value = """
            SELECT offer.workload_transfer_offer_id
            FROM workload_transfer_offers offer
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = offer.workflow_id
            JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workload_transfer_workflow_candidate_id =
                 offer.workflow_candidate_id
            WHERE offer.status = 'READY'
              AND offer.updated_at < :staleBefore
              AND workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND workflow.current_offer_id IS NULL
              AND candidate.workflow_id =
                  workflow.workload_transfer_workflow_id
              AND candidate.status = 'WAITING'
            ORDER BY offer.workload_transfer_offer_id
            LIMIT :rowLimit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockOrphanReadyOfferIds(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflow_candidates candidate
            JOIN workload_transfer_offers offer
              ON offer.workflow_candidate_id =
                 candidate.workload_transfer_workflow_candidate_id
            SET candidate.status = 'DELIVERY_FAILED',
                candidate.last_responded_at = :now,
                candidate.response_reason =
                    'Оторванное READY-предложение отменено самодиагностикой',
                candidate.updated_at = :now
            WHERE offer.workload_transfer_offer_id IN (:offerIds)
              AND offer.status = 'READY'
              AND candidate.status = 'WAITING'
            """, nativeQuery = true)
    int closeCandidatesForOrphanReadyOffers(
            @Param("offerIds") List<Long> offerIds,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'CANCELLED',
                responded_at = COALESCE(responded_at, :now),
                response_reason =
                    'Оторванное READY-предложение отменено самодиагностикой',
                next_attempt_at = NULL,
                processing_token = NULL,
                processing_lease_until = NULL,
                last_error_code = 'ORPHAN_READY_OFFER',
                last_error =
                    'Предложение не было атомарно связано с workflow и кандидатом',
                updated_at = :now
            WHERE workload_transfer_offer_id IN (:offerIds)
              AND status = 'READY'
            """, nativeQuery = true)
    int cancelOrphanReadyOffers(
            @Param("offerIds") List<Long> offerIds,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT workflow.workload_transfer_workflow_id
            FROM workload_transfer_workflows workflow
            WHERE workflow.active = TRUE
              AND workflow.decision_date < :today
              AND workflow.status IN (
                    'READY_TO_OFFER',
                    'OFFERED',
                    'ACCEPTED',
                    'AWAITING_OWNER_CONFIRMATION',
                    'STAFFING_REQUIRED'
              )
            ORDER BY workload_transfer_workflow_id
            LIMIT :rowLimit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockExpiredWorkflowIds(
            @Param("today") LocalDate today,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers
            SET status = 'CANCELLED',
                responded_at = COALESCE(responded_at, :now),
                response_reason = LEFT(
                    CONCAT_WS(
                        '; ',
                        NULLIF(response_reason, ''),
                        'Workflow закрыт после смены рабочего дня'
                    ),
                    500
                ),
                next_attempt_at = NULL,
                processing_token = NULL,
                processing_lease_until = NULL,
                last_error_code = 'DECISION_DAY_EXPIRED',
                last_error =
                    'Предложение отменено при завершении просроченного workflow',
                updated_at = :now
            WHERE workflow_id IN (:workflowIds)
              AND status IN ('READY', 'RETRY', 'SENDING', 'OFFERED', 'ACCEPTED')
            """, nativeQuery = true)
    int closeOffersForExpiredWorkflows(
            @Param("workflowIds") List<Long> workflowIds,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflow_candidates
            SET status = 'CANCELLED',
                last_responded_at = COALESCE(last_responded_at, :now),
                response_reason = LEFT(
                    CONCAT_WS(
                        '; ',
                        NULLIF(response_reason, ''),
                        'Workflow закрыт после смены рабочего дня'
                    ),
                    500
                ),
                updated_at = :now
            WHERE workflow_id IN (:workflowIds)
              AND status IN ('WAITING', 'OFFERED', 'ACCEPTED')
            """, nativeQuery = true)
    int closeCandidatesForExpiredWorkflows(
            @Param("workflowIds") List<Long> workflowIds,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT
              (
                SELECT COUNT(*)
                FROM workload_transfer_offers offer
                WHERE offer.workflow_id IN (:workflowIds)
                  AND offer.status IN (
                        'READY',
                        'RETRY',
                        'SENDING',
                        'OFFERED',
                        'ACCEPTED'
                  )
              )
              +
              (
                SELECT COUNT(*)
                FROM workload_transfer_workflow_candidates candidate
                WHERE candidate.workflow_id IN (:workflowIds)
                  AND candidate.status IN ('WAITING', 'OFFERED', 'ACCEPTED')
              )
            """, nativeQuery = true)
    long countOpenChildrenForWorkflows(
            @Param("workflowIds") List<Long> workflowIds
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows
            SET status = 'CANCELLED_EXPIRED',
                active = FALSE,
                current_offer_id = NULL,
                accepted_worker_id = NULL,
                last_error_code = 'DECISION_DAY_EXPIRED',
                last_error_message =
                    'Workflow закрыт самодиагностикой после смены рабочего дня',
                last_transition_at = :now,
                resolved_at = :now,
                updated_at = :now
            WHERE workload_transfer_workflow_id IN (:workflowIds)
              AND active = TRUE
              AND decision_date < :today
              AND status IN (
                    'READY_TO_OFFER',
                    'OFFERED',
                    'ACCEPTED',
                    'AWAITING_OWNER_CONFIRMATION',
                    'STAFFING_REQUIRED'
              )
            """, nativeQuery = true)
    int cancelExpiredWorkflows(
            @Param("workflowIds") List<Long> workflowIds,
            @Param("today") LocalDate today,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_transfer_assignment_audit
            WHERE workload_transfer_assignment_audit_id IN (
                SELECT batch.audit_id
                FROM (
                    SELECT audit.workload_transfer_assignment_audit_id AS audit_id
                    FROM workload_transfer_assignment_audit audit
                    JOIN workload_transfer_executions execution
                      ON execution.workload_transfer_execution_id =
                         audit.execution_id
                    WHERE execution.status IN (
                            'APPLIED',
                            'ROLLED_BACK',
                            'FAILED',
                            'BLOCKED'
                          )
                      AND execution.updated_at < :cutoff
                    ORDER BY audit.workload_transfer_assignment_audit_id
                    LIMIT :rowLimit
                ) batch
            )
            """, nativeQuery = true)
    int deleteOldAssignmentAudit(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_transfer_executions
            WHERE workload_transfer_execution_id IN (
                SELECT batch.execution_id
                FROM (
                    SELECT execution.workload_transfer_execution_id AS execution_id
                    FROM workload_transfer_executions execution
                    WHERE execution.status IN (
                            'APPLIED',
                            'ROLLED_BACK',
                            'FAILED',
                            'BLOCKED'
                          )
                      AND execution.updated_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1
                          FROM workload_transfer_assignment_audit audit
                          WHERE audit.execution_id =
                                execution.workload_transfer_execution_id
                      )
                    ORDER BY execution.workload_transfer_execution_id
                    LIMIT :rowLimit
                ) batch
            )
            """, nativeQuery = true)
    int deleteOldExecutions(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_transfer_workflows
            WHERE workload_transfer_workflow_id IN (
                SELECT batch.workflow_id
                FROM (
                    SELECT workflow.workload_transfer_workflow_id AS workflow_id
                    FROM workload_transfer_workflows workflow
                    WHERE workflow.active = FALSE
                      AND workflow.updated_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1
                          FROM workload_transfer_executions execution
                          WHERE execution.workflow_id =
                                workflow.workload_transfer_workflow_id
                      )
                    ORDER BY workflow.workload_transfer_workflow_id
                    LIMIT :rowLimit
                ) batch
            )
            """, nativeQuery = true)
    int deleteOldWorkflows(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("rowLimit") int rowLimit
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_transfer_emergency_assignments
            WHERE workload_transfer_emergency_assignment_id IN (
                SELECT batch.assignment_id
                FROM (
                    SELECT emergency.workload_transfer_emergency_assignment_id
                               AS assignment_id
                    FROM workload_transfer_emergency_assignments emergency
                    WHERE emergency.status IN (
                            'NOTIFIED',
                            'NOTIFY_FAILED',
                            'ROLLED_BACK',
                            'BLOCKED',
                            'FAILED'
                          )
                      AND emergency.updated_at < :cutoff
                    ORDER BY emergency.workload_transfer_emergency_assignment_id
                    LIMIT :rowLimit
                ) batch
            )
            """, nativeQuery = true)
    int deleteOldEmergencyAssignments(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("rowLimit") int rowLimit
    );
}
