package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.u_users.model.Worker;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Bulk read model for the observation-only company transfer graph.
 *
 * <p>Every method is one set-based query for all source workers in a shadow run.
 * No method is called once per worker.</p>
 */
public interface WorkloadTransferGraphRepository extends Repository<Worker, Long> {

    @Query(value = """
            WITH source_workers AS (
                SELECT current.worker_id, current.manager_id
                FROM workload_shadow_worker_current current
                WHERE current.worker_id IN (:sourceWorkerIds)
                  AND current.manager_id IS NOT NULL
            ),
            relevant_companies AS (
                SELECT wc.worker_id AS source_worker_id, wc.company_id
                FROM workers_companies wc
                WHERE wc.worker_id IN (:sourceWorkerIds)

                UNION

                SELECT orders.order_worker, orders.order_company
                FROM orders
                WHERE orders.order_worker IN (:sourceWorkerIds)
                  AND COALESCE(orders.order_complete, 0) = 0
                  AND orders.order_company IS NOT NULL

                UNION

                SELECT review.review_worker, orders.order_company
                FROM reviews review
                JOIN order_details detail
                  ON detail.order_detail_id = review.review_order_details
                JOIN orders ON orders.order_id = detail.order_detail_order
                WHERE review.review_worker IN (:sourceWorkerIds)
                  AND COALESCE(review.review_publish, 0) = 0
                  AND orders.order_company IS NOT NULL

                UNION

                SELECT bad_task.bad_review_task_worker, orders.order_company
                FROM bad_review_tasks bad_task
                JOIN orders ON orders.order_id = bad_task.bad_review_task_order
                WHERE bad_task.bad_review_task_worker IN (:sourceWorkerIds)
                  AND bad_task.bad_review_task_status = 'NEW'
                  AND orders.order_company IS NOT NULL

                UNION

                SELECT recovery_task.review_recovery_task_worker,
                       COALESCE(
                           orders.order_company,
                           recovery_task.review_recovery_task_archive_company_id
                       )
                FROM review_recovery_tasks recovery_task
                JOIN review_recovery_batches recovery_batch
                  ON recovery_batch.review_recovery_batch_id =
                     recovery_task.review_recovery_task_batch
                LEFT JOIN orders
                  ON orders.order_id = recovery_task.review_recovery_task_order
                WHERE recovery_task.review_recovery_task_worker IN (:sourceWorkerIds)
                  AND recovery_task.review_recovery_task_status = 'PLANNED'
                  AND recovery_batch.review_recovery_batch_status = 'OPEN'
                  AND COALESCE(
                      orders.order_company,
                      recovery_task.review_recovery_task_archive_company_id
                  ) IS NOT NULL
            )
            SELECT source.worker_id AS sourceWorkerId,
                   source.manager_id AS managerId,
                   company.company_id AS companyId,
                   company.company_title AS companyTitle,
                   COALESCE(company.company_active, 0) AS companyActive,
                   company_status.status_title AS companyStatus,
                   company.company_manager AS companyManagerId
            FROM source_workers source
            JOIN relevant_companies relevant
              ON relevant.source_worker_id = source.worker_id
            JOIN companies company
              ON company.company_id = relevant.company_id
            LEFT JOIN company_status
              ON company_status.company_status_id = company.company_status
            """, nativeQuery = true)
    List<SourceCompanyProjection> findSourceCompanies(
            @Param("sourceWorkerIds") Collection<Long> sourceWorkerIds
    );

    @Query(value = """
            SELECT wc.company_id AS companyId,
                   wc.worker_id AS workerId
            FROM workers_companies wc
            WHERE wc.company_id IN (:companyIds)
              AND wc.worker_id IS NOT NULL
            GROUP BY wc.company_id, wc.worker_id
            """, nativeQuery = true)
    List<CompanyWorkerLinkProjection> findCompanyWorkerLinks(
            @Param("companyIds") Collection<Long> companyIds
    );

