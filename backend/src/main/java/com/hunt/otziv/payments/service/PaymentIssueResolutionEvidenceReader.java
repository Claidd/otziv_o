package com.hunt.otziv.payments.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads one settled financial fact for a live or archived order; never changes payment data. */
@Service
@RequiredArgsConstructor
public class PaymentIssueResolutionEvidenceReader {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public Evidence confirmedPaidOrder(long orderId) {
        if (orderId <= 0) return Evidence.unresolved("missing_order");
        Map<String, Object> params = Map.of("orderId", orderId);
        if (!lockOrder(orderId))
            return Evidence.unresolved("order_missing_or_ambiguous");
        List<String> liveStatuses = orderStatuses("orders", params);
        List<String> archivedStatuses = orderStatuses("archive_orders", params);
        if (liveStatuses.size() + archivedStatuses.size() != 1)
            return Evidence.unresolved("order_missing_or_ambiguous");
        if (!"Оплачено".equals(liveStatuses.isEmpty() ? archivedStatuses.getFirst() : liveStatuses.getFirst()))
            return Evidence.unresolved("order_not_paid");

        // A check in both tables is ambiguous even when the order has only one row.
        List<PaymentCheckFact> liveChecks = activeChecks("payment_check", params);
        List<PaymentCheckFact> archivedChecks = activeChecks("archive_payment_check", params);
        if (liveChecks.size() + archivedChecks.size() != 1
                || (liveStatuses.isEmpty() ? !liveChecks.isEmpty() : !archivedChecks.isEmpty()))
            return Evidence.unresolved("check_missing_or_ambiguous");
        PaymentCheckFact check = liveChecks.isEmpty() ? archivedChecks.getFirst() : liveChecks.getFirst();
        if (check.amountKopecks() <= 0 || check.paidAmount() == null || check.paidAmount() <= 0
                || check.paymentLinkId() == null)
            return Evidence.unresolved("check_without_settlement_snapshot");

        // A second link, including an unfinished payment instruction, makes this fact ambiguous.
        List<PaymentLinkFact> liveLinks = orderLinks("payment_links", params);
        List<PaymentLinkFact> archivedLinks = orderLinks("archive_payment_links", params);
        if (liveLinks.size() + archivedLinks.size() != 1)
            return Evidence.unresolved("link_missing_or_ambiguous");
        PaymentLinkFact link = liveLinks.isEmpty() ? archivedLinks.getFirst() : liveLinks.getFirst();
        if (link.id() != check.paymentLinkId() || !"CONFIRMED".equals(link.status())
                || !link.paidAt() || link.amountKopecks() == null
                || link.amountKopecks() != check.amountKopecks())
            return Evidence.unresolved("confirmed_link_missing_or_amount_mismatch");
        if (link.returnOrCancelMarkers())
            return Evidence.unresolved("payment_return_or_cancel_marker");

