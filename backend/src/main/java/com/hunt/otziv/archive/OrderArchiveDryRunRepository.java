package com.hunt.otziv.archive;

import com.hunt.otziv.analytics.service.AnalyticsAggregateReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
class OrderArchiveDryRunRepository {

    private static final List<String> ARCHIVE_METADATA_COLUMNS = List.of(
            "archived_at",
            "archive_reason",
            "archive_batch_id"
    );

    private static final String ELIGIBLE_ORDER_WHERE = """
            FROM orders o
            JOIN order_statuses s ON s.order_status_id = o.order_status
            WHERE s.order_status_title IN ('Архив', 'Оплачено')
              AND (
                    (s.order_status_title = 'Оплачено'
                     AND COALESCE(o.order_pay_day, o.order_changed, o.order_created) <= :cutoffDate)
                 OR (s.order_status_title = 'Архив'
                     AND COALESCE(o.order_changed, o.order_pay_day, o.order_created) <= :cutoffDate)
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM bad_review_tasks brt
                    WHERE brt.bad_review_task_order = o.order_id
                      AND brt.bad_review_task_status = 'NEW'
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM next_order_requests nor
                    WHERE nor.source_order_id = o.order_id
                      AND nor.request_status IN ('PENDING', 'FAILED')
              )
            """;

