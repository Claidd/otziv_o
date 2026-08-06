package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveListItem;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveOrderItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class CommonInvoiceArchiveRepository {

    private static final Set<String> TERMINAL_ARCHIVED_PAYMENT_REF_STATUSES = Set.of(
            "APPLIED",
            "ARCHIVED",
            "CANCELED",
            "REJECTED",
            "REFUNDED",
            "PARTIAL_REFUNDED",
            "REVERSED",
            "PARTIAL_REVERSED"
    );

    private static final List<String> ARCHIVE_ONLY_COLUMNS = List.of(
            "archived_at",
            "archive_reason",
            "archive_batch_id",
            "restored_at",
            "restored_by",
            "restore_batch_id"
    );

    private final NamedParameterJdbcTemplate jdbc;

    public long count(ArchiveAccessScope scope, String keyword) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                """ + liveSelect(scope, false) + """
                    UNION ALL
                """ + archiveSelect(scope, false) + """
                ) common_invoice_archive
                """, params(scope, keyword), Long.class);
        return value == null ? 0L : value;
    }

    public List<CommonInvoiceArchiveListItem> find(
            ArchiveAccessScope scope,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        String direction = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        MapSqlParameterSource params = params(scope, keyword)
                .addValue("limit", pageSize)
                .addValue("offset", Math.max(0, pageNumber) * pageSize);
        String sql = """
                SELECT *
                FROM (
                """ + liveSelect(scope, true) + """
                    UNION ALL
                """ + archiveSelect(scope, true) + """
                ) common_invoice_archive
                ORDER BY COALESCE(archived_at, closed_at) """ + direction
                + ", invoice_id " + direction + "\n" + """
                LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(sql, params, (rs, rowNum) -> item(rs));
    }

    public Optional<CommonInvoiceArchiveListItem> findOne(ArchiveAccessScope scope, Long invoiceId) {
        MapSqlParameterSource params = params(scope, "")
                .addValue("invoiceId", invoiceId);
        List<CommonInvoiceArchiveListItem> rows = jdbc.query("""
                SELECT *
                FROM (
                """ + liveSelect(scope, true) + """
                    UNION ALL
                """ + archiveSelect(scope, true) + """
                ) common_invoice_archive
                WHERE invoice_id = :invoiceId
                ORDER BY source = 'live' DESC
                LIMIT 1
                """, params, (rs, rowNum) -> item(rs));
        return rows.stream().findFirst();
    }

    public List<CommonInvoiceArchiveOrderItem> findOrders(Long invoiceId, String source) {
        if ("live".equalsIgnoreCase(source)) {
            return jdbc.query("""
                    SELECT
                        o.order_id,
                        COALESCE(c.company_title, '') AS company_title,
                        COALESCE(f.filial_title, '') AS filial_title,
                        COALESCE(s.order_status_title, '') AS status_title,
                        COALESCE(cio.archive_source_order_status_title, '') AS archive_source_status,
                        cio.amount_kopecks,
                        cio.paid
                    FROM common_invoice_orders cio
                    JOIN orders o ON o.order_id = cio.order_id
                    LEFT JOIN companies c ON c.company_id = o.order_company
                    LEFT JOIN filial f ON f.filial_id = o.order_filial
                    LEFT JOIN order_statuses s ON s.order_status_id = o.order_status
                    WHERE cio.invoice_id = :invoiceId
                    ORDER BY cio.invoice_order_id
                    """, Map.of("invoiceId", invoiceId), (rs, rowNum) -> orderItem(rs));
        }
        return jdbc.query("""
                SELECT
                    ao.order_id,
                    COALESCE(ao.company_title_snapshot, c.company_title, '') AS company_title,
                    COALESCE(ao.filial_title_snapshot, f.filial_title, '') AS filial_title,
                    COALESCE(s.order_status_title, '') AS status_title,
                    COALESCE(acio.archive_source_order_status_title, '') AS archive_source_status,
                    acio.amount_kopecks,
                    acio.paid
                FROM archive_common_invoice_orders acio
                JOIN archive_orders ao ON ao.order_id = acio.order_id
                LEFT JOIN companies c ON c.company_id = ao.order_company
                LEFT JOIN filial f ON f.filial_id = ao.order_filial
                LEFT JOIN order_statuses s ON s.order_status_id = ao.order_status
                WHERE acio.invoice_id = :invoiceId
                ORDER BY acio.invoice_order_id
                """, Map.of("invoiceId", invoiceId), (rs, rowNum) -> orderItem(rs));
    }

    public String archivedStatus(Long invoiceId) {
        return jdbc.queryForObject("""
                SELECT status
                FROM archive_common_invoices
                WHERE invoice_id = :invoiceId
                  AND restored_at IS NULL
                """, Map.of("invoiceId", invoiceId), String.class);
    }

    /**
     * Serializes physical restores for one invoice and rechecks the archived payment registry
     * immediately before any child orders are restored. Legacy archives may contain CURRENT or
     * otherwise unfinished refs which must never be copied into the V200 live-only CURRENT guard.
     */
    public boolean lockAndCheckPaymentRefsRestorable(Long invoiceId) {
        Map<String, Long> params = Map.of("invoiceId", invoiceId);
        List<Long> lockedInvoices = jdbc.queryForList("""
                SELECT invoice_id
                FROM archive_common_invoices
                WHERE invoice_id = :invoiceId
                  AND restored_at IS NULL
                FOR UPDATE
                """, params, Long.class);
        if (lockedInvoices.isEmpty()) {
            return false;
        }
        List<String> statuses = jdbc.queryForList("""
                SELECT status
                FROM archive_common_invoice_payment_refs
                WHERE invoice_id = :invoiceId
                ORDER BY payment_ref_id
                FOR UPDATE
                """, params, String.class);
        return statuses.stream().allMatch(this::isTerminalArchivedPaymentRefStatus);
    }

    public void restoreInvoice(Long invoiceId, String token, String restoredBy, Long restoreBatchId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("token", token)
                .addValue("restoredBy", trim(restoredBy, 255))
                .addValue("restoreBatchId", restoreBatchId);
        List<String> invoiceColumns = commonColumns("archive_common_invoices", "common_invoices");
        jdbc.update(
                "INSERT INTO common_invoices (" + quoteList(invoiceColumns) + ") "
                        + "SELECT " + selectList("aci", invoiceColumns) + " "
                        + "FROM archive_common_invoices aci WHERE aci.invoice_id = :invoiceId",
                params
        );
        jdbc.update("""
                UPDATE common_invoices
                SET token = :token
                WHERE invoice_id = :invoiceId
                """, params);

        restoreTable(
                "archive_common_invoice_orders",
                "common_invoice_orders",
                "acio",
                "FROM archive_common_invoice_orders acio WHERE acio.invoice_id = :invoiceId",
                params
        );
        restoreTable(
                "archive_common_invoice_payment_refs",
                "common_invoice_payment_refs",
                "acipr",
                "FROM archive_common_invoice_payment_refs acipr WHERE acipr.invoice_id = :invoiceId",
                params
        );
        jdbc.update("""
                UPDATE archive_common_invoices
                SET restored_at = CURRENT_TIMESTAMP(6),
                    restored_by = :restoredBy,
                    restore_batch_id = :restoreBatchId
                WHERE invoice_id = :invoiceId
                """, params);
    }

    public void reopenRestoredManualInvoice(Long invoiceId) {
        jdbc.update("""
                UPDATE common_invoices
                SET status = 'COLLECTING',
                    previous_status = NULL,
                    closed_at = NULL,
                    closed_by = NULL,
                    close_reason = NULL,
                    next_reminder_at = NULL,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE invoice_id = :invoiceId
                """, Map.of("invoiceId", invoiceId));
    }

    public void refreshRestoredClosedRetention(Long invoiceId, String restoredBy) {
        jdbc.update("""
                UPDATE common_invoices
                SET closed_at = CURRENT_TIMESTAMP(6),
                    closed_by = :restoredBy,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE invoice_id = :invoiceId
                """, new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("restoredBy", trim(restoredBy, 160)));
    }

    private String liveSelect(ArchiveAccessScope scope, boolean full) {
        String columns = full ? """
                    ci.invoice_id,
                    COALESCE(account.account_name, ci.title, '') AS account_name,
                    COALESCE(ci.title, '') AS title,
                    ci.status,
                    ci.amount_kopecks,
                    ci.paid_kopecks,
                    COUNT(cio.invoice_order_id) AS order_count,
                    ci.closed_at,
                    COALESCE(ci.closed_by, '') AS closed_by,
                    COALESCE(ci.close_reason, '') AS close_reason,
                    NULL AS archived_at,
                    'live' AS source,
                    (ci.status = 'ARCHIVED') AS restorable
                """ : "DISTINCT ci.invoice_id";
        return """
                SELECT
                """ + columns + "\n" + """
                FROM common_invoices ci
                JOIN common_billing_accounts account ON account.account_id = ci.account_id
                LEFT JOIN companies invoice_company ON invoice_company.company_id = account.invoice_company_id
                LEFT JOIN common_invoice_orders cio ON cio.invoice_id = ci.invoice_id
                WHERE ci.status IN ('ARCHIVED', 'BAN', 'PAID')
                """ + scopeFilter(scope, "account", "invoice_company", "ci", "orders") + """
                  AND (
                        :keyword = ''
                     OR LOWER(COALESCE(account.account_name, '')) LIKE :keywordLike
                     OR LOWER(COALESCE(ci.title, '')) LIKE :keywordLike
                     OR CAST(ci.invoice_id AS CHAR) LIKE :keywordLike
                  )
                """ + (full ? """
                GROUP BY ci.invoice_id, account.account_name, ci.title, ci.status, ci.amount_kopecks,
                         ci.paid_kopecks, ci.closed_at, ci.closed_by, ci.close_reason
                """ : "") ;
    }

    private String archiveSelect(ArchiveAccessScope scope, boolean full) {
        String columns = full ? """
                    aci.invoice_id,
                    COALESCE(account.account_name, aci.title, '') AS account_name,
                    COALESCE(aci.title, '') AS title,
                    aci.status,
                    aci.amount_kopecks,
                    aci.paid_kopecks,
                    COUNT(acio.invoice_order_id) AS order_count,
                    aci.closed_at,
                    COALESCE(aci.closed_by, '') AS closed_by,
                    COALESCE(aci.close_reason, '') AS close_reason,
                    aci.archived_at,
                    'archive' AS source,
                    NOT EXISTS (
                        SELECT 1
                        FROM archive_common_invoice_payment_refs restore_ref
                        WHERE restore_ref.invoice_id = aci.invoice_id
                          AND UPPER(TRIM(COALESCE(restore_ref.status, '')))
                              NOT IN (:terminalArchivedPaymentRefStatuses)
                    ) AS restorable
                """ : "DISTINCT aci.invoice_id";
        return """
                SELECT
                """ + columns + "\n" + """
                FROM archive_common_invoices aci
                LEFT JOIN common_billing_accounts account ON account.account_id = aci.account_id
                LEFT JOIN companies invoice_company ON invoice_company.company_id = account.invoice_company_id
                LEFT JOIN archive_common_invoice_orders acio ON acio.invoice_id = aci.invoice_id
                WHERE aci.restored_at IS NULL
                """ + scopeFilter(scope, "account", "invoice_company", "aci", "archive_orders") + """
                  AND (
                        :keyword = ''
                     OR LOWER(COALESCE(account.account_name, aci.title, '')) LIKE :keywordLike
                     OR LOWER(COALESCE(aci.title, '')) LIKE :keywordLike
                     OR CAST(aci.invoice_id AS CHAR) LIKE :keywordLike
                  )
                """ + (full ? """
                GROUP BY aci.invoice_id, account.account_name, aci.title, aci.status, aci.amount_kopecks,
                         aci.paid_kopecks, aci.closed_at, aci.closed_by, aci.close_reason, aci.archived_at
                """ : "");
    }

    private String scopeFilter(
            ArchiveAccessScope scope,
            String accountAlias,
            String invoiceCompanyAlias,
            String invoiceAlias,
            String orderTable
    ) {
        if (scope.isUnrestricted()) {
            return "";
        }
        if (scope.managerIds().isEmpty()) {
            return " AND 1 = 0 ";
        }
        boolean archived = "archive_orders".equals(orderTable);
        String orderAlias = archived ? "scoped_archive_order" : "scoped_order";
        String itemTable = archived ? "archive_common_invoice_orders" : "common_invoice_orders";
        return " AND ("
                + accountAlias + ".manager_id IN (:managerIds)"
                + " OR " + invoiceCompanyAlias + ".company_manager IN (:managerIds)"
                + " OR EXISTS ("
                + "SELECT 1 FROM " + itemTable + " scoped_item JOIN " + orderTable + " " + orderAlias
                + " ON " + orderAlias + ".order_id = scoped_item.order_id"
                + " WHERE scoped_item.invoice_id = " + invoiceAlias + ".invoice_id"
                + " AND " + orderAlias + ".order_manager IN (:managerIds)"
                + ")) ";
    }

    private MapSqlParameterSource params(ArchiveAccessScope scope, String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Set<Long> managerIds = scope.managerIds().isEmpty() ? Set.of(-1L) : scope.managerIds();
        return new MapSqlParameterSource()
                .addValue("keyword", normalized)
                .addValue("keywordLike", "%" + normalized + "%")
                .addValue("managerIds", managerIds)
                .addValue("terminalArchivedPaymentRefStatuses", TERMINAL_ARCHIVED_PAYMENT_REF_STATUSES);
    }

    private CommonInvoiceArchiveListItem item(ResultSet rs) throws SQLException {
        return new CommonInvoiceArchiveListItem(
                rs.getLong("invoice_id"),
                safe(rs.getString("account_name")),
                safe(rs.getString("title")),
                safe(rs.getString("status")),
                rs.getLong("amount_kopecks"),
                rs.getLong("paid_kopecks"),
                rs.getInt("order_count"),
                localDateTime(rs, "closed_at"),
                safe(rs.getString("closed_by")),
                safe(rs.getString("close_reason")),
                localDateTime(rs, "archived_at"),
                safe(rs.getString("source")),
                rs.getBoolean("restorable")
        );
    }

    private CommonInvoiceArchiveOrderItem orderItem(ResultSet rs) throws SQLException {
        return new CommonInvoiceArchiveOrderItem(
                rs.getLong("order_id"),
                safe(rs.getString("company_title")),
                safe(rs.getString("filial_title")),
                safe(rs.getString("status_title")),
                safe(rs.getString("archive_source_status")),
                rs.getLong("amount_kopecks"),
                rs.getBoolean("paid")
        );
    }

    private void restoreTable(
            String sourceTable,
            String targetTable,
            String alias,
            String fromClause,
            MapSqlParameterSource params
    ) {
        List<String> columns = commonColumns(sourceTable, targetTable);
        jdbc.update(
                "INSERT INTO " + targetTable + " (" + quoteList(columns) + ") "
                        + "SELECT " + selectList(alias, columns) + " " + fromClause,
                params
        );
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
                Map.of("sourceTable", sourceTable, "targetTable", targetTable),
                String.class
        ).stream()
                .filter(column -> !ARCHIVE_ONLY_COLUMNS.contains(column))
                .map(this::safeIdentifier)
                .toList();
    }

    private String quoteList(List<String> columns) {
        return columns.stream()
                .map(column -> "`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalStateException("No columns available for common invoice restore"));
    }

    private String selectList(String alias, List<String> columns) {
        String safeAlias = safeIdentifier(alias);
        return columns.stream()
                .map(column -> safeAlias + ".`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalStateException("No columns available for common invoice restore"));
    }

    private String safeIdentifier(String identifier) {
        if (!StringUtils.hasText(identifier) || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isTerminalArchivedPaymentRefStatus(String status) {
        return status != null
                && TERMINAL_ARCHIVED_PAYMENT_REF_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
