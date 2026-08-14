package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferWorkflowEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadTransferWorkflowRepository
        extends Repository<WorkloadTransferWorkflowEntity, Long> {

    @Query(value = """
            WITH eligible_cases AS (
                SELECT workload_shadow_transfer_case_id,
                       source_worker_id,
                       company_id
                FROM workload_shadow_transfer_cases
                WHERE active = TRUE
                  AND status = 'SHADOW_PENDING'
                  AND graph_error_count = 0
            ),
            financially_unsafe AS (
                SELECT eligible.workload_shadow_transfer_case_id AS shadow_case_id,
                       COUNT(DISTINCT unsafe_order.order_id) AS unsafe_order_count
                FROM eligible_cases eligible
                JOIN orders unsafe_order
                  ON unsafe_order.order_company = eligible.company_id
                 AND unsafe_order.order_worker = eligible.source_worker_id
                 AND unsafe_order.order_complete = FALSE
                WHERE (
                    (
                        COALESCE(unsafe_order.order_amount, 0) > 0
                        AND COALESCE(unsafe_order.order_counter, 0) >=
                            COALESCE(unsafe_order.order_amount, 0)
                    )
                    OR unsafe_order.order_pay_day IS NOT NULL
                    OR EXISTS (
                        SELECT 1
                        FROM zp unsafe_salary
                        WHERE unsafe_salary.zp_order = unsafe_order.order_id
                          AND COALESCE(unsafe_salary.zp_active, 0) = 1
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM payment_check unsafe_check
                        WHERE unsafe_check.check_order = unsafe_order.order_id
                          AND COALESCE(unsafe_check.check_active, 0) = 1
                    )
                )
                GROUP BY eligible.workload_shadow_transfer_case_id
            )
            SELECT transfer_case.workload_shadow_transfer_case_id AS shadowCaseId,
                   transfer_case.manager_id AS managerId,
                   transfer_case.source_worker_id AS sourceWorkerId,
                   transfer_case.company_id AS companyId,
                   transfer_case.company_title AS companyTitle,
                   transfer_case.failure_number AS failureNumber,
                   transfer_case.transfer_percent AS transferPercent,
                   transfer_case.selection_rank AS selectionRank,
                   transfer_case.problem_units AS problemUnits,
                   transfer_case.estimated_minutes AS estimatedMinutes,
                   transfer_case.active_order_count AS activeOrderCount,
                   transfer_case.new_unit_count AS newUnitCount,
                   transfer_case.correction_count AS correctionCount,
                   transfer_case.nagul_count AS nagulCount,
                   transfer_case.publish_count AS publishCount,
                   transfer_case.recovery_count AS recoveryCount,
                   transfer_case.bad_count AS badCount,
                   COALESCE(financially_unsafe.unsafe_order_count, 0)
                       AS financiallyUnsafeOrderCount,
                   candidate.worker_id AS candidateWorkerId,
                   candidate.sequence_number AS sequenceNumber,
                   candidate.rating AS rating,
                   candidate.hundred_percent_days AS hundredPercentDays,
                   candidate.failure_days AS failureDays,
                   candidate.current_estimated_minutes AS currentEstimatedMinutes,
                   candidate_user.worker_telegram_group_chat_id AS targetGroupChatId,
                   candidate_user.telegram_chat_id AS candidateTelegramId
            FROM workload_shadow_transfer_cases transfer_case
            JOIN workload_shadow_transfer_candidates candidate
              ON candidate.transfer_case_id =
                 transfer_case.workload_shadow_transfer_case_id
            JOIN eligible_cases eligible
              ON eligible.workload_shadow_transfer_case_id =
                 transfer_case.workload_shadow_transfer_case_id
            LEFT JOIN financially_unsafe
              ON financially_unsafe.shadow_case_id =
                 transfer_case.workload_shadow_transfer_case_id
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate.worker_id
             AND candidate_current.manager_id = transfer_case.manager_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate.worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
            WHERE transfer_case.active = TRUE
              AND transfer_case.status = 'SHADOW_PENDING'
              AND transfer_case.graph_error_count = 0
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
              AND candidate_user.worker_telegram_group_chat_id < 0
              AND candidate_user.telegram_chat_id > 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflows existing_workflow
                  WHERE existing_workflow.source_worker_id =
                        transfer_case.source_worker_id
                    AND existing_workflow.company_id = transfer_case.company_id
                    AND existing_workflow.active = TRUE
              )
            ORDER BY transfer_case.manager_id,
                     transfer_case.failure_number DESC,
                     transfer_case.selection_rank,
                     transfer_case.workload_shadow_transfer_case_id,
                     candidate.sequence_number
            """, nativeQuery = true)
    List<RecommendationCandidateProjection> findRecommendationCandidates();

    @Query(value = """
            SELECT reserved.manager_id AS managerId,
                   SUM(reserved.reserved_count) AS reservedCount
            FROM (
                SELECT workflow.manager_id AS manager_id,
                       COUNT(*) AS reserved_count
                FROM workload_transfer_workflows workflow
                WHERE workflow.created_at >= :dayStart
                  AND workflow.status NOT IN (
                        'CANCELLED',
                        'CANCELLED_EXPIRED',
                        'FAILED',
                        'ROLLED_BACK',
                        'STAFFING_REQUIRED',
                        'EMERGENCY_APPLIED'
                  )
                GROUP BY workflow.manager_id

                UNION ALL

                SELECT emergency.source_manager_id AS manager_id,
                       COUNT(*) AS reserved_count
                FROM workload_transfer_emergency_assignments emergency
                WHERE emergency.created_at >= :dayStart
                  AND emergency.status NOT IN (
                        'BLOCKED',
                        'FAILED',
                        'ROLLED_BACK'
                  )
                GROUP BY emergency.source_manager_id
            ) reserved
            GROUP BY reserved.manager_id
            """, nativeQuery = true)
    List<ManagerReservationProjection> reservedByManagerSince(
            @Param("dayStart") LocalDateTime dayStart
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM workload_transfer_executions
            WHERE status = 'APPLIED'
            """, nativeQuery = true)
    long countAppliedExecutions();

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO workload_transfer_workflows (
                workflow_key,
                shadow_case_id,
                manager_id,
                source_worker_id,
                company_id,
                company_title,
                failure_number,
                transfer_percent,
                selection_rank,
                problem_units,
                estimated_minutes,
                active_order_count,
                new_unit_count,
                correction_count,
                nagul_count,
                publish_count,
                recovery_count,
                bad_count,
                graph_fingerprint,
                graph_json,
                mode,
                status,
                live_settings_revision,
                shadow_settings_revision,
                workflow_version,
                owner_confirmation_required,
                decision_date,
                active,
                last_transition_at,
                created_at,
                updated_at
            )
            SELECT workflow_row.workflow_key,
                   transfer_case.workload_shadow_transfer_case_id,
                   transfer_case.manager_id,
                   transfer_case.source_worker_id,
                   transfer_case.company_id,
                   transfer_case.company_title,
                   transfer_case.failure_number,
                   transfer_case.transfer_percent,
                   transfer_case.selection_rank,
                   transfer_case.problem_units,
                   transfer_case.estimated_minutes,
                   transfer_case.active_order_count,
                   transfer_case.new_unit_count,
                   transfer_case.correction_count,
                   transfer_case.nagul_count,
                   transfer_case.publish_count,
                   transfer_case.recovery_count,
                   transfer_case.bad_count,
                   workflow_row.graph_fingerprint,
                   workflow_row.graph_json,
                   :mode,
                   'READY_TO_OFFER',
                   :liveSettingsRevision,
                   :shadowSettingsRevision,
                   0,
                   :ownerConfirmationRequired,
                   :decisionDate,
                   TRUE,
                   :now,
                   :now,
                   :now
            FROM JSON_TABLE(
                :workflowsJson,
                '$[*]' COLUMNS (
                    workflow_key CHAR(36)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.workflowKey',
                    shadow_case_id BIGINT PATH '$.shadowCaseId',
                    manager_id BIGINT PATH '$.managerId',
                    source_worker_id BIGINT PATH '$.sourceWorkerId',
                    company_id BIGINT PATH '$.companyId',
                    graph_fingerprint CHAR(64)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.graphFingerprint',
                    graph_json LONGTEXT
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.graphJson',
                    candidate_count INT PATH '$.candidateCount'
                )
            ) workflow_row
            JOIN workload_shadow_transfer_cases transfer_case
              ON transfer_case.workload_shadow_transfer_case_id =
                 workflow_row.shadow_case_id
             AND transfer_case.manager_id = workflow_row.manager_id
             AND transfer_case.source_worker_id =
                 workflow_row.source_worker_id
             AND transfer_case.company_id = workflow_row.company_id
            WHERE transfer_case.active = TRUE
              AND transfer_case.status = 'SHADOW_PENDING'
              AND transfer_case.graph_error_count = 0
              AND workflow_row.candidate_count > 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflows existing_workflow
                  WHERE existing_workflow.source_worker_id =
                        transfer_case.source_worker_id
                    AND existing_workflow.company_id = transfer_case.company_id
                    AND existing_workflow.active = TRUE
              )
            ORDER BY workflow_row.workflow_key
            """, nativeQuery = true)
    int insertWorkflowsBulk(
            @Param("workflowsJson") String workflowsJson,
            @Param("mode") String mode,
            @Param("ownerConfirmationRequired") boolean ownerConfirmationRequired,
            @Param("liveSettingsRevision") long liveSettingsRevision,
            @Param("shadowSettingsRevision") long shadowSettingsRevision,
            @Param("decisionDate") LocalDate decisionDate,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO workload_transfer_workflow_candidates (
                workflow_id,
                worker_id,
                sequence_number,
                rating,
                hundred_percent_days,
                failure_days,
                current_estimated_minutes,
                target_group_chat_id,
                candidate_telegram_id,
                status,
                created_at,
                updated_at
            )
            SELECT workflow.workload_transfer_workflow_id,
                   candidate_row.worker_id,
                   candidate_row.sequence_number,
                   candidate_row.rating,
                   candidate_row.hundred_percent_days,
                   candidate_row.failure_days,
                   candidate_row.current_estimated_minutes,
                   candidate_row.target_group_chat_id,
                   candidate_row.candidate_telegram_id,
                   'WAITING',
                   :now,
                   :now
            FROM JSON_TABLE(
                :candidatesJson,
                '$[*]' COLUMNS (
                    workflow_key CHAR(36)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.workflowKey',
                    manager_id BIGINT PATH '$.managerId',
                    source_worker_id BIGINT PATH '$.sourceWorkerId',
                    company_id BIGINT PATH '$.companyId',
                    worker_id BIGINT PATH '$.workerId',
                    sequence_number INT PATH '$.sequenceNumber',
                    rating DECIMAL(5,2) PATH '$.rating',
                    hundred_percent_days INT PATH '$.hundredPercentDays',
                    failure_days INT PATH '$.failureDays',
                    current_estimated_minutes BIGINT
                        PATH '$.currentEstimatedMinutes',
                    target_group_chat_id BIGINT PATH '$.targetGroupChatId',
                    candidate_telegram_id BIGINT PATH '$.candidateTelegramId'
                )
            ) candidate_row
            JOIN workload_transfer_workflows workflow
              ON workflow.workflow_key =
                 candidate_row.workflow_key COLLATE utf8mb4_unicode_ci
             AND workflow.manager_id = candidate_row.manager_id
             AND workflow.source_worker_id = candidate_row.source_worker_id
             AND workflow.company_id = candidate_row.company_id
             AND workflow.active = TRUE
            JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = candidate_row.worker_id
             AND candidate_current.manager_id = workflow.manager_id
            JOIN workers candidate_worker
              ON candidate_worker.worker_id = candidate_row.worker_id
            JOIN users candidate_user
              ON candidate_user.id = candidate_worker.user_id
             AND candidate_user.worker_telegram_group_chat_id =
                 candidate_row.target_group_chat_id
             AND candidate_user.telegram_chat_id =
                 candidate_row.candidate_telegram_id
            WHERE candidate_row.worker_id <> workflow.source_worker_id
              AND candidate_row.sequence_number > 0
              AND candidate_current.recipient_eligible = TRUE
              AND candidate_current.accepts_company_transfers = TRUE
              AND candidate_current.worker_group_connected = TRUE
            ORDER BY workflow.workload_transfer_workflow_id,
                     candidate_row.sequence_number
            """, nativeQuery = true)
    int insertWorkflowCandidatesBulk(
            @Param("candidatesJson") String candidatesJson,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM (
                SELECT workflow.workload_transfer_workflow_id
                FROM JSON_TABLE(
                    :workflowsJson,
                    '$[*]' COLUMNS (
                        workflow_key CHAR(36)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                            PATH '$.workflowKey',
                        manager_id BIGINT PATH '$.managerId',
                        source_worker_id BIGINT PATH '$.sourceWorkerId',
                        company_id BIGINT PATH '$.companyId',
                        expected_candidate_count INT
                            PATH '$.candidateCount'
                    )
                ) workflow_row
                JOIN workload_transfer_workflows workflow
                  ON workflow.workflow_key =
                     workflow_row.workflow_key COLLATE utf8mb4_unicode_ci
                 AND workflow.manager_id = workflow_row.manager_id
                 AND workflow.source_worker_id =
                     workflow_row.source_worker_id
                 AND workflow.company_id = workflow_row.company_id
                 AND workflow.active = TRUE
                LEFT JOIN workload_transfer_workflow_candidates candidate
                  ON candidate.workflow_id =
                     workflow.workload_transfer_workflow_id
                GROUP BY workflow.workload_transfer_workflow_id,
                         workflow_row.expected_candidate_count
                HAVING COUNT(candidate.workload_transfer_workflow_candidate_id) <>
                       workflow_row.expected_candidate_count
            ) incomplete_queue
            """, nativeQuery = true)
    long countIncompleteWorkflowQueues(
            @Param("workflowsJson") String workflowsJson
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            SET workflow.status = 'STAFFING_REQUIRED',
                workflow.last_transition_at = :now,
                workflow.updated_at = :now
            WHERE workflow.active = TRUE
              AND workflow.status = 'READY_TO_OFFER'
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_workflow_candidates candidate
                  WHERE candidate.workflow_id =
                        workflow.workload_transfer_workflow_id
                    AND candidate.status = 'WAITING'
              )
            """, nativeQuery = true)
    int markExhaustedWorkflows(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows
            SET status = 'CANCELLED',
                active = FALSE,
                current_offer_id = NULL,
                accepted_worker_id = NULL,
                last_transition_at = :now,
                resolved_at = :now,
                updated_at = :now
            WHERE active = TRUE
              AND status IN (
                    'READY_TO_OFFER',
                    'OFFERED',
                    'ACCEPTED',
                    'AWAITING_OWNER_CONFIRMATION'
              )
            """, nativeQuery = true)
    int cancelOpenWorkflows(@Param("now") LocalDateTime now);

    interface RecommendationCandidateProjection {
        Long getShadowCaseId();
        Long getManagerId();
        Long getSourceWorkerId();
        Long getCompanyId();
        String getCompanyTitle();
        Integer getFailureNumber();
        Integer getTransferPercent();
        Integer getSelectionRank();
        Long getProblemUnits();
        Long getEstimatedMinutes();
        Long getActiveOrderCount();
        Long getNewUnitCount();
        Long getCorrectionCount();
        Long getNagulCount();
        Long getPublishCount();
        Long getRecoveryCount();
        Long getBadCount();
        Long getFinanciallyUnsafeOrderCount();
        Long getCandidateWorkerId();
        Integer getSequenceNumber();
        BigDecimal getRating();
        Integer getHundredPercentDays();
        Integer getFailureDays();
        Long getCurrentEstimatedMinutes();
        Long getTargetGroupChatId();
        Long getCandidateTelegramId();
    }

    interface ManagerReservationProjection {
        Long getManagerId();
        Long getReservedCount();
    }
}
