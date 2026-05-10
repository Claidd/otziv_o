package com.hunt.otziv.archive;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
class OrderArchiveRestoreRepository {

    private static final List<String> ARCHIVE_ONLY_COLUMNS = List.of(
            "archived_at",
            "archive_reason",
            "archive_batch_id",
            "restored_at",
            "restored_by",
            "restore_batch_id"
    );

    private final NamedParameterJdbcTemplate jdbc;

    boolean isAlreadyRestored(Long orderId) {
        Boolean restored = jdbc.queryForObject("""
                SELECT restored_at IS NOT NULL
                FROM archive_orders
                WHERE order_id = :orderId
                """, Map.of("orderId", orderId), Boolean.class);
        return Boolean.TRUE.equals(restored);
    }

    Long findStatusId(String status) {
        List<Long> ids = jdbc.queryForList("""
                SELECT order_status_id
                FROM order_statuses
                WHERE order_status_title = :status
                ORDER BY order_status_id
                LIMIT 1
                """, Map.of("status", status), Long.class);
        return ids.stream().findFirst().orElse(null);
    }

    ArchiveCandidateCounts countArchiveRows(Long orderId) {
        return new ArchiveCandidateCounts(
                count("SELECT COUNT(*) FROM archive_orders WHERE order_id = :orderId", orderId),
                count("SELECT COUNT(*) FROM archive_order_details WHERE order_detail_order = :orderId", orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_reviews ar
                        JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                        WHERE aod.order_detail_order = :orderId
                        """, orderId),
                count("SELECT COUNT(*) FROM archive_bad_review_tasks WHERE bad_review_task_order = :orderId", orderId),
                count("SELECT COUNT(*) FROM archive_next_order_requests WHERE source_order_id = :orderId", orderId),
                count("SELECT COUNT(*) FROM archive_zp WHERE zp_order = :orderId", orderId),
                count("SELECT COUNT(*) FROM archive_payment_check WHERE check_order = :orderId", orderId)
        );
    }

    ArchiveCandidateCounts countLiveConflicts(Long orderId) {
        return new ArchiveCandidateCounts(
                count("""
                        SELECT COUNT(*)
                        FROM orders o
                        JOIN archive_orders ao ON ao.order_id = o.order_id
                        WHERE ao.order_id = :orderId
                        """, orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_order_details aod
                        JOIN order_details od ON od.order_detail_id = aod.order_detail_id
                        WHERE aod.order_detail_order = :orderId
                        """, orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_reviews ar
                        JOIN reviews r ON r.review_id = ar.review_id
                        JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                        WHERE aod.order_detail_order = :orderId
                        """, orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_bad_review_tasks abrt
                        JOIN bad_review_tasks brt ON brt.bad_review_task_id = abrt.bad_review_task_id
                        WHERE abrt.bad_review_task_order = :orderId
                        """, orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_next_order_requests anor
                        JOIN next_order_requests nor
                          ON nor.next_order_request_id = anor.next_order_request_id
                          OR nor.source_order_id = anor.source_order_id
                        WHERE anor.source_order_id = :orderId
                        """, orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_zp az
                        JOIN zp z ON z.zp_id = az.zp_id
                        WHERE az.zp_order = :orderId
                        """, orderId),
                count("""
                        SELECT COUNT(*)
                        FROM archive_payment_check apc
                        JOIN payment_check pc ON pc.check_id = apc.check_id
                        WHERE apc.check_order = :orderId
                        """, orderId)
        );
    }

