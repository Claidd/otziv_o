package com.hunt.otziv.payments.repository;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Locks the immutable archive row that replaces a deleted live payment source. */
@Repository
@RequiredArgsConstructor
public class ManualPaymentTaskArchivedSourceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean lockPaymentLink(Long sourceId) {
        if (sourceId == null) {
            return false;
        }
        List<Long> rows = jdbc.queryForList("""
                SELECT id
                FROM archive_payment_links
                WHERE id = :sourceId
                FOR UPDATE
                """, Map.of("sourceId", sourceId), Long.class);
        return !rows.isEmpty();
    }

    public boolean lockCommonInvoice(Long sourceId) {
        if (sourceId == null) {
            return false;
        }
        List<Long> rows = jdbc.queryForList("""
                SELECT invoice_id
                FROM archive_common_invoices
                WHERE invoice_id = :sourceId
                  AND restored_at IS NULL
                FOR UPDATE
                """, Map.of("sourceId", sourceId), Long.class);
        return !rows.isEmpty();
    }

    /** Locks an archived common invoice and returns its immutable task route identity. */
    public ArchivedCommonTaskSource lockCommonTaskInvoice(Long sourceId) {
        if (sourceId == null) {
            return null;
        }
        List<ArchivedCommonTaskSource> rows = jdbc.query("""
                SELECT payment_route_manual_task_id, payment_route_amount_kopecks
                FROM archive_common_invoices
                WHERE invoice_id = :sourceId
                  AND restored_at IS NULL
                  AND payment_route_manual_source = 'MANUAL_TASK'
                  AND payment_route_manual_task_id IS NOT NULL
                  AND payment_route_amount_kopecks > 0
                FOR UPDATE
                """, Map.of("sourceId", sourceId), (rs, rowNum) ->
                new ArchivedCommonTaskSource(
                        rs.getObject("payment_route_manual_task_id", Long.class),
                        rs.getObject("payment_route_amount_kopecks", Long.class)
                ));
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    public record ArchivedCommonTaskSource(Long taskId, Long routeAmountKopecks) {
    }
}
