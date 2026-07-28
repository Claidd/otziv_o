package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowEventEntity;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowClaimedNotificationProjection;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowHealthProjection;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadShadowEventRepository
        extends Repository<WorkloadShadowEventEntity, Long> {

    @Query(value = """
            SELECT workload_shadow_event_id
            FROM workload_shadow_events
            WHERE active = 1
              AND delivery_status IN ('PENDING', 'RETRY')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY next_attempt_at, first_seen_at, workload_shadow_event_id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findDueEventIds(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'PROCESSING',
                processing_started_at = :processingStartedAt,
                processing_lease_until = :leaseUntil,
                next_attempt_at = NULL
            WHERE workload_shadow_event_id IN (:eventIds)
              AND active = 1
              AND delivery_status IN ('PENDING', 'RETRY')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :processingStartedAt)
            """, nativeQuery = true)
    int claimDueEvents(
            @Param("eventIds") Collection<Long> eventIds,
            @Param("processingStartedAt") LocalDateTime processingStartedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Query(value = """
            SELECT wse.workload_shadow_event_id AS id,
                   wse.severity AS severity,
                   wse.event_type AS eventType,
                   wse.manager_id AS managerId,
                   wse.title AS title,
                   wse.message AS message,
                   wse.target_group_type AS targetGroupType,
                   wse.target_group_chat_id AS targetGroupChatId,
                   wse.delivery_attempts AS deliveryAttempts,
                   manager.audit_telegram_group_chat_id AS managerAuditGroupChatId
            FROM workload_shadow_events wse
            LEFT JOIN managers manager
              ON manager.manager_id = wse.manager_id
            WHERE wse.workload_shadow_event_id IN (:eventIds)
              AND wse.delivery_status = 'PROCESSING'
              AND wse.processing_started_at = :processingStartedAt
              AND wse.processing_lease_until = :leaseUntil
            ORDER BY wse.workload_shadow_event_id
            """, nativeQuery = true)
    List<WorkloadShadowClaimedNotificationProjection> findClaimedEvents(
            @Param("eventIds") Collection<Long> eventIds,
            @Param("processingStartedAt") LocalDateTime processingStartedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events wse
            JOIN JSON_TABLE(
                :outcomesJson,
                '$[*]' COLUMNS (
                    event_id BIGINT PATH '$.eventId',
                    delivery_status VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.deliveryStatus',
                    delivery_attempts INT PATH '$.deliveryAttempts',
                    delivered_at DATETIME(6) PATH '$.deliveredAt' NULL ON EMPTY,
                    next_attempt_at DATETIME(6) PATH '$.nextAttemptAt' NULL ON EMPTY,
                    error_code VARCHAR(80)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.errorCode' NULL ON EMPTY,
                    error_message VARCHAR(1000)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.error' NULL ON EMPTY
                )
            ) outcome
              ON outcome.event_id = wse.workload_shadow_event_id
            SET wse.delivery_status = outcome.delivery_status,
                wse.delivery_attempts = outcome.delivery_attempts,
                wse.delivered_at = CASE
                    WHEN outcome.delivery_status = 'SENT' THEN outcome.delivered_at
                    ELSE wse.delivered_at
                END,
                wse.next_attempt_at = outcome.next_attempt_at,
                wse.processing_started_at = NULL,
                wse.processing_lease_until = NULL,
                wse.last_error_code = outcome.error_code,
                wse.last_error = outcome.error_message
            WHERE wse.delivery_status = 'PROCESSING'
              AND wse.processing_started_at = :processingStartedAt
              AND wse.processing_lease_until = :leaseUntil
            """, nativeQuery = true)
    int applyDeliveryOutcomes(
            @Param("outcomesJson") String outcomesJson,
            @Param("processingStartedAt") LocalDateTime processingStartedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'CANCELLED',
                next_attempt_at = NULL,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error_code = 'EVENT_RESOLVED',
                last_error = 'Событие закрыто до отправки',
                resolved_at = COALESCE(resolved_at, :now)
            WHERE active = 0
              AND (
                delivery_status IN ('PENDING', 'RETRY')
                OR (
                  delivery_status = 'PROCESSING'
                  AND (
                    processing_lease_until IS NULL
                    OR processing_lease_until < :now
                  )
                )
              )
            ORDER BY workload_shadow_event_id
            LIMIT :batchSize
            """, nativeQuery = true)
    int cancelInactiveDeliveries(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET delivery_status = 'RETRY',
                next_attempt_at = :now,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error_code = 'STALE_PROCESSING_LEASE',
                last_error = 'Зависшая доставка автоматически возвращена в очередь'
            WHERE active = 1
              AND delivery_status = 'PROCESSING'
              AND (
                processing_lease_until IS NULL
                OR processing_lease_until < :now
              )
            ORDER BY processing_lease_until
            LIMIT :batchSize
            """, nativeQuery = true)
    int retryStaleProcessingEvents(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_events
            WHERE active = 0
              AND delivery_status IN (
                'SENT',
                'CANCELLED',
                'SKIPPED',
                'RESOLVED',
                'DEAD',
                'MISSING_GROUP_BINDING'
              )
              AND last_seen_at < :cutoff
            ORDER BY last_seen_at
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteTerminalInactiveEvents(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT event_counts.due_events AS dueEvents,
                   event_counts.processing_events AS processingEvents,
                   event_counts.stale_processing_events AS staleProcessingEvents,
                   event_counts.dead_events AS deadEvents,
                   event_counts.missing_group_bindings AS missingGroupBindings,
                   run_counts.running_runs AS runningRuns,
                   run_counts.stale_running_runs AS staleRunningRuns,
                   graph_counts.graph_warning_cases AS graphWarningCases,
                   graph_counts.graph_error_cases AS graphErrorCases,
                   lock_counts.expired_recalculation_locks AS expiredRecalculationLocks,
                   event_counts.oldest_due_event_at AS oldestDueEventAt,
                   run_counts.last_successful_run_at AS lastSuccessfulRunAt,
                   snapshot_counts.last_snapshot_at AS lastSnapshotAt
            FROM (
                SELECT COUNT(CASE
                           WHEN active = 1
                            AND delivery_status IN ('PENDING', 'RETRY')
                            AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                           THEN 1
                       END) AS due_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND delivery_status = 'PROCESSING'
                           THEN 1
                       END) AS processing_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND delivery_status = 'PROCESSING'
                            AND (
                              processing_lease_until IS NULL
                              OR processing_lease_until < :now
                            )
                           THEN 1
                       END) AS stale_processing_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND delivery_status = 'DEAD'
                           THEN 1
                       END) AS dead_events,
                       COUNT(CASE
                           WHEN active = 1
                            AND (
                              delivery_status = 'MISSING_GROUP_BINDING'
                              OR (
                                delivery_status = 'DEAD'
                                AND last_error_code = 'MISSING_GROUP_BINDING'
                              )
                            )
                           THEN 1
                       END) AS missing_group_bindings,
                       MIN(CASE
                           WHEN active = 1
                            AND delivery_status IN ('PENDING', 'RETRY')
                            AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                           THEN first_seen_at
                       END) AS oldest_due_event_at
                FROM workload_shadow_events
            ) event_counts
            CROSS JOIN (
                SELECT COUNT(CASE
                           WHEN status = 'RUNNING'
                           THEN 1
                       END) AS running_runs,
                       COUNT(CASE
                           WHEN status = 'RUNNING'
                            AND started_at < :staleRunBefore
                           THEN 1
                       END) AS stale_running_runs,
                       MAX(CASE
                           WHEN status IN ('SUCCESS', 'SUCCEEDED')
                           THEN finished_at
                       END) AS last_successful_run_at
                FROM workload_shadow_runs
            ) run_counts
            CROSS JOIN (
                SELECT COUNT(CASE
                           WHEN active = 1
                            AND graph_warning_count > 0
                           THEN 1
                       END) AS graph_warning_cases,
                       COUNT(CASE
                           WHEN active = 1
                            AND graph_error_count > 0
                           THEN 1
                       END) AS graph_error_cases
                FROM workload_shadow_transfer_cases
            ) graph_counts
            CROSS JOIN (
                SELECT COUNT(*) AS expired_recalculation_locks
                FROM workload_shadow_recalculation_locks
                WHERE owner_token IS NOT NULL
                  AND lease_until <= CURRENT_TIMESTAMP(6)
            ) lock_counts
            CROSS JOIN (
                SELECT MAX(snapshot_at) AS last_snapshot_at
                FROM workload_shadow_worker_current
            ) snapshot_counts
            """, nativeQuery = true)
    WorkloadShadowHealthProjection healthData(
            @Param("now") LocalDateTime now,
            @Param("staleRunBefore") LocalDateTime staleRunBefore
    );
}