    @Query(value = """
            SELECT orders.order_company AS companyId,
                   orders.order_worker AS workerId,
                   COUNT(*) AS activeOrderCount
            FROM orders
            WHERE orders.order_company IN (:companyIds)
              AND COALESCE(orders.order_complete, 0) = 0
            GROUP BY orders.order_company, orders.order_worker
            """, nativeQuery = true)
    List<CompanyOrderOwnershipProjection> findCompanyOrderOwnership(
            @Param("companyIds") Collection<Long> companyIds
    );

    @Query(value = """
            SELECT orders.order_worker AS sourceWorkerId,
                   orders.order_id AS orderId,
                   orders.order_company AS companyId,
                   order_status.order_status_title AS orderStatus,
                   orders.order_worker AS workerId,
                   orders.order_manager AS managerId,
                   COALESCE(orders.order_waiting_for_client, 0) AS waitingForClient,
                   COALESCE(orders.order_client_text_expected, 0) AS clientTextExpected,
                   orders.order_created AS createdDate,
                   orders.order_changed AS changedDate,
                   COALESCE(orders.order_amount, 0) AS declaredOrderUnits
            FROM orders
            JOIN workload_shadow_worker_current source
              ON source.worker_id = orders.order_worker
             AND source.worker_id IN (:sourceWorkerIds)
            JOIN companies company
              ON company.company_id = orders.order_company
            LEFT JOIN order_statuses order_status
              ON order_status.order_status_id = orders.order_status
            WHERE orders.order_company IN (:companyIds)
              AND COALESCE(orders.order_complete, 0) = 0
            """, nativeQuery = true)
    List<OrderProjection> findActiveOrders(
            @Param("sourceWorkerIds") Collection<Long> sourceWorkerIds,
            @Param("companyIds") Collection<Long> companyIds
    );

    @Query(value = """
            SELECT detail.order_detail_order AS orderId,
                   COALESCE(detail.order_detail_amount, 0) AS declaredUnits,
                   COUNT(review.review_id) AS actualReviewCount,
                   COALESCE(SUM(CASE
                       WHEN review.review_id IS NULL THEN 0
                       WHEN COALESCE(review.review_publish, 0) = 1 THEN 0
                       WHEN review.review_text IS NOT NULL
                        AND TRIM(review.review_text) <> ''
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'текст отзыва%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подставить%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подсавить%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'подставить текст%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'подсавить текст%'
                           THEN 0
                       ELSE 1
                   END), 0) AS pendingReviewCount
            FROM order_details detail
            LEFT JOIN reviews review
              ON review.review_order_details = detail.order_detail_id
            WHERE detail.order_detail_order IN (:orderIds)
            GROUP BY detail.order_detail_id,
                     detail.order_detail_order,
                     detail.order_detail_amount
            """, nativeQuery = true)
    List<DetailProjection> findOrderDetails(@Param("orderIds") Collection<Long> orderIds);

