package com.hunt.otziv.archive.repository;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import com.hunt.otziv.archive.dto.ArchiveBadReviewTaskItem;
import com.hunt.otziv.archive.dto.ArchiveNextOrderRequestItem;
import com.hunt.otziv.archive.dto.ArchiveOrderDetailItem;
import com.hunt.otziv.archive.dto.ArchivePaymentCheckItem;
import com.hunt.otziv.archive.dto.ArchiveReviewItem;
import com.hunt.otziv.archive.dto.ArchiveZpItem;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ManagerArchiveRepository {

    private static final int MAX_KEYWORD_TOKENS = 6;

    private final NamedParameterJdbcTemplate jdbc;

    public long countOrders(ArchiveAccessScope scope, String mode, String keyword) {
        MapSqlParameterSource params = orderParams(scope, mode, keyword);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                """ + archiveOrderIdsSelect(scope, mode, keyword) + """
                    UNION ALL
                """ + liveClosedOrderIdsSelect(scope, mode, keyword) + """
                ) archive_union
                """, params, Long.class);
        return count == null ? 0L : count;
    }

    public List<ManagerArchiveOrderListItem> findOrders(
            ArchiveAccessScope scope,
            String mode,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        MapSqlParameterSource params = orderParams(scope, mode, keyword)
                .addValue("limit", pageSize)
                .addValue("offset", Math.max(pageNumber, 0) * pageSize);
        String direction = orderDirection(sortDirection);
        String orderBy = "ORDER BY sort_at " + direction + ", order_id " + direction;

        return jdbc.query("""
                WITH archive_page AS (
                    SELECT *
                    FROM (
                """ + archiveOrderPageKeysSelect(scope, mode, keyword) + """
                        UNION ALL
                """ + liveClosedOrderPageKeysSelect(scope, mode, keyword) + """
                    ) archive_keys
                    """ + orderBy + """
                    LIMIT :limit OFFSET :offset
                )
                SELECT *
                FROM (
                """ + archiveOrdersSelectFromPage() + """
                    UNION ALL
                """ + liveClosedOrdersSelectFromPage() + """
                ) archive_union
                """ + orderBy + """
                """, params, (rs, rowNum) -> orderListItem(rs));
    }

    public Optional<ManagerArchiveOrderListItem> findOrder(ArchiveAccessScope scope, Long orderId) {
        MapSqlParameterSource params = scopeParams(scope).addValue("orderId", orderId);
        List<ManagerArchiveOrderListItem> orders = jdbc.query("""
                SELECT
                    ao.order_id,
                    ao.order_company,
                    (
                        SELECT BIN_TO_UUID(aod.order_detail_id)
                        FROM archive_order_details aod
                        WHERE aod.order_detail_order = ao.order_id
                        ORDER BY aod.order_detail_date_published, aod.order_detail_id
                        LIMIT 1
                    ) AS order_detail_uuid,
                    COALESCE(ao.company_title_snapshot, c.company_title, '') AS company_title,
                    COALESCE(ao.company_phone_snapshot, c.company_phone, '') AS company_phone,
                    COALESCE(c.company_url_chat, '') AS company_url_chat,
                    COALESCE(ao.company_city_snapshot, c.company_city, '') AS company_city,
                    COALESCE(ao.filial_title_snapshot, f.filial_title, '') AS filial_title,
                    COALESCE(f.filial_url, '') AS filial_url,
                    COALESCE(os.order_status_title, '') AS order_status_title,
                    ao.order_sum,
                    ao.order_amount,
                    ao.order_counter,
                    ao.order_waiting_for_client,
                    COALESCE(ao.manager_name_snapshot, mu.fio, mu.username, '') AS manager_name,
                    COALESCE(ao.worker_name_snapshot, wu.fio, wu.username, '') AS worker_name,
                    ao.order_created,
                    ao.order_changed,
                    ao.order_pay_day,
                    ao.archived_at,
                    ao.archive_reason,
                    ao.archive_batch_id,
                    ao.restored_at,
                    ao.restored_by,
                    ao.restore_batch_id,
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
                    ) AS reviews_count,
                    COALESCE((
                        SELECT SUM(apc.check_sum)
                        FROM archive_payment_check apc
                        WHERE apc.check_order = ao.order_id
                    ), 0) AS payment_check_sum,
                    COALESCE((
                        SELECT SUM(az.zp_sum)
                        FROM archive_zp az
                        WHERE az.zp_order = ao.order_id
                    ), 0) AS zp_sum,
                    'archive' AS source
                FROM archive_orders ao
                LEFT JOIN companies c ON c.company_id = ao.order_company
                LEFT JOIN filial f ON f.filial_id = ao.order_filial
                LEFT JOIN order_statuses os ON os.order_status_id = ao.order_status
                LEFT JOIN managers m ON m.manager_id = ao.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = ao.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                WHERE ao.order_id = :orderId
                """ + scopeFilter(scope), params, (rs, rowNum) -> orderListItem(rs));
        return orders.stream().findFirst();
    }

    public String findOrderComments(Long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource("orderId", orderId);
        List<String> comments = jdbc.query("""
                SELECT COALESCE(order_zametka, '') AS order_comments
                FROM archive_orders
                WHERE order_id = :orderId
                LIMIT 1
                """, params, (rs, rowNum) -> safeString(rs.getString("order_comments")));
        return comments.stream().findFirst().orElse("");
    }

    public List<ArchiveOrderDetailItem> findOrderDetails(Long orderId) {
        return jdbc.query("""
                SELECT
                    BIN_TO_UUID(aod.order_detail_id) AS order_detail_uuid,
                    aod.order_detail_product,
                    COALESCE(p.product_title, '') AS product_title,
                    aod.order_detail_amount,
                    aod.order_detail_price,
                    COALESCE(aod.order_detail_comments, '') AS order_detail_comments,
                    aod.order_detail_date_published
                FROM archive_order_details aod
                LEFT JOIN products p ON p.product_id = aod.order_detail_product
                WHERE aod.order_detail_order = :orderId
                ORDER BY aod.order_detail_date_published DESC, aod.order_detail_id
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new ArchiveOrderDetailItem(
                rowUuid(rs, "order_detail_uuid"),
                rowLong(rs, "order_detail_product"),
                safeString(rs.getString("product_title")),
                rowInteger(rs, "order_detail_amount"),
                rowBigDecimal(rs, "order_detail_price"),
                safeString(rs.getString("order_detail_comments")),
                rowLocalDate(rs, "order_detail_date_published")
        ));
    }

    public List<ArchiveReviewItem> findReviews(Long orderId) {
        return jdbc.query("""
                SELECT
                    ar.review_id,
                    BIN_TO_UUID(ar.review_order_details) AS order_detail_uuid,
                    COALESCE(ar.review_text, '') AS review_text,
                    COALESCE(ar.review_answer, '') AS review_answer,
                    COALESCE(cat.category_title, '') AS category_title,
                    COALESCE(sub.subcategory_title, '') AS subcategory_title,
                    ar.review_bot,
                    COALESCE(b.bot_fio, '') AS bot_fio,
                    COALESCE(b.bot_login, '') AS bot_login,
                    ar.review_product,
                    COALESCE(p.product_title, '') AS product_title,
                    COALESCE(wu.fio, wu.username, '') AS worker_fio,
                    COALESCE(f.filial_title, '') AS filial_title,
                    ar.review_created,
                    ar.review_changed,
                    ar.review_publish_date,
                    ar.review_publish,
                    ar.review_vigul,
                    ar.review_price,
                    COALESCE(ar.review_url, '') AS review_url
                FROM archive_reviews ar
                JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                LEFT JOIN categorys cat ON cat.category_id = ar.review_category
                LEFT JOIN subcategoryes sub ON sub.subcategory_id = ar.review_subcategory
                LEFT JOIN bots b ON b.bot_id = ar.review_bot
                LEFT JOIN products p ON p.product_id = ar.review_product
                LEFT JOIN workers rw ON rw.worker_id = ar.review_worker
                LEFT JOIN users wu ON wu.id = rw.user_id
                LEFT JOIN filial f ON f.filial_id = ar.review_filial
                WHERE aod.order_detail_order = :orderId
                ORDER BY ar.review_created DESC, ar.review_id DESC
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new ArchiveReviewItem(
                rowLong(rs, "review_id"),
                rowUuid(rs, "order_detail_uuid"),
                safeString(rs.getString("review_text")),
                safeString(rs.getString("review_answer")),
                safeString(rs.getString("category_title")),
                safeString(rs.getString("subcategory_title")),
                rowLong(rs, "review_bot"),
                safeString(rs.getString("bot_fio")),
                safeString(rs.getString("bot_login")),
                rowLong(rs, "review_product"),
                safeString(rs.getString("product_title")),
                safeString(rs.getString("worker_fio")),
                safeString(rs.getString("filial_title")),
                rowLocalDate(rs, "review_created"),
                rowLocalDate(rs, "review_changed"),
                rowLocalDate(rs, "review_publish_date"),
                rowBoolean(rs, "review_publish"),
                rowBoolean(rs, "review_vigul"),
                rowBigDecimal(rs, "review_price"),
                safeString(rs.getString("review_url"))
        ));
    }

    public List<ArchiveBadReviewTaskItem> findBadReviewTasks(Long orderId) {
        return jdbc.query("""
                SELECT
                    abrt.bad_review_task_id,
                    abrt.bad_review_task_review,
                    COALESCE(abrt.bad_review_task_status, '') AS bad_review_task_status,
                    abrt.bad_review_task_original_rating,
                    abrt.bad_review_task_target_rating,
                    abrt.bad_review_task_price,
                    abrt.bad_review_task_scheduled_date,
                    abrt.bad_review_task_completed_date,
                    COALESCE(wu.fio, wu.username, '') AS worker_fio,
                    COALESCE(b.bot_fio, '') AS bot_fio,
                    COALESCE(abrt.bad_review_task_comment, '') AS bad_review_task_comment
                FROM archive_bad_review_tasks abrt
                LEFT JOIN workers w ON w.worker_id = abrt.bad_review_task_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                LEFT JOIN bots b ON b.bot_id = abrt.bad_review_task_bot
                WHERE abrt.bad_review_task_order = :orderId
                ORDER BY abrt.bad_review_task_created DESC, abrt.bad_review_task_id DESC
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new ArchiveBadReviewTaskItem(
                rowLong(rs, "bad_review_task_id"),
                rowLong(rs, "bad_review_task_review"),
                safeString(rs.getString("bad_review_task_status")),
                rowInteger(rs, "bad_review_task_original_rating"),
                rowInteger(rs, "bad_review_task_target_rating"),
                rowBigDecimal(rs, "bad_review_task_price"),
                rowLocalDate(rs, "bad_review_task_scheduled_date"),
                rowLocalDate(rs, "bad_review_task_completed_date"),
                safeString(rs.getString("worker_fio")),
                safeString(rs.getString("bot_fio")),
                safeString(rs.getString("bad_review_task_comment"))
        ));
    }

    public List<ArchiveNextOrderRequestItem> findNextOrderRequests(Long orderId) {
        return jdbc.query("""
                SELECT
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
                FROM archive_next_order_requests
                WHERE source_order_id = :orderId
                   OR created_order_id = :orderId
                ORDER BY created_at DESC, next_order_request_id DESC
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new ArchiveNextOrderRequestItem(
                rowLong(rs, "next_order_request_id"),
                rowLong(rs, "company_id"),
                rowLong(rs, "filial_id"),
                rowLong(rs, "source_order_id"),
                rowLong(rs, "created_order_id"),
                safeString(rs.getString("request_status")),
                rowInteger(rs, "attempts", 0),
                safeString(rs.getString("error_message")),
                rowLocalDateTime(rs, "created_at"),
                rowLocalDateTime(rs, "updated_at")
        ));
    }

    public List<ArchiveZpItem> findZp(Long orderId) {
        return jdbc.query("""
                SELECT
                    zp_id,
                    zp_fio,
                    zp_sum,
                    zp_user,
                    zp_profession,
                    zp_order,
                    zp_amount,
                    zp_date,
                    zp_active
                FROM archive_zp
                WHERE zp_order = :orderId
                ORDER BY zp_date DESC, zp_id DESC
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new ArchiveZpItem(
                rowLong(rs, "zp_id"),
                safeString(rs.getString("zp_fio")),
                rowBigDecimal(rs, "zp_sum"),
                rowLong(rs, "zp_user"),
                rowLong(rs, "zp_profession"),
                rowLong(rs, "zp_order"),
                rowInteger(rs, "zp_amount", 0),
                rowLocalDate(rs, "zp_date"),
                rowBoolean(rs, "zp_active")
        ));
    }

    public List<ArchivePaymentCheckItem> findPaymentChecks(Long orderId) {
        return jdbc.query("""
                SELECT
                    check_id,
                    check_title,
                    check_sum,
                    check_company,
                    check_order,
                    check_manager,
                    check_worker,
                    check_date,
                    check_active
                FROM archive_payment_check
                WHERE check_order = :orderId
                ORDER BY check_date DESC, check_id DESC
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new ArchivePaymentCheckItem(
                rowLong(rs, "check_id"),
                safeString(rs.getString("check_title")),
                rowBigDecimal(rs, "check_sum"),
                rowLong(rs, "check_company"),
                rowLong(rs, "check_order"),
                rowLong(rs, "check_manager"),
                rowLong(rs, "check_worker"),
                rowLocalDate(rs, "check_date"),
                rowBoolean(rs, "check_active")
        ));
    }

    private String archiveOrdersFromWhere(ArchiveAccessScope scope, String mode, String keyword) {
        return """
                FROM archive_orders ao
                LEFT JOIN companies c ON c.company_id = ao.order_company
                LEFT JOIN filial f ON f.filial_id = ao.order_filial
                LEFT JOIN order_statuses os ON os.order_status_id = ao.order_status
                LEFT JOIN managers m ON m.manager_id = ao.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = ao.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                WHERE 1 = 1
                  AND ao.restored_at IS NULL
                """ + scopeFilter(scope) + modeFilter(mode) + keywordFilter(keyword);
    }

    private String archiveOrderIdsSelect(ArchiveAccessScope scope, String mode, String keyword) {
        return """
                    SELECT ao.order_id
                """ + archiveOrdersFromWhere(scope, mode, keyword);
    }

    private String liveClosedOrderIdsSelect(ArchiveAccessScope scope, String mode, String keyword) {
        return """
                    SELECT o.order_id
                """ + liveClosedOrdersFromWhere(scope, mode, keyword);
    }

    private String archiveOrderPageKeysSelect(ArchiveAccessScope scope, String mode, String keyword) {
        return """
                    SELECT
                        ao.order_id,
                        'archive' AS source,
                        COALESCE(ao.archived_at, TIMESTAMP(ao.order_changed)) AS sort_at
                """ + archiveOrdersFromWhere(scope, mode, keyword);
    }

    private String liveClosedOrderPageKeysSelect(ArchiveAccessScope scope, String mode, String keyword) {
        return """
                    SELECT
                        o.order_id,
                        'live' AS source,
                        TIMESTAMP(o.order_changed) AS sort_at
                """ + liveClosedOrdersFromWhere(scope, mode, keyword);
    }

    private String archiveOrdersSelectFromPage() {
        return """
                    SELECT
                        ao.order_id,
                        ao.order_company,
                        (
                            SELECT BIN_TO_UUID(aod.order_detail_id)
                            FROM archive_order_details aod
                            WHERE aod.order_detail_order = ao.order_id
                            ORDER BY aod.order_detail_date_published, aod.order_detail_id
                            LIMIT 1
                        ) AS order_detail_uuid,
                        COALESCE(ao.company_title_snapshot, c.company_title, '') AS company_title,
                        COALESCE(ao.company_phone_snapshot, c.company_phone, '') AS company_phone,
                        COALESCE(c.company_url_chat, '') AS company_url_chat,
                        COALESCE(ao.company_city_snapshot, c.company_city, '') AS company_city,
                        COALESCE(ao.filial_title_snapshot, f.filial_title, '') AS filial_title,
                        COALESCE(f.filial_url, '') AS filial_url,
                        COALESCE(os.order_status_title, '') AS order_status_title,
                        ao.order_sum,
                        ao.order_amount,
                        ao.order_counter,
                        ao.order_waiting_for_client,
                        COALESCE(ao.manager_name_snapshot, mu.fio, mu.username, '') AS manager_name,
                        COALESCE(ao.worker_name_snapshot, wu.fio, wu.username, '') AS worker_name,
                        ao.order_created,
                        ao.order_changed,
                        ao.order_pay_day,
                        ao.archived_at,
                        ao.archive_reason,
                        ao.archive_batch_id,
                        ao.restored_at,
                        ao.restored_by,
                        ao.restore_batch_id,
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
                        ) AS reviews_count,
                        COALESCE((
                            SELECT SUM(apc.check_sum)
                            FROM archive_payment_check apc
                            WHERE apc.check_order = ao.order_id
                        ), 0) AS payment_check_sum,
                        COALESCE((
                            SELECT SUM(az.zp_sum)
                            FROM archive_zp az
                            WHERE az.zp_order = ao.order_id
                        ), 0) AS zp_sum,
                        'archive' AS source,
                        page.sort_at AS sort_at
                FROM archive_page page
                JOIN archive_orders ao ON ao.order_id = page.order_id
                LEFT JOIN companies c ON c.company_id = ao.order_company
                LEFT JOIN filial f ON f.filial_id = ao.order_filial
                LEFT JOIN order_statuses os ON os.order_status_id = ao.order_status
                LEFT JOIN managers m ON m.manager_id = ao.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = ao.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                WHERE page.source = 'archive'
                """;
    }

    private String liveClosedOrdersSelectFromPage() {
        return """
                    SELECT
                        o.order_id,
                        o.order_company,
                        (
                            SELECT BIN_TO_UUID(od.order_detail_id)
                            FROM order_details od
                            WHERE od.order_detail_order = o.order_id
                            ORDER BY od.order_detail_date_published, od.order_detail_id
                            LIMIT 1
                        ) AS order_detail_uuid,
                        COALESCE(c.company_title, '') AS company_title,
                        COALESCE(c.company_phone, '') AS company_phone,
                        COALESCE(c.company_url_chat, '') AS company_url_chat,
                        COALESCE(c.company_city, '') AS company_city,
                        COALESCE(f.filial_title, '') AS filial_title,
                        COALESCE(f.filial_url, '') AS filial_url,
                        COALESCE(os.order_status_title, '') AS order_status_title,
                        o.order_sum,
                        o.order_amount,
                        o.order_counter,
                        o.order_waiting_for_client,
                        COALESCE(mu.fio, mu.username, '') AS manager_name,
                        COALESCE(wu.fio, wu.username, '') AS worker_name,
                        o.order_created,
                        o.order_changed,
                        o.order_pay_day,
                        CAST(NULL AS DATETIME) AS archived_at,
                        'live-closed' AS archive_reason,
                        CAST(NULL AS SIGNED) AS archive_batch_id,
                        CAST(NULL AS DATETIME) AS restored_at,
                        '' AS restored_by,
                        CAST(NULL AS SIGNED) AS restore_batch_id,
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
                        ) AS reviews_count,
                        COALESCE((
                            SELECT SUM(pc.check_sum)
                            FROM payment_check pc
                            WHERE pc.check_order = o.order_id
                        ), 0) AS payment_check_sum,
                        COALESCE((
                            SELECT SUM(z.zp_sum)
                            FROM zp z
                            WHERE z.zp_order = o.order_id
                        ), 0) AS zp_sum,
                        'live' AS source,
                        page.sort_at AS sort_at
                FROM archive_page page
                JOIN orders o ON o.order_id = page.order_id
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN filial f ON f.filial_id = o.order_filial
                LEFT JOIN order_statuses os ON os.order_status_id = o.order_status
                LEFT JOIN managers m ON m.manager_id = o.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = o.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                WHERE page.source = 'live'
                """;
    }

    private String liveClosedOrdersFromWhere(ArchiveAccessScope scope, String mode, String keyword) {
        return """
                FROM orders o
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN filial f ON f.filial_id = o.order_filial
                LEFT JOIN order_statuses os ON os.order_status_id = o.order_status
                LEFT JOIN managers m ON m.manager_id = o.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                LEFT JOIN workers w ON w.worker_id = o.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM archive_orders existing
                    WHERE existing.order_id = o.order_id
                      AND existing.restored_at IS NULL
                )
                """ + liveScopeFilter(scope) + modeFilter(mode) + liveKeywordFilter(keyword);
    }

    private String scopeFilter(ArchiveAccessScope scope) {
        if (scope.isUnrestricted()) {
            return "";
        }
        if (scope.managerIds().isEmpty()) {
            return " AND 1 = 0\n";
        }
        return " AND ao.order_manager IN (:managerIds)\n";
    }

    private String liveScopeFilter(ArchiveAccessScope scope) {
        if (scope.isUnrestricted()) {
            return "";
        }
        if (scope.managerIds().isEmpty()) {
            return " AND 1 = 0\n";
        }
        return " AND o.order_manager IN (:managerIds)\n";
    }

    private String modeFilter(String mode) {
        return switch (normalizeMode(mode)) {
            case "paid" -> " AND os.order_status_title = 'Оплачено'\n";
            case "archive" -> " AND os.order_status_title = 'Архив'\n";
            default -> " AND os.order_status_title IN ('Архив', 'Оплачено', 'Бан')\n";
        };
    }

    private String keywordFilter(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }

        return """
                AND (
                    (:keywordNumber IS NOT NULL AND ao.order_id = :keywordNumber)
                    OR CAST(ao.order_id AS CHAR) LIKE :keywordLike
                    OR LOWER(COALESCE(ao.company_title_snapshot, c.company_title, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(ao.company_phone_snapshot, c.company_phone, '')) LIKE :keywordLike
                    OR (
                        :keywordDigitsLike IS NOT NULL
                        AND REGEXP_REPLACE(COALESCE(ao.company_phone_snapshot, c.company_phone, ''), '[^0-9]', '') LIKE :keywordDigitsLike
                    )
                    OR LOWER(COALESCE(ao.company_city_snapshot, c.company_city, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(ao.filial_title_snapshot, f.filial_title, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(ao.manager_name_snapshot, mu.fio, mu.username, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(ao.worker_name_snapshot, wu.fio, wu.username, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(os.order_status_title, '')) LIKE :keywordLike
                    OR EXISTS (
                        SELECT 1
                        FROM archive_order_details aod_search
                        LEFT JOIN products p_search ON p_search.product_id = aod_search.order_detail_product
                        WHERE aod_search.order_detail_order = ao.order_id
                          AND (
                              LOWER(COALESCE(p_search.product_title, '')) LIKE :keywordLike
                              OR LOWER(COALESCE(aod_search.order_detail_comments, '')) LIKE :keywordLike
                          )
                    )
                """ + archiveVisibleKeywordTokensFilter(keyword) + """
                """ + archiveReviewTextKeywordFilter(keyword) + """
                )
                """;
    }

    private String archiveReviewTextKeywordFilter(String keyword) {
        if (!shouldSearchReviewText(keyword)) {
            return "";
        }

        return """
                    OR EXISTS (
                        SELECT 1
                        FROM archive_order_details aod_search
                        JOIN archive_reviews ar_search ON ar_search.review_order_details = aod_search.order_detail_id
                        WHERE aod_search.order_detail_order = ao.order_id
                          AND (
                              LOWER(COALESCE(ar_search.review_text, '')) LIKE :reviewKeywordLike
                              OR LOWER(COALESCE(ar_search.review_answer, '')) LIKE :reviewKeywordLike
                          )
                    )
                """;
    }

    private String liveKeywordFilter(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }

        return """
                AND (
                    (:keywordNumber IS NOT NULL AND o.order_id = :keywordNumber)
                    OR CAST(o.order_id AS CHAR) LIKE :keywordLike
                    OR LOWER(COALESCE(c.company_title, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(c.company_phone, '')) LIKE :keywordLike
                    OR (
                        :keywordDigitsLike IS NOT NULL
                        AND REGEXP_REPLACE(COALESCE(c.company_phone, ''), '[^0-9]', '') LIKE :keywordDigitsLike
                    )
                    OR LOWER(COALESCE(c.company_city, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(f.filial_title, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(mu.fio, mu.username, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(wu.fio, wu.username, '')) LIKE :keywordLike
                    OR LOWER(COALESCE(os.order_status_title, '')) LIKE :keywordLike
                    OR EXISTS (
                        SELECT 1
                        FROM order_details od_search
                        LEFT JOIN products p_search ON p_search.product_id = od_search.order_detail_product
                        WHERE od_search.order_detail_order = o.order_id
                          AND (
                              LOWER(COALESCE(p_search.product_title, '')) LIKE :keywordLike
                              OR LOWER(COALESCE(od_search.order_detail_comments, '')) LIKE :keywordLike
                          )
                    )
                """ + liveVisibleKeywordTokensFilter(keyword) + """
                """ + liveReviewTextKeywordFilter(keyword) + """
                )
                """;
    }

    private String liveReviewTextKeywordFilter(String keyword) {
        if (!shouldSearchReviewText(keyword)) {
            return "";
        }

        return """
                    OR EXISTS (
                        SELECT 1
                        FROM order_details od_search
                        JOIN reviews r_search ON r_search.review_order_details = od_search.order_detail_id
                        WHERE od_search.order_detail_order = o.order_id
                          AND (
                              LOWER(COALESCE(r_search.review_text, '')) LIKE :reviewKeywordLike
                              OR LOWER(COALESCE(r_search.review_answer, '')) LIKE :reviewKeywordLike
                          )
                    )
                """;
    }

    private String archiveVisibleKeywordTokensFilter(String keyword) {
        return visibleKeywordTokensFilter(keyword, """
                LOWER(CONCAT_WS(' ',
                    CAST(ao.order_id AS CHAR),
                    COALESCE(ao.company_title_snapshot, c.company_title, ''),
                    COALESCE(ao.company_phone_snapshot, c.company_phone, ''),
                    REGEXP_REPLACE(COALESCE(ao.company_phone_snapshot, c.company_phone, ''), '[^0-9]', ''),
                    COALESCE(ao.company_city_snapshot, c.company_city, ''),
                    COALESCE(ao.filial_title_snapshot, f.filial_title, ''),
                    COALESCE(ao.manager_name_snapshot, mu.fio, mu.username, ''),
                    COALESCE(ao.worker_name_snapshot, wu.fio, wu.username, ''),
                    COALESCE(os.order_status_title, '')
                ))
                """);
    }

    private String liveVisibleKeywordTokensFilter(String keyword) {
        return visibleKeywordTokensFilter(keyword, """
                LOWER(CONCAT_WS(' ',
                    CAST(o.order_id AS CHAR),
                    COALESCE(c.company_title, ''),
                    COALESCE(c.company_phone, ''),
                    REGEXP_REPLACE(COALESCE(c.company_phone, ''), '[^0-9]', ''),
                    COALESCE(c.company_city, ''),
                    COALESCE(f.filial_title, ''),
                    COALESCE(mu.fio, mu.username, ''),
                    COALESCE(wu.fio, wu.username, ''),
                    COALESCE(os.order_status_title, '')
                ))
                """);
    }

    private String visibleKeywordTokensFilter(String keyword, String searchableExpression) {
        List<String> tokens = keywordTokens(keyword);
        if (tokens.size() <= 1) {
            return "";
        }

        StringBuilder filter = new StringBuilder("                    OR (\n");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                filter.append("\n                        AND ");
            } else {
                filter.append("                        ");
            }
            filter.append(searchableExpression.trim()).append(" LIKE :keywordTokenLike").append(i);
        }
        filter.append("\n                    )\n");
        return filter.toString();
    }

    private MapSqlParameterSource orderParams(ArchiveAccessScope scope, String mode, String keyword) {
        MapSqlParameterSource params = scopeParams(scope).addValue("mode", normalizeMode(mode));
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            params.addValue("keywordLike", "%" + trimmed.toLowerCase(Locale.ROOT) + "%");
            params.addValue("keywordNumber", parseOrderIdKeyword(trimmed));
            params.addValue("keywordDigitsLike", phoneDigitsLike(trimmed));
            List<String> tokens = keywordTokens(trimmed);
            for (int i = 0; i < tokens.size(); i++) {
                params.addValue("keywordTokenLike" + i, "%" + tokens.get(i) + "%");
            }
            if (shouldSearchReviewText(trimmed)) {
                params.addValue("reviewKeywordLike", "%" + trimmed.toLowerCase(Locale.ROOT) + "%");
            }
        }
        return params;
    }

    private Long parseOrderIdKeyword(String keyword) {
        String candidate = keyword.startsWith("#") ? keyword.substring(1) : keyword;
        if (!candidate.matches("\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(candidate);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String phoneDigitsLike(String keyword) {
        String digits = keyword.replaceAll("\\D+", "");
        return digits.length() >= 4 ? "%" + digits + "%" : null;
    }

    private List<String> keywordTokens(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        String normalized = keyword.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank() || tokens.contains(token)) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() >= MAX_KEYWORD_TOKENS) {
                break;
            }
        }
        return tokens;
    }

    private boolean shouldSearchReviewText(String keyword) {
        if (keyword == null) {
            return false;
        }
        String trimmed = keyword.trim();
        return trimmed.length() >= 5 && !trimmed.matches("#?\\d+");
    }

    private MapSqlParameterSource scopeParams(ArchiveAccessScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!scope.isUnrestricted() && !scope.managerIds().isEmpty()) {
            params.addValue("managerIds", scope.managerIds());
        }
        return params;
    }

    private String normalizeMode(String mode) {
        if (mode == null) {
            return "all";
        }
        String normalized = mode.trim().toLowerCase();
        return "paid".equals(normalized) || "archive".equals(normalized) ? normalized : "all";
    }

    private String orderDirection(String sortDirection) {
        return "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
    }

    private ManagerArchiveOrderListItem orderListItem(ResultSet rs) throws SQLException {
        return new ManagerArchiveOrderListItem(
                rowLong(rs, "order_id"),
                rowLong(rs, "order_company"),
                rowUuid(rs, "order_detail_uuid"),
                safeString(rs.getString("company_title")),
                safeString(rs.getString("company_phone")),
                safeString(rs.getString("company_url_chat")),
                safeString(rs.getString("company_city")),
                safeString(rs.getString("filial_title")),
                safeString(rs.getString("filial_url")),
                safeString(rs.getString("order_status_title")),
                rowBigDecimal(rs, "order_sum"),
                rowInteger(rs, "order_amount"),
                rowInteger(rs, "order_counter"),
                rowBoolean(rs, "order_waiting_for_client"),
                safeString(rs.getString("manager_name")),
                safeString(rs.getString("worker_name")),
                rowLocalDate(rs, "order_created"),
                rowLocalDate(rs, "order_changed"),
                rowLocalDate(rs, "order_pay_day"),
                rowLocalDateTime(rs, "archived_at"),
                safeString(rs.getString("archive_reason")),
                rowLong(rs, "archive_batch_id"),
                rowLocalDateTime(rs, "restored_at"),
                safeString(rs.getString("restored_by")),
                rowLong(rs, "restore_batch_id"),
                rowLong(rs, "order_details_count", 0L),
                rowLong(rs, "reviews_count", 0L),
                rowBigDecimal(rs, "payment_check_sum"),
                rowBigDecimal(rs, "zp_sum"),
                safeString(rs.getString("source"))
        );
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

    private int rowInteger(ResultSet rs, String column, int fallback) throws SQLException {
        Integer value = rowInteger(rs, column);
        return value == null ? fallback : value;
    }

    private BigDecimal rowBigDecimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean rowBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length > 0 && bytes[0] != 0;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private LocalDate rowLocalDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private LocalDateTime rowLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private UUID rowUuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }
}
