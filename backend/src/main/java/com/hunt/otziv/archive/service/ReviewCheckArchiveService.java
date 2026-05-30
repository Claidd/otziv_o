package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveRestoreResult;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewCheckArchiveService {

    private final NamedParameterJdbcTemplate jdbc;
    private final OrderArchiveRestoreService restoreService;

    @Transactional(readOnly = true)
    public Optional<ArchivedReviewCheck> findByOrderDetailId(UUID orderDetailId) {
        if (orderDetailId == null) {
            return Optional.empty();
        }

        MapSqlParameterSource params = orderDetailParams(orderDetailId);
        List<ArchivedReviewCheckBase> bases = jdbc.query("""
                SELECT
                    BIN_TO_UUID(aod.order_detail_id) AS order_detail_uuid,
                    aod.order_detail_order AS order_id,
                    ao.order_company AS company_id,
                    COALESCE(ao.company_title_snapshot, c.company_title, '') AS company_title,
                    COALESCE(ao.filial_title_snapshot, f.filial_title, '') AS filial_title,
                    COALESCE(os.order_status_title, 'Архив') AS order_status_title,
                    COALESCE(ao.worker_name_snapshot, wu.fio, wu.username, ao.manager_name_snapshot, mu.fio, mu.username, '') AS worker_fio,
                    COALESCE(ao.order_zametka, '') AS order_comments,
                    COALESCE(c.company_comments, '') AS company_comments,
                    COALESCE(aod.order_detail_comments, '') AS order_detail_comments,
                    COALESCE(aod.order_detail_amount, 0) AS order_detail_amount,
                    COALESCE(ao.order_counter, 0) AS order_counter,
                    ao.order_sum
                FROM archive_order_details aod
                JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                LEFT JOIN companies c ON c.company_id = ao.order_company
                LEFT JOIN filial f ON f.filial_id = ao.order_filial
                LEFT JOIN order_statuses os ON os.order_status_id = ao.order_status
                LEFT JOIN workers w ON w.worker_id = ao.order_worker
                LEFT JOIN users wu ON wu.id = w.user_id
                LEFT JOIN managers m ON m.manager_id = ao.order_manager
                LEFT JOIN users mu ON mu.id = m.user_id
                WHERE aod.order_detail_id = UUID_TO_BIN(:orderDetailId)
                  AND ao.restored_at IS NULL
                LIMIT 1
                """, params, (rs, rowNum) -> archiveBase(rs));

        return bases.stream()
                .findFirst()
                .map(base -> new ArchivedReviewCheck(
                        base.orderDetailId(),
                        base.orderId(),
                        base.companyId(),
                        base.companyTitle(),
                        base.filialTitle(),
                        base.status(),
                        base.workerFio(),
                        base.orderComments(),
                        base.companyComments(),
                        base.comment(),
                        base.amount(),
                        base.counter(),
                        base.sum(),
                        findReviews(orderDetailId)
                ));
    }

    @Transactional
    public ArchiveRestoreResult restoreByOrderDetailId(UUID orderDetailId, String targetStatus, String restoredBy) {
        Long orderId = findOrderIdByOrderDetailId(orderDetailId)
                .orElseThrow(() -> new IllegalArgumentException("Archived review check not found: " + orderDetailId));

        return restoreService.restoreOrder(orderId, targetStatus, restoredBy, true);
    }

    @Transactional(readOnly = true)
    public Optional<Long> findOrderIdByOrderDetailId(UUID orderDetailId) {
        if (orderDetailId == null) {
            return Optional.empty();
        }

        List<Long> ids = jdbc.queryForList("""
                SELECT aod.order_detail_order
                FROM archive_order_details aod
                JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                WHERE aod.order_detail_id = UUID_TO_BIN(:orderDetailId)
                  AND ao.restored_at IS NULL
                LIMIT 1
                """, orderDetailParams(orderDetailId), Long.class);
        return ids.stream().findFirst();
    }

    private List<ArchivedReviewCheckReview> findReviews(UUID orderDetailId) {
        return jdbc.query("""
                SELECT
                    ar.review_id,
                    COALESCE(ar.review_text, '') AS review_text,
                    COALESCE(ar.review_answer, '') AS review_answer,
                    COALESCE(b.bot_fio, '') AS bot_fio,
                    COALESCE(review_product.product_title, detail_product.product_title, '') AS product_title,
                    COALESCE(review_product.product_photo, detail_product.product_photo, 0) AS product_photo,
                    COALESCE(ar.review_url, '') AS review_url,
                    ar.review_publish_date,
                    COALESCE(ar.review_publish, 0) AS review_publish
                FROM archive_reviews ar
                JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                LEFT JOIN bots b ON b.bot_id = ar.review_bot
                LEFT JOIN products review_product ON review_product.product_id = ar.review_product
                LEFT JOIN products detail_product ON detail_product.product_id = aod.order_detail_product
                WHERE ar.review_order_details = UUID_TO_BIN(:orderDetailId)
                ORDER BY ar.review_id
                """, orderDetailParams(orderDetailId), (rs, rowNum) -> new ArchivedReviewCheckReview(
                rowLong(rs, "review_id"),
                safeString(rs.getString("review_text")),
                safeString(rs.getString("review_answer")),
                safeString(rs.getString("bot_fio")),
                safeString(rs.getString("product_title")),
                rs.getBoolean("product_photo"),
                safeString(rs.getString("review_url")),
                rowLocalDate(rs, "review_publish_date"),
                rs.getBoolean("review_publish")
        ));
    }

    private MapSqlParameterSource orderDetailParams(UUID orderDetailId) {
        return new MapSqlParameterSource("orderDetailId", orderDetailId.toString());
    }

    private ArchivedReviewCheckBase archiveBase(ResultSet rs) throws SQLException {
        return new ArchivedReviewCheckBase(
                rowUuid(rs, "order_detail_uuid"),
                rowLong(rs, "order_id"),
                rowLong(rs, "company_id"),
                safeString(rs.getString("company_title")),
                safeString(rs.getString("filial_title")),
                safeString(rs.getString("order_status_title")),
                safeString(rs.getString("worker_fio")),
                safeString(rs.getString("order_comments")),
                safeString(rs.getString("company_comments")),
                safeString(rs.getString("order_detail_comments")),
                rowInt(rs, "order_detail_amount"),
                rowInt(rs, "order_counter"),
                rs.getBigDecimal("order_sum")
        );
    }

    private UUID rowUuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private Long rowLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private int rowInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? 0 : value;
    }

    private LocalDate rowLocalDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private String safeString(String value) {
        return value != null ? value : "";
    }

    private record ArchivedReviewCheckBase(
            UUID orderDetailId,
            Long orderId,
            Long companyId,
            String companyTitle,
            String filialTitle,
            String status,
            String workerFio,
            String orderComments,
            String companyComments,
            String comment,
            int amount,
            int counter,
            BigDecimal sum
    ) {
    }

    public record ArchivedReviewCheck(
            UUID orderDetailId,
            Long orderId,
            Long companyId,
            String companyTitle,
            String filialTitle,
            String status,
            String workerFio,
            String orderComments,
            String companyComments,
            String comment,
            int amount,
            int counter,
            BigDecimal sum,
            List<ArchivedReviewCheckReview> reviews
    ) {
    }

    public record ArchivedReviewCheckReview(
            Long id,
            String text,
            String answer,
            String botName,
            String productTitle,
            boolean productPhoto,
            String url,
            LocalDate publishedDate,
            boolean publish
    ) {
    }
}