    @Query(value = """
            WITH source_workers AS (
                SELECT current.worker_id, current.manager_id
                FROM workload_shadow_worker_current current
                WHERE current.worker_id IN (:sourceWorkerIds)
                  AND current.manager_id IS NOT NULL
            ),
            relevant_reviews AS (
                SELECT source.worker_id AS source_worker_id, review.review_id
                FROM source_workers source
                JOIN orders
                  ON orders.order_worker = source.worker_id
                 AND COALESCE(orders.order_complete, 0) = 0
                JOIN companies company
                  ON company.company_id = orders.order_company
                JOIN order_details detail
                  ON detail.order_detail_order = orders.order_id
                JOIN reviews review
                  ON review.review_order_details = detail.order_detail_id
                 AND COALESCE(review.review_publish, 0) = 0
                WHERE orders.order_company IN (:companyIds)

                UNION

                SELECT source.worker_id, review.review_id
                FROM source_workers source
                JOIN reviews review
                  ON review.review_worker = source.worker_id
                 AND COALESCE(review.review_publish, 0) = 0
                JOIN order_details detail
                  ON detail.order_detail_id = review.review_order_details
                JOIN orders ON orders.order_id = detail.order_detail_order
                JOIN companies company
                  ON company.company_id = orders.order_company
                WHERE orders.order_company IN (:companyIds)
            )
            SELECT relevant.source_worker_id AS sourceWorkerId,
                   review.review_id AS reviewId,
                   orders.order_id AS orderId,
                   orders.order_company AS companyId,
                   review.review_worker AS workerId,
                   review.review_bot AS botId,
                   bot.bot_active AS botActive,
                   bot.bot_worker AS botOwnerWorkerId,
                   review.review_publish_date AS publicationDate,
                   COALESCE(review.review_vigul, 0) AS walked,
                   CASE
                       WHEN review.review_text IS NOT NULL
                        AND TRIM(review.review_text) <> ''
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'текст отзыва%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подставить%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'нужно подсавить%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'подставить текст%'
                        AND LOWER(TRIM(review.review_text)) NOT LIKE 'подсавить текст%'
                           THEN 1
                       ELSE 0
                   END AS textReady,
                   COALESCE(orders.order_waiting_for_client, 0) AS orderWaitingForClient,
                   COALESCE(bot_usage.active_review_count, 0) AS activeBotReviewCount,
                   review.review_account_walk_delay_bot_id AS accountWalkDelayBotId
            FROM relevant_reviews relevant
            JOIN reviews review ON review.review_id = relevant.review_id
            JOIN order_details detail
              ON detail.order_detail_id = review.review_order_details
            JOIN orders ON orders.order_id = detail.order_detail_order
            LEFT JOIN bots bot ON bot.bot_id = review.review_bot
            LEFT JOIN (
                SELECT active_review.review_bot AS bot_id,
                       COUNT(*) AS active_review_count
                FROM reviews active_review
                WHERE COALESCE(active_review.review_publish, 0) = 0
                  AND active_review.review_bot IS NOT NULL
                  AND active_review.review_bot <> 1
                  AND active_review.review_bot IN (
                      SELECT DISTINCT relevant_bot.review_bot
                      FROM relevant_reviews relevant_bot_row
                      JOIN reviews relevant_bot
                        ON relevant_bot.review_id = relevant_bot_row.review_id
                      WHERE relevant_bot.review_bot IS NOT NULL
                        AND relevant_bot.review_bot <> 1
                  )
                GROUP BY active_review.review_bot
            ) bot_usage ON bot_usage.bot_id = review.review_bot
            """, nativeQuery = true)
    List<ReviewProjection> findUnpublishedReviews(
            @Param("sourceWorkerIds") Collection<Long> sourceWorkerIds,
            @Param("companyIds") Collection<Long> companyIds
    );

