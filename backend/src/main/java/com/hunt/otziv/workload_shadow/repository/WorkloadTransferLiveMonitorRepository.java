package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferWorkflowEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadTransferLiveMonitorRepository
        extends Repository<WorkloadTransferWorkflowEntity, Long> {

    @Query(value = """
            SELECT workflow.workload_transfer_workflow_id AS workflowId,
                   workflow.workflow_key AS workflowKey,
                   workflow.mode AS mode,
                   workflow.status AS status,
                   workflow.manager_id AS managerId,
                   COALESCE(
                     NULLIF(TRIM(manager_user.fio), ''),
                     manager_user.username,
                     CONCAT('Менеджер #', workflow.manager_id)
                   ) AS managerName,
                   workflow.source_worker_id AS sourceWorkerId,
                   COALESCE(
                     NULLIF(TRIM(source_user.fio), ''),
                     source_user.username,
                     CONCAT('Специалист #', workflow.source_worker_id)
                   ) AS sourceWorkerName,
                   workflow.accepted_worker_id AS targetWorkerId,
                   CASE
                     WHEN workflow.accepted_worker_id IS NULL THEN NULL
                     ELSE COALESCE(
                       NULLIF(TRIM(target_user.fio), ''),
                       target_user.username,
                       CONCAT('Специалист #', workflow.accepted_worker_id)
                     )
                   END AS targetWorkerName,
                   workflow.company_id AS companyId,
                   workflow.company_title AS companyTitle,
                   workflow.failure_number AS failureNumber,
                   workflow.transfer_percent AS transferPercent,
                   workflow.problem_units AS problemUnits,
                   workflow.estimated_minutes AS estimatedMinutes,
                   workflow.active_order_count AS activeOrderCount,
                   workflow.new_unit_count AS newUnitCount,
                   workflow.correction_count AS correctionCount,
                   workflow.nagul_count AS nagulCount,
                   workflow.publish_count AS publishCount,
                   workflow.recovery_count AS recoveryCount,
                   workflow.bad_count AS badCount,
                   workflow.owner_confirmation_required AS ownerConfirmationRequired,
                   workflow.owner_confirmed_at AS ownerConfirmedAt,
                   workflow.last_error_code AS lastErrorCode,
                   workflow.last_error_message AS lastErrorMessage,
                   workflow.decision_date AS decisionDate,
                   workflow.last_transition_at AS lastTransitionAt,
                   workflow.created_at AS createdAt,
                   current_offer.expires_at AS currentOfferExpiresAt,
                   COUNT(DISTINCT candidate.workload_transfer_workflow_candidate_id)
                       AS candidateCount,
                   SUM(CASE WHEN candidate.status = 'DECLINED' THEN 1 ELSE 0 END)
                       AS declinedCandidateCount,
                   SUM(CASE WHEN candidate.status IN (
                                'UNAVAILABLE',
                                'EXPIRED',
                                'DELIVERY_FAILED'
                            )
                            THEN 1 ELSE 0 END)
                       AS unavailableCandidateCount
            FROM workload_transfer_workflows workflow
            JOIN managers manager
              ON manager.manager_id = workflow.manager_id
            JOIN users manager_user
              ON manager_user.id = manager.user_id
            JOIN workers source_worker
              ON source_worker.worker_id = workflow.source_worker_id
            JOIN users source_user
              ON source_user.id = source_worker.user_id
            LEFT JOIN workers target_worker
              ON target_worker.worker_id = workflow.accepted_worker_id
            LEFT JOIN users target_user
              ON target_user.id = target_worker.user_id
            LEFT JOIN workload_transfer_workflow_candidates candidate
              ON candidate.workflow_id =
                 workflow.workload_transfer_workflow_id
            LEFT JOIN workload_transfer_offers current_offer
              ON current_offer.workload_transfer_offer_id =
                 workflow.current_offer_id
            WHERE (:managerId IS NULL OR workflow.manager_id = :managerId)
            GROUP BY workflow.workload_transfer_workflow_id,
                     workflow.workflow_key,
                     workflow.mode,
                     workflow.status,
                     workflow.manager_id,
                     manager_user.fio,
                     manager_user.username,
                     workflow.source_worker_id,
                     source_user.fio,
                     source_user.username,
                     workflow.accepted_worker_id,
                     target_user.fio,
                     target_user.username,
                     workflow.company_id,
                     workflow.company_title,
                     workflow.failure_number,
                     workflow.transfer_percent,
                     workflow.problem_units,
                     workflow.estimated_minutes,
                     workflow.active_order_count,
                     workflow.new_unit_count,
                     workflow.correction_count,
                     workflow.nagul_count,
                     workflow.publish_count,
                     workflow.recovery_count,
                     workflow.bad_count,
                     workflow.owner_confirmation_required,
                     workflow.owner_confirmed_at,
                     workflow.last_error_code,
                     workflow.last_error_message,
                     workflow.decision_date,
                     workflow.last_transition_at,
                     workflow.created_at,
                     current_offer.expires_at
            ORDER BY workflow.created_at DESC,
                     workflow.workload_transfer_workflow_id DESC
            LIMIT :rowLimit
            """, nativeQuery = true)
    List<WorkflowProjection> findWorkflows(
            @Param("managerId") Long managerId,
            @Param("rowLimit") int rowLimit
    );

    @Query(value = """
            SELECT execution.workload_transfer_execution_id AS executionId,
                   execution.workflow_id AS workflowId,
                   execution.status AS status,
                   execution.manager_id AS managerId,
                   COALESCE(
                     NULLIF(TRIM(manager_user.fio), ''),
                     manager_user.username,
                     CONCAT('Менеджер #', execution.manager_id)
                   ) AS managerName,
                   execution.source_worker_id AS sourceWorkerId,
                   COALESCE(
                     NULLIF(TRIM(source_user.fio), ''),
                     source_user.username,
                     CONCAT('Специалист #', execution.source_worker_id)
                   ) AS sourceWorkerName,
                   execution.target_worker_id AS targetWorkerId,
                   COALESCE(
                     NULLIF(TRIM(target_user.fio), ''),
                     target_user.username,
                     CONCAT('Специалист #', execution.target_worker_id)
                   ) AS targetWorkerName,
                   execution.company_id AS companyId,
                   company.company_title AS companyTitle,
                   execution.transferred_order_count AS orderCount,
                   execution.transferred_review_count AS reviewCount,
                   execution.transferred_bad_task_count AS badTaskCount,
                   execution.transferred_recovery_task_count AS recoveryTaskCount,
                   execution.started_at AS startedAt,
                   execution.applied_at AS appliedAt,
                   execution.rollback_deadline_at AS rollbackDeadlineAt,
                   execution.rolled_back_at AS rolledBackAt,
                   execution.error_code AS errorCode,
                   execution.error_message AS errorMessage
            FROM workload_transfer_executions execution
            JOIN managers manager
              ON manager.manager_id = execution.manager_id
            JOIN users manager_user
              ON manager_user.id = manager.user_id
            JOIN workers source_worker
              ON source_worker.worker_id = execution.source_worker_id
            JOIN users source_user
              ON source_user.id = source_worker.user_id
            JOIN workers target_worker
              ON target_worker.worker_id = execution.target_worker_id
            JOIN users target_user
              ON target_user.id = target_worker.user_id
            JOIN companies company
              ON company.company_id = execution.company_id
            WHERE (:managerId IS NULL OR execution.manager_id = :managerId)
            ORDER BY execution.created_at DESC,
                     execution.workload_transfer_execution_id DESC
            LIMIT :rowLimit
            """, nativeQuery = true)
    List<ExecutionProjection> findExecutions(
            @Param("managerId") Long managerId,
            @Param("rowLimit") int rowLimit
    );

    @Query(value = """
            SELECT emergency.workload_transfer_emergency_assignment_id
                       AS assignmentId,
                   emergency.mode AS mode,
                   emergency.status AS status,
                   emergency.source_manager_id AS sourceManagerId,
                   COALESCE(
                     NULLIF(TRIM(source_manager_user.fio), ''),
                     source_manager_user.username,
                     CONCAT('Менеджер #', emergency.source_manager_id)
                   ) AS sourceManagerName,
                   emergency.source_worker_id AS sourceWorkerId,
                   COALESCE(
                     NULLIF(TRIM(source_user.fio), ''),
                     source_user.username,
                     CONCAT('Специалист #', emergency.source_worker_id)
                   ) AS sourceWorkerName,
                   emergency.target_manager_id AS targetManagerId,
                   COALESCE(
                     NULLIF(TRIM(target_manager_user.fio), ''),
                     target_manager_user.username,
                     CONCAT('Менеджер #', emergency.target_manager_id)
                   ) AS targetManagerName,
                   emergency.target_worker_id AS targetWorkerId,
                   COALESCE(
                     NULLIF(TRIM(target_user.fio), ''),
                     target_user.username,
                     CONCAT('Специалист #', emergency.target_worker_id)
                   ) AS targetWorkerName,
                   emergency.company_id AS companyId,
                   company.company_title AS companyTitle,
                   emergency.review_id AS reviewId,
                   emergency.reason AS reason,
                   emergency.target_notification_status
                       AS targetNotificationStatus,
                   emergency.audit_notification_status
                       AS auditNotificationStatus,
                   emergency.notification_attempts AS notificationAttempts,
                   emergency.decision_date AS decisionDate,
                   emergency.applied_at AS appliedAt,
                   emergency.rollback_deadline_at AS rollbackDeadlineAt,
                   emergency.rolled_back_at AS rolledBackAt,
                   emergency.last_error AS lastError
            FROM workload_transfer_emergency_assignments emergency
            JOIN managers source_manager
              ON source_manager.manager_id = emergency.source_manager_id
            JOIN users source_manager_user
              ON source_manager_user.id = source_manager.user_id
            JOIN workers source_worker
              ON source_worker.worker_id = emergency.source_worker_id
            JOIN users source_user ON source_user.id = source_worker.user_id
            JOIN managers target_manager
              ON target_manager.manager_id = emergency.target_manager_id
            JOIN users target_manager_user
              ON target_manager_user.id = target_manager.user_id
            JOIN workers target_worker
              ON target_worker.worker_id = emergency.target_worker_id
            JOIN users target_user ON target_user.id = target_worker.user_id
            JOIN companies company ON company.company_id = emergency.company_id
            WHERE (
                    :managerId IS NULL
                    OR emergency.source_manager_id = :managerId
                    OR emergency.target_manager_id = :managerId
                  )
            ORDER BY emergency.created_at DESC,
                     emergency.workload_transfer_emergency_assignment_id DESC
            LIMIT :rowLimit
            """, nativeQuery = true)
    List<EmergencyAssignmentProjection> findEmergencyAssignments(
            @Param("managerId") Long managerId,
            @Param("rowLimit") int rowLimit
    );

    interface WorkflowProjection {
        Long getWorkflowId();
        String getWorkflowKey();
        String getMode();
        String getStatus();
        Long getManagerId();
        String getManagerName();
        Long getSourceWorkerId();
        String getSourceWorkerName();
        Long getTargetWorkerId();
        String getTargetWorkerName();
        Long getCompanyId();
        String getCompanyTitle();
        Integer getFailureNumber();
        Integer getTransferPercent();
        Long getProblemUnits();
        Long getEstimatedMinutes();
        Long getActiveOrderCount();
        Long getNewUnitCount();
        Long getCorrectionCount();
        Long getNagulCount();
        Long getPublishCount();
        Long getRecoveryCount();
        Long getBadCount();
        Boolean getOwnerConfirmationRequired();
        LocalDateTime getOwnerConfirmedAt();
        String getLastErrorCode();
        String getLastErrorMessage();
        java.time.LocalDate getDecisionDate();
        LocalDateTime getLastTransitionAt();
        LocalDateTime getCreatedAt();
        LocalDateTime getCurrentOfferExpiresAt();
        Long getCandidateCount();
        Long getDeclinedCandidateCount();
        Long getUnavailableCandidateCount();
    }

    interface ExecutionProjection {
        Long getExecutionId();
        Long getWorkflowId();
        String getStatus();
        Long getManagerId();
        String getManagerName();
        Long getSourceWorkerId();
        String getSourceWorkerName();
        Long getTargetWorkerId();
        String getTargetWorkerName();
        Long getCompanyId();
        String getCompanyTitle();
        Integer getOrderCount();
        Integer getReviewCount();
        Integer getBadTaskCount();
        Integer getRecoveryTaskCount();
        LocalDateTime getStartedAt();
        LocalDateTime getAppliedAt();
        LocalDateTime getRollbackDeadlineAt();
        LocalDateTime getRolledBackAt();
        String getErrorCode();
        String getErrorMessage();
    }

    interface EmergencyAssignmentProjection {
        Long getAssignmentId();
        String getMode();
        String getStatus();
        Long getSourceManagerId();
        String getSourceManagerName();
        Long getSourceWorkerId();
        String getSourceWorkerName();
        Long getTargetManagerId();
        String getTargetManagerName();
        Long getTargetWorkerId();
        String getTargetWorkerName();
        Long getCompanyId();
        String getCompanyTitle();
        Long getReviewId();
        String getReason();
        String getTargetNotificationStatus();
        String getAuditNotificationStatus();
        Integer getNotificationAttempts();
        java.time.LocalDate getDecisionDate();
        LocalDateTime getAppliedAt();
        LocalDateTime getRollbackDeadlineAt();
        LocalDateTime getRolledBackAt();
        String getLastError();
    }
}