    ArchiveCandidateCounts restoreOrder(Long orderId, Long targetStatusId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("targetStatusId", targetStatusId);

        long orders = restoreOrders(params);
        long orderDetails = restoreTable(
                "archive_order_details",
                "order_details",
                "aod",
                """
                        FROM archive_order_details aod
                        WHERE aod.order_detail_order = :orderId
                        """,
                params
        );
        long reviews = restoreTable(
                "archive_reviews",
                "reviews",
                "ar",
                """
                        FROM archive_reviews ar
                        JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                        WHERE aod.order_detail_order = :orderId
                        """,
                params
        );
        long badReviewTasks = restoreTable(
                "archive_bad_review_tasks",
                "bad_review_tasks",
                "abrt",
                """
                        FROM archive_bad_review_tasks abrt
                        WHERE abrt.bad_review_task_order = :orderId
                        """,
                params
        );
        long nextOrderRequests = restoreNextOrderRequests(params);
        long zp = restoreTable(
                "archive_zp",
                "zp",
                "az",
                """
                        FROM archive_zp az
                        WHERE az.zp_order = :orderId
                        """,
                params
        );
        long paymentCheck = restoreTable(
                "archive_payment_check",
                "payment_check",
                "apc",
                """
                        FROM archive_payment_check apc
                        WHERE apc.check_order = :orderId
                        """,
                params
        );

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

    Long insertRestoreBatch(
            Long orderId,
            LocalDateTime restoredAt,
            String restoredBy,
            String targetStatus,
            ArchiveCandidateCounts restored,
            String message
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("restoredAt", restoredAt)
                .addValue("restoredBy", trim(restoredBy, 255))
                .addValue("targetStatus", trim(targetStatus, 100))
                .addValue("ordersRestored", restored.orders())
                .addValue("orderDetailsRestored", restored.orderDetails())
                .addValue("reviewsRestored", restored.reviews())
                .addValue("badReviewTasksRestored", restored.badReviewTasks())
                .addValue("nextOrderRequestsRestored", restored.nextOrderRequests())
                .addValue("zpRestored", restored.zp())
                .addValue("paymentCheckRestored", restored.paymentCheck())
                .addValue("message", trim(message, 1000));

        jdbc.update("""
                INSERT INTO archive_restore_batches (
                    archive_order_id,
                    restored_at,
                    restored_by,
                    target_status,
                    status,
                    orders_restored,
                    order_details_restored,
                    reviews_restored,
                    bad_review_tasks_restored,
                    next_order_requests_restored,
                    zp_restored,
                    payment_check_restored,
                    message
                )
                VALUES (
                    :orderId,
                    :restoredAt,
                    :restoredBy,
                    :targetStatus,
                    'COMPLETED',
                    :ordersRestored,
                    :orderDetailsRestored,
                    :reviewsRestored,
                    :badReviewTasksRestored,
                    :nextOrderRequestsRestored,
                    :zpRestored,
                    :paymentCheckRestored,
                    :message
                )
                """, params);

        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
    }

    void markArchiveOrderRestored(Long orderId, Long restoreBatchId, LocalDateTime restoredAt, String restoredBy) {
        jdbc.update("""
                UPDATE archive_orders
                SET restored_at = :restoredAt,
                    restored_by = :restoredBy,
                    restore_batch_id = :restoreBatchId
                WHERE order_id = :orderId
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("restoredAt", restoredAt)
                .addValue("restoredBy", trim(restoredBy, 255))
                .addValue("restoreBatchId", restoreBatchId));
    }

    private long restoreOrders(MapSqlParameterSource params) {
        List<String> columns = commonColumns("archive_orders", "orders");
        String sql = "INSERT INTO orders (" + quoteList(columns) + ") "
                + "SELECT " + selectList("ao", columns) + " "
                + "FROM archive_orders ao WHERE ao.order_id = :orderId";
        long restored = jdbc.update(sql, params);
        jdbc.update("""
                UPDATE orders
                SET order_status = :targetStatusId,
                    order_changed = CURRENT_DATE()
                WHERE order_id = :orderId
                """, params);
        return restored;
    }

    private long restoreTable(
            String archiveTable,
            String liveTable,
            String archiveAlias,
            String fromClause,
            MapSqlParameterSource params
    ) {
        List<String> columns = commonColumns(archiveTable, liveTable);
        String sql = "INSERT INTO " + liveTable + " (" + quoteList(columns) + ") "
                + "SELECT " + selectList(archiveAlias, columns) + " "
                + fromClause;
        return jdbc.update(sql, params);
    }

    private long restoreNextOrderRequests(MapSqlParameterSource params) {
        return jdbc.update("""
                INSERT INTO next_order_requests (
                    next_order_request_id,
                    company_id,
                    filial_id,
                    source_order_id,
                    created_order_id,
                    request_status,
                    attempts,
                    error_message,
                    created_at,
                    updated_at
                )
                SELECT
                    anor.next_order_request_id,
                    anor.company_id,
                    anor.filial_id,
                    anor.source_order_id,
                    CASE
                        WHEN anor.created_order_id IS NULL THEN NULL
                        WHEN EXISTS (
                            SELECT 1
                            FROM orders created_order
                            WHERE created_order.order_id = anor.created_order_id
                        ) THEN anor.created_order_id
                        ELSE NULL
                    END,
                    anor.request_status,
                    anor.attempts,
                    anor.error_message,
                    anor.created_at,
                    anor.updated_at
                FROM archive_next_order_requests anor
                WHERE anor.source_order_id = :orderId
                """, params);
    }

    private List<String> commonColumns(String sourceTable, String targetTable) {
        return jdbc.queryForList("""
                        SELECT source_cols.COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS source_cols
                        JOIN INFORMATION_SCHEMA.COLUMNS target_cols
                          ON target_cols.TABLE_SCHEMA = source_cols.TABLE_SCHEMA
                         AND target_cols.TABLE_NAME = :targetTable
                         AND target_cols.COLUMN_NAME = source_cols.COLUMN_NAME
                        WHERE source_cols.TABLE_SCHEMA = DATABASE()
                          AND source_cols.TABLE_NAME = :sourceTable
                          AND COALESCE(source_cols.GENERATION_EXPRESSION, '') = ''
                          AND COALESCE(target_cols.GENERATION_EXPRESSION, '') = ''
                        ORDER BY target_cols.ORDINAL_POSITION
                        """,
                Map.of(
                        "sourceTable", sourceTable,
                        "targetTable", targetTable
                ),
                String.class
        ).stream()
                .filter(column -> !ARCHIVE_ONLY_COLUMNS.contains(column))
                .map(this::safeIdentifier)
                .toList();
    }

    private long count(String sql, Long orderId) {
        Long value = jdbc.queryForObject(sql, Map.of("orderId", orderId), Long.class);
        return value == null ? 0L : value;
    }

    private String quoteList(List<String> columns) {
        return columns.stream()
                .map(column -> "`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalStateException("No columns available for archive restore"));
    }

    private String selectList(String alias, List<String> columns) {
        String prefix = safeIdentifier(alias);
        return columns.stream()
                .map(column -> prefix + ".`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalStateException("No columns available for archive restore"));
    }

    private String safeIdentifier(String identifier) {
        if (!StringUtils.hasText(identifier) || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