        Long returnOutbox = jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_link_return_reconciliation_outbox
                WHERE payment_link_id = :linkId
                """, Map.of("linkId", link.id()), Long.class);
        if (returnOutbox == null || returnOutbox > 0)
            return Evidence.unresolved("payment_return_outbox");
        // A common invoice is a different route; mixed-route orders need manual review.
        Long commonInvoices = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM common_invoice_orders WHERE order_id = :orderId)
                     + (SELECT COUNT(*) FROM archive_common_invoice_orders WHERE order_id = :orderId)
                """, params, Long.class);
        if (commonInvoices == null || commonInvoices > 0)
            return Evidence.unresolved("common_invoice_route");
        Long unresolvedReviews = jdbc.queryForObject("""
                SELECT COUNT(*) FROM personal_reminders
                WHERE source_order_id = :orderId
                  AND source_type IN (
                      'PAYMENT_RETURN_RECONCILIATION',
                      'PAYMENT_ACCOUNTING_MISMATCH',
                      'PAYMENT_ORDER_FACT_MISMATCH'
                  )
                  AND completed_at IS NULL
                """, params, Long.class);
        if (unresolvedReviews == null || unresolvedReviews > 0)
            return Evidence.unresolved("unresolved_payment_review");
        return Evidence.confirmed(link.id());
    }

    /** Must run inside the reconciliation transaction before locking its reminder. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockOrder(long orderId) {
        if (orderId <= 0) return false;
        Map<String, Object> params = Map.of("orderId", orderId);
        // Settlement and return paths lock the order before changing its financial fact.
        List<Long> lockedLiveOrder = jdbc.queryForList("""
                SELECT order_id FROM orders WHERE order_id = :orderId FOR UPDATE
                """, params, Long.class);
        if (lockedLiveOrder.isEmpty()) {
            List<Long> lockedArchiveOrder = jdbc.queryForList("""
                    SELECT order_id FROM archive_orders
                    WHERE order_id = :orderId AND restored_at IS NULL FOR UPDATE
                    """, params, Long.class);
            return lockedArchiveOrder.size() == 1;
        }
        return lockedLiveOrder.size() == 1;
    }

    private List<String> orderStatuses(String table, Map<String, Object> params) {
        return jdbc.queryForList("""
                SELECT status.order_status_title
                FROM %s base_order
                LEFT JOIN order_statuses status ON status.order_status_id = base_order.order_status
                WHERE base_order.order_id = :orderId
                """.formatted(table), params, String.class);
    }

    private List<PaymentCheckFact> activeChecks(String table, Map<String, Object> params) {
        return jdbc.query("""
                SELECT check_id, ROUND(check_sum * 100) AS check_kopecks,
                       check_paid_amount, check_payment_link
                FROM %s
                WHERE check_order = :orderId AND check_active = 1
                """.formatted(table), params, (rs, rowNum) -> new PaymentCheckFact(
                rs.getLong("check_kopecks"),
                nullableLong(rs, "check_paid_amount"),
                nullableLong(rs, "check_payment_link")
        ));
    }

    private List<PaymentLinkFact> orderLinks(String table, Map<String, Object> params) {
        // archive_payment_links was copied before the live bank-cancel and provider
        // columns were introduced. Its later migrations added only return_recovery_*.
        String liveOnlyMarkers = "payment_links".equals(table) ? """
                           OR bank_cancel_nonce IS NOT NULL
                           OR bank_cancel_lease_until IS NOT NULL
                           OR bank_cancel_origin_status IS NOT NULL
                           OR bank_cancel_origin_error IS NOT NULL
                           OR COALESCE(provider_terminal_status, '') IN (
                               'REVERSED', 'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED', 'CANCELED'
                           )
                """ : "";
        return jdbc.query("""
                SELECT id, status, confirmed_amount_kopecks,
                       paid_at IS NOT NULL AS has_paid_at,
                       (
                           return_recovery_processed_at IS NOT NULL
                           OR return_recovery_payment_check_id IS NOT NULL
                           OR return_recovery_outcome IS NOT NULL
                           OR return_recovery_resolved_at IS NOT NULL
                           OR return_recovery_resolved_by IS NOT NULL
                           OR return_recovery_resolution_reason IS NOT NULL
                           %s
                       ) AS has_return_or_cancel_markers
                FROM %s WHERE order_id = :orderId
                """.formatted(liveOnlyMarkers, table), params, (rs, rowNum) -> paymentLinkFact(rs));
    }

    private PaymentLinkFact paymentLinkFact(ResultSet rs) throws SQLException {
        return new PaymentLinkFact(
                rs.getLong("id"), rs.getString("status"),
                nullableLong(rs, "confirmed_amount_kopecks"),
                rs.getBoolean("has_paid_at"), rs.getBoolean("has_return_or_cancel_markers")
        );
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private record PaymentCheckFact(long amountKopecks, Long paidAmount, Long paymentLinkId) {}
    private record PaymentLinkFact(long id, String status, Long amountKopecks,
                                   boolean paidAt, boolean returnOrCancelMarkers) {}

    public record Evidence(boolean confirmed, String reason, Long paymentLinkId) {
        static Evidence unresolved(String reason) { return new Evidence(false, reason, null); }
        static Evidence confirmed(Long paymentLinkId) {
            return new Evidence(true, "confirmed_paid_order", paymentLinkId);
        }
    }
}