    private static final String CANDIDATE_ORDER_CTE = """
            WITH candidate_orders AS (
                SELECT o.order_id
                """ + ELIGIBLE_ORDER_WHERE + """
                ORDER BY
                    CASE
                        WHEN s.order_status_title = 'Оплачено'
                            THEN COALESCE(o.order_pay_day, o.order_changed, o.order_created)
                        ELSE COALESCE(o.order_changed, o.order_pay_day, o.order_created)
                    END,
                    o.order_id
                LIMIT :batchLimit
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    long countEligibleOrders(LocalDate cutoffDate) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) " + ELIGIBLE_ORDER_WHERE,
                Map.of("cutoffDate", cutoffDate),
                Long.class
        );
        return count == null ? 0L : count;
    }

    void prepareCandidateOrders(LocalDate cutoffDate, int batchLimit) {
        jdbc.update("""
                CREATE TEMPORARY TABLE IF NOT EXISTS archive_candidate_orders (
                    order_id BIGINT NOT NULL,
                    PRIMARY KEY (order_id)
                ) ENGINE = MEMORY
                """, Map.of());
        jdbc.update("DELETE FROM archive_candidate_orders", Map.of());
        jdbc.update("""
                INSERT INTO archive_candidate_orders (order_id)
                SELECT o.order_id
                """ + ELIGIBLE_ORDER_WHERE + """
                ORDER BY
                    CASE
                        WHEN s.order_status_title = 'Оплачено'
                            THEN COALESCE(o.order_pay_day, o.order_changed, o.order_created)
                        ELSE COALESCE(o.order_changed, o.order_pay_day, o.order_created)
                    END,
                    o.order_id
                LIMIT :batchLimit
                """, candidateParams(cutoffDate, batchLimit));
    }

    ArchiveCandidateCounts countSelected(LocalDate cutoffDate, int batchLimit) {
        MapSqlParameterSource params = candidateParams(cutoffDate, batchLimit);
        return jdbc.queryForObject(CANDIDATE_ORDER_CTE + """
                SELECT
                    (SELECT COUNT(*) FROM candidate_orders) AS orders_selected,
                    (
                        SELECT COUNT(*)
                        FROM order_details od
                        JOIN candidate_orders co ON co.order_id = od.order_detail_order
                    ) AS order_details_selected,
                    (
                        SELECT COUNT(*)
                        FROM reviews r
                        JOIN order_details od ON od.order_detail_id = r.review_order_details
                        JOIN candidate_orders co ON co.order_id = od.order_detail_order
                    ) AS reviews_selected,
                    (
                        SELECT COUNT(*)
                        FROM bad_review_tasks brt
                        JOIN candidate_orders co ON co.order_id = brt.bad_review_task_order
                    ) AS bad_review_tasks_selected,
                    (
                        SELECT COUNT(*)
                        FROM next_order_requests nor
                        JOIN candidate_orders co ON co.order_id = nor.source_order_id
                    ) AS next_order_requests_selected,
                    (
                        SELECT COUNT(*)
                        FROM zp z
                        JOIN candidate_orders co ON co.order_id = z.zp_order
                    ) AS zp_selected,
                    (
                        SELECT COUNT(*)
                        FROM payment_check pc
                        JOIN candidate_orders co ON co.order_id = pc.check_order
                    ) AS payment_check_selected
                """, params, (rs, rowNum) -> new ArchiveCandidateCounts(
                rs.getLong("orders_selected"),
                rs.getLong("order_details_selected"),
                rs.getLong("reviews_selected"),
                rs.getLong("bad_review_tasks_selected"),
                rs.getLong("next_order_requests_selected"),
                rs.getLong("zp_selected"),
                rs.getLong("payment_check_selected")
        ));
    }

    List<ArchiveOrderCandidateItem> findCandidateOrders(LocalDate cutoffDate, int batchLimit, int previewLimit) {
        MapSqlParameterSource params = candidateParams(cutoffDate, batchLimit)
                .addValue("previewLimit", previewLimit);
        return jdbc.query(CANDIDATE_ORDER_CTE + """
                SELECT
                    o.order_id,
                    o.order_company,
                    COALESCE(c.company_title, '') AS company_title,
                    COALESCE(c.company_phone, '') AS company_phone,
                    COALESCE(c.company_city, city.city_title, '') AS company_city,
                    COALESCE(f.filial_title, '') AS filial_title,
                    COALESCE(s.order_status_title, '') AS order_status_title,
                    o.order_sum,
                    o.order_amount,
                    o.order_counter,
                    COALESCE(mu.fio, mu.username, '') AS manager_name,
                    COALESCE(wu.fio, wu.username, '') AS worker_name,
                    o.order_created,
                    o.order_changed,
                    o.order_pay_day,
                    CASE
                        WHEN s.order_status_title = 'Оплачено'
                            THEN COALESCE(o.order_pay_day, o.order_changed, o.order_created)
                        ELSE COALESCE(o.order_changed, o.order_pay_day, o.order_created)
                    END AS candidate_date,
                    (
                        SELECT COUNT(*)
                        FROM order_details od
                        WHERE od.order_detail_order = o.order_id
                    ) AS order_details_count,
                    (
                        SELECT COUNT(*)
                        FROM reviews r
                        JOIN order_details od ON od.order_detail_id = r.review_order_details
                        WHERE od.order_detail_order = o.order_id
                    ) AS reviews_count
                FROM candidate_orders co
                JOIN orders o ON o.order_id = co.order_id
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN filial f ON f.filial_id = o.order_filial
                LEFT JOIN cities city ON city.city_id = f.city_id
                LEFT JOIN order_statuses s ON s.order_status_id = o.order_status
                LEFT JOIN managers m ON m.manager_id = o.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = o.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                ORDER BY candidate_date, o.order_id
                LIMIT :previewLimit
                """, params, (rs, rowNum) -> candidateItem(rs));
    }

    ArchiveCandidateCounts countPreparedCandidates() {
        return new ArchiveCandidateCounts(
                count("""
                        SELECT COUNT(*)
                        FROM archive_candidate_orders
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM order_details od
                        JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM reviews r
                        JOIN order_details od ON od.order_detail_id = r.review_order_details
                        JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM bad_review_tasks brt
                        JOIN archive_candidate_orders co ON co.order_id = brt.bad_review_task_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM next_order_requests nor
                        JOIN archive_candidate_orders co ON co.order_id = nor.source_order_id
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM zp z
                        JOIN archive_candidate_orders co ON co.order_id = z.zp_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM payment_check pc
                        JOIN archive_candidate_orders co ON co.order_id = pc.check_order
                        """)
        );
    }

    long countMissingClosedAnalyticsMonths(LocalDate cutoffDate, int batchLimit, LocalDate currentMonthStart) {
        MapSqlParameterSource params = candidateParams(cutoffDate, batchLimit)
                .addValue("currentMonthStart", currentMonthStart)
                .addValue("adminScopeKey", AnalyticsAggregateReadService.SCOPE_ADMIN_ALL);
        Long count = jdbc.queryForObject(CANDIDATE_ORDER_CTE + """
                , candidate_dates AS (
                    SELECT COALESCE(o.order_pay_day, o.order_changed, o.order_created) AS metric_date
                    FROM orders o
                    JOIN candidate_orders co ON co.order_id = o.order_id

                    UNION ALL

                    SELECT r.review_publish_date AS metric_date
                    FROM reviews r
                    JOIN order_details od ON od.order_detail_id = r.review_order_details
                    JOIN candidate_orders co ON co.order_id = od.order_detail_order

                    UNION ALL

                    SELECT z.zp_date AS metric_date
                    FROM zp z
                    JOIN candidate_orders co ON co.order_id = z.zp_order

                    UNION ALL

                    SELECT pc.check_date AS metric_date
                    FROM payment_check pc
                    JOIN candidate_orders co ON co.order_id = pc.check_order
                ),
                candidate_months AS (
                    SELECT DISTINCT DATE_SUB(metric_date, INTERVAL (DAYOFMONTH(metric_date) - 1) DAY) AS month_start
                    FROM candidate_dates
                    WHERE metric_date IS NOT NULL
                      AND metric_date < :currentMonthStart
                )
                SELECT COUNT(*)
                FROM candidate_months cm
                LEFT JOIN analytics_monthly_total amt
                  ON amt.month_start = cm.month_start
                 AND amt.scope_key = :adminScopeKey
                 AND amt.period_closed = 1
                WHERE amt.analytics_monthly_total_id IS NULL
                """, params, Long.class);
        return count == null ? 0L : count;
    }

    long countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate currentMonthStart) {
        jdbc.update("""
                CREATE TEMPORARY TABLE IF NOT EXISTS archive_candidate_dates (
                    metric_date DATE NULL
                ) ENGINE = MEMORY
                """, Map.of());
        jdbc.update("DELETE FROM archive_candidate_dates", Map.of());
        jdbc.update("""
                INSERT INTO archive_candidate_dates (metric_date)
                SELECT COALESCE(o.order_pay_day, o.order_changed, o.order_created)
                FROM orders o
                JOIN archive_candidate_orders co ON co.order_id = o.order_id
                """, Map.of());
        jdbc.update("""
                INSERT INTO archive_candidate_dates (metric_date)
                SELECT r.review_publish_date
                FROM reviews r
                JOIN order_details od ON od.order_detail_id = r.review_order_details
                JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                """, Map.of());
        jdbc.update("""
                INSERT INTO archive_candidate_dates (metric_date)
                SELECT z.zp_date
                FROM zp z
                JOIN archive_candidate_orders co ON co.order_id = z.zp_order
                """, Map.of());
        jdbc.update("""
                INSERT INTO archive_candidate_dates (metric_date)
                SELECT pc.check_date
                FROM payment_check pc
                JOIN archive_candidate_orders co ON co.order_id = pc.check_order
                """, Map.of());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("currentMonthStart", currentMonthStart)
                .addValue("adminScopeKey", AnalyticsAggregateReadService.SCOPE_ADMIN_ALL);
        Long count = jdbc.queryForObject("""
                WITH candidate_months AS (
                    SELECT DISTINCT DATE_SUB(metric_date, INTERVAL (DAYOFMONTH(metric_date) - 1) DAY) AS month_start
                    FROM archive_candidate_dates
                    WHERE metric_date IS NOT NULL
                      AND metric_date < :currentMonthStart
                )
                SELECT COUNT(*)
                FROM candidate_months cm
                LEFT JOIN analytics_monthly_total amt
                  ON amt.month_start = cm.month_start
                 AND amt.scope_key = :adminScopeKey
                 AND amt.period_closed = 1
                WHERE amt.analytics_monthly_total_id IS NULL
                """, params, Long.class);
        return count == null ? 0L : count;
    }

    Long insertDryRunBatch(
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String archiveReason,
            int retentionDays,
            long eligibleOrders,
            ArchiveCandidateCounts selected,
            String message
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startedAt", startedAt)
                .addValue("finishedAt", finishedAt)
                .addValue("archiveReason", archiveReason)
                .addValue("retentionDays", retentionDays)
                .addValue("ordersSelected", selected.orders())
                .addValue("ordersArchived", selected.orders())
                .addValue("orderDetailsArchived", selected.orderDetails())
                .addValue("reviewsArchived", selected.reviews())
                .addValue("badReviewTasksArchived", selected.badReviewTasks())
                .addValue("nextOrderRequestsArchived", selected.nextOrderRequests())
                .addValue("zpArchived", selected.zp())
                .addValue("paymentCheckArchived", selected.paymentCheck())
                .addValue("message", trim(message + "; eligibleOrders=" + eligibleOrders, 1000));

        jdbc.update("""
                INSERT INTO archive_batches (
                    started_at,
                    finished_at,
                    dry_run,
                    status,
                    archive_reason,
                    retention_days,
                    orders_selected,
                    orders_archived,
                    order_details_archived,
                    reviews_archived,
                    bad_review_tasks_archived,
                    next_order_requests_archived,
                    zp_archived,
                    payment_check_archived,
                    message
                )
                VALUES (
                    :startedAt,
                    :finishedAt,
                    1,
                    'DRY_RUN_COMPLETED',
                    :archiveReason,
                    :retentionDays,
                    :ordersSelected,
                    :ordersArchived,
                    :orderDetailsArchived,
                    :reviewsArchived,
                    :badReviewTasksArchived,
                    :nextOrderRequestsArchived,
                    :zpArchived,
                    :paymentCheckArchived,
                    :message
                )
                """, params);

        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
    }

    Long insertStartedArchiveBatch(
            LocalDateTime startedAt,
            String archiveReason,
            int retentionDays,
            ArchiveCandidateCounts selected,
            String message
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startedAt", startedAt)
                .addValue("archiveReason", archiveReason)
                .addValue("retentionDays", retentionDays)
                .addValue("ordersSelected", selected.orders())
                .addValue("message", trim(message, 1000));

        jdbc.update("""
                INSERT INTO archive_batches (
                    started_at,
                    dry_run,
                    status,
                    archive_reason,
                    retention_days,
                    orders_selected,
                    message
                )
                VALUES (
                    :startedAt,
                    0,
                    'STARTED',
                    :archiveReason,
                    :retentionDays,
                    :ordersSelected,
                    :message
                )
                """, params);

        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
    }

    void copyPreparedCandidatesToArchive(Long batchId, LocalDateTime archivedAt, String archiveReason) {
        MapSqlParameterSource params = archiveParams(batchId, archivedAt, archiveReason);
        copyOrders(params);
        copyTable(
                "order_details",
                "archive_order_details",
                "od",
                """
                        FROM order_details od
                        JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                        """,
                params
        );
        copyTable(
                "reviews",
                "archive_reviews",
                "r",
                """
                        FROM reviews r
                        JOIN order_details od ON od.order_detail_id = r.review_order_details
                        JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                        """,
                params
        );
        copyTable(
                "bad_review_tasks",
                "archive_bad_review_tasks",
                "brt",
                """
                        FROM bad_review_tasks brt
                        JOIN archive_candidate_orders co ON co.order_id = brt.bad_review_task_order
                        """,
                params
        );
        copyTable(
                "next_order_requests",
                "archive_next_order_requests",
                "nor",
                """
                        FROM next_order_requests nor
                        JOIN archive_candidate_orders co ON co.order_id = nor.source_order_id
                        """,
                params
        );
        copyTable(
                "zp",
                "archive_zp",
                "z",
                """
                        FROM zp z
                        JOIN archive_candidate_orders co ON co.order_id = z.zp_order
                        """,
                params
        );
        copyTable(
                "payment_check",
                "archive_payment_check",
                "pc",
                """
                        FROM payment_check pc
                        JOIN archive_candidate_orders co ON co.order_id = pc.check_order
                        """,
                params
        );
    }

    ArchiveCandidateCounts countArchivedPreparedCandidates() {
        return new ArchiveCandidateCounts(
                count("""
                        SELECT COUNT(*)
                        FROM archive_orders ao
                        JOIN archive_candidate_orders co ON co.order_id = ao.order_id
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM archive_order_details aod
                        JOIN order_details od ON od.order_detail_id = aod.order_detail_id
                        JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM archive_reviews ar
                        JOIN order_details od ON od.order_detail_id = ar.review_order_details
                        JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM archive_bad_review_tasks abrt
                        JOIN archive_candidate_orders co ON co.order_id = abrt.bad_review_task_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM archive_next_order_requests anor
                        JOIN archive_candidate_orders co ON co.order_id = anor.source_order_id
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM archive_zp az
                        JOIN archive_candidate_orders co ON co.order_id = az.zp_order
                        """),
                count("""
                        SELECT COUNT(*)
                        FROM archive_payment_check apc
                        JOIN archive_candidate_orders co ON co.order_id = apc.check_order
                        """)
        );
    }

    ArchiveCandidateCounts deletePreparedCandidatesFromLive() {
        long paymentCheck = jdbc.update("""
                DELETE pc
                FROM payment_check pc
                JOIN archive_candidate_orders co ON co.order_id = pc.check_order
                """, Map.of());
        long zp = jdbc.update("""
                DELETE z
                FROM zp z
                JOIN archive_candidate_orders co ON co.order_id = z.zp_order
                """, Map.of());
        long nextOrderRequests = jdbc.update("""
                DELETE nor
                FROM next_order_requests nor
                JOIN archive_candidate_orders co ON co.order_id = nor.source_order_id
                """, Map.of());
        long badReviewTasks = jdbc.update("""
                DELETE brt
                FROM bad_review_tasks brt
                JOIN archive_candidate_orders co ON co.order_id = brt.bad_review_task_order
                """, Map.of());
        long reviews = jdbc.update("""
                DELETE r
                FROM reviews r
                JOIN order_details od ON od.order_detail_id = r.review_order_details
                JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                """, Map.of());
        long orderDetails = jdbc.update("""
                DELETE od
                FROM order_details od
                JOIN archive_candidate_orders co ON co.order_id = od.order_detail_order
                """, Map.of());
        long orders = jdbc.update("""
                DELETE o
                FROM orders o
                JOIN archive_candidate_orders co ON co.order_id = o.order_id
                """, Map.of());

        return new ArchiveCandidateCounts(
                orders,
                orderDetails,
                reviews,
                badReviewTasks,
                nextOrderRequests,
                zp,
                paymentCheck
        );
    }

    void completeArchiveBatch(
            Long batchId,
            LocalDateTime finishedAt,
            ArchiveCandidateCounts archived,
            ArchiveCandidateCounts deleted,
            String message
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("finishedAt", finishedAt)
                .addValue("ordersArchived", archived.orders())
                .addValue("orderDetailsArchived", archived.orderDetails())
                .addValue("reviewsArchived", archived.reviews())
                .addValue("badReviewTasksArchived", archived.badReviewTasks())
                .addValue("nextOrderRequestsArchived", archived.nextOrderRequests())
                .addValue("zpArchived", archived.zp())
                .addValue("paymentCheckArchived", archived.paymentCheck())
                .addValue("message", trim(message + "; deleted=" + deleted, 1000));

        jdbc.update("""
                UPDATE archive_batches
                SET finished_at = :finishedAt,
                    status = 'COMPLETED',
                    orders_archived = :ordersArchived,
                    order_details_archived = :orderDetailsArchived,
                    reviews_archived = :reviewsArchived,
                    bad_review_tasks_archived = :badReviewTasksArchived,
                    next_order_requests_archived = :nextOrderRequestsArchived,
                    zp_archived = :zpArchived,
                    payment_check_archived = :paymentCheckArchived,
                    message = :message
                WHERE archive_batch_id = :batchId
                """, params);
    }

    List<ArchiveBatchSummary> findLatestBatches(int limit) {
        return jdbc.query("""
                SELECT
                    archive_batch_id,
                    started_at,
                    finished_at,
                    dry_run,
                    status,
                    archive_reason,
                    retention_days,
                    orders_selected,
                    orders_archived,
                    order_details_archived,
                    reviews_archived,
                    bad_review_tasks_archived,
                    next_order_requests_archived,
                    zp_archived,
                    payment_check_archived,
                    message
                FROM archive_batches
                ORDER BY archive_batch_id DESC
                LIMIT :limit
                """, Map.of("limit", limit), batchSummaryMapper());
    }

    ArchiveBatchSummary findBatch(Long batchId) {
        List<ArchiveBatchSummary> batches = jdbc.query("""
                SELECT
                    archive_batch_id,
                    started_at,
                    finished_at,
                    dry_run,
                    status,
                    archive_reason,
                    retention_days,
                    orders_selected,
                    orders_archived,
                    order_details_archived,
                    reviews_archived,
                    bad_review_tasks_archived,
                    next_order_requests_archived,
                    zp_archived,
                    payment_check_archived,
                    message
                FROM archive_batches
                WHERE archive_batch_id = :batchId
                LIMIT 1
                """, Map.of("batchId", batchId), batchSummaryMapper());
        return batches.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Archive batch not found: " + batchId));
    }

    List<ArchiveOrderCandidateItem> findArchivedOrdersByBatch(Long batchId, int limit) {
        return jdbc.query("""
                SELECT
                    ao.order_id,
                    ao.order_company,
                    COALESCE(ao.company_title_snapshot, c.company_title, '') AS company_title,
                    COALESCE(ao.company_phone_snapshot, c.company_phone, '') AS company_phone,
                    COALESCE(ao.company_city_snapshot, c.company_city, city.city_title, '') AS company_city,
                    COALESCE(ao.filial_title_snapshot, f.filial_title, '') AS filial_title,
                    COALESCE(s.order_status_title, '') AS order_status_title,
                    ao.order_sum,
                    ao.order_amount,
                    ao.order_counter,
                    COALESCE(ao.manager_name_snapshot, mu.fio, mu.username, '') AS manager_name,
                    COALESCE(ao.worker_name_snapshot, wu.fio, wu.username, '') AS worker_name,
                    ao.order_created,
                    ao.order_changed,
                    ao.order_pay_day,
                    COALESCE(ao.order_pay_day, ao.order_changed, ao.order_created) AS candidate_date,
                    (
                        SELECT COUNT(*)
                        FROM archive_order_details aod
                        WHERE aod.order_detail_order = ao.order_id
                    ) AS order_details_count,
                    (
                        SELECT COUNT(*)
                        FROM archive_reviews ar
                        JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                        WHERE aod.order_detail_order = ao.order_id
                    ) AS reviews_count
                FROM archive_orders ao
                LEFT JOIN companies c ON c.company_id = ao.order_company
                LEFT JOIN filial f ON f.filial_id = ao.order_filial
                LEFT JOIN cities city ON city.city_id = f.city_id
                LEFT JOIN order_statuses s ON s.order_status_id = ao.order_status
                LEFT JOIN managers m ON m.manager_id = ao.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = ao.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                WHERE ao.archive_batch_id = :batchId
                ORDER BY ao.order_id
                LIMIT :limit
                """, Map.of("batchId", batchId, "limit", limit), (rs, rowNum) -> candidateItem(rs));
    }

    boolean tryAcquireArchiveLock(String lockName, int timeoutSeconds) {
        Long acquired = jdbc.queryForObject("""
                SELECT GET_LOCK(:lockName, :timeoutSeconds)
                """, Map.of("lockName", lockName, "timeoutSeconds", timeoutSeconds), Long.class);
        return acquired != null && acquired == 1L;
    }

    void releaseArchiveLock(String lockName) {
        jdbc.queryForObject("SELECT RELEASE_LOCK(:lockName)", Map.of("lockName", lockName), Long.class);
    }

    ArchiveLockStatus lockStatus(String lockName) {
        Long ownerConnectionId = jdbc.queryForObject(
                "SELECT IS_USED_LOCK(:lockName)",
                Map.of("lockName", lockName),
                Long.class
        );
        Long currentConnectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Map.of(), Long.class);
        return new ArchiveLockStatus(
                lockName,
                ownerConnectionId != null,
                ownerConnectionId,
                ownerConnectionId != null && ownerConnectionId.equals(currentConnectionId)
        );
    }

    private MapSqlParameterSource candidateParams(LocalDate cutoffDate, int batchLimit) {
        return new MapSqlParameterSource()
                .addValue("cutoffDate", cutoffDate)
                .addValue("batchLimit", batchLimit);
    }

    private MapSqlParameterSource archiveParams(Long batchId, LocalDateTime archivedAt, String archiveReason) {
        return new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("archivedAt", archivedAt)
                .addValue("archiveReason", archiveReason);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Map.of(), Long.class);
        return value == null ? 0L : value;
    }

    private void copyOrders(MapSqlParameterSource params) {
        List<String> columns = commonColumns("orders", "archive_orders");
        String archiveColumns = quoteList(columns) + """
                , archived_at, archive_reason, archive_batch_id,
                  company_title_snapshot, company_phone_snapshot, company_city_snapshot,
                  filial_title_snapshot, manager_name_snapshot, worker_name_snapshot
                """;
        String selectColumns = selectList("o", columns) + """
                , :archivedAt, :archiveReason, :batchId,
                  c.company_title,
                  c.company_phone,
                  COALESCE(city.city_title, c.company_city),
                  f.filial_title,
                  manager_user.fio,
                  worker_user.fio
                """;

        jdbc.update("""
                INSERT IGNORE INTO archive_orders (
                """ + archiveColumns + """
                )
                SELECT
                """ + selectColumns + """
                FROM orders o
                JOIN archive_candidate_orders co ON co.order_id = o.order_id
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN filial f ON f.filial_id = o.order_filial
                LEFT JOIN cities city ON city.city_id = f.city_id
                LEFT JOIN managers m ON m.manager_id = o.order_manager
                LEFT JOIN users manager_user ON manager_user.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = o.order_worker
                LEFT JOIN users worker_user ON worker_user.id = w.user_id
                """, params);
    }

    private void copyTable(
            String sourceTable,
            String archiveTable,
            String sourceAlias,
            String fromClause,
            MapSqlParameterSource params
    ) {
        List<String> columns = commonColumns(sourceTable, archiveTable);
        String sql = "INSERT IGNORE INTO " + archiveTable
                + " (" + quoteList(columns) + ", archived_at, archive_reason, archive_batch_id) "
                + "SELECT " + selectList(sourceAlias, columns) + ", :archivedAt, :archiveReason, :batchId "
                + fromClause;
        jdbc.update(sql, params);
    }

    private List<String> commonColumns(String sourceTable, String archiveTable) {
        return jdbc.queryForList("""
                        SELECT source_cols.COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS source_cols
                        JOIN INFORMATION_SCHEMA.COLUMNS archive_cols
                          ON archive_cols.TABLE_SCHEMA = source_cols.TABLE_SCHEMA
                         AND archive_cols.TABLE_NAME = :archiveTable
                         AND archive_cols.COLUMN_NAME = source_cols.COLUMN_NAME
                        WHERE source_cols.TABLE_SCHEMA = DATABASE()
                          AND source_cols.TABLE_NAME = :sourceTable
                          AND COALESCE(source_cols.GENERATION_EXPRESSION, '') = ''
                          AND COALESCE(archive_cols.GENERATION_EXPRESSION, '') = ''
                        ORDER BY source_cols.ORDINAL_POSITION
                        """,
                Map.of(
                        "sourceTable", sourceTable,
                        "archiveTable", archiveTable
                ),
                String.class
        ).stream()
                .filter(column -> !ARCHIVE_METADATA_COLUMNS.contains(column))
                .map(this::safeIdentifier)
                .toList();
    }

    private String quoteList(List<String> columns) {
        return columns.stream()
                .map(column -> "`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalStateException("No columns available for archive copy"));
    }

    private String selectList(String alias, List<String> columns) {
        String prefix = safeIdentifier(alias);
        return columns.stream()
                .map(column -> prefix + ".`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalStateException("No columns available for archive copy"));
    }

    private String safeIdentifier(String identifier) {
        if (!StringUtils.hasText(identifier) || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }

    private RowMapper<ArchiveBatchSummary> batchSummaryMapper() {
        return (rs, rowNum) -> new ArchiveBatchSummary(
                rs.getLong("archive_batch_id"),
                toLocalDateTime(rs.getTimestamp("started_at")),
                toLocalDateTime(rs.getTimestamp("finished_at")),
                rs.getBoolean("dry_run"),
                rs.getString("status"),
                rs.getString("archive_reason"),
                rs.getInt("retention_days"),
                rs.getLong("orders_selected"),
                rs.getLong("orders_archived"),
                rs.getLong("order_details_archived"),
                rs.getLong("reviews_archived"),
                rs.getLong("bad_review_tasks_archived"),
                rs.getLong("next_order_requests_archived"),
                rs.getLong("zp_archived"),
                rs.getLong("payment_check_archived"),
                rs.getString("message")
        );
    }

    private ArchiveOrderCandidateItem candidateItem(ResultSet rs) throws SQLException {
        return new ArchiveOrderCandidateItem(
                rowLong(rs, "order_id"),
                rowLong(rs, "order_company"),
                safeString(rs.getString("company_title")),
                safeString(rs.getString("company_phone")),
                safeString(rs.getString("company_city")),
                safeString(rs.getString("filial_title")),
                safeString(rs.getString("order_status_title")),
                rowBigDecimal(rs, "order_sum"),
                rowInteger(rs, "order_amount"),
                rowInteger(rs, "order_counter"),
                safeString(rs.getString("manager_name")),
                safeString(rs.getString("worker_name")),
                rowLocalDate(rs, "order_created"),
                rowLocalDate(rs, "order_changed"),
                rowLocalDate(rs, "order_pay_day"),
                rowLocalDate(rs, "candidate_date"),
                rowLong(rs, "order_details_count", 0L),
                rowLong(rs, "reviews_count", 0L)
        );
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Long rowLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    private long rowLong(ResultSet rs, String column, long fallback) throws SQLException {
        Long value = rowLong(rs, column);
        return value == null ? fallback : value;
    }

    private Integer rowInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }

    private BigDecimal rowBigDecimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate rowLocalDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }
}