    @Query(value = """
            WITH source_workers AS (
                SELECT current.worker_id, current.manager_id
                FROM workload_shadow_worker_current current
                WHERE current.worker_id IN (:sourceWorkerIds)
                  AND current.manager_id IS NOT NULL
            ),
            relevant_tasks AS (
                SELECT source.worker_id AS source_worker_id,
                       recovery_task.review_recovery_task_id AS task_id
                FROM source_workers source
                JOIN orders
                  ON orders.order_worker = source.worker_id
                 AND COALESCE(orders.order_complete, 0) = 0
                JOIN companies company
                  ON company.company_id = orders.order_company
                JOIN review_recovery_tasks recovery_task
                  ON recovery_task.review_recovery_task_order = orders.order_id
                 AND recovery_task.review_recovery_task_status = 'PLANNED'
                JOIN review_recovery_batches recovery_batch
                  ON recovery_batch.review_recovery_batch_id =
                     recovery_task.review_recovery_task_batch
                 AND recovery_batch.review_recovery_batch_status = 'OPEN'
                WHERE orders.order_company IN (:companyIds)

                UNION

                SELECT source.worker_id,
                       recovery_task.review_recovery_task_id
                FROM source_workers source
                JOIN review_recovery_tasks recovery_task
                  ON recovery_task.review_recovery_task_worker = source.worker_id
                 AND recovery_task.review_recovery_task_status = 'PLANNED'
                JOIN review_recovery_batches recovery_batch
                  ON recovery_batch.review_recovery_batch_id =
                     recovery_task.review_recovery_task_batch
                 AND recovery_batch.review_recovery_batch_status = 'OPEN'
                LEFT JOIN orders
                  ON orders.order_id = recovery_task.review_recovery_task_order
                JOIN companies company
                  ON company.company_id = COALESCE(
                      orders.order_company,
                      recovery_task.review_recovery_task_archive_company_id
                  )
                WHERE company.company_id IN (:companyIds)
            )
            SELECT relevant.source_worker_id AS sourceWorkerId,
                   recovery_task.review_recovery_task_id AS taskId,
                   recovery_task.review_recovery_task_order AS orderId,
                   COALESCE(
                       orders.order_company,
                       recovery_task.review_recovery_task_archive_company_id
                   ) AS companyId,
                   recovery_task.review_recovery_task_archive_company_id AS archiveCompanyId,
                   recovery_task.review_recovery_task_worker AS workerId,
                   recovery_task.review_recovery_task_manager AS taskManagerId,
                   recovery_batch.review_recovery_batch_manager AS batchManagerId,
                   recovery_task.review_recovery_task_bot AS botId,
                   bot.bot_active AS botActive,
                   recovery_task.review_recovery_task_scheduled_date AS scheduledDate,
                   CASE WHEN orders.order_id IS NULL THEN 1 ELSE 0 END AS archivedSource
            FROM relevant_tasks relevant
            JOIN review_recovery_tasks recovery_task
              ON recovery_task.review_recovery_task_id = relevant.task_id
            JOIN review_recovery_batches recovery_batch
              ON recovery_batch.review_recovery_batch_id =
                 recovery_task.review_recovery_task_batch
            LEFT JOIN orders
              ON orders.order_id = recovery_task.review_recovery_task_order
            LEFT JOIN bots bot
              ON bot.bot_id = recovery_task.review_recovery_task_bot
            """, nativeQuery = true)
    List<RecoveryProjection> findOpenRecoveryTasks(
            @Param("sourceWorkerIds") Collection<Long> sourceWorkerIds,
            @Param("companyIds") Collection<Long> companyIds
    );

    @Query(value = """
            WITH source_workers AS (
                SELECT current.worker_id, current.manager_id
                FROM workload_shadow_worker_current current
                WHERE current.worker_id IN (:sourceWorkerIds)
                  AND current.manager_id IS NOT NULL
            ),
            relevant_tasks AS (
                SELECT source.worker_id AS source_worker_id,
                       bad_task.bad_review_task_id AS task_id
                FROM source_workers source
                JOIN orders
                  ON orders.order_worker = source.worker_id
                 AND COALESCE(orders.order_complete, 0) = 0
                JOIN companies company
                  ON company.company_id = orders.order_company
                JOIN bad_review_tasks bad_task
                  ON bad_task.bad_review_task_order = orders.order_id
                 AND bad_task.bad_review_task_status = 'NEW'
                WHERE orders.order_company IN (:companyIds)

                UNION

                SELECT source.worker_id, bad_task.bad_review_task_id
                FROM source_workers source
                JOIN bad_review_tasks bad_task
                  ON bad_task.bad_review_task_worker = source.worker_id
                 AND bad_task.bad_review_task_status = 'NEW'
                JOIN orders
                  ON orders.order_id = bad_task.bad_review_task_order
                JOIN companies company
                  ON company.company_id = orders.order_company
                WHERE orders.order_company IN (:companyIds)
            )
            SELECT relevant.source_worker_id AS sourceWorkerId,
                   bad_task.bad_review_task_id AS taskId,
                   orders.order_id AS orderId,
                   orders.order_company AS companyId,
                   bad_task.bad_review_task_review AS sourceReviewId,
                   bad_task.bad_review_task_worker AS workerId,
                   bad_task.bad_review_task_bot AS botId,
                   bot.bot_active AS botActive,
                   bad_task.bad_review_task_scheduled_date AS scheduledDate
            FROM relevant_tasks relevant
            JOIN bad_review_tasks bad_task
              ON bad_task.bad_review_task_id = relevant.task_id
            JOIN orders ON orders.order_id = bad_task.bad_review_task_order
            LEFT JOIN bots bot ON bot.bot_id = bad_task.bad_review_task_bot
            """, nativeQuery = true)
    List<BadProjection> findOpenBadTasks(
            @Param("sourceWorkerIds") Collection<Long> sourceWorkerIds,
            @Param("companyIds") Collection<Long> companyIds
    );

