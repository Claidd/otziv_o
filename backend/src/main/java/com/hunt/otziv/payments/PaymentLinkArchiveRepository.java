package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class PaymentLinkArchiveRepository {

    private static final List<String> COPY_COLUMNS = List.of(
            "id",
            "token",
            "order_id",
            "amount_kopecks",
            "reserved_amount_kopecks",
            "confirmed_amount_kopecks",
            "description",
            "payer_email",
            "status",
            "payment_method",
            "manual_source",
            "manual_task_id",
            "manual_payment_type",
            "tbank_payment_id",
            "tbank_order_id",
            "tbank_terminal_key",
            "payment_profile_id",
            "payment_profile_code",
            "payment_profile_name",
            "payment_url",
            "sbp_qr_payload",
            "sbp_qr_image",
            "sbp_qr_data_type",
            "sbp_qr_created_at",
            "manual_phone",
            "manual_recipient_name",
            "manual_payment_url",
            "manual_payment_button_label",
            "manual_comment",
            "manual_reported_at",
            "manual_confirmed_by",
            "manual_confirmed_at",
            "receipt_status",
            "payment_success_notified_at",
            "payment_success_notification_error",
            "last_error",
            "created_at",
            "updated_at",
            "expires_at",
            "initiated_at",
            "paid_at",
            "offer_consent_at",
            "privacy_consent_at",
            "receipt_consent_at",
            "consent_ip",
            "consent_user_agent",
            "offer_document_url",
            "privacy_document_url",
            "receipt_consent_document_url"
    );

    private final NamedParameterJdbcTemplate jdbc;

    public List<AdminPaymentLinkResponse> findArchivedPage(
            int page,
            int size,
            String statusFilter,
            String search,
            Long searchId,
            LocalDate from,
            LocalDate to,
            String publicBaseUrl
    ) {
        MapSqlParameterSource params = filterParams(statusFilter, search, searchId, from, to)
                .addValue("limit", Math.max(1, size))
                .addValue("offset", Math.max(0, page) * Math.max(1, size));
        return jdbc.query("""
                SELECT apl.*
                FROM archive_payment_links apl
                """ + filterWhereClause() + """
                ORDER BY apl.created_at DESC, apl.id DESC
                LIMIT :limit OFFSET :offset
                """, params, (rs, rowNum) -> archivedResponse(rs, publicBaseUrl));
    }

    public PaymentLinkAdminSummary summarizeArchived(
            String statusFilter,
            String search,
            Long searchId,
            LocalDate from,
            LocalDate to
    ) {
        MapSqlParameterSource params = filterParams(statusFilter, search, searchId, from, to);
        return jdbc.queryForObject("""
                SELECT
                  COUNT(*) AS total_elements,
                  COALESCE(SUM(apl.amount_kopecks), 0) AS total_amount_kopecks,
                  COALESCE(SUM(CASE WHEN apl.status IN ('AUTHORIZED', 'TEST_CONFIRMED', 'CONFIRMED', 'AMOUNT_MISMATCH') THEN 1 ELSE 0 END), 0) AS paid,
                  COALESCE(SUM(CASE WHEN apl.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK') AND apl.status IN ('WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED') THEN 1 ELSE 0 END), 0) AS manual_pending,
                  COALESCE(SUM(CASE WHEN apl.status = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmed,
                  COALESCE(SUM(CASE WHEN apl.status = 'CONFIRMED' AND apl.payment_success_notified_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS notifications_sent,
                  COALESCE(SUM(CASE WHEN apl.status = 'CONFIRMED' AND apl.payment_success_notified_at IS NULL AND apl.payment_success_notification_error IS NOT NULL THEN 1 ELSE 0 END), 0) AS notification_errors,
                  0 AS refundable,
                  COALESCE(SUM(CASE WHEN apl.status IN ('REVERSED', 'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED', 'CANCELED') THEN 1 ELSE 0 END), 0) AS refunded,
                  COALESCE(SUM(CASE WHEN apl.status IN ('REJECTED', 'FAILED') THEN 1 ELSE 0 END), 0) AS rejected
                FROM archive_payment_links apl
                """ + filterWhereClause(), params, (rs, rowNum) -> new PaymentLinkAdminSummary(
                rs.getLong("total_elements"),
                rs.getLong("total_amount_kopecks"),
                rs.getLong("paid"),
                rs.getLong("manual_pending"),
                rs.getLong("confirmed"),
                rs.getLong("notifications_sent"),
                rs.getLong("notification_errors"),
                rs.getLong("refundable"),
                rs.getLong("refunded"),
                rs.getLong("rejected")
        ));
    }

    public List<Long> findArchiveCandidateIds(
            LocalDateTime paidCutoff,
            LocalDateTime finalCutoff,
            int limit
    ) {
        return jdbc.queryForList("""
                SELECT pl.id
                FROM payment_links pl
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM archive_payment_links apl
                    WHERE apl.id = pl.id
                )
                  AND (
                    (
                      pl.status IN ('CONFIRMED', 'TEST_CONFIRMED')
                      AND COALESCE(pl.paid_at, pl.updated_at, pl.created_at) < :paidCutoff
                      AND COALESCE(pl.receipt_status, 'DONE') <> 'PENDING'
                    )
                    OR (
                      pl.status IN ('EXPIRED', 'REJECTED', 'FAILED', 'CANCELED', 'REVERSED', 'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED')
                      AND COALESCE(pl.updated_at, pl.created_at) < :finalCutoff
                    )
                  )
                ORDER BY pl.created_at ASC, pl.id ASC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("paidCutoff", Timestamp.valueOf(paidCutoff))
                .addValue("finalCutoff", Timestamp.valueOf(finalCutoff))
                .addValue("limit", Math.max(1, limit)), Long.class);
    }

    public int archiveIds(Collection<Long> ids, LocalDateTime archivedAt, String reason, Long batchId) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String columns = String.join(", ", COPY_COLUMNS);
        String selectColumns = COPY_COLUMNS.stream()
                .map(column -> "pl." + column)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String sql = """
                INSERT IGNORE INTO archive_payment_links (
                  %s,
                  archived_at,
                  archive_reason,
                  archive_batch_id,
                  company_title_snapshot,
                  filial_title_snapshot,
                  manager_name_snapshot
                )
                SELECT
                  %s,
                  :archivedAt,
                  :reason,
                  :batchId,
                  c.company_title,
                  f.filial_title,
                  u.fio
                FROM payment_links pl
                LEFT JOIN orders o ON o.order_id = pl.order_id
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN filial f ON f.filial_id = o.order_filial
                LEFT JOIN managers m ON m.manager_id = o.order_manager
                LEFT JOIN users u ON u.id = m.user_id
                WHERE pl.id IN (:ids)
                """.formatted(columns, selectColumns);
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("archivedAt", Timestamp.valueOf(archivedAt))
                .addValue("reason", reason)
                .addValue("batchId", batchId));
    }

    public int deleteLiveIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return jdbc.update("DELETE FROM payment_links WHERE id IN (:ids)", Map.of("ids", ids));
    }

    private MapSqlParameterSource filterParams(
            String statusFilter,
            String search,
            Long searchId,
            LocalDate from,
            LocalDate to
    ) {
        String normalizedSearch = normalize(search);
        return new MapSqlParameterSource()
                .addValue("statusFilter", normalizeStatusFilter(statusFilter))
                .addValue("searchText", normalizedSearch.isBlank() ? null : "%" + normalizedSearch.toLowerCase(Locale.ROOT) + "%")
                .addValue("searchId", searchId)
                .addValue("from", from == null ? null : Timestamp.valueOf(from.atStartOfDay()))
                .addValue("to", to == null ? null : Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
    }

    private String filterWhereClause() {
        return """
                WHERE (:from IS NULL OR apl.created_at >= :from)
                  AND (:to IS NULL OR apl.created_at < :to)
                  AND (
                    :statusFilter = 'all'
                    OR (:statusFilter = 'active' AND apl.status IN ('CREATED', 'INITIATED', 'AUTHORIZED', 'WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED'))
                    OR (:statusFilter = 'paid' AND apl.status IN ('AUTHORIZED', 'TEST_CONFIRMED', 'CONFIRMED', 'AMOUNT_MISMATCH'))
                    OR (:statusFilter = 'refunded' AND apl.status IN ('REVERSED', 'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED', 'CANCELED'))
                    OR (:statusFilter = 'failed' AND apl.status IN ('REJECTED', 'FAILED', 'EXPIRED'))
                    OR (:statusFilter = 'created' AND apl.status = 'CREATED')
                    OR (:statusFilter = 'manual' AND apl.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK'))
                  )
                  AND (
                    :searchText IS NULL
                    OR LOWER(COALESCE(apl.company_title_snapshot, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.filial_title_snapshot, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.description, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.tbank_payment_id, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.tbank_order_id, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.payment_profile_name, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.tbank_terminal_key, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.payer_email, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.manual_phone, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.manual_recipient_name, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.manual_payment_url, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.manual_payment_button_label, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.manual_comment, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.payment_success_notification_error, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.last_error, '')) LIKE :searchText
                    OR (:searchId IS NOT NULL AND (apl.id = :searchId OR apl.order_id = :searchId))
                  )
                """;
    }

    private AdminPaymentLinkResponse archivedResponse(ResultSet rs, String publicBaseUrl) throws SQLException {
        long amountKopecks = rs.getLong("amount_kopecks");
        String token = rs.getString("token");
        return new AdminPaymentLinkResponse(
                rs.getLong("id"),
                token,
                publicBaseUrl + "/pay/" + token,
                nullableLong(rs, "order_id"),
                value(rs, "company_title_snapshot"),
                value(rs, "filial_title_snapshot"),
                value(rs, "description"),
                BigDecimal.valueOf(amountKopecks).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP),
                amountKopecks,
                nullableLong(rs, "reserved_amount_kopecks"),
                nullableLong(rs, "confirmed_amount_kopecks"),
                value(rs, "status"),
                value(rs, "payment_method"),
                value(rs, "payment_profile_code"),
                value(rs, "payment_profile_name"),
                value(rs, "manual_source"),
                nullableLong(rs, "manual_task_id"),
                value(rs, "manual_recipient_name"),
                value(rs, "tbank_terminal_key"),
                value(rs, "tbank_payment_id"),
                value(rs, "tbank_order_id"),
                value(rs, "payer_email"),
                value(rs, "payment_url"),
                value(rs, "manual_payment_type"),
                value(rs, "manual_phone"),
                value(rs, "manual_recipient_name"),
                value(rs, "manual_payment_url"),
                value(rs, "manual_payment_button_label"),
                value(rs, "manual_comment"),
                nullableDateTime(rs, "manual_reported_at"),
                value(rs, "manual_confirmed_by"),
                nullableDateTime(rs, "manual_confirmed_at"),
                value(rs, "receipt_status"),
                nullableDateTime(rs, "payment_success_notified_at"),
                value(rs, "payment_success_notification_error"),
                "ARCHIVE",
                false,
                "Архивная запись: действия с платежом недоступны",
                value(rs, "last_error"),
                nullableDateTime(rs, "created_at"),
                nullableDateTime(rs, "updated_at"),
                nullableDateTime(rs, "expires_at"),
                nullableDateTime(rs, "initiated_at"),
                nullableDateTime(rs, "paid_at"),
                nullableDateTime(rs, "sbp_qr_created_at"),
                true,
                nullableDateTime(rs, "archived_at"),
                value(rs, "archive_reason"),
                false
        );
    }

    private String normalizeStatusFilter(String statusFilter) {
        String value = normalize(statusFilter).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "active", "paid", "refunded", "failed", "created", "manual" -> value;
            default -> "all";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String value(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime nullableDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
