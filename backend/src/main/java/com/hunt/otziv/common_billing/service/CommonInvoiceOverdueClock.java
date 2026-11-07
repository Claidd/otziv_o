package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * When the payment term of a common invoice starts. An ordinary invoice counts from its sending.
 * A bank paper invoice counts from the confirmed delivery of its documents: only when every
 * active paper set is delivered, none is paid, held or being cancelled, and every unpaid order
 * belongs to such a set; then from the latest delivery. A manual (legacy) paper invoice without
 * bank sets counts from the manager's confirmation that it was issued. Otherwise there is no
 * start yet, so no automatic move.
 */
@Component
class CommonInvoiceOverdueClock {
    private final JdbcTemplate jdbc;

    CommonInvoiceOverdueClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LocalDateTime> start(CommonInvoice invoice) {
        if (invoice == null) return Optional.empty();
        if (!CommonInvoiceRouteState.isOwnerPaperInvoice(invoice)) return Optional.ofNullable(invoice.getSentAt());
        if (invoice.getId() == null) return Optional.empty();
        var sets = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(document_id IS NOT NULL AND delivered_at IS NOT NULL AND paid_at IS NULL
                           AND NOT automation_hold), 0) AS settled,
                       MAX(delivered_at) AS last_delivery
                FROM common_invoice_paper_parts
                WHERE invoice_id = ? AND cancelled_at IS NULL
                """, invoice.getId());
        long total = ((Number) sets.get("total")).longValue();
        if (total == 0) {
            boolean anyBankSet = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT COUNT(*) > 0 FROM common_invoice_paper_parts WHERE invoice_id = ?", Boolean.class, invoice.getId()));
            // Every bank set was cancelled: the invoice waits for a new paper or ordinary invoice.
            return anyBankSet ? Optional.empty() : Optional.ofNullable(invoice.getPaperInvoiceIssuedAt());
        }
        if (((Number) sets.get("settled")).longValue() != total) return Optional.empty();
        Long uncovered = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM common_invoice_orders o
                LEFT JOIN common_invoice_paper_part_orders po ON po.active_order_id = o.order_id
                LEFT JOIN common_invoice_paper_parts p ON p.part_id = po.part_id AND p.invoice_id = o.invoice_id
                    AND p.cancelled_at IS NULL
                WHERE o.invoice_id = ? AND o.active_membership = 1 AND o.paid = 0
                  AND (p.part_id IS NULL OR o.ordinary_after_paper_cancel = 1)
                """, Long.class, invoice.getId());
        if (uncovered == null || uncovered > 0) return Optional.empty();
        Object last = sets.get("last_delivery");
        if (last instanceof Timestamp timestamp) return Optional.of(timestamp.toLocalDateTime());
        return last instanceof LocalDateTime time ? Optional.of(time) : Optional.empty();
    }
}