    @Query(value = """
            SELECT assignment.review_id AS reviewId,
                   COUNT(*) AS activeCount
            FROM review_performer_assignments assignment
            WHERE assignment.review_id IN (:reviewIds)
              AND assignment.status IN (
                  'CREATED',
                  'OFFERING',
                  'ACCEPTED',
                  'WALKED',
                  'WAITING_PUBLICATION',
                  'PUBLISHED_CLAIMED'
              )
            GROUP BY assignment.review_id
            """, nativeQuery = true)
    List<PerformerCountProjection> findActivePerformerCounts(
            @Param("reviewIds") Collection<Long> reviewIds
    );

    @Query(value = """
            SELECT external_check.review_id AS reviewId,
                   SUM(CASE
                       WHEN external_check.status IN ('PENDING', 'CHECKING') THEN 1
                       ELSE 0
                   END) AS activeCount,
                   SUM(CASE
                       WHEN external_check.status IN (
                           'NEEDS_REVIEW',
                           'NOT_FOUND',
                           'BLOCKED',
                           'ERROR'
                       ) THEN 1
                       ELSE 0
                   END) AS attentionCount
            FROM review_external_checks external_check
            WHERE external_check.review_id IN (:reviewIds)
              AND external_check.status <> 'CONFIRMED'
            GROUP BY external_check.review_id
            """, nativeQuery = true)
    List<ExternalCheckCountProjection> findExternalCheckCounts(
            @Param("reviewIds") Collection<Long> reviewIds
    );

    interface SourceCompanyProjection {
        Long getSourceWorkerId();

        Long getManagerId();

        Long getCompanyId();

        String getCompanyTitle();

        Object getCompanyActive();

        String getCompanyStatus();

        Long getCompanyManagerId();
    }

    interface CompanyWorkerLinkProjection {
        Long getCompanyId();

        Long getWorkerId();
    }

    interface CompanyOrderOwnershipProjection {
        Long getCompanyId();

        Long getWorkerId();

        Long getActiveOrderCount();
    }

    interface OrderProjection {
        Long getSourceWorkerId();

        Long getOrderId();

        Long getCompanyId();

        String getOrderStatus();

        Long getWorkerId();

        Long getManagerId();

        Object getWaitingForClient();

        Object getClientTextExpected();

        LocalDate getCreatedDate();

        LocalDate getChangedDate();

        Integer getDeclaredOrderUnits();
    }

    interface DetailProjection {
        Long getOrderId();

        Integer getDeclaredUnits();

        Integer getActualReviewCount();

        Integer getPendingReviewCount();
    }

    interface ReviewProjection {
        Long getSourceWorkerId();

        Long getReviewId();

        Long getOrderId();

        Long getCompanyId();

        Long getWorkerId();

        Long getBotId();

        Object getBotActive();

        Long getBotOwnerWorkerId();

        LocalDate getPublicationDate();

        Object getWalked();

        Object getTextReady();

        Object getOrderWaitingForClient();

        Long getActiveBotReviewCount();

        Long getAccountWalkDelayBotId();
    }

    interface RecoveryProjection {
        Long getSourceWorkerId();

        Long getTaskId();

        Long getOrderId();

        Long getCompanyId();

        Long getArchiveCompanyId();

        Long getWorkerId();

        Long getTaskManagerId();

        Long getBatchManagerId();

        Long getBotId();

        Object getBotActive();

        LocalDate getScheduledDate();

        Object getArchivedSource();
    }

    interface BadProjection {
        Long getSourceWorkerId();

        Long getTaskId();

        Long getOrderId();

        Long getCompanyId();

        Long getSourceReviewId();

        Long getWorkerId();

        Long getBotId();

        Object getBotActive();

        LocalDate getScheduledDate();
    }

    interface PerformerCountProjection {
        Long getReviewId();

        Long getActiveCount();
    }

    interface ExternalCheckCountProjection {
        Long getReviewId();

        Long getActiveCount();

        Long getAttentionCount();
    }
}
