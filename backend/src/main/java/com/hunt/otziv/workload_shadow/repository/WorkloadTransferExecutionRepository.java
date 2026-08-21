package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadTransferExecutionEntity;
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
 * Exact, set-based mutations used by the protected workload transfer executor.
 *
 * <p>Every update repeats ownership and lifecycle predicates. A stale browser,
 * scheduler race or changed graph therefore cannot turn into a partial transfer:
 * the service verifies every affected-row count inside the same transaction.</p>
 */
public interface WorkloadTransferExecutionRepository
        extends Repository<WorkloadTransferExecutionEntity, Long> {

    @Query(value = """
            SELECT workflow.workload_transfer_workflow_id AS workflowId,
                   workflow.workflow_version AS workflowVersion
            FROM workload_transfer_workflows workflow
            WHERE workflow.active = TRUE
              AND workflow.status = 'ACCEPTED'
            ORDER BY workflow.last_transition_at,
                     workflow.workload_transfer_workflow_id
            LIMIT :rowLimit
            """, nativeQuery = true)
    List<ReadyWorkflowProjection> findReadyWorkflows(@Param("rowLimit") int rowLimit);

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows
            SET status = 'APPLYING',
                workflow_version = workflow_version + 1,
                last_error_code = NULL,
                last_error_message = NULL,
                last_transition_at = :now,
                updated_at = :now
            WHERE workload_transfer_workflow_id = :workflowId
              AND workflow_version = :expectedVersion
              AND active = TRUE
              AND status = 'ACCEPTED'
              AND accepted_worker_id IS NOT NULL
              AND current_offer_id IS NOT NULL
            """, nativeQuery = true)
    int claimWorkflow(
            @Param("workflowId") long workflowId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT workflow.workload_transfer_workflow_id AS workflowId,
                   workflow.workflow_key AS workflowKey,
                   workflow.manager_id AS managerId,
                   workflow.source_worker_id AS sourceWorkerId,
                   workflow.accepted_worker_id AS targetWorkerId,
                   workflow.company_id AS companyId,
                   workflow.company_title AS companyTitle,
                   workflow.graph_fingerprint AS graphFingerprint,
                   workflow.graph_json AS graphJson,
                   workflow.mode AS mode,
                   workflow.decision_date AS decisionDate,
                   workflow.current_offer_id AS acceptedOfferId,
                   CASE
                       WHEN candidate_current.worker_id IS NOT NULL
                        AND candidate_current.manager_id = workflow.manager_id
                        AND candidate_current.recipient_eligible = TRUE
                        AND candidate_current.accepts_company_transfers = TRUE
                        AND candidate_current.worker_group_connected = TRUE
                       THEN TRUE
                       ELSE FALSE
                   END AS targetEligible
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workload_transfer_offer_id = workflow.current_offer_id
             AND offer.workflow_id = workflow.workload_transfer_workflow_id
             AND offer.candidate_worker_id = workflow.accepted_worker_id
             AND offer.status = 'ACCEPTED'
            LEFT JOIN workload_shadow_worker_current candidate_current
              ON candidate_current.worker_id = workflow.accepted_worker_id
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status = 'APPLYING'
            """, nativeQuery = true)
    Optional<ExecutionContextProjection> findClaimedContext(
            @Param("workflowId") long workflowId
    );

    /**
     * Locks the authoritative manager-to-worker links for both transfer
     * participants. The live executor validates that every participant still has
     * exactly one manager and that this manager is the one captured by the
     * workflow. This protects the final apply from a team reassignment that
     * happened after the shadow snapshot or after the offer was accepted.
     */
    @Query(value = """
            SELECT linked_worker.worker_id AS workerId,
                   manager.manager_id AS managerId
            FROM workers_users linked_worker
            JOIN managers manager
              ON manager.user_id = linked_worker.user_id
            WHERE linked_worker.worker_id IN (:workerIds)
            ORDER BY linked_worker.worker_id, manager.manager_id
            FOR UPDATE
            """, nativeQuery = true)
    List<WorkerManagerAssignmentProjection> lockWorkerManagerAssignments(
            @Param("workerIds") Collection<Long> workerIds
    );

    /**
     * Serializes a transfer with order completion/payment and with creation of new
     * company orders. The company row is the parent lock; active source orders are
     * locked separately so publication/payment transactions cannot change their
     * ownership or completion state between validation and the set-based updates.
     */
    @Query(value = """
            SELECT company.company_id
            FROM companies company
            WHERE company.company_id = :companyId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockCompanyForTransfer(@Param("companyId") long companyId);

    @Query(value = """
            SELECT orders.order_id
            FROM orders
            WHERE orders.order_company = :companyId
              AND orders.order_worker = :sourceWorkerId
              AND COALESCE(orders.order_complete, 0) = 0
            ORDER BY orders.order_id
            FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockActiveSourceOrderIds(
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("companyId") long companyId
    );

    @Query(value = """
            SELECT COUNT(DISTINCT unsafe_order.order_id)
            FROM orders unsafe_order
            WHERE unsafe_order.order_company = :companyId
              AND unsafe_order.order_worker = :sourceWorkerId
              AND COALESCE(unsafe_order.order_complete, 0) = 0
              AND (
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
                    OR EXISTS (
                        SELECT 1
                        FROM contractor_payment_allocations unsafe_allocation
                        WHERE (
                              unsafe_allocation.order_id = unsafe_order.order_id
                              OR EXISTS (
                                  SELECT 1
                                  FROM common_invoice_orders unsafe_invoice_item
                                  WHERE unsafe_invoice_item.invoice_id = unsafe_allocation.common_invoice_id
                                    AND unsafe_invoice_item.order_id = unsafe_order.order_id
                              )
                          )
                          AND unsafe_allocation.mode = 'LIVE'
                          AND unsafe_allocation.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                    )
              )
            """, nativeQuery = true)
    long countFinanciallyUnsafeOrders(
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("companyId") long companyId
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_executions (
                workflow_id,
                accepted_offer_id,
                idempotency_key,
                status,
                source_worker_id,
                target_worker_id,
                manager_id,
                company_id,
                graph_fingerprint,
                plan_json,
                before_snapshot_json,
                started_at,
                rollback_deadline_at,
                created_at,
                updated_at
            )
            SELECT workflow.workload_transfer_workflow_id,
                   offer.workload_transfer_offer_id,
                   :idempotencyKey,
                   'PREPARED',
                   workflow.source_worker_id,
                   workflow.accepted_worker_id,
                   workflow.manager_id,
                   workflow.company_id,
                   workflow.graph_fingerprint,
                   :planJson,
                   workflow.graph_json,
                   :now,
                   :rollbackDeadline,
                   :now,
                   :now
            FROM workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workload_transfer_offer_id = workflow.current_offer_id
             AND offer.status = 'ACCEPTED'
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status = 'APPLYING'
              AND NOT EXISTS (
                  SELECT 1
                  FROM workload_transfer_executions existing_execution
                  WHERE existing_execution.accepted_offer_id =
                        offer.workload_transfer_offer_id
              )
            """, nativeQuery = true)
    int insertExecution(
            @Param("workflowId") long workflowId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("planJson") String planJson,
            @Param("now") LocalDateTime now,
            @Param("rollbackDeadline") LocalDateTime rollbackDeadline
    );

    @Query(value = """
            SELECT workload_transfer_execution_id
            FROM workload_transfer_executions
            WHERE idempotency_key = :idempotencyKey
            """, nativeQuery = true)
    Optional<Long> findExecutionIdByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_assignment_audit (
                execution_id,
                entity_type,
                entity_id,
                previous_worker_id,
                new_worker_id,
                details_json,
                created_at
            )
            SELECT :executionId,
                   'ORDER',
                   orders.order_id,
                   orders.order_worker,
                   :targetWorkerId,
                   JSON_OBJECT(
                       'statusId', orders.order_status,
                       'complete', COALESCE(orders.order_complete, 0),
                       'counter', COALESCE(orders.order_counter, 0),
                       'waitingForClient',
                           COALESCE(orders.order_waiting_for_client, 0),
                       'clientTextExpected',
                           COALESCE(orders.order_client_text_expected, 0)
                   ),
                   :now
            FROM orders
            WHERE orders.order_id IN (:entityIds)
              AND orders.order_worker = :sourceWorkerId
              AND orders.order_company = :companyId
              AND COALESCE(orders.order_complete, 0) = 0
            """, nativeQuery = true)
    int auditOrders(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_assignment_audit (
                execution_id,
                entity_type,
                entity_id,
                previous_worker_id,
                new_worker_id,
                details_json,
                created_at
            )
            SELECT :executionId,
                   'REVIEW',
                   review.review_id,
                   review.review_worker,
                   :targetWorkerId,
                   JSON_OBJECT(
                       'published', COALESCE(review.review_publish, 0),
                       'walked', COALESCE(review.review_vigul, 0),
                       'textReadyAt', COALESCE(
                           DATE_FORMAT(
                               review.review_text_ready_at,
                               '%Y-%m-%d %H:%i:%s.%f'
                           ),
                           ''
                       ),
                       'walkedAt', COALESCE(
                           DATE_FORMAT(
                               review.review_vigul_changed_at,
                               '%Y-%m-%d %H:%i:%s.%f'
                           ),
                           ''
                       ),
                       'publishedAt', COALESCE(
                           DATE_FORMAT(
                               review.review_published_marked_at,
                               '%Y-%m-%d %H:%i:%s.%f'
                           ),
                           ''
                       ),
                       'publishedDate', COALESCE(
                           DATE_FORMAT(review.review_publish_date, '%Y-%m-%d'),
                           ''
                       ),
                       'textReadyWorkerId',
                           COALESCE(review.review_text_ready_worker_id, 0),
                       'botId', COALESCE(review.review_bot, 0),
                       'textHash',
                           SHA2(COALESCE(review.review_text, ''), 256),
                       'answerHash',
                           SHA2(COALESCE(review.review_answer, ''), 256)
                   ),
                   :now
            FROM reviews review
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders orders
              ON orders.order_id = detail.order_detail_order
            WHERE review.review_id IN (:entityIds)
              AND review.review_worker = :sourceWorkerId
              AND COALESCE(review.review_publish, 0) = 0
              AND orders.order_company = :companyId
            """, nativeQuery = true)
    int auditReviews(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_assignment_audit (
                execution_id,
                entity_type,
                entity_id,
                previous_worker_id,
                new_worker_id,
                details_json,
                created_at
            )
            SELECT :executionId,
                   'BAD_TASK',
                   task.bad_review_task_id,
                   task.bad_review_task_worker,
                   :targetWorkerId,
                   JSON_OBJECT('status', task.bad_review_task_status),
                   :now
            FROM bad_review_tasks task
            JOIN orders orders
              ON orders.order_id = task.bad_review_task_order
            WHERE task.bad_review_task_id IN (:entityIds)
              AND task.bad_review_task_worker = :sourceWorkerId
              AND task.bad_review_task_status = 'NEW'
              AND orders.order_company = :companyId
            """, nativeQuery = true)
    int auditBadTasks(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_assignment_audit (
                execution_id,
                entity_type,
                entity_id,
                previous_worker_id,
                new_worker_id,
                details_json,
                created_at
            )
            SELECT :executionId,
                   'RECOVERY_TASK',
                   task.review_recovery_task_id,
                   task.review_recovery_task_worker,
                   :targetWorkerId,
                   JSON_OBJECT(
                       'status', task.review_recovery_task_status,
                       'batchStatus', batch.review_recovery_batch_status
                   ),
                   :now
            FROM review_recovery_tasks task
            JOIN review_recovery_batches batch
              ON batch.review_recovery_batch_id =
                 task.review_recovery_task_batch
            LEFT JOIN orders orders
              ON orders.order_id = task.review_recovery_task_order
            WHERE task.review_recovery_task_id IN (:entityIds)
              AND task.review_recovery_task_worker = :sourceWorkerId
              AND task.review_recovery_task_status = 'PLANNED'
              AND batch.review_recovery_batch_status = 'OPEN'
              AND COALESCE(
                    orders.order_company,
                    task.review_recovery_task_archive_company_id
                  ) = :companyId
            """, nativeQuery = true)
    int auditRecoveryTasks(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workers_companies (company_id, worker_id)
            SELECT :companyId, :targetWorkerId
            WHERE NOT EXISTS (
                SELECT 1
                FROM workers_companies existing_link
                WHERE existing_link.company_id = :companyId
                  AND existing_link.worker_id = :targetWorkerId
            )
            """, nativeQuery = true)
    int ensureTargetCompanyLink(
            @Param("companyId") long companyId,
            @Param("targetWorkerId") long targetWorkerId
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_transfer_assignment_audit (
                execution_id,
                entity_type,
                entity_id,
                previous_worker_id,
                new_worker_id,
                details_json,
                created_at
            )
            VALUES (
                :executionId,
                'COMPANY_LINK',
                :companyId,
                NULL,
                :targetWorkerId,
                JSON_OBJECT('createdByExecution', TRUE),
                :now
            )
            """, nativeQuery = true)
    int auditAddedCompanyLink(
            @Param("executionId") long executionId,
            @Param("companyId") long companyId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            DELETE FROM worker_credential_preparations
            WHERE review_id IN (:reviewIds)
            """, nativeQuery = true)
    int clearCredentialPreparations(@Param("reviewIds") Collection<Long> reviewIds);

    @Modifying
    @Query(value = """
            UPDATE reviews
            SET review_worker = :targetWorkerId,
                row_version = row_version + 1
            WHERE review_id IN (:entityIds)
              AND review_worker = :sourceWorkerId
              AND COALESCE(review_publish, 0) = 0
            """, nativeQuery = true)
    int transferReviews(
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId
    );

    @Modifying
    @Query(value = """
            UPDATE bad_review_tasks
            SET bad_review_task_worker = :targetWorkerId
            WHERE bad_review_task_id IN (:entityIds)
              AND bad_review_task_worker = :sourceWorkerId
              AND bad_review_task_status = 'NEW'
            """, nativeQuery = true)
    int transferBadTasks(
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId
    );

    @Modifying
    @Query(value = """
            UPDATE review_recovery_tasks task
            JOIN review_recovery_batches batch
              ON batch.review_recovery_batch_id =
                 task.review_recovery_task_batch
            SET task.review_recovery_task_worker = :targetWorkerId,
                task.review_recovery_task_updated_at = :now
            WHERE task.review_recovery_task_id IN (:entityIds)
              AND task.review_recovery_task_worker = :sourceWorkerId
              AND task.review_recovery_task_status = 'PLANNED'
              AND batch.review_recovery_batch_status = 'OPEN'
            """, nativeQuery = true)
    int transferRecoveryTasks(
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE orders
            SET order_worker = :targetWorkerId,
                row_version = row_version + 1
            WHERE order_id IN (:entityIds)
              AND order_worker = :sourceWorkerId
              AND order_company = :companyId
              AND COALESCE(order_complete, 0) = 0
            """, nativeQuery = true)
    int transferOrders(
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId
    );

    @Modifying
    @Query(value = """
            DELETE source_link
            FROM workers_companies source_link
            WHERE source_link.company_id = :companyId
              AND source_link.worker_id = :sourceWorkerId
              AND NOT EXISTS (
                  SELECT 1
                  FROM orders orders
                  WHERE orders.order_company = :companyId
                    AND orders.order_worker = :sourceWorkerId
                    AND COALESCE(orders.order_complete, 0) = 0
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM reviews review
                  JOIN order_details detail
                    ON detail.order_detail_id = review.review_order_details
                  JOIN orders orders
                    ON orders.order_id = detail.order_detail_order
                  WHERE orders.order_company = :companyId
                    AND review.review_worker = :sourceWorkerId
                    AND COALESCE(review.review_publish, 0) = 0
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM bad_review_tasks task
                  JOIN orders orders
                    ON orders.order_id = task.bad_review_task_order
                  WHERE orders.order_company = :companyId
                    AND task.bad_review_task_worker = :sourceWorkerId
                    AND task.bad_review_task_status = 'NEW'
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM review_recovery_tasks task
                  JOIN review_recovery_batches batch
                    ON batch.review_recovery_batch_id =
                       task.review_recovery_task_batch
                  LEFT JOIN orders orders
                    ON orders.order_id = task.review_recovery_task_order
                  WHERE COALESCE(
                          orders.order_company,
                          task.review_recovery_task_archive_company_id
                        ) = :companyId
                    AND task.review_recovery_task_worker = :sourceWorkerId
                    AND task.review_recovery_task_status = 'PLANNED'
                    AND batch.review_recovery_batch_status = 'OPEN'
              )
            """, nativeQuery = true)
    int removeSourceCompanyLinkIfUnused(
            @Param("companyId") long companyId,
            @Param("sourceWorkerId") long sourceWorkerId
    );


    @Query(value = """
            SELECT execution.workload_transfer_execution_id AS executionId,
                   execution.workflow_id AS workflowId,
                   workflow.mode AS mode,
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
                   COALESCE(
                     NULLIF(TRIM(company.company_title), ''),
                     NULLIF(TRIM(workflow.company_title), ''),
                     CONCAT('Компания #', execution.company_id)
                   ) AS companyTitle,
                   execution.transferred_order_count AS orderCount,
                   execution.transferred_review_count AS reviewCount,
                   execution.transferred_bad_task_count AS badTaskCount,
                   execution.transferred_recovery_task_count AS recoveryTaskCount,
                   execution.applied_at AS appliedAt,
                   execution.rollback_deadline_at AS rollbackDeadlineAt,
                   COALESCE(
                     GROUP_CONCAT(
                       CASE
                         WHEN audit.entity_type = 'ORDER' THEN audit.entity_id
                         ELSE NULL
                       END
                       ORDER BY audit.entity_id
                       SEPARATOR ', '
                     ),
                     ''
                   ) AS orderIds
            FROM workload_transfer_executions execution
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = execution.workflow_id
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
            LEFT JOIN workload_transfer_assignment_audit audit
              ON audit.execution_id = execution.workload_transfer_execution_id
            WHERE execution.workload_transfer_execution_id = :executionId
              AND execution.status = 'APPLIED'
            GROUP BY execution.workload_transfer_execution_id,
                     execution.workflow_id,
                     workflow.mode,
                     execution.manager_id,
                     manager_user.fio,
                     manager_user.username,
                     execution.source_worker_id,
                     source_user.fio,
                     source_user.username,
                     execution.target_worker_id,
                     target_user.fio,
                     target_user.username,
                     execution.company_id,
                     company.company_title,
                     workflow.company_title,
                     execution.transferred_order_count,
                     execution.transferred_review_count,
                     execution.transferred_bad_task_count,
                     execution.transferred_recovery_task_count,
                     execution.applied_at,
                     execution.rollback_deadline_at
            """, nativeQuery = true)
    Optional<AppliedNotificationProjection> findAppliedNotification(
            @Param("executionId") long executionId
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_executions
            SET status = 'APPLIED',
                transferred_order_count = :orderCount,
                transferred_review_count = :reviewCount,
                transferred_bad_task_count = :badTaskCount,
                transferred_recovery_task_count = :recoveryTaskCount,
                after_snapshot_json = :afterSnapshotJson,
                applied_at = :now,
                updated_at = :now
            WHERE workload_transfer_execution_id = :executionId
              AND status = 'PREPARED'
            """, nativeQuery = true)
    int markExecutionApplied(
            @Param("executionId") long executionId,
            @Param("orderCount") int orderCount,
            @Param("reviewCount") int reviewCount,
            @Param("badTaskCount") int badTaskCount,
            @Param("recoveryTaskCount") int recoveryTaskCount,
            @Param("afterSnapshotJson") String afterSnapshotJson,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows
            SET status = 'APPLIED',
                active = FALSE,
                current_offer_id = NULL,
                last_transition_at = :now,
                resolved_at = :now,
                updated_at = :now
            WHERE workload_transfer_workflow_id = :workflowId
              AND active = TRUE
              AND status = 'APPLYING'
            """, nativeQuery = true)
    int markWorkflowApplied(
            @Param("workflowId") long workflowId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflow_candidates candidate
            JOIN workload_transfer_offers offer
              ON offer.workflow_candidate_id =
                 candidate.workload_transfer_workflow_candidate_id
             AND offer.workflow_id = candidate.workflow_id
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = offer.workflow_id
             AND workflow.current_offer_id =
                 offer.workload_transfer_offer_id
            SET candidate.status = 'CANCELLED',
                candidate.last_responded_at =
                    COALESCE(candidate.last_responded_at, :now),
                candidate.response_reason = LEFT(
                    CONCAT_WS(
                        '; ',
                        NULLIF(candidate.response_reason, ''),
                        :reason
                    ),
                    500
                ),
                candidate.updated_at = :now
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status IN ('ACCEPTED', 'APPLYING')
              AND offer.status = 'ACCEPTED'
              AND candidate.status = 'ACCEPTED'
            """, nativeQuery = true)
    int closeAcceptedCandidateForBlockedWorkflow(
            @Param("workflowId") long workflowId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_offers offer
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = offer.workflow_id
             AND workflow.current_offer_id =
                 offer.workload_transfer_offer_id
            SET offer.status = 'CANCELLED',
                offer.responded_at = COALESCE(offer.responded_at, :now),
                offer.response_reason = LEFT(
                    CONCAT_WS(
                        '; ',
                        NULLIF(offer.response_reason, ''),
                        :reason
                    ),
                    500
                ),
                offer.next_attempt_at = NULL,
                offer.processing_token = NULL,
                offer.processing_lease_until = NULL,
                offer.last_error_code = :errorCode,
                offer.last_error = :reason,
                offer.updated_at = :now
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status IN ('ACCEPTED', 'APPLYING')
              AND offer.status = 'ACCEPTED'
            """, nativeQuery = true)
    int closeAcceptedOfferForBlockedWorkflow(
            @Param("workflowId") long workflowId,
            @Param("errorCode") String errorCode,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows
            SET status = :status,
                active = FALSE,
                current_offer_id = NULL,
                accepted_worker_id = NULL,
                last_error_code = :errorCode,
                last_error_message = :errorMessage,
                last_transition_at = :now,
                resolved_at = :now,
                updated_at = :now
            WHERE workload_transfer_workflow_id = :workflowId
              AND active = TRUE
              AND status IN ('ACCEPTED', 'APPLYING')
            """, nativeQuery = true)
    int blockWorkflow(
            @Param("workflowId") long workflowId,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_workflows workflow
            JOIN workload_transfer_offers offer
              ON offer.workload_transfer_offer_id = workflow.current_offer_id
             AND offer.status = 'ACCEPTED'
            SET workflow.status = 'ACCEPTED',
                workflow.owner_confirmed_at = :now,
                workflow.last_transition_at = :now,
                workflow.workflow_version = workflow.workflow_version + 1,
                workflow.updated_at = :now
            WHERE workflow.workload_transfer_workflow_id = :workflowId
              AND workflow.active = TRUE
              AND workflow.status = 'AWAITING_OWNER_CONFIRMATION'
              AND workflow.owner_confirmation_required = TRUE
              AND workflow.accepted_worker_id =
                  offer.candidate_worker_id
            """, nativeQuery = true)
    int confirmByOwner(
            @Param("workflowId") long workflowId,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT audit.entity_id
            FROM workload_transfer_assignment_audit audit
            WHERE audit.execution_id = :executionId
              AND audit.entity_type = :entityType
            ORDER BY audit.entity_id
            """, nativeQuery = true)
    List<Long> findAuditEntityIds(
            @Param("executionId") long executionId,
            @Param("entityType") String entityType
    );

    @Query(value = """
            SELECT execution.workload_transfer_execution_id AS executionId,
                   execution.workflow_id AS workflowId,
                   execution.source_worker_id AS sourceWorkerId,
                   execution.target_worker_id AS targetWorkerId,
                   execution.company_id AS companyId,
                   execution.applied_at AS appliedAt,
                   execution.rollback_deadline_at AS rollbackDeadlineAt
            FROM workload_transfer_executions execution
            WHERE execution.workload_transfer_execution_id = :executionId
              AND execution.status = 'ROLLING_BACK'
            """, nativeQuery = true)
    Optional<RollbackContextProjection> findRollbackContext(
            @Param("executionId") long executionId
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_executions
            SET status = 'ROLLING_BACK',
                updated_at = :now
            WHERE workload_transfer_execution_id = :executionId
              AND status = 'APPLIED'
              AND rollback_deadline_at >= :now
            """, nativeQuery = true)
    int claimRollback(
            @Param("executionId") long executionId,
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT (
                SELECT COUNT(*)
                FROM workload_transfer_assignment_audit audit
                JOIN orders orders
                  ON audit.entity_type = 'ORDER'
                 AND orders.order_id = audit.entity_id
                WHERE audit.execution_id = :executionId
                  AND (
                        orders.order_worker <> :targetWorkerId
                        OR COALESCE(orders.order_status, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.statusId'
                               )) AS UNSIGNED),
                               COALESCE(orders.order_status, 0)
                           )
                        OR COALESCE(orders.order_complete, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.complete'
                               )) AS UNSIGNED),
                               COALESCE(orders.order_complete, 0)
                           )
                        OR COALESCE(orders.order_counter, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.counter'
                               )) AS UNSIGNED),
                               COALESCE(orders.order_counter, 0)
                           )
                        OR COALESCE(orders.order_waiting_for_client, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.waitingForClient'
                               )) AS UNSIGNED),
                               COALESCE(orders.order_waiting_for_client, 0)
                           )
                        OR COALESCE(orders.order_client_text_expected, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.clientTextExpected'
                               )) AS UNSIGNED),
                               COALESCE(orders.order_client_text_expected, 0)
                           )
                        OR EXISTS (
                            SELECT 1
                            FROM contractor_payment_allocations allocation
                            WHERE (
                                  allocation.order_id = orders.order_id
                                  OR EXISTS (
                                      SELECT 1
                                      FROM common_invoice_orders frozen_invoice_item
                                      WHERE frozen_invoice_item.invoice_id = allocation.common_invoice_id
                                        AND frozen_invoice_item.order_id = orders.order_id
                                  )
                              )
                              AND allocation.mode = 'LIVE'
                              AND allocation.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                        )
                  )
            ) + (
                SELECT COUNT(*)
                FROM workload_transfer_assignment_audit audit
                JOIN reviews review
                  ON audit.entity_type = 'REVIEW'
                 AND review.review_id = audit.entity_id
                WHERE audit.execution_id = :executionId
                  AND (
                        review.review_worker <> :targetWorkerId
                        OR COALESCE(review.review_publish, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.published'
                               )) AS UNSIGNED),
                               COALESCE(review.review_publish, 0)
                           )
                        OR COALESCE(review.review_vigul, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.walked'
                               )) AS UNSIGNED),
                               COALESCE(review.review_vigul, 0)
                           )
                        OR COALESCE(
                               DATE_FORMAT(
                                   review.review_text_ready_at,
                                   '%Y-%m-%d %H:%i:%s.%f'
                               ),
                               ''
                           ) <> COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.textReadyAt'
                               )),
                               COALESCE(
                                   DATE_FORMAT(
                                       review.review_text_ready_at,
                                       '%Y-%m-%d %H:%i:%s.%f'
                                   ),
                                   ''
                               )
                           )
                        OR COALESCE(
                               DATE_FORMAT(
                                   review.review_vigul_changed_at,
                                   '%Y-%m-%d %H:%i:%s.%f'
                               ),
                               ''
                           ) <> COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.walkedAt'
                               )),
                               COALESCE(
                                   DATE_FORMAT(
                                       review.review_vigul_changed_at,
                                       '%Y-%m-%d %H:%i:%s.%f'
                                   ),
                                   ''
                               )
                           )
                        OR COALESCE(
                               DATE_FORMAT(
                                   review.review_published_marked_at,
                                   '%Y-%m-%d %H:%i:%s.%f'
                               ),
                               ''
                           ) <> COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.publishedAt'
                               )),
                               COALESCE(
                                   DATE_FORMAT(
                                       review.review_published_marked_at,
                                       '%Y-%m-%d %H:%i:%s.%f'
                                   ),
                                   ''
                               )
                           )
                        OR COALESCE(
                               DATE_FORMAT(
                                   review.review_publish_date,
                                   '%Y-%m-%d'
                               ),
                               ''
                           ) <> COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.publishedDate'
                               )),
                               COALESCE(
                                   DATE_FORMAT(
                                       review.review_publish_date,
                                       '%Y-%m-%d'
                                   ),
                                   ''
                               )
                           )
                        OR COALESCE(review.review_text_ready_worker_id, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.textReadyWorkerId'
                               )) AS UNSIGNED),
                               COALESCE(review.review_text_ready_worker_id, 0)
                           )
                        OR COALESCE(review.review_bot, 0) <>
                           COALESCE(
                               CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.botId'
                               )) AS UNSIGNED),
                               COALESCE(review.review_bot, 0)
                           )
                        OR SHA2(COALESCE(review.review_text, ''), 256) <>
                           COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.textHash'
                               )),
                               SHA2(COALESCE(review.review_text, ''), 256)
                           )
                        OR SHA2(COALESCE(review.review_answer, ''), 256) <>
                           COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.answerHash'
                               )),
                               SHA2(COALESCE(review.review_answer, ''), 256)
                           )
                  )
            ) + (
                SELECT COUNT(*)
                FROM workload_transfer_assignment_audit audit
                JOIN bad_review_tasks task
                  ON audit.entity_type = 'BAD_TASK'
                 AND task.bad_review_task_id = audit.entity_id
                WHERE audit.execution_id = :executionId
                  AND (
                        task.bad_review_task_worker <> :targetWorkerId
                        OR task.bad_review_task_status <>
                           COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.status'
                               )),
                               task.bad_review_task_status
                           )
                  )
            ) + (
                SELECT COUNT(*)
                FROM workload_transfer_assignment_audit audit
                JOIN review_recovery_tasks task
                  ON audit.entity_type = 'RECOVERY_TASK'
                 AND task.review_recovery_task_id = audit.entity_id
                JOIN review_recovery_batches batch
                  ON batch.review_recovery_batch_id =
                     task.review_recovery_task_batch
                WHERE audit.execution_id = :executionId
                  AND (
                        task.review_recovery_task_worker <> :targetWorkerId
                        OR task.review_recovery_task_status <>
                           COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.status'
                               )),
                               task.review_recovery_task_status
                           )
                        OR batch.review_recovery_batch_status <>
                           COALESCE(
                               JSON_UNQUOTE(JSON_EXTRACT(
                                   audit.details_json,
                                   '$.batchStatus'
                               )),
                               batch.review_recovery_batch_status
                           )
                  )
            )
            """, nativeQuery = true)
    long countRollbackUnsafeEntities(
            @Param("executionId") long executionId,
            @Param("targetWorkerId") long targetWorkerId
    );

    /**
     * Serializes rollback with every payment-route creation path. Those paths
     * acquire the canonical order row before freezing contractor requisites,
     * so the unsafe-entity recheck below cannot race a new LIVE allocation.
     */
    @Query(value = """
            SELECT orders.order_id
            FROM workload_transfer_assignment_audit audit
            JOIN orders orders
              ON orders.order_id = audit.entity_id
            WHERE audit.execution_id = :executionId
              AND audit.entity_type = 'ORDER'
              AND orders.order_id IN (:entityIds)
            ORDER BY orders.order_id
            FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockRollbackOrderIds(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds
    );

    @Modifying
    @Query(value = """
            UPDATE orders orders
            JOIN workload_transfer_assignment_audit audit
              ON audit.execution_id = :executionId
             AND audit.entity_type = 'ORDER'
             AND audit.entity_id = orders.order_id
            SET orders.order_worker = :sourceWorkerId,
                orders.row_version = orders.row_version + 1
            WHERE orders.order_id IN (:entityIds)
              AND orders.order_worker = :targetWorkerId
              AND orders.order_company = :companyId
              AND COALESCE(orders.order_status, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.statusId'
                      )) AS UNSIGNED),
                      COALESCE(orders.order_status, 0)
                  )
              AND COALESCE(orders.order_complete, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.complete'
                      )) AS UNSIGNED),
                      COALESCE(orders.order_complete, 0)
                  )
              AND COALESCE(orders.order_counter, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.counter'
                      )) AS UNSIGNED),
                      COALESCE(orders.order_counter, 0)
                  )
              AND COALESCE(orders.order_waiting_for_client, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.waitingForClient'
                      )) AS UNSIGNED),
                      COALESCE(orders.order_waiting_for_client, 0)
                  )
              AND COALESCE(orders.order_client_text_expected, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.clientTextExpected'
                      )) AS UNSIGNED),
                      COALESCE(orders.order_client_text_expected, 0)
                  )
              AND NOT EXISTS (
                  SELECT 1
                  FROM contractor_payment_allocations frozen_allocation
                  WHERE (
                        frozen_allocation.order_id = orders.order_id
                        OR EXISTS (
                            SELECT 1
                            FROM common_invoice_orders frozen_invoice_item
                            WHERE frozen_invoice_item.invoice_id = frozen_allocation.common_invoice_id
                              AND frozen_invoice_item.order_id = orders.order_id
                        )
                    )
                    AND frozen_allocation.mode = 'LIVE'
                    AND frozen_allocation.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
              )
            """, nativeQuery = true)
    int rollbackOrders(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("companyId") long companyId
    );

    @Modifying
    @Query(value = """
            UPDATE reviews review
            JOIN workload_transfer_assignment_audit audit
              ON audit.execution_id = :executionId
             AND audit.entity_type = 'REVIEW'
             AND audit.entity_id = review.review_id
            SET review.review_worker = :sourceWorkerId,
                review.row_version = review.row_version + 1
            WHERE review.review_id IN (:entityIds)
              AND review.review_worker = :targetWorkerId
              AND COALESCE(review.review_publish, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.published'
                      )) AS UNSIGNED),
                      COALESCE(review.review_publish, 0)
                  )
              AND COALESCE(review.review_vigul, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.walked'
                      )) AS UNSIGNED),
                      COALESCE(review.review_vigul, 0)
                  )
              AND COALESCE(
                      DATE_FORMAT(
                          review.review_text_ready_at,
                          '%Y-%m-%d %H:%i:%s.%f'
                      ),
                      ''
                  ) = COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.textReadyAt'
                      )),
                      COALESCE(
                          DATE_FORMAT(
                              review.review_text_ready_at,
                              '%Y-%m-%d %H:%i:%s.%f'
                          ),
                          ''
                      )
                  )
              AND COALESCE(
                      DATE_FORMAT(
                          review.review_vigul_changed_at,
                          '%Y-%m-%d %H:%i:%s.%f'
                      ),
                      ''
                  ) = COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.walkedAt'
                      )),
                      COALESCE(
                          DATE_FORMAT(
                              review.review_vigul_changed_at,
                              '%Y-%m-%d %H:%i:%s.%f'
                          ),
                          ''
                      )
                  )
              AND COALESCE(
                      DATE_FORMAT(
                          review.review_published_marked_at,
                          '%Y-%m-%d %H:%i:%s.%f'
                      ),
                      ''
                  ) = COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.publishedAt'
                      )),
                      COALESCE(
                          DATE_FORMAT(
                              review.review_published_marked_at,
                              '%Y-%m-%d %H:%i:%s.%f'
                          ),
                          ''
                      )
                  )
              AND COALESCE(
                      DATE_FORMAT(review.review_publish_date, '%Y-%m-%d'),
                      ''
                  ) = COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.publishedDate'
                      )),
                      COALESCE(
                          DATE_FORMAT(
                              review.review_publish_date,
                              '%Y-%m-%d'
                          ),
                          ''
                      )
                  )
              AND COALESCE(review.review_text_ready_worker_id, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.textReadyWorkerId'
                      )) AS UNSIGNED),
                      COALESCE(review.review_text_ready_worker_id, 0)
                  )
              AND COALESCE(review.review_bot, 0) =
                  COALESCE(
                      CAST(JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.botId'
                      )) AS UNSIGNED),
                      COALESCE(review.review_bot, 0)
                  )
              AND SHA2(COALESCE(review.review_text, ''), 256) =
                  COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.textHash'
                      )),
                      SHA2(COALESCE(review.review_text, ''), 256)
                  )
              AND SHA2(COALESCE(review.review_answer, ''), 256) =
                  COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.answerHash'
                      )),
                      SHA2(COALESCE(review.review_answer, ''), 256)
                  )
            """, nativeQuery = true)
    int rollbackReviews(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId
    );

    @Modifying
    @Query(value = """
            UPDATE bad_review_tasks task
            JOIN workload_transfer_assignment_audit audit
              ON audit.execution_id = :executionId
             AND audit.entity_type = 'BAD_TASK'
             AND audit.entity_id = task.bad_review_task_id
            SET task.bad_review_task_worker = :sourceWorkerId
            WHERE task.bad_review_task_id IN (:entityIds)
              AND task.bad_review_task_worker = :targetWorkerId
              AND task.bad_review_task_status =
                  COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.status'
                      )),
                      task.bad_review_task_status
                  )
            """, nativeQuery = true)
    int rollbackBadTasks(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId
    );

    @Modifying
    @Query(value = """
            UPDATE review_recovery_tasks task
            JOIN review_recovery_batches batch
              ON batch.review_recovery_batch_id =
                 task.review_recovery_task_batch
            JOIN workload_transfer_assignment_audit audit
              ON audit.execution_id = :executionId
             AND audit.entity_type = 'RECOVERY_TASK'
             AND audit.entity_id = task.review_recovery_task_id
            SET task.review_recovery_task_worker = :sourceWorkerId,
                task.review_recovery_task_updated_at = :now
            WHERE task.review_recovery_task_id IN (:entityIds)
              AND task.review_recovery_task_worker = :targetWorkerId
              AND task.review_recovery_task_status =
                  COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.status'
                      )),
                      task.review_recovery_task_status
                  )
              AND batch.review_recovery_batch_status =
                  COALESCE(
                      JSON_UNQUOTE(JSON_EXTRACT(
                          audit.details_json,
                          '$.batchStatus'
                      )),
                      batch.review_recovery_batch_status
                  )
            """, nativeQuery = true)
    int rollbackRecoveryTasks(
            @Param("executionId") long executionId,
            @Param("entityIds") Collection<Long> entityIds,
            @Param("sourceWorkerId") long sourceWorkerId,
            @Param("targetWorkerId") long targetWorkerId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workers_companies (company_id, worker_id)
            SELECT :companyId, :sourceWorkerId
            WHERE NOT EXISTS (
                SELECT 1
                FROM workers_companies existing_link
                WHERE existing_link.company_id = :companyId
                  AND existing_link.worker_id = :sourceWorkerId
            )
            """, nativeQuery = true)
    int ensureSourceCompanyLink(
            @Param("companyId") long companyId,
            @Param("sourceWorkerId") long sourceWorkerId
    );

    @Modifying
    @Query(value = """
            DELETE target_link
            FROM workers_companies target_link
            WHERE target_link.company_id = :companyId
              AND target_link.worker_id = :targetWorkerId
              AND NOT EXISTS (
                  SELECT 1
                  FROM orders orders
                  WHERE orders.order_company = :companyId
                    AND orders.order_worker = :targetWorkerId
                    AND COALESCE(orders.order_complete, 0) = 0
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM reviews review
                  JOIN order_details detail
                    ON detail.order_detail_id = review.review_order_details
                  JOIN orders orders
                    ON orders.order_id = detail.order_detail_order
                  WHERE orders.order_company = :companyId
                    AND review.review_worker = :targetWorkerId
                    AND COALESCE(review.review_publish, 0) = 0
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM bad_review_tasks task
                  JOIN orders orders
                    ON orders.order_id = task.bad_review_task_order
                  WHERE orders.order_company = :companyId
                    AND task.bad_review_task_worker = :targetWorkerId
                    AND task.bad_review_task_status = 'NEW'
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM review_recovery_tasks task
                  JOIN review_recovery_batches batch
                    ON batch.review_recovery_batch_id =
                       task.review_recovery_task_batch
                  LEFT JOIN orders orders
                    ON orders.order_id = task.review_recovery_task_order
                  WHERE COALESCE(
                          orders.order_company,
                          task.review_recovery_task_archive_company_id
                        ) = :companyId
                    AND task.review_recovery_task_worker = :targetWorkerId
                    AND task.review_recovery_task_status = 'PLANNED'
                    AND batch.review_recovery_batch_status = 'OPEN'
              )
            """, nativeQuery = true)
    int removeTargetCompanyLinkIfUnused(
            @Param("companyId") long companyId,
            @Param("targetWorkerId") long targetWorkerId
    );

    @Modifying
    @Query(value = """
            UPDATE workload_transfer_executions execution
            JOIN workload_transfer_workflows workflow
              ON workflow.workload_transfer_workflow_id = execution.workflow_id
            SET execution.status = 'ROLLED_BACK',
                execution.rolled_back_at = :now,
                execution.updated_at = :now,
                workflow.status = 'ROLLED_BACK',
                workflow.last_transition_at = :now,
                workflow.updated_at = :now
            WHERE execution.workload_transfer_execution_id = :executionId
              AND execution.status = 'ROLLING_BACK'
            """, nativeQuery = true)
    int markRolledBack(
            @Param("executionId") long executionId,
            @Param("now") LocalDateTime now
    );

    interface ReadyWorkflowProjection {
        Long getWorkflowId();
        Long getWorkflowVersion();
    }

    interface ExecutionContextProjection {
        Long getWorkflowId();
        String getWorkflowKey();
        Long getManagerId();
        Long getSourceWorkerId();
        Long getTargetWorkerId();
        Long getCompanyId();
        String getCompanyTitle();
        String getGraphFingerprint();
        String getGraphJson();
        String getMode();
        LocalDate getDecisionDate();
        Long getAcceptedOfferId();
        /*
         * MySQL exposes CASE ... THEN TRUE ELSE FALSE as a numeric BIGINT in a
         * native projection. Keeping the projection numeric avoids Spring Data's
         * unsupported Long -> Boolean conversion.
         */
        Long getTargetEligible();
    }

    interface WorkerManagerAssignmentProjection {
        Long getWorkerId();
        Long getManagerId();
    }

    interface AppliedNotificationProjection {
        Long getExecutionId();
        Long getWorkflowId();
        String getMode();
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
        LocalDateTime getAppliedAt();
        LocalDateTime getRollbackDeadlineAt();
        String getOrderIds();
    }
    interface RollbackContextProjection {
        Long getExecutionId();
        Long getWorkflowId();
        Long getSourceWorkerId();
        Long getTargetWorkerId();
        Long getCompanyId();
        LocalDateTime getAppliedAt();
        LocalDateTime getRollbackDeadlineAt();
    }
}
