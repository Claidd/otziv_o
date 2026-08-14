package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowWorkerDailyEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Set-based persistence boundary for the observation projection.
 *
 * <p>All native SQL is deliberately kept in repository methods. Services only combine
 * already bulk-loaded rows and never issue a query from a worker/company loop.</p>
 */
public interface WorkloadShadowProjectionRepository
        extends Repository<WorkloadShadowWorkerDailyEntity, Long> {

    @Query(value = """
            SELECT snapshot.worker_id AS workerId,
                   snapshot.completed_units AS completedUnits,
                   snapshot.eligible_units AS eligibleUnits,
                   snapshot.late_excluded_units AS lateExcludedUnits,
                   snapshot.external_blocked_units AS externalBlockedUnits,
                   snapshot.progress_percent AS progressPercent,
                   CASE
                       WHEN snapshot.eligible_units > 0
                        AND snapshot.progress_percent >= 100
                       THEN 1
                       ELSE 0
                   END AS reached100,
                   CASE
                       WHEN daily.reached_100_once = TRUE
                         OR (
                             snapshot.eligible_units > 0
                             AND snapshot.progress_percent >= 100
                         )
                       THEN 1
                       ELSE 0
                   END AS reached100Once,
                   COALESCE(
                       daily.first_reached_100_at,
                       CASE
                           WHEN snapshot.eligible_units > 0
                            AND snapshot.progress_percent >= 100
                           THEN snapshot.snapshot_at
                           ELSE NULL
                       END
                   ) AS firstReached100At,
                   COALESCE(
                       daily.last_reached_100_at,
                       CASE
                           WHEN snapshot.eligible_units > 0
                            AND snapshot.progress_percent >= 100
                           THEN snapshot.snapshot_at
                           ELSE NULL
                       END
                   ) AS lastReached100At
            FROM workload_shadow_worker_current snapshot
            LEFT JOIN workload_shadow_worker_daily daily
                   ON daily.progress_date = snapshot.progress_date
                  AND daily.worker_id = snapshot.worker_id
            WHERE snapshot.progress_date = :progressDate
              AND snapshot.worker_id IN (:workerIds)
            """, nativeQuery = true)
    List<WorkloadShadowProgressView> findCurrentWorkerProgress(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("progressDate") LocalDate progressDate
    );

    @Query(value = """
            SELECT daily.worker_id AS workerId,
                   daily.completed_units AS completedUnits,
                   daily.eligible_units AS eligibleUnits,
                   daily.late_excluded_units AS lateExcludedUnits,
                   daily.external_blocked_units AS externalBlockedUnits,
                   daily.progress_percent AS progressPercent,
                   CASE WHEN daily.reached_100 = TRUE THEN 1 ELSE 0 END AS reached100,
                   CASE WHEN daily.reached_100_once = TRUE THEN 1 ELSE 0 END AS reached100Once,
                   daily.first_reached_100_at AS firstReached100At,
                   daily.last_reached_100_at AS lastReached100At
            FROM workload_shadow_worker_daily daily
            WHERE daily.progress_date = :progressDate
              AND daily.worker_id IN (:workerIds)
              AND daily.finalized = TRUE
              AND daily.finalization_status = 'ON_TIME'
            """, nativeQuery = true)
    List<WorkloadShadowProgressView> findFinalizedWorkerProgress(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("progressDate") LocalDate progressDate
    );

    /**
     * Repairs a final snapshot that disagrees with a confirmed 100% operational
     * result for the same completed day. The operational counter is deliberately
     * used only as a completion watermark: the workload projection remains the
     * owner of the eligible denominator and of all late/external-blocker decisions.
     */
    @Modifying
    @Query(value = """
            UPDATE workload_shadow_worker_daily daily
            JOIN worker_daily_performance actual
              ON actual.progress_date = daily.progress_date
             AND actual.worker_id = daily.worker_id
            SET daily.active_units = GREATEST(
                    daily.late_excluded_units,
                    daily.active_units - (daily.eligible_units - daily.completed_units)
                ),
                daily.completed_units = daily.eligible_units,
                daily.progress_percent = 100,
                daily.reached_100 = TRUE,
                daily.reached_100_once = TRUE,
                daily.first_reached_100_at = COALESCE(
                    daily.first_reached_100_at,
                    actual.last_completed_at
                ),
                daily.last_reached_100_at = CASE
                    WHEN daily.last_reached_100_at IS NULL
                      OR actual.last_completed_at > daily.last_reached_100_at
                    THEN actual.last_completed_at
                    ELSE daily.last_reached_100_at
                END,
                daily.last_snapshot_at = GREATEST(
                    daily.last_snapshot_at,
                    actual.last_completed_at
                ),
                daily.finalized_at = GREATEST(
                    COALESCE(daily.finalized_at, actual.last_completed_at),
                    actual.last_completed_at
                ),
                daily.finalization_status = 'ON_TIME'
            WHERE daily.progress_date = :progressDate
              AND daily.finalized = TRUE
              AND daily.finalization_status = 'ON_TIME'
              AND daily.eligible_units > 0
              AND daily.completed_units < daily.eligible_units
              AND daily.external_blocked_units = 0
              AND actual.completed_count > 0
              AND actual.active_count = 0
              AND actual.reached_100 = TRUE
              AND actual.last_completed_at IS NOT NULL
              AND actual.last_completed_at < DATE_ADD(daily.progress_date, INTERVAL 1 DAY)
            """, nativeQuery = true)
    int reconcileCompletedFinalProgress(@Param("progressDate") LocalDate progressDate);

    @Query(value = """
            SELECT assignment.worker_id,
                   worker.user_id AS worker_user_id,
                   assignment.manager_id,
                   assignment.manager_link_count,
                   COALESCE(NULLIF(TRIM(worker_user.fio), ''), worker_user.username,
                            CONCAT('Специалист #', assignment.worker_id)) AS worker_name,
                   COALESCE(NULLIF(TRIM(manager_user.fio), ''), manager_user.username,
                            CONCAT('Менеджер #', assignment.manager_id)) AS manager_name,
                   COALESCE(worker.accepts_company_transfers, TRUE) AS accepts_company_transfers,
                   worker_user.worker_telegram_group_chat_id,
                   manager.audit_telegram_group_chat_id
            FROM (
                SELECT linked_worker.worker_id,
                       MIN(manager.manager_id) AS manager_id,
                       COUNT(DISTINCT manager.manager_id) AS manager_link_count
                FROM managers manager
                JOIN workers_users linked_worker ON linked_worker.user_id = manager.user_id
                GROUP BY linked_worker.worker_id
            ) assignment
            JOIN workers worker ON worker.worker_id = assignment.worker_id
            JOIN users worker_user ON worker_user.id = worker.user_id
            JOIN managers manager ON manager.manager_id = assignment.manager_id
            LEFT JOIN users manager_user ON manager_user.id = manager.user_id
            WHERE COALESCE(worker_user.active, FALSE) = TRUE
            ORDER BY assignment.manager_id, assignment.worker_id
            """, nativeQuery = true)
    List<Map<String, Object>> findWorkers();

    @Query(value = """
            WITH target_orders AS (
                SELECT orders.order_id,
                       orders.order_worker,
                       status.order_status_title
                FROM orders
                JOIN order_statuses status ON status.order_status_id = orders.order_status
                WHERE orders.order_worker IN (:workerIds)
                  AND COALESCE(orders.order_complete, FALSE) = FALSE
                  AND COALESCE(orders.order_waiting_for_client, FALSE) = TRUE
                  AND status.order_status_title IN ('Новый', 'Коррекция')
            ),
            pending_cards AS (
                SELECT detail.order_detail_order AS order_id,
                       SUM(CASE
                           WHEN review.review_text IS NOT NULL
                            AND TRIM(review.review_text) <> ''
                            AND LOWER(TRIM(review.review_text)) NOT LIKE 'текст отзыва%'
                            AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подставить%'
                            AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подсавить%'
                            AND LOWER(TRIM(review.review_text)) NOT LIKE 'подставить текст%'
                            AND LOWER(TRIM(review.review_text)) NOT LIKE 'подсавить текст%'
                           THEN 0 ELSE 1
                       END) AS pending_cards
                FROM order_details detail
                JOIN target_orders target_order
                  ON target_order.order_id = detail.order_detail_order
                JOIN reviews review ON review.review_order_details = detail.order_detail_id
                GROUP BY detail.order_detail_order
            ),
            classified AS (
                SELECT target_order.order_worker AS worker_id,
                       0 AS external_blocked_units,
                       SUM(CASE
                           WHEN target_order.order_status_title = 'Коррекция' THEN 1
                           ELSE COALESCE(pending_cards.pending_cards, 0)
                       END) AS client_deferred_units,
                       0 AS manager_deferred_units
                FROM target_orders target_order
                LEFT JOIN pending_cards ON pending_cards.order_id = target_order.order_id
                GROUP BY target_order.order_worker

                UNION ALL

                SELECT review.review_worker AS worker_id,
                       COUNT(DISTINCT review.review_id) AS external_blocked_units,
                       0 AS client_deferred_units,
                       0 AS manager_deferred_units
                FROM reviews review
                JOIN order_details detail ON detail.order_detail_id = review.review_order_details
                JOIN orders orders ON orders.order_id = detail.order_detail_order
                LEFT JOIN bots bot ON bot.bot_id = review.review_bot
                WHERE review.review_worker IN (:workerIds)
                  AND review.review_publish = FALSE
                  AND (
                      (review.review_vigul = FALSE AND review.review_publish_date <= :nagulDate)
                      OR (review.review_vigul = TRUE AND review.review_publish_date <= :today)
                  )
                  AND review.review_text IS NOT NULL
                  AND TRIM(review.review_text) <> ''
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'текст отзыва%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подставить%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подсавить%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'подставить текст%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'подсавить текст%'
                  AND (review.review_bot IS NULL OR COALESCE(bot.bot_active, FALSE) = FALSE)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM review_recovery_batches recovery_batch
                      JOIN review_recovery_tasks recovery_task
                        ON recovery_task.review_recovery_task_batch =
                           recovery_batch.review_recovery_batch_id
                      WHERE recovery_batch.review_recovery_batch_order = orders.order_id
                        AND recovery_batch.review_recovery_batch_status = 'OPEN'
                        AND recovery_task.review_recovery_task_status = 'PLANNED'
                  )
                GROUP BY review.review_worker

                UNION ALL

                SELECT task.bad_review_task_worker AS worker_id,
                       COUNT(*) AS external_blocked_units,
                       0 AS client_deferred_units,
                       0 AS manager_deferred_units
                FROM bad_review_tasks task
                LEFT JOIN bots bot ON bot.bot_id = task.bad_review_task_bot
                WHERE task.bad_review_task_worker IN (:workerIds)
                  AND task.bad_review_task_status = 'NEW'
                  AND task.bad_review_task_scheduled_date <= :today
                  AND (task.bad_review_task_bot IS NULL OR COALESCE(bot.bot_active, FALSE) = FALSE)
                GROUP BY task.bad_review_task_worker

                UNION ALL

                SELECT task.review_recovery_task_worker AS worker_id,
                       COUNT(*) AS external_blocked_units,
                       0 AS client_deferred_units,
                       0 AS manager_deferred_units
                FROM review_recovery_tasks task
                JOIN review_recovery_batches batch
                  ON batch.review_recovery_batch_id = task.review_recovery_task_batch
                LEFT JOIN bots bot ON bot.bot_id = task.review_recovery_task_bot
                WHERE task.review_recovery_task_worker IN (:workerIds)
                  AND task.review_recovery_task_status = 'PLANNED'
                  AND batch.review_recovery_batch_status = 'OPEN'
                  AND task.review_recovery_task_scheduled_date <= :today
                  AND (
                      task.review_recovery_task_bot IS NULL
                      OR COALESCE(bot.bot_active, FALSE) = FALSE
                  )
                GROUP BY task.review_recovery_task_worker

                UNION ALL

                SELECT review.review_worker AS worker_id,
                       0 AS external_blocked_units,
                       0 AS client_deferred_units,
                       COUNT(DISTINCT event.review_id) AS manager_deferred_units
                FROM business_audit_events event
                JOIN reviews review ON review.review_id = event.review_id
                WHERE review.review_worker IN (:workerIds)
                  AND event.action = 'review_publish_date_changed'
                  AND event.source IN ('manager_board', 'admin_api')
                  AND event.created_at >= :today
                  AND event.created_at < DATE_ADD(:today, INTERVAL 1 DAY)
                  AND event.new_value REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                  AND STR_TO_DATE(event.new_value, '%Y-%m-%d') > :today
                GROUP BY review.review_worker
            )
            SELECT classified.worker_id,
                   SUM(classified.external_blocked_units) AS external_blocked_units,
                   SUM(classified.client_deferred_units) AS client_deferred_units,
                   SUM(classified.manager_deferred_units) AS manager_deferred_units
            FROM classified
            WHERE classified.worker_id IS NOT NULL
            GROUP BY classified.worker_id
            """, nativeQuery = true)
    List<Map<String, Object>> findDeferredAndBlockedUnits(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("today") LocalDate today,
            @Param("nagulDate") LocalDate nagulDate
    );

    @Query(value = """
            SELECT worker_id,
                   batch_key,
                   section_code,
                   decision_code,
                   decision_origin,
                   cohort_key,
                   initial_units,
                   initial_estimated_minutes,
                   first_detected_at,
                   source_available_at,
                   available_minutes_at_decision,
                   cohort_estimated_minutes_at_decision
            FROM workload_shadow_late_batches
            WHERE progress_date = :progressDate
              AND worker_id IN (:workerIds)
            """, nativeQuery = true)
    List<Map<String, Object>> findDailyBatchDecisions(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("progressDate") LocalDate progressDate
    );

    @Query(value = """
            SELECT worker_id, last_snapshot_at
            FROM workload_shadow_worker_daily
            WHERE progress_date = :progressDate
              AND worker_id IN (:workerIds)
            """, nativeQuery = true)
    List<Map<String, Object>> findDailyObservationWatermarks(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("progressDate") LocalDate progressDate
    );

    @Query(value = """
            WITH order_status_audit AS (
                SELECT event.order_id,
                       MAX(event.created_at) AS actual_status_changed_at
                FROM business_audit_events event
                JOIN orders audited_order
                  ON audited_order.order_id = event.order_id
                 AND audited_order.order_worker IN (:workerIds)
                WHERE event.action = 'order_status_changed'
                  AND event.created_at <= :observedAt
                GROUP BY event.order_id
            ),
            target_orders AS (
                SELECT orders.order_id,
                       orders.order_worker AS worker_id,
                       orders.order_company AS company_id,
                       status.order_status_title AS status_title,
                        COALESCE(
                            CASE
                                WHEN orders.order_status_changed_at <= :observedAt
                                THEN orders.order_status_changed_at
                            END,
                            status_audit.actual_status_changed_at,
                            TIMESTAMP(
                                DATE(orders.order_created),
                                CAST(:shiftStart AS TIME)
                            )
                        ) AS available_at
                FROM orders
                JOIN order_statuses status ON status.order_status_id = orders.order_status
                LEFT JOIN order_status_audit status_audit
                  ON status_audit.order_id = orders.order_id
                WHERE orders.order_worker IN (:workerIds)
                  AND COALESCE(orders.order_complete, FALSE) = FALSE
                  AND COALESCE(orders.order_waiting_for_client, FALSE) = FALSE
                  AND status.order_status_title IN ('Новый', 'Коррекция')
            )
            SELECT target_order.worker_id,
                   target_order.company_id,
                   target_order.order_id,
                   target_order.status_title,
                   1 AS units,
                   target_order.available_at,
                   CONCAT(
                       'CORRECTION:',
                       target_order.order_id,
                       ':',
                       DATE_FORMAT(target_order.available_at, '%Y%m%d%H%i%s%f')
                   ) AS batch_key
            FROM target_orders target_order
            WHERE target_order.status_title = 'Коррекция'

            UNION ALL

            SELECT target_order.worker_id,
                   target_order.company_id,
                   target_order.order_id,
                   target_order.status_title,
                   1 AS units,
                   GREATEST(
                       COALESCE(
                           review.review_created_at,
                           TIMESTAMP('1970-01-01 00:00:00')
                       ),
                       COALESCE(
                           target_order.available_at,
                           TIMESTAMP('1970-01-01 00:00:00')
                       )
                   ) AS available_at,
                   CONCAT('NEW:', review.review_id) AS batch_key
            FROM target_orders target_order
            JOIN order_details detail
              ON detail.order_detail_order = target_order.order_id
            JOIN reviews review
              ON review.review_order_details = detail.order_detail_id
            WHERE target_order.status_title = 'Новый'
              AND (
                  review.review_text IS NULL
                  OR TRIM(review.review_text) = ''
                  OR LOWER(TRIM(review.review_text)) LIKE 'текст отзыва%'
                  OR LOWER(TRIM(review.review_text)) LIKE 'нужно подставить%'
                  OR LOWER(TRIM(review.review_text)) LIKE 'нужно подсавить%'
                  OR LOWER(TRIM(review.review_text)) LIKE 'подставить текст%'
                  OR LOWER(TRIM(review.review_text)) LIKE 'подсавить текст%'
              )
            """, nativeQuery = true)
    List<Map<String, Object>> findOrderBatches(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("observedAt") LocalDateTime observedAt,
            @Param("shiftStart") String shiftStart
    );

    @Query(value = """
            WITH order_status_audit AS (
                SELECT event.order_id,
                       MAX(event.created_at) AS actual_status_changed_at
                FROM business_audit_events event
                JOIN orders audited_order
                  ON audited_order.order_id = event.order_id
                 AND audited_order.order_worker IN (:workerIds)
                WHERE event.action = 'order_status_changed'
                  AND event.created_at <= :observedAt
                GROUP BY event.order_id
            ),
            relevant_reviews AS (
                SELECT review.review_id,
                       review.review_worker AS worker_id,
                       review.review_order_details AS order_detail_id,
                       orders.order_company AS company_id,
                       orders.order_id,
                       review.review_vigul_changed_at,
                       GREATEST(
                           COALESCE(
                               review.review_text_ready_at,
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               review.review_created_at,
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               CASE
                                   WHEN orders.order_status_changed_at <= :observedAt
                                   THEN orders.order_status_changed_at
                               END,
                               status_audit.actual_status_changed_at,
                               TIMESTAMP(
                                   DATE(orders.order_created),
                                   CAST(:shiftStart AS TIME)
                               ),
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               TIMESTAMP(
                                   DATE(orders.order_created),
                                   CAST(:shiftStart AS TIME)
                               ),
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               TIMESTAMP(
                                   DATE(review.review_created),
                                   CAST(:shiftStart AS TIME)
                               ),
                               TIMESTAMP('1970-01-01 00:00:00')
                           )
                       ) AS base_available_at
                FROM reviews review
                JOIN order_details detail
                  ON detail.order_detail_id = review.review_order_details
                JOIN orders orders ON orders.order_id = detail.order_detail_order
                LEFT JOIN order_status_audit status_audit
                  ON status_audit.order_id = orders.order_id
                JOIN bots bot ON bot.bot_id = review.review_bot
                WHERE review.review_worker IN (:workerIds)
                  AND review.review_publish = FALSE
                  AND review.review_vigul = FALSE
                  AND COALESCE(bot.bot_active, FALSE) = TRUE
                  AND review.review_publish_date <= :nagulDate
                  AND review.review_text IS NOT NULL
                  AND TRIM(review.review_text) <> ''
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'текст отзыва%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подставить%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подсавить%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'подставить текст%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'подсавить текст%'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM review_recovery_batches recovery_batch
                      JOIN review_recovery_tasks recovery_task
                        ON recovery_task.review_recovery_task_batch =
                           recovery_batch.review_recovery_batch_id
                      WHERE recovery_batch.review_recovery_batch_order = orders.order_id
                        AND recovery_batch.review_recovery_batch_status = 'OPEN'
                        AND recovery_task.review_recovery_task_status = 'PLANNED'
                  )
            ),
            review_audit AS (
                SELECT event.review_id,
                       MAX(CASE
                           WHEN event.action = 'review_account_walk_schedule_checked'
                           THEN event.created_at
                       END) AS account_schedule_checked_at,
                       MAX(CASE
                           WHEN event.action = 'review_publish_date_changed'
                           THEN event.created_at
                       END) AS publish_date_changed_at
                FROM business_audit_events event
                JOIN relevant_reviews relevant
                  ON relevant.review_id = event.review_id
                WHERE event.action IN (
                    'review_account_walk_schedule_checked',
                    'review_publish_date_changed'
                )
                GROUP BY event.review_id
            ),
            approval_audit AS (
                SELECT relevant.review_id,
                       MAX(event.created_at) AS publication_allowed_at
                FROM relevant_reviews relevant
                JOIN business_audit_events event
                  ON event.action = 'publication_allowed'
                 AND event.order_id = relevant.order_id
                 AND (
                     (
                         event.entity_type = 'order_detail'
                         AND LOWER(event.entity_id) =
                             LOWER(BIN_TO_UUID(relevant.order_detail_id))
                     )
                     OR event.entity_type = 'order'
                 )
                GROUP BY relevant.review_id
            )
            SELECT relevant.worker_id,
                   relevant.company_id,
                   relevant.order_id,
                   1 AS units,
                   GREATEST(
                       relevant.base_available_at,
                       COALESCE(
                           approval.publication_allowed_at,
                           relevant.base_available_at
                       ),
                       COALESCE(
                           audit.account_schedule_checked_at,
                           relevant.base_available_at
                       ),
                       COALESCE(
                           audit.publish_date_changed_at,
                           relevant.base_available_at
                       ),
                       COALESCE(
                           relevant.review_vigul_changed_at,
                           relevant.base_available_at
                       )
                   ) AS available_at,
                   CONCAT('NAGUL:', relevant.review_id) AS batch_key
            FROM relevant_reviews relevant
            LEFT JOIN review_audit audit ON audit.review_id = relevant.review_id
            LEFT JOIN approval_audit approval ON approval.review_id = relevant.review_id
            """, nativeQuery = true)
    List<Map<String, Object>> findNagulBatches(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("nagulDate") LocalDate nagulDate,
            @Param("observedAt") LocalDateTime observedAt,
            @Param("shiftStart") String shiftStart
    );

    @Query(value = """
            WITH valid_samples AS (
                SELECT TIMESTAMPDIFF(
                           SECOND,
                           LAG(activity.created_at) OVER (
                               PARTITION BY activity.worker_user_id
                               ORDER BY activity.created_at, activity.event_id
                           ),
                           activity.created_at
                       ) AS gap_seconds
                FROM worker_activity_events activity
                WHERE activity.action = 'REVIEW_NAGUL'
                  AND activity.created_at >= :from
                  AND activity.created_at < :to
            ),
            ranked_samples AS (
                SELECT sample.gap_seconds,
                       ROW_NUMBER() OVER (ORDER BY sample.gap_seconds) AS sample_rank,
                       COUNT(*) OVER () AS sample_count
                FROM valid_samples sample
                WHERE sample.gap_seconds BETWEEN 60 AND 1800
            )
            SELECT COALESCE(MAX(sample.sample_count), 0) AS sample_count,
                   COALESCE(ROUND(AVG(CASE
                       WHEN sample.sample_rank IN (
                           FLOOR((sample.sample_count + 1) / 2),
                           FLOOR((sample.sample_count + 2) / 2)
                       )
                       THEN sample.gap_seconds
                   END)), 0) AS average_seconds
            FROM ranked_samples sample
            """, nativeQuery = true)
    Map<String, Object> findWalkEstimate(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = """
            WITH order_status_audit AS (
                SELECT event.order_id,
                       MAX(event.created_at) AS actual_status_changed_at
                FROM business_audit_events event
                JOIN orders audited_order
                  ON audited_order.order_id = event.order_id
                 AND audited_order.order_worker IN (:workerIds)
                WHERE event.action = 'order_status_changed'
                  AND event.created_at <= :observedAt
                GROUP BY event.order_id
            ),
            relevant_reviews AS (
                SELECT review.review_id,
                       review.review_worker AS worker_id,
                       review.review_order_details AS order_detail_id,
                       orders.order_company AS company_id,
                       orders.order_id,
                       review.review_vigul_changed_at,
                       TIMESTAMP(
                           review.review_publish_date,
                           CAST(:shiftStart AS TIME)
                       )
                           AS publish_due_at,
                       GREATEST(
                           COALESCE(
                               review.review_text_ready_at,
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               review.review_created_at,
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               CASE
                                   WHEN orders.order_status_changed_at <= :observedAt
                                   THEN orders.order_status_changed_at
                               END,
                               status_audit.actual_status_changed_at,
                               TIMESTAMP(
                                   DATE(orders.order_created),
                                   CAST(:shiftStart AS TIME)
                               ),
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               TIMESTAMP(
                                   DATE(orders.order_created),
                                   CAST(:shiftStart AS TIME)
                               ),
                               TIMESTAMP('1970-01-01 00:00:00')
                           ),
                           COALESCE(
                               TIMESTAMP(
                                   DATE(review.review_created),
                                   CAST(:shiftStart AS TIME)
                               ),
                               TIMESTAMP('1970-01-01 00:00:00')
                           )
                       ) AS base_available_at
                FROM reviews review
                JOIN order_details detail
                  ON detail.order_detail_id = review.review_order_details
                JOIN orders orders ON orders.order_id = detail.order_detail_order
                LEFT JOIN order_status_audit status_audit
                  ON status_audit.order_id = orders.order_id
                JOIN bots bot ON bot.bot_id = review.review_bot
                WHERE review.review_worker IN (:workerIds)
                  AND review.review_publish = FALSE
                  AND review.review_vigul = TRUE
                  AND COALESCE(bot.bot_active, FALSE) = TRUE
                  AND review.review_publish_date <= :today
                  AND review.review_text IS NOT NULL
                  AND TRIM(review.review_text) <> ''
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'текст отзыва%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подставить%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подсавить%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'подставить текст%'
                  AND LOWER(TRIM(review.review_text)) NOT LIKE 'подсавить текст%'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM review_recovery_batches recovery_batch
                      JOIN review_recovery_tasks recovery_task
                        ON recovery_task.review_recovery_task_batch =
                           recovery_batch.review_recovery_batch_id
                      WHERE recovery_batch.review_recovery_batch_order = orders.order_id
                        AND recovery_batch.review_recovery_batch_status = 'OPEN'
                        AND recovery_task.review_recovery_task_status = 'PLANNED'
                  )
            ),
            review_audit AS (
                SELECT event.review_id,
                       MAX(CASE
                           WHEN event.action = 'review_publish_date_changed'
                           THEN event.created_at
                       END) AS publish_date_changed_at
                FROM business_audit_events event
                JOIN relevant_reviews relevant
                  ON relevant.review_id = event.review_id
                WHERE event.action = 'review_publish_date_changed'
                GROUP BY event.review_id
            ),
            approval_audit AS (
                SELECT relevant.review_id,
                       MAX(event.created_at) AS publication_allowed_at
                FROM relevant_reviews relevant
                JOIN business_audit_events event
                  ON event.action = 'publication_allowed'
                 AND event.order_id = relevant.order_id
                 AND (
                     (
                         event.entity_type = 'order_detail'
                         AND LOWER(event.entity_id) =
                             LOWER(BIN_TO_UUID(relevant.order_detail_id))
                     )
                     OR event.entity_type = 'order'
                 )
                GROUP BY relevant.review_id
            )
            SELECT relevant.worker_id,
                   relevant.company_id,
                   relevant.order_id,
                   1 AS units,
                   GREATEST(
                       relevant.base_available_at,
                       relevant.publish_due_at,
                       COALESCE(
                           approval.publication_allowed_at,
                           relevant.base_available_at
                       ),
                       COALESCE(
                           audit.publish_date_changed_at,
                           relevant.base_available_at
                       ),
                       COALESCE(
                           relevant.review_vigul_changed_at,
                           relevant.base_available_at
                       )
                   ) AS available_at,
                   CONCAT('PUBLISH:', relevant.review_id) AS batch_key
            FROM relevant_reviews relevant
            LEFT JOIN review_audit audit ON audit.review_id = relevant.review_id
            LEFT JOIN approval_audit approval ON approval.review_id = relevant.review_id
            """, nativeQuery = true)
    List<Map<String, Object>> findPublishBatches(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("today") LocalDate today,
            @Param("observedAt") LocalDateTime observedAt,
            @Param("shiftStart") String shiftStart
    );

    @Query(value = """
            SELECT task.bad_review_task_worker AS worker_id,
                   orders.order_company AS company_id,
                   orders.order_id,
                   1 AS units,
                   GREATEST(
                        COALESCE(
                            task.bad_review_task_created_at,
                            TIMESTAMP(
                                DATE(task.bad_review_task_created),
                                CAST(:shiftStart AS TIME)
                            ),
                            TIMESTAMP(
                                task.bad_review_task_scheduled_date,
                                CAST(:shiftStart AS TIME)
                            )
                        ),
                        TIMESTAMP(
                            task.bad_review_task_scheduled_date,
                            CAST(:shiftStart AS TIME)
                        )
                    ) AS available_at,
                   CONCAT('BAD:', task.bad_review_task_id) AS batch_key
            FROM bad_review_tasks task
            JOIN orders orders ON orders.order_id = task.bad_review_task_order
            JOIN bots bot ON bot.bot_id = task.bad_review_task_bot
            WHERE task.bad_review_task_worker IN (:workerIds)
              AND task.bad_review_task_status = 'NEW'
              AND task.bad_review_task_scheduled_date <= :today
              AND COALESCE(bot.bot_active, FALSE) = TRUE
            """, nativeQuery = true)
    List<Map<String, Object>> findBadBatches(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("today") LocalDate today,
            @Param("shiftStart") String shiftStart
    );

    @Query(value = """
            SELECT task.review_recovery_task_worker AS worker_id,
                   COALESCE(orders.order_company, task.review_recovery_task_archive_company_id) AS company_id,
                   COALESCE(
                       orders.order_id,
                       task.review_recovery_task_archive_order_id,
                       -task.review_recovery_task_batch
                   ) AS order_id,
                   1 AS units,
                   GREATEST(
                        COALESCE(
                            task.review_recovery_task_created_at,
                            TIMESTAMP(
                                task.review_recovery_task_scheduled_date,
                                CAST(:shiftStart AS TIME)
                            )
                        ),
                        TIMESTAMP(
                            task.review_recovery_task_scheduled_date,
                            CAST(:shiftStart AS TIME)
                        )
                    ) AS available_at,
                   CONCAT('RECOVERY:', task.review_recovery_task_id) AS batch_key
            FROM review_recovery_tasks task
            JOIN review_recovery_batches batch
              ON batch.review_recovery_batch_id = task.review_recovery_task_batch
            LEFT JOIN orders orders ON orders.order_id = task.review_recovery_task_order
            JOIN bots bot ON bot.bot_id = task.review_recovery_task_bot
            WHERE task.review_recovery_task_worker IN (:workerIds)
              AND task.review_recovery_task_status = 'PLANNED'
              AND batch.review_recovery_batch_status = 'OPEN'
              AND task.review_recovery_task_scheduled_date <= :today
              AND COALESCE(bot.bot_active, FALSE) = TRUE
            """, nativeQuery = true)
    List<Map<String, Object>> findRecoveryBatches(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("today") LocalDate today,
            @Param("shiftStart") String shiftStart
    );

    @Query(value = """
            SELECT orders.order_worker AS worker_id,
                   'Коррекция' AS old_status,
                   COUNT(*) AS units
            FROM orders orders
            JOIN business_audit_events event
              ON event.order_id = orders.order_id
             AND event.action = 'order_status_changed'
             AND event.created_at >= :from
             AND event.created_at < :to
             AND event.old_value = 'Коррекция'
             AND COALESCE(event.new_value, '') <> 'Коррекция'
            WHERE orders.order_worker IN (:workerIds)
            GROUP BY orders.order_worker
            """, nativeQuery = true)
    List<Map<String, Object>> findCorrectionCompletions(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = """
            WITH actor_workers AS (
                SELECT worker.user_id, MIN(worker.worker_id) AS worker_id
                FROM workers worker
                WHERE worker.user_id IS NOT NULL
                GROUP BY worker.user_id
            )
            SELECT completed.worker_id,
                   completed.action,
                   COUNT(DISTINCT completed.unit_id) AS units
            FROM (
                SELECT review.review_text_ready_worker_id AS worker_id,
                       'REVIEW_TEXT_UPDATE' AS action,
                       review.review_id AS unit_id
                FROM reviews review
                WHERE review.review_text_ready_worker_id IN (:workerIds)
                  AND review.review_text_ready_at >= :from
                  AND review.review_text_ready_at < :to

                UNION ALL

                SELECT review.review_worker AS worker_id,
                       'REVIEW_TEXT_UPDATE' AS action,
                       review.review_id AS unit_id
                FROM reviews review
                WHERE review.review_text_ready_worker_id IS NULL
                  AND review.review_worker IN (:workerIds)
                  AND review.review_text_ready_at >= :from
                  AND review.review_text_ready_at < :to

                UNION ALL

                SELECT COALESCE(actor_worker.worker_id, review.review_worker) AS worker_id,
                       'REVIEW_NAGUL' AS action,
                       review.review_id AS unit_id
                FROM worker_activity_events activity
                JOIN reviews review ON review.review_id = activity.review_id
                LEFT JOIN actor_workers actor_worker
                  ON actor_worker.user_id = activity.worker_user_id
                WHERE COALESCE(actor_worker.worker_id, review.review_worker) IN (:workerIds)
                  AND activity.created_at >= :from
                  AND activity.created_at < :to
                  AND activity.action = 'REVIEW_NAGUL'

                UNION ALL

                SELECT COALESCE(actor_worker.worker_id, review.review_worker) AS worker_id,
                       'REVIEW_PUBLISH' AS action,
                       review.review_id AS unit_id
                FROM worker_activity_events activity
                JOIN reviews review ON review.review_id = activity.review_id
                LEFT JOIN actor_workers actor_worker
                  ON actor_worker.user_id = activity.worker_user_id
                WHERE COALESCE(actor_worker.worker_id, review.review_worker) IN (:workerIds)
                  AND activity.created_at >= :from
                  AND activity.created_at < :to
                  AND activity.action = 'REVIEW_PUBLISH'

                UNION ALL

                SELECT review.review_worker AS worker_id,
                       'REVIEW_PUBLISH' AS action,
                       review.review_id AS unit_id
                FROM reviews review
                WHERE review.review_worker IN (:workerIds)
                  AND review.review_publish = TRUE
                  AND review.review_published_marked_at >= :from
                  AND review.review_published_marked_at < :to
                  AND NOT EXISTS (
                      SELECT 1
                      FROM worker_activity_events publish_activity
                      WHERE publish_activity.review_id = review.review_id
                        AND publish_activity.action = 'REVIEW_PUBLISH'
                        AND publish_activity.created_at >= :from
                        AND publish_activity.created_at < :to
                  )

                UNION ALL

                SELECT review.review_worker AS worker_id,
                       'REVIEW_PUBLISH' AS action,
                       review.review_id AS unit_id
                FROM reviews review
                WHERE review.review_worker IN (:workerIds)
                  AND review.review_publish = TRUE
                  AND review.review_published_marked_at IS NULL
                  AND review.review_changed = :today
                  AND NOT EXISTS (
                      SELECT 1
                      FROM worker_activity_events publish_activity
                      WHERE publish_activity.review_id = review.review_id
                        AND publish_activity.action = 'REVIEW_PUBLISH'
                        AND publish_activity.created_at >= :from
                        AND publish_activity.created_at < :to
                  )

                UNION ALL

                SELECT task.bad_review_task_worker AS worker_id,
                       'BAD_TASK_COMPLETE' AS action,
                       task.bad_review_task_id AS unit_id
                FROM bad_review_tasks task
                WHERE task.bad_review_task_worker IN (:workerIds)
                  AND task.bad_review_task_status = 'DONE'
                  AND task.bad_review_task_completed_date = :today

                UNION ALL

                SELECT task.review_recovery_task_worker AS worker_id,
                       'RECOVERY_TASK_COMPLETE' AS action,
                       task.review_recovery_task_id AS unit_id
                FROM review_recovery_tasks task
                WHERE task.review_recovery_task_worker IN (:workerIds)
                  AND task.review_recovery_task_status = 'DONE'
                  AND task.review_recovery_task_completed_date = :today
            ) completed
            WHERE completed.worker_id IS NOT NULL
            GROUP BY completed.worker_id, completed.action
            """, nativeQuery = true)
    List<Map<String, Object>> findUnitCompletions(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("today") LocalDate today,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = """
            SELECT ranked.worker_id,
                   SUM(CASE
                       WHEN ranked.progress_date >= :monthFrom THEN ranked.hundred_day
                       ELSE 0
                   END) AS hundred_days,
                   SUM(CASE
                       WHEN ranked.progress_date >= :monthFrom THEN ranked.failure_day
                       ELSE 0
                   END) AS failure_days,
                   SUM(CASE
                       WHEN ranked.progress_date >= :monthFrom THEN ranked.protected_day
                       ELSE 0
                   END) AS protected_days,
                   SUM(ranked.hundred_day) AS rolling_hundred_days,
                   SUM(ranked.failure_day) AS rolling_failure_days,
                   MAX(CASE
                       WHEN ranked.latest_rank = 1 THEN ranked.hundred_day
                       ELSE 0
                   END) AS last_day_reached_100,
                   MAX(ranked.progress_date) AS latest_progress_date
            FROM (
                SELECT history.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY history.worker_id
                           ORDER BY history.progress_date DESC
                       ) AS latest_rank
                FROM (
                    SELECT daily.worker_id,
                           CASE WHEN daily.reached_100 = TRUE THEN 1 ELSE 0 END AS hundred_day,
                           CASE
                               WHEN daily.reached_100 = FALSE
                                AND daily.freeze_applied = FALSE THEN 1
                               ELSE 0
                           END AS failure_day,
                           CASE WHEN daily.freeze_applied = TRUE THEN 1 ELSE 0 END AS protected_day,
                           daily.progress_date
                    FROM workload_shadow_worker_daily daily
                    WHERE daily.worker_id IN (:workerIds)
                      AND daily.finalized = TRUE
                      AND daily.finalization_status <> 'STALE_SNAPSHOT'
                      AND daily.eligible_units > 0
                      AND daily.progress_date >= :from
                      AND daily.progress_date < :to

                    UNION ALL

                    SELECT achievement.actor_id AS worker_id,
                           CASE WHEN achievement.reached_100 = TRUE THEN 1 ELSE 0 END AS hundred_day,
                           CASE WHEN achievement.reached_100 = FALSE THEN 1 ELSE 0 END AS failure_day,
                           0 AS protected_day,
                           achievement.result_date
                    FROM end_of_day_achievement_results achievement
                    WHERE achievement.actor_role = 'WORKER'
                      AND achievement.actor_id IN (:workerIds)
                      AND achievement.eligible_count > 0
                      AND achievement.result_date >= :from
                      AND achievement.result_date < :currentDate
                      AND NOT EXISTS (
                          SELECT 1
                          FROM workload_shadow_worker_daily shadow_daily
                          WHERE shadow_daily.worker_id = achievement.actor_id
                            AND shadow_daily.progress_date = achievement.result_date
                            AND shadow_daily.finalized = TRUE
                            AND shadow_daily.finalization_status <> 'STALE_SNAPSHOT'
                      )
                ) history
            ) ranked
            GROUP BY ranked.worker_id
            """, nativeQuery = true)
    List<Map<String, Object>> findHistory(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("from") LocalDate from,
            @Param("monthFrom") LocalDate monthFrom,
            @Param("currentDate") LocalDate currentDate,
            @Param("to") LocalDate to
    );

    @Query(value = """
            SELECT worker_id, ROUND(AVG(efficiency_score), 2) AS rating
            FROM worker_daily_performance
            WHERE worker_id IN (:workerIds)
              AND progress_date >= :from
              AND progress_date < :to
              AND total_count > 0
            GROUP BY worker_id
            """, nativeQuery = true)
    List<Map<String, Object>> findRatings(
            @Param("workerIds") Collection<Long> workerIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query(value = """
            SELECT worker_id, available_credits
            FROM workload_shadow_freeze_accounts
            WHERE worker_id IN (:workerIds)
            """, nativeQuery = true)
    List<Map<String, Object>> findFreezeCredits(@Param("workerIds") Collection<Long> workerIds);

    @Query(value = """
            SELECT daily.worker_id,
                   daily.progress_date,
                   daily.eligible_units,
                   daily.reached_100 AS reached_100,
                   COALESCE(account.available_credits, 0) AS available_credits,
                   COALESCE(account.successful_days_since_credit, 0) AS successful_days,
                   COALESCE(account.earned_total, 0) AS earned_total,
                   COALESCE(account.simulated_used_total, 0) AS used_total,
                   account.last_evaluated_date
            FROM workload_shadow_worker_daily daily
            LEFT JOIN workload_shadow_freeze_accounts account ON account.worker_id = daily.worker_id
            WHERE daily.finalized = TRUE
              AND daily.finalization_status <> 'STALE_SNAPSHOT'
              AND daily.progress_date <= :throughDate
              AND (
                  account.last_evaluated_date IS NULL
                  OR account.last_evaluated_date < daily.progress_date
              )
            ORDER BY daily.worker_id, daily.progress_date
            """, nativeQuery = true)
    List<Map<String, Object>> findPendingFreezeEvaluationRows(
            @Param("throughDate") LocalDate throughDate
    );

    @Modifying
    @Query(value = "DELETE FROM workload_shadow_worker_current", nativeQuery = true)
    int deleteAllCurrent();

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_worker_current
            WHERE run_id IS NULL OR run_id <> :runId
            """, nativeQuery = true)
    int deleteCurrentExceptRun(@Param("runId") long runId);

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_late_batches
            SET active = FALSE
            WHERE progress_date = :progressDate
              AND active = TRUE
            """, nativeQuery = true)
    int deactivateDailyBatchDecisions(@Param("progressDate") LocalDate progressDate);

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_late_batches
            SET active = FALSE
            WHERE progress_date < :progressDate
              AND active = TRUE
            """, nativeQuery = true)
    int closePreviousDayDecisions(@Param("progressDate") LocalDate progressDate);

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_late_batches
            WHERE progress_date = :progressDate
              AND source_available_at > :observedAt
            """, nativeQuery = true)
    int deleteFutureDailyBatchDecisions(
            @Param("progressDate") LocalDate progressDate,
            @Param("observedAt") LocalDateTime observedAt
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_late_batches (
                progress_date, worker_id, batch_key, section_code,
                decision_code, decision_origin, cohort_key,
                initial_units, remaining_units,
                initial_estimated_minutes, remaining_estimated_minutes,
                source_available_at, available_minutes_at_decision,
                cohort_estimated_minutes_at_decision,
                first_detected_at, last_seen_at, active
            )
            SELECT decision_row.progress_date,
                   decision_row.worker_id,
                   decision_row.batch_key,
                   decision_row.section_code,
                   decision_row.decision_code,
                   decision_row.decision_origin,
                   decision_row.cohort_key,
                   decision_row.units,
                   decision_row.units,
                   decision_row.estimated_minutes,
                   decision_row.estimated_minutes,
                   decision_row.source_available_at,
                   decision_row.available_minutes_at_decision,
                   decision_row.cohort_estimated_minutes_at_decision,
                   decision_row.observed_at,
                   decision_row.observed_at,
                   TRUE
            FROM JSON_TABLE(
                :decisionsJson,
                '$[*]' COLUMNS (
                    progress_date DATE PATH '$.progressDate',
                    worker_id BIGINT PATH '$.workerId',
                    batch_key VARCHAR(190)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.batchKey',
                    section_code VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.sectionCode',
                    decision_code VARCHAR(16)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.decisionCode',
                    decision_origin VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.decisionOrigin',
                    cohort_key VARCHAR(190)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.cohortKey',
                    units BIGINT PATH '$.units',
                    estimated_minutes BIGINT PATH '$.estimatedMinutes',
                    source_available_at DATETIME(6) PATH '$.sourceAvailableAt',
                    available_minutes_at_decision BIGINT
                        PATH '$.availableMinutesAtDecision',
                    cohort_estimated_minutes_at_decision BIGINT
                        PATH '$.cohortEstimatedMinutesAtDecision',
                    observed_at DATETIME(6) PATH '$.observedAt'
                )
            ) decision_row
            ON DUPLICATE KEY UPDATE
                decision_code = CASE
                    WHEN workload_shadow_late_batches.decision_code = 'MANDATORY'
                      OR VALUES(decision_code) = 'MANDATORY'
                        THEN 'MANDATORY'
                    ELSE VALUES(decision_code)
                END,
                decision_origin = CASE
                    WHEN workload_shadow_late_batches.decision_code = 'MANDATORY'
                        THEN workload_shadow_late_batches.decision_origin
                    WHEN VALUES(decision_code) = 'MANDATORY'
                        THEN VALUES(decision_origin)
                    ELSE VALUES(decision_origin)
                END,
                remaining_units = VALUES(remaining_units),
                remaining_estimated_minutes = VALUES(remaining_estimated_minutes),
                last_seen_at = VALUES(last_seen_at),
                active = TRUE
            """, nativeQuery = true)
    int upsertDailyBatchDecisions(@Param("decisionsJson") String decisionsJson);

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_estimate_stats (
                section_code, sample_count, average_seconds, effective_minutes,
                minimum_minutes, estimate_source, calculated_at
            ) VALUES (
                'NAGUL', :sampleCount, :averageSeconds, :effectiveMinutes,
                :minimumMinutes, :estimateSource, :calculatedAt
            )
            ON DUPLICATE KEY UPDATE
                sample_count = VALUES(sample_count),
                average_seconds = VALUES(average_seconds),
                effective_minutes = VALUES(effective_minutes),
                minimum_minutes = VALUES(minimum_minutes),
                estimate_source = VALUES(estimate_source),
                calculated_at = VALUES(calculated_at)
            """, nativeQuery = true)
    int upsertWalkEstimate(
            @Param("sampleCount") long sampleCount,
            @Param("averageSeconds") long averageSeconds,
            @Param("effectiveMinutes") int effectiveMinutes,
            @Param("minimumMinutes") int minimumMinutes,
            @Param("estimateSource") String estimateSource,
            @Param("calculatedAt") LocalDateTime calculatedAt
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_worker_current (
                worker_id, worker_user_id, manager_id, progress_date, snapshot_at,
                completed_units, active_units, late_excluded_units, eligible_units,
                progress_percent, feasible_units, estimated_remaining_minutes,
                planned_units, incoming_units, urgent_units, external_blocked_units,
                client_deferred_units, manager_deferred_units,
                new_units, correction_units, nagul_units, publish_units, recovery_units, bad_units,
                rating, hundred_percent_days, failure_days, freeze_credits, transfer_stage,
                last_day_reached_100, accepts_company_transfers, recipient_eligible,
                worker_group_connected, diagnostic_status, last_available_at, run_id
            )
            SELECT snapshot.worker_id,
                   snapshot.worker_user_id,
                   snapshot.manager_id,
                   snapshot.progress_date,
                   snapshot.snapshot_at,
                   snapshot.completed_units,
                   snapshot.active_units,
                   snapshot.late_excluded_units,
                   snapshot.eligible_units,
                   snapshot.progress_percent,
                   snapshot.feasible_units,
                   snapshot.estimated_remaining_minutes,
                   snapshot.planned_units,
                   snapshot.incoming_units,
                   snapshot.urgent_units,
                   snapshot.external_blocked_units,
                   snapshot.client_deferred_units,
                   snapshot.manager_deferred_units,
                   snapshot.new_units,
                   snapshot.correction_units,
                   snapshot.nagul_units,
                   snapshot.publish_units,
                   snapshot.recovery_units,
                   snapshot.bad_units,
                   snapshot.rating,
                   snapshot.hundred_percent_days,
                   snapshot.failure_days,
                   snapshot.freeze_credits,
                   snapshot.transfer_stage,
                   snapshot.last_day_reached_100,
                   snapshot.accepts_company_transfers,
                   snapshot.recipient_eligible,
                   snapshot.worker_group_connected,
                   snapshot.diagnostic_status,
                   snapshot.last_available_at,
                   :runId
            FROM JSON_TABLE(
                :snapshotsJson,
                '$[*]' COLUMNS (
                    worker_id BIGINT PATH '$.workerId',
                    worker_user_id BIGINT PATH '$.workerUserId' NULL ON EMPTY,
                    manager_id BIGINT PATH '$.managerId',
                    progress_date DATE PATH '$.progressDate',
                    snapshot_at DATETIME(6) PATH '$.snapshotAt',
                    completed_units BIGINT PATH '$.completedUnits',
                    active_units BIGINT PATH '$.activeUnits',
                    late_excluded_units BIGINT PATH '$.lateExcludedUnits',
                    eligible_units BIGINT PATH '$.eligibleUnits',
                    progress_percent DECIMAL(5,2) PATH '$.progressPercent',
                    feasible_units BIGINT PATH '$.feasibleUnits',
                    estimated_remaining_minutes BIGINT PATH '$.estimatedRemainingMinutes',
                    planned_units BIGINT PATH '$.plannedUnits',
                    incoming_units BIGINT PATH '$.incomingUnits',
                    urgent_units BIGINT PATH '$.urgentUnits',
                    external_blocked_units BIGINT PATH '$.externalBlockedUnits',
                    client_deferred_units BIGINT PATH '$.clientDeferredUnits',
                    manager_deferred_units BIGINT PATH '$.managerDeferredUnits',
                    new_units BIGINT PATH '$.newUnits',
                    correction_units BIGINT PATH '$.correctionUnits',
                    nagul_units BIGINT PATH '$.nagulUnits',
                    publish_units BIGINT PATH '$.publishUnits',
                    recovery_units BIGINT PATH '$.recoveryUnits',
                    bad_units BIGINT PATH '$.badUnits',
                    rating DECIMAL(5,2) PATH '$.rating',
                    hundred_percent_days INT PATH '$.hundredPercentDays',
                    failure_days INT PATH '$.failureDays',
                    freeze_credits INT PATH '$.freezeCredits',
                    transfer_stage INT PATH '$.transferStage',
                    last_day_reached_100 TINYINT PATH '$.lastDayReached100',
                    accepts_company_transfers TINYINT PATH '$.acceptsCompanyTransfers',
                    recipient_eligible TINYINT PATH '$.recipientEligible',
                    worker_group_connected TINYINT PATH '$.workerGroupConnected',
                    diagnostic_status VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.diagnosticStatus',
                    last_available_at DATETIME(6) PATH '$.lastAvailableAt' NULL ON EMPTY
                )
            ) snapshot
            ON DUPLICATE KEY UPDATE
                worker_user_id = VALUES(worker_user_id),
                manager_id = VALUES(manager_id),
                progress_date = VALUES(progress_date),
                snapshot_at = VALUES(snapshot_at),
                completed_units = VALUES(completed_units),
                active_units = VALUES(active_units),
                late_excluded_units = VALUES(late_excluded_units),
                eligible_units = VALUES(eligible_units),
                progress_percent = VALUES(progress_percent),
                feasible_units = VALUES(feasible_units),
                estimated_remaining_minutes = VALUES(estimated_remaining_minutes),
                planned_units = VALUES(planned_units),
                incoming_units = VALUES(incoming_units),
                urgent_units = VALUES(urgent_units),
                external_blocked_units = VALUES(external_blocked_units),
                client_deferred_units = VALUES(client_deferred_units),
                manager_deferred_units = VALUES(manager_deferred_units),
                new_units = VALUES(new_units),
                correction_units = VALUES(correction_units),
                nagul_units = VALUES(nagul_units),
                publish_units = VALUES(publish_units),
                recovery_units = VALUES(recovery_units),
                bad_units = VALUES(bad_units),
                rating = VALUES(rating),
                hundred_percent_days = VALUES(hundred_percent_days),
                failure_days = VALUES(failure_days),
                freeze_credits = VALUES(freeze_credits),
                transfer_stage = VALUES(transfer_stage),
                last_day_reached_100 = VALUES(last_day_reached_100),
                accepts_company_transfers = VALUES(accepts_company_transfers),
                recipient_eligible = VALUES(recipient_eligible),
                worker_group_connected = VALUES(worker_group_connected),
                diagnostic_status = VALUES(diagnostic_status),
                last_available_at = VALUES(last_available_at),
                run_id = VALUES(run_id)
            """, nativeQuery = true)
    int upsertCurrentSnapshots(
            @Param("snapshotsJson") String snapshotsJson,
            @Param("runId") long runId
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_worker_daily (
                progress_date, worker_id, worker_user_id, manager_id,
                settings_revision,
                completed_units, active_units, late_excluded_units, eligible_units,
                progress_percent, rating, planned_units, incoming_units, urgent_units,
                external_blocked_units, client_deferred_units, manager_deferred_units,
                reached_100, reached_100_once,
                first_reached_100_at, last_reached_100_at,
                finalized, finalization_status,
                first_snapshot_at, last_snapshot_at, finalized_at
            )
            SELECT snapshot.progress_date,
                   snapshot.worker_id,
                   snapshot.worker_user_id,
                   snapshot.manager_id,
                   snapshot.settings_revision,
                   snapshot.completed_units,
                   snapshot.active_units,
                   snapshot.late_excluded_units,
                   snapshot.eligible_units,
                   snapshot.progress_percent,
                   snapshot.rating,
                   snapshot.planned_units,
                   snapshot.incoming_units,
                   snapshot.urgent_units,
                   snapshot.external_blocked_units,
                   snapshot.client_deferred_units,
                   snapshot.manager_deferred_units,
                   snapshot.reached_100,
                   snapshot.reached_100,
                   CASE
                       WHEN snapshot.reached_100 = TRUE THEN snapshot.snapshot_at
                       ELSE NULL
                   END,
                   CASE
                       WHEN snapshot.reached_100 = TRUE THEN snapshot.snapshot_at
                       ELSE NULL
                   END,
                   :finalized,
                   CASE WHEN :finalized = TRUE THEN 'ON_TIME' ELSE 'LIVE' END,
                   snapshot.snapshot_at,
                   snapshot.snapshot_at,
                   CASE WHEN :finalized = TRUE THEN :observedAt ELSE NULL END
            FROM JSON_TABLE(
                :snapshotsJson,
                '$[*]' COLUMNS (
                    progress_date DATE PATH '$.progressDate',
                    worker_id BIGINT PATH '$.workerId',
                    worker_user_id BIGINT PATH '$.workerUserId' NULL ON EMPTY,
                    manager_id BIGINT PATH '$.managerId',
                    settings_revision BIGINT PATH '$.settingsRevision',
                    completed_units BIGINT PATH '$.completedUnits',
                    active_units BIGINT PATH '$.activeUnits',
                    late_excluded_units BIGINT PATH '$.lateExcludedUnits',
                    eligible_units BIGINT PATH '$.eligibleUnits',
                    progress_percent DECIMAL(5,2) PATH '$.progressPercent',
                    rating DECIMAL(5,2) PATH '$.rating',
                    planned_units BIGINT PATH '$.plannedUnits',
                    incoming_units BIGINT PATH '$.incomingUnits',
                    urgent_units BIGINT PATH '$.urgentUnits',
                    external_blocked_units BIGINT PATH '$.externalBlockedUnits',
                    client_deferred_units BIGINT PATH '$.clientDeferredUnits',
                    manager_deferred_units BIGINT PATH '$.managerDeferredUnits',
                    reached_100 TINYINT PATH '$.reached100',
                    snapshot_at DATETIME(6) PATH '$.snapshotAt'
                )
            ) snapshot
            ON DUPLICATE KEY UPDATE
                worker_user_id = VALUES(worker_user_id),
                manager_id = VALUES(manager_id),
                settings_revision = VALUES(settings_revision),
                completed_units = VALUES(completed_units),
                active_units = VALUES(active_units),
                late_excluded_units = VALUES(late_excluded_units),
                eligible_units = VALUES(eligible_units),
                progress_percent = VALUES(progress_percent),
                rating = VALUES(rating),
                planned_units = VALUES(planned_units),
                incoming_units = VALUES(incoming_units),
                urgent_units = VALUES(urgent_units),
                external_blocked_units = VALUES(external_blocked_units),
                client_deferred_units = VALUES(client_deferred_units),
                manager_deferred_units = VALUES(manager_deferred_units),
                reached_100_once =
                    CASE
                        WHEN workload_shadow_worker_daily.reached_100_once = TRUE
                          OR VALUES(reached_100) = TRUE
                        THEN TRUE
                        ELSE FALSE
                    END,
                first_reached_100_at =
                    CASE
                        WHEN workload_shadow_worker_daily.first_reached_100_at IS NOT NULL
                            THEN workload_shadow_worker_daily.first_reached_100_at
                        WHEN VALUES(reached_100) = TRUE
                            THEN VALUES(first_reached_100_at)
                        ELSE NULL
                    END,
                last_reached_100_at =
                    CASE
                        WHEN workload_shadow_worker_daily.reached_100 = FALSE
                         AND VALUES(reached_100) = TRUE
                            THEN VALUES(last_reached_100_at)
                        ELSE workload_shadow_worker_daily.last_reached_100_at
                    END,
                reached_100 = VALUES(reached_100),
                finalization_status =
                    IF(
                        workload_shadow_worker_daily.finalized = TRUE,
                        workload_shadow_worker_daily.finalization_status,
                        VALUES(finalization_status)
                    ),
                last_snapshot_at = VALUES(last_snapshot_at),
                finalized_at = CASE
                    WHEN workload_shadow_worker_daily.finalized_at IS NOT NULL
                        THEN workload_shadow_worker_daily.finalized_at
                    ELSE VALUES(finalized_at)
                END,
                finalized = CASE
                    WHEN workload_shadow_worker_daily.finalized = TRUE THEN TRUE
                    ELSE VALUES(finalized)
                END
            """, nativeQuery = true)
    int upsertDailySnapshots(
            @Param("snapshotsJson") String snapshotsJson,
            @Param("finalized") boolean finalized,
            @Param("observedAt") LocalDateTime observedAt
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_freeze_accounts (
                worker_id, available_credits, successful_days_since_credit,
                earned_total, simulated_used_total, last_evaluated_date
            )
            SELECT freeze_row.worker_id,
                   freeze_row.credits,
                   freeze_row.successful_days,
                   freeze_row.earned_total,
                   freeze_row.used_total,
                   freeze_row.last_evaluated_date
            FROM JSON_TABLE(
                :freezeRowsJson,
                '$[*]' COLUMNS (
                    worker_id BIGINT PATH '$.workerId',
                    credits INT PATH '$.credits',
                    successful_days INT PATH '$.successfulDays',
                    earned_total INT PATH '$.earnedTotal',
                    used_total INT PATH '$.usedTotal',
                    last_evaluated_date DATE PATH '$.lastEvaluatedDate'
                )
            ) freeze_row
            ON DUPLICATE KEY UPDATE
                available_credits = VALUES(available_credits),
                successful_days_since_credit = VALUES(successful_days_since_credit),
                earned_total = VALUES(earned_total),
                simulated_used_total = VALUES(simulated_used_total),
                last_evaluated_date = VALUES(last_evaluated_date)
            """, nativeQuery = true)
    int upsertFreezeAccounts(@Param("freezeRowsJson") String freezeRowsJson);

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_worker_daily daily
            JOIN JSON_TABLE(
                :freezeRowsJson,
                '$[*]' COLUMNS (
                    worker_id BIGINT PATH '$.workerId',
                    progress_date DATE PATH '$.progressDate',
                    freeze_applied TINYINT PATH '$.freezeApplied'
                )
            ) freeze_row
              ON freeze_row.worker_id = daily.worker_id
             AND freeze_row.progress_date = daily.progress_date
            SET daily.freeze_applied = freeze_row.freeze_applied
            WHERE freeze_row.freeze_applied = TRUE
            """, nativeQuery = true)
    int applyDailyFreezes(@Param("freezeRowsJson") String freezeRowsJson);

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_worker_current current
            JOIN workload_shadow_freeze_accounts account ON account.worker_id = current.worker_id
            SET current.freeze_credits = account.available_credits
            WHERE current.progress_date = :progressDate
            """, nativeQuery = true)
    int refreshCurrentFreezeCredits(@Param("progressDate") LocalDate progressDate);

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_events (
                deduplication_key, severity, event_type, manager_id, worker_id,
                title, message, target_group_type, target_group_chat_id,
                delivery_status, first_seen_at, last_seen_at, next_attempt_at, active
            )
            SELECT CONCAT(
                       'MISSED_FINAL_SNAPSHOT:',
                       daily.progress_date,
                       ':',
                       daily.worker_id
                   ),
                   'WARNING',
                   'MISSED_FINAL_SNAPSHOT',
                   daily.manager_id,
                   daily.worker_id,
                   'Итог дня восстановлен после пропуска расчёта',
                   CONCAT(
                       'НАБЛЮДЕНИЕ. Для специалиста ',
                       COALESCE(NULLIF(TRIM(worker_user.fio), ''), worker_user.username,
                                CONCAT('#', daily.worker_id)),
                       ' за ',
                       daily.progress_date,
                       ' отсутствовал снимок на конец смены. Ранний снимок сохранён только для диагностики ',
                       'и исключён из месячного рейтинга и заморозок.'
                   ),
                   'ADMIN_OWNER_MONITORING',
                   :notificationGroupChatId,
                   CASE
                       WHEN :groupNotificationsEnabled = FALSE THEN 'SKIPPED'
                       WHEN :notificationGroupChatId < 0 THEN 'PENDING'
                       ELSE 'MISSING_GROUP_BINDING'
                   END,
                   :now,
                   :now,
                   CASE
                       WHEN :groupNotificationsEnabled = TRUE
                        AND :notificationGroupChatId < 0 THEN :now
                       ELSE NULL
                   END,
                   TRUE
            FROM workload_shadow_worker_daily daily
            JOIN workers worker ON worker.worker_id = daily.worker_id
            LEFT JOIN users worker_user ON worker_user.id = worker.user_id
            WHERE daily.progress_date < :progressDate
              AND daily.finalized = FALSE
            ON DUPLICATE KEY UPDATE
                manager_id = VALUES(manager_id),
                worker_id = VALUES(worker_id),
                message = VALUES(message),
                target_group_chat_id = VALUES(target_group_chat_id),
                delivery_attempts = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 0
                    WHEN VALUES(target_group_chat_id) IS NULL OR VALUES(target_group_chat_id) >= 0
                        THEN 0
                    WHEN workload_shadow_events.active = FALSE
                        THEN 0
                    WHEN workload_shadow_events.delivery_status IN ('PROCESSING', 'RETRY', 'PENDING')
                        THEN workload_shadow_events.delivery_attempts
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN workload_shadow_events.delivery_attempts
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.delivery_attempts
                    ELSE 0
                END,
                next_attempt_at = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN NULL
                    WHEN VALUES(target_group_chat_id) IS NULL OR VALUES(target_group_chat_id) >= 0
                        THEN NULL
                    WHEN workload_shadow_events.active = FALSE
                        THEN VALUES(next_attempt_at)
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN NULL
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'RETRY'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'PENDING'
                        THEN CASE
                            WHEN workload_shadow_events.next_attempt_at IS NULL
                              OR VALUES(next_attempt_at) IS NULL
                                THEN NULL
                            ELSE LEAST(
                                workload_shadow_events.next_attempt_at,
                                VALUES(next_attempt_at)
                            )
                        END
                    ELSE VALUES(next_attempt_at)
                END,
                last_error_code = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'NOTIFICATIONS_DISABLED'
                    WHEN workload_shadow_events.active = FALSE
                        THEN CASE
                            WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                                THEN 'MISSING_GROUP_BINDING'
                            ELSE NULL
                        END
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'MISSING_GROUP_BINDING'
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN workload_shadow_events.last_error_code
                    WHEN VALUES(delivery_status) = 'PENDING'
                     AND COALESCE(workload_shadow_events.last_error_code, '') IN (
                         'MISSING_GROUP_BINDING',
                         'ROUTING_POLICY_CHANGED',
                         'NOTIFICATIONS_DISABLED',
                         'NOTIFICATION_BASELINE'
                     )
                        THEN NULL
                    ELSE workload_shadow_events.last_error_code
                END,
                last_error = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'Telegram-уведомления SHADOW выключены; событие доступно только в мониторинге'
                    WHEN workload_shadow_events.active = FALSE
                        THEN CASE
                            WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                                THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                            ELSE NULL
                        END
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN workload_shadow_events.last_error
                    WHEN VALUES(delivery_status) = 'PENDING'
                     AND COALESCE(workload_shadow_events.last_error_code, '') IN (
                         'MISSING_GROUP_BINDING',
                         'ROUTING_POLICY_CHANGED',
                         'NOTIFICATIONS_DISABLED',
                         'NOTIFICATION_BASELINE'
                     )
                        THEN NULL
                    ELSE workload_shadow_events.last_error
                END,
                delivery_status = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'SKIPPED'
                    WHEN workload_shadow_events.active = FALSE
                        THEN VALUES(delivery_status)
                    WHEN VALUES(target_group_chat_id) IS NULL OR VALUES(target_group_chat_id) >= 0
                        THEN 'MISSING_GROUP_BINDING'
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN 'SKIPPED'
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN 'PROCESSING'
                    WHEN workload_shadow_events.delivery_status = 'RETRY'
                        THEN 'RETRY'
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN 'DEAD'
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN 'SENT'
                    WHEN workload_shadow_events.delivery_status = 'PENDING'
                        THEN 'PENDING'
                    ELSE VALUES(delivery_status)
                END,
                processing_started_at = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                      OR workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN NULL
                    ELSE workload_shadow_events.processing_started_at
                END,
                processing_lease_until = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                      OR workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN NULL
                    ELSE workload_shadow_events.processing_lease_until
                END,
                delivered_at = CASE
                    WHEN workload_shadow_events.active = FALSE THEN NULL
                    ELSE workload_shadow_events.delivered_at
                END,
                occurrence_count = workload_shadow_events.occurrence_count + 1,
                last_seen_at = VALUES(last_seen_at),
                active = TRUE,
                resolved_at = NULL
            """, nativeQuery = true)
    int emitMissedFinalSnapshotEvents(
            @Param("progressDate") LocalDate progressDate,
            @Param("now") LocalDateTime now,
            @Param("cooldownStart") LocalDateTime cooldownStart,
            @Param("groupNotificationsEnabled") boolean groupNotificationsEnabled,
            @Param("notificationGroupChatId") Long notificationGroupChatId
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_worker_daily
            SET finalized = TRUE,
                finalization_status = 'STALE_SNAPSHOT',
                finalized_at = COALESCE(finalized_at, last_snapshot_at, :now)
            WHERE progress_date < :progressDate
              AND finalized = FALSE
            """, nativeQuery = true)
    int finalizePreviousSnapshots(
            @Param("progressDate") LocalDate progressDate,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_events (
                deduplication_key, severity, event_type, manager_id, worker_id,
                company_id, transfer_case_id, title, message,
                target_group_type, target_group_chat_id, delivery_status,
                first_seen_at, last_seen_at, next_attempt_at, active
            )
            SELECT event_row.deduplication_key,
                   event_row.severity,
                   event_row.event_type,
                   event_row.manager_id,
                   event_row.worker_id,
                   event_row.company_id,
                   event_row.transfer_case_id,
                   event_row.title,
                   event_row.message,
                   'ADMIN_OWNER_MONITORING',
                   event_row.target_group_chat_id,
                   event_row.delivery_status,
                   event_row.observed_at,
                   event_row.observed_at,
                   event_row.next_attempt_at,
                   TRUE
            FROM JSON_TABLE(
                :eventsJson,
                '$[*]' COLUMNS (
                    deduplication_key VARCHAR(190)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.deduplicationKey',
                    severity VARCHAR(16)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.severity',
                    event_type VARCHAR(48)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.eventType',
                    manager_id BIGINT PATH '$.managerId' NULL ON EMPTY,
                    worker_id BIGINT PATH '$.workerId' NULL ON EMPTY,
                    company_id BIGINT PATH '$.companyId' NULL ON EMPTY,
                    transfer_case_id BIGINT PATH '$.transferCaseId' NULL ON EMPTY,
                    title VARCHAR(220)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.title',
                    message VARCHAR(2000)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.message',
                    target_group_chat_id BIGINT PATH '$.targetGroupChatId' NULL ON EMPTY,
                    delivery_status VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.deliveryStatus',
                    observed_at DATETIME(6) PATH '$.observedAt',
                    next_attempt_at DATETIME(6) PATH '$.nextAttemptAt' NULL ON EMPTY
                )
            ) event_row
            ON DUPLICATE KEY UPDATE
                severity = VALUES(severity),
                manager_id = VALUES(manager_id),
                worker_id = VALUES(worker_id),
                company_id = VALUES(company_id),
                transfer_case_id = VALUES(transfer_case_id),
                title = VALUES(title),
                message = VALUES(message),
                target_group_type = 'ADMIN_OWNER_MONITORING',
                target_group_chat_id = VALUES(target_group_chat_id),
                delivery_attempts = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 0
                    WHEN VALUES(target_group_chat_id) IS NULL OR VALUES(target_group_chat_id) >= 0
                        THEN 0
                    WHEN workload_shadow_events.active = FALSE
                        THEN 0
                    WHEN workload_shadow_events.delivery_status IN ('PROCESSING', 'RETRY', 'PENDING')
                        THEN workload_shadow_events.delivery_attempts
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN workload_shadow_events.delivery_attempts
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.delivery_attempts
                    ELSE 0
                END,
                next_attempt_at = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN NULL
                    WHEN VALUES(target_group_chat_id) IS NULL OR VALUES(target_group_chat_id) >= 0
                        THEN NULL
                    WHEN workload_shadow_events.active = FALSE
                        THEN VALUES(next_attempt_at)
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN NULL
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'RETRY'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'PENDING'
                        THEN CASE
                            WHEN workload_shadow_events.next_attempt_at IS NULL
                              OR VALUES(next_attempt_at) IS NULL
                                THEN NULL
                            ELSE LEAST(
                                workload_shadow_events.next_attempt_at,
                                VALUES(next_attempt_at)
                            )
                        END
                    ELSE VALUES(next_attempt_at)
                END,
                last_error_code = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'NOTIFICATIONS_DISABLED'
                    WHEN workload_shadow_events.active = FALSE
                        THEN CASE
                            WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                                THEN 'MISSING_GROUP_BINDING'
                            ELSE NULL
                        END
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'MISSING_GROUP_BINDING'
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN workload_shadow_events.last_error_code
                    WHEN VALUES(delivery_status) = 'PENDING'
                     AND COALESCE(workload_shadow_events.last_error_code, '') IN (
                         'MISSING_GROUP_BINDING',
                         'ROUTING_POLICY_CHANGED',
                         'NOTIFICATIONS_DISABLED',
                         'NOTIFICATION_BASELINE'
                     )
                        THEN NULL
                    ELSE workload_shadow_events.last_error_code
                END,
                last_error = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'Telegram-уведомления SHADOW выключены; событие доступно только в мониторинге'
                    WHEN workload_shadow_events.active = FALSE
                        THEN CASE
                            WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                                THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                            ELSE NULL
                        END
                    WHEN VALUES(delivery_status) = 'MISSING_GROUP_BINDING'
                        THEN 'Не настроена общая Telegram-группа администраторов и владельцев'
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN workload_shadow_events.last_error
                    WHEN VALUES(delivery_status) = 'PENDING'
                     AND COALESCE(workload_shadow_events.last_error_code, '') IN (
                         'MISSING_GROUP_BINDING',
                         'ROUTING_POLICY_CHANGED',
                         'NOTIFICATIONS_DISABLED',
                         'NOTIFICATION_BASELINE'
                     )
                        THEN NULL
                    ELSE workload_shadow_events.last_error
                END,
                delivery_status = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                        THEN 'SKIPPED'
                    WHEN workload_shadow_events.active = FALSE
                        THEN VALUES(delivery_status)
                    WHEN VALUES(target_group_chat_id) IS NULL OR VALUES(target_group_chat_id) >= 0
                        THEN 'MISSING_GROUP_BINDING'
                    WHEN workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN 'SKIPPED'
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN 'PROCESSING'
                    WHEN workload_shadow_events.delivery_status = 'RETRY'
                        THEN 'RETRY'
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN 'DEAD'
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN 'SENT'
                    WHEN workload_shadow_events.delivery_status = 'PENDING'
                        THEN 'PENDING'
                    ELSE VALUES(delivery_status)
                END,
                processing_started_at = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                      OR workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN NULL
                    ELSE workload_shadow_events.processing_started_at
                END,
                processing_lease_until = CASE
                    WHEN VALUES(delivery_status) = 'SKIPPED'
                      OR workload_shadow_events.delivery_status = 'SKIPPED'
                        THEN NULL
                    ELSE workload_shadow_events.processing_lease_until
                END,
                delivered_at = CASE
                    WHEN workload_shadow_events.active = FALSE THEN NULL
                    ELSE workload_shadow_events.delivered_at
                END,
                occurrence_count = workload_shadow_events.occurrence_count + 1,
                last_seen_at = VALUES(last_seen_at),
                active = TRUE,
                resolved_at = NULL
            """, nativeQuery = true)
    int upsertEvents(
            @Param("eventsJson") String eventsJson,
            @Param("cooldownStart") LocalDateTime cooldownStart
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET active = FALSE,
                resolved_at = :observedAt
            WHERE active = TRUE
              AND event_type IN (:eventTypes)
              AND last_seen_at < :observedAt
            """, nativeQuery = true)
    int resolveMissingEvents(
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("observedAt") LocalDateTime observedAt
    );
}
