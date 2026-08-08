package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.service.PaymentUrlPolicy;
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
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
            "contractor_allocation_id",
            "shadow_route_generation",
            "shadow_route_order_id",
            "shadow_route_worker_id",
            "shadow_route_worker_user_id",
            "shadow_route_manager_id",
            "shadow_route_manager_user_id",
            "shadow_route_amount_kopecks",
            "shadow_route_company_routing_allowed",
            "shadow_route_prepared_at",
            "contractor_evidence_original_link_id",
            "payment_url",
            "sbp_qr_payload",
            "sbp_qr_image",
            "sbp_qr_data_type",
            "sbp_qr_created_at",
            "manual_phone",
            "manual_recipient_name",
            "manual_bank_name",
            "manual_payment_url",
            "manual_payment_button_label",
            "manual_comment",
            "manual_reported_at",
            "manual_confirmed_by",
            "manual_confirmed_at",
            "receipt_status",
            "payment_success_notified_at",
            "payment_success_notification_error",
            "payment_success_notification_retry_eligible",
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

    /**
     * A live payment link must remain authoritative while a provider operation,
     * delayed cancel observation, or retryable client notification is pending.
     */
    private static final String ARCHIVE_STATE_BLOCKER_SQL = """
            (
                pl.status = 'NEEDS_RECONCILIATION'
                OR COALESCE(pl.status, '') NOT IN (
                    'CREATED',
                    'TEST_CONFIRMED',
                    'CONFIRMED',
                    'REJECTED',
                    'CANCELED',
                    'REVERSED',
                    'REFUNDED',
                    'EXPIRED',
                    'FAILED'
                )
                OR pl.bank_init_nonce IS NOT NULL
                OR pl.bank_cancel_nonce IS NOT NULL
                OR pl.bank_cancel_origin_status IS NOT NULL
                OR COALESCE(pl.receipt_status, 'DONE') = 'PENDING'
                OR (
                    pl.status = 'CONFIRMED'
                    AND pl.payment_success_notified_at IS NULL
                    AND COALESCE(pl.payment_success_notification_retry_eligible, 0) = 1
                )
            )
            """;

    /**
     * The generic closed-link archiver has no contractor reconciliation
     * service. It may move a routed source only after every current SHADOW/LIVE
     * attempt is terminal, has no unresolved return, has no in-flight claim,
     * and was reconciled after the latest source change. Order archiving uses
     * its own two-phase strict reconciliation and intentionally bypasses this
     * selection-only fence.
     */
    private static final String CONTRACTOR_ARCHIVE_SELECTION_BLOCKER_SQL = """
            (
            (
                pl.shadow_route_generation IS NOT NULL
                AND pl.shadow_route_prepared_at IS NOT NULL
                AND NOT EXISTS (
                    SELECT 1
                    FROM contractor_payment_allocations prepared_shadow
                    WHERE prepared_shadow.mode = 'SHADOW'
                      AND prepared_shadow.source_type = 'PAYMENT_LINK'
                      AND prepared_shadow.source_id = pl.id
                      AND prepared_shadow.source_generation_snapshot = pl.shadow_route_generation
                )
            )
            OR EXISTS (
                SELECT 1
                FROM contractor_payment_allocations contractor_allocation
                WHERE contractor_allocation.source_type = 'PAYMENT_LINK'
                  AND contractor_allocation.source_id = pl.id
                  AND contractor_allocation.attempt_no = (
                      SELECT MAX(contractor_latest.attempt_no)
                      FROM contractor_payment_allocations contractor_latest
                      WHERE contractor_latest.mode = contractor_allocation.mode
                        AND contractor_latest.source_type = contractor_allocation.source_type
                        AND contractor_latest.source_id = contractor_allocation.source_id
                  )
                  AND (
                      contractor_allocation.status NOT IN (
                          'CONFIRMED',
                          'SIMULATED_PAID',
                          'LATE_PAYMENT_AFTER_RELEASE',
                          'OWNER_FALLBACK',
                          'RELEASED_UNPAID',
                          'EXPIRED',
                          'CANCELED',
                          'RETURNED',
                          'PARTIALLY_RETURNED'
                      )
                      OR contractor_allocation.needs_return_amount = TRUE
                      OR contractor_allocation.reconcile_claim_token IS NOT NULL
                      OR contractor_allocation.last_reconciled_at IS NULL
                      OR pl.updated_at > contractor_allocation.last_reconciled_at
                  )
            ))
            """;

    /** Keeps both the original bank link and its separate manual evidence row
     * live until every current contractor allocation has durably recorded the
     * evidence event. */
    private static final String CONTRACTOR_MANUAL_EVIDENCE_BLOCKER_SQL = """
            EXISTS (
                SELECT 1
                FROM payment_links contractor_evidence
                JOIN contractor_payment_allocations contractor_allocation
                  ON contractor_allocation.source_type = 'PAYMENT_LINK'
                 AND contractor_allocation.source_id = contractor_evidence.contractor_evidence_original_link_id
                 AND contractor_allocation.recipient_profile_id IS NOT NULL
                 AND contractor_allocation.attempt_no = (
                     SELECT MAX(contractor_latest.attempt_no)
                     FROM contractor_payment_allocations contractor_latest
                     WHERE contractor_latest.mode = contractor_allocation.mode
                       AND contractor_latest.source_type = contractor_allocation.source_type
                       AND contractor_latest.source_id = contractor_allocation.source_id
                 )
                WHERE (contractor_evidence.id = pl.id
                       OR contractor_evidence.contractor_evidence_original_link_id = pl.id)
                  AND contractor_evidence.contractor_evidence_original_link_id IS NOT NULL
                  AND contractor_evidence.status = 'CONFIRMED'
                  AND contractor_evidence.payment_method = 'MANUAL_MOBILE_BANK'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM contractor_payment_allocation_events contractor_event
                      WHERE contractor_event.allocation_id = contractor_allocation.id
                        AND contractor_event.external_ref = CONCAT(
                            'MANUAL_EVIDENCE:', contractor_evidence.id
                        )
                  )
            )
            """;

    /**
     * MySQL forbids a DELETE target from being read again by a correlated
     * subquery (error 1093). DISTINCT makes this bounded derived table
     * non-mergeable, so deletion evaluates the exact same evidence fence from
     * a materialized snapshot of only the requested source/evidence rows.
     */
    private static final String CONTRACTOR_MANUAL_EVIDENCE_DELETE_BLOCKER_SQL = """
            EXISTS (
                SELECT 1
                FROM (
                    SELECT DISTINCT
                        contractor_evidence_source.id,
                        contractor_evidence_source.contractor_evidence_original_link_id,
                        contractor_evidence_source.status,
                        contractor_evidence_source.payment_method
                    FROM payment_links contractor_evidence_source
                    WHERE contractor_evidence_source.id IN (:ids)
                       OR contractor_evidence_source.contractor_evidence_original_link_id IN (:ids)
                ) contractor_evidence
                JOIN contractor_payment_allocations contractor_allocation
                  ON contractor_allocation.source_type = 'PAYMENT_LINK'
                 AND contractor_allocation.source_id = contractor_evidence.contractor_evidence_original_link_id
                 AND contractor_allocation.recipient_profile_id IS NOT NULL
                 AND contractor_allocation.attempt_no = (
                     SELECT MAX(contractor_latest.attempt_no)
                     FROM contractor_payment_allocations contractor_latest
                     WHERE contractor_latest.mode = contractor_allocation.mode
                       AND contractor_latest.source_type = contractor_allocation.source_type
                       AND contractor_latest.source_id = contractor_allocation.source_id
                 )
                WHERE (contractor_evidence.id = pl.id
                       OR contractor_evidence.contractor_evidence_original_link_id = pl.id)
                  AND contractor_evidence.contractor_evidence_original_link_id IS NOT NULL
                  AND contractor_evidence.status = 'CONFIRMED'
                  AND contractor_evidence.payment_method = 'MANUAL_MOBILE_BANK'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM contractor_payment_allocation_events contractor_event
                      WHERE contractor_event.allocation_id = contractor_allocation.id
                        AND contractor_event.external_ref = CONCAT(
                            'MANUAL_EVIDENCE:', contractor_evidence.id
                        )
                  )
            )
            """;

    /**
     * Candidate discovery may pass an abandoned claim only after its database
     * lease has expired and the payment link no longer represents retryable
     * notification work. A live lease remains an unconditional blocker.
     */
    private static final String ARCHIVE_SELECTION_BLOCKER_SQL = """
            (
                %s
                OR EXISTS (
                    SELECT 1
                    FROM payment_success_notification_retry_claims notification_claim
                    WHERE notification_claim.payment_link_id = pl.id
                      AND notification_claim.processing_lease_until > CURRENT_TIMESTAMP(6)
                )
                OR %s
                OR %s
            )
            """.formatted(
            ARCHIVE_STATE_BLOCKER_SQL,
            CONTRACTOR_ARCHIVE_SELECTION_BLOCKER_SQL,
            CONTRACTOR_MANUAL_EVIDENCE_BLOCKER_SQL
    );

    /**
     * Copy/delete and hard-delete guards remain strict: cleanup must have
     * removed every expired, ineligible claim before any live row can move.
     */
    private static final String ARCHIVE_FINAL_BLOCKER_SQL = archiveFinalBlockerSql(
            CONTRACTOR_MANUAL_EVIDENCE_BLOCKER_SQL
    );

    private static final String ARCHIVE_DELETE_BLOCKER_SQL = archiveFinalBlockerSql(
            CONTRACTOR_MANUAL_EVIDENCE_DELETE_BLOCKER_SQL
    );

    private static final String ARCHIVE_ELIGIBILITY_SQL = """
            (
                (
                    pl.status IN ('CONFIRMED', 'TEST_CONFIRMED')
                    AND COALESCE(pl.paid_at, pl.updated_at, pl.created_at) < :paidCutoff
                    AND COALESCE(pl.receipt_status, 'DONE') <> 'PENDING'
                )
                OR (
                    pl.status IN (
                        'EXPIRED', 'REJECTED', 'FAILED', 'CANCELED', 'REVERSED',
                        'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED'
                    )
                    AND COALESCE(pl.updated_at, pl.created_at) < :finalCutoff
                )
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<AdminPaymentLinkResponse> findArchivedPage(
            int page,
            int size,
            String statusFilter,
            String search,
            Long searchId,
            LocalDate from,
            LocalDate to,
            boolean excludePrivilegedTargets,
            String publicBaseUrl
    ) {
        MapSqlParameterSource params = filterParams(
                statusFilter,
                search,
                searchId,
                from,
                to,
                excludePrivilegedTargets
        )
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
            LocalDate to,
            boolean excludePrivilegedTargets
    ) {
        MapSqlParameterSource params = filterParams(
                statusFilter,
                search,
                searchId,
                from,
                to,
                excludePrivilegedTargets
        );
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
                  COALESCE(SUM(CASE WHEN apl.status IN ('REJECTED', 'FAILED', 'NEEDS_RECONCILIATION') THEN 1 ELSE 0 END), 0) AS rejected,
                  COALESCE(SUM(CASE WHEN apl.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK') AND apl.status = 'CONFIRMED' AND apl.receipt_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS receipt_pending,
                  COALESCE(SUM(CASE WHEN apl.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK') AND apl.status = 'CONFIRMED' AND apl.receipt_status = 'PENDING' AND apl.paid_at <= CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR THEN 1 ELSE 0 END), 0) AS receipt_overdue
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
                rs.getLong("rejected"),
                rs.getLong("receipt_pending"),
                rs.getLong("receipt_overdue")
        ));
    }

    public List<Long> findArchiveCandidateIds(
            LocalDateTime paidCutoff,
            LocalDateTime finalCutoff,
            int limit
    ) {
        String sql = """
                SELECT pl.id
                FROM payment_links pl
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM archive_payment_links apl
                    WHERE apl.id = pl.id
                )
                  AND %s
                  AND NOT %s
                ORDER BY pl.created_at ASC, pl.id ASC
                LIMIT :limit
                """.formatted(ARCHIVE_ELIGIBILITY_SQL, ARCHIVE_SELECTION_BLOCKER_SQL);
        return jdbc.queryForList(sql, new MapSqlParameterSource()
                .addValue("paidCutoff", Timestamp.valueOf(paidCutoff))
                .addValue("finalCutoff", Timestamp.valueOf(finalCutoff))
                .addValue("limit", Math.max(1, limit)), Long.class);
    }

    public List<Long> findOrderIdsForPaymentLinkIds(Collection<Long> paymentLinkIds) {
        if (paymentLinkIds == null || paymentLinkIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT DISTINCT pl.order_id
                FROM payment_links pl
                WHERE pl.id IN (:paymentLinkIds)
                ORDER BY pl.order_id
                """, Map.of("paymentLinkIds", paymentLinkIds), Long.class);
    }

    public List<Long> lockOrderIdsForArchive(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT o.order_id
                FROM orders o
                WHERE o.order_id IN (:orderIds)
                ORDER BY o.order_id
                FOR UPDATE
                """, Map.of("orderIds", orderIds), Long.class);
    }

    public List<Long> findArchiveCandidateIdsForUpdate(
            Collection<Long> snapshotIds,
            Collection<Long> lockedOrderIds,
            LocalDateTime paidCutoff,
            LocalDateTime finalCutoff
    ) {
        if (snapshotIds == null || snapshotIds.isEmpty()
                || lockedOrderIds == null || lockedOrderIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT pl.id
                FROM payment_links pl
                WHERE pl.id IN (:snapshotIds)
                  AND pl.order_id IN (:lockedOrderIds)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM archive_payment_links archived
                      WHERE archived.id = pl.id
                  )
                  AND %s
                  AND NOT %s
                ORDER BY pl.order_id, pl.id
                FOR UPDATE SKIP LOCKED
                """.formatted(ARCHIVE_ELIGIBILITY_SQL, ARCHIVE_SELECTION_BLOCKER_SQL);
        return jdbc.queryForList(sql, new MapSqlParameterSource()
                .addValue("snapshotIds", snapshotIds)
                .addValue("lockedOrderIds", lockedOrderIds)
                .addValue("paidCutoff", Timestamp.valueOf(paidCutoff))
                .addValue("finalCutoff", Timestamp.valueOf(finalCutoff)), Long.class);
    }

    public List<Long> findLiveIdsByOrderIdForUpdate(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT pl.id
                FROM payment_links pl
                WHERE pl.order_id = :orderId
                ORDER BY pl.created_at ASC, pl.id ASC
                FOR UPDATE
                """, Map.of("orderId", orderId), Long.class);
    }

    /**
     * Deletes only abandoned leases for payment rows already locked by the
     * caller. The canonical order is therefore Order -&gt; PaymentLink -&gt; Claim.
     * A live lease, or an expired lease whose CONFIRMED link is still retryable,
     * is deliberately preserved.
     */
    public int deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(
            Collection<Long> lockedPaymentLinkIds
    ) {
        if (lockedPaymentLinkIds == null || lockedPaymentLinkIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                DELETE notification_claim
                FROM payment_success_notification_retry_claims notification_claim
                JOIN payment_links pl ON pl.id = notification_claim.payment_link_id
                WHERE pl.id IN (:paymentLinkIds)
                  AND notification_claim.processing_lease_until <= CURRENT_TIMESTAMP(6)
                  AND NOT (
                      pl.status = 'CONFIRMED'
                      AND pl.payment_success_notified_at IS NULL
                      AND COALESCE(pl.payment_success_notification_retry_eligible, 0) = 1
                  )
                """, Map.of("paymentLinkIds", lockedPaymentLinkIds));
    }

    public boolean hasLiveArchiveBlockerForOrder(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM payment_links pl
                    WHERE pl.order_id = :orderId
                      AND %s
                )
                """.formatted(ARCHIVE_FINAL_BLOCKER_SQL);
        Long present = jdbc.queryForObject(sql, Map.of("orderId", orderId), Long.class);
        return present != null && present > 0;
    }

    public List<Long> findLiveIdsForPreparedOrderArchiveCandidatesForUpdate() {
        return jdbc.queryForList("""
                SELECT pl.id
                FROM payment_links pl
                JOIN archive_candidate_orders co ON co.order_id = pl.order_id
                ORDER BY pl.created_at ASC, pl.id ASC
                FOR UPDATE
                """, Map.of(), Long.class);
    }

    public boolean hasPreparedOrderArchiveBlocker() {
        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM payment_links pl
                    JOIN archive_candidate_orders co ON co.order_id = pl.order_id
                    WHERE %s
                )
                """.formatted(ARCHIVE_FINAL_BLOCKER_SQL);
        Long present = jdbc.queryForObject(sql, Map.of(), Long.class);
        return present != null && present > 0;
    }

    public int countArchivedIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM archive_payment_links
                WHERE id IN (:ids)
                """, Map.of("ids", ids), Integer.class);
        return count == null ? 0 : count;
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
        String sql = ("""
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
                  AND NOT %s
                """).formatted(columns, selectColumns, ARCHIVE_FINAL_BLOCKER_SQL);
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
        String sql = """
                DELETE pl
                FROM payment_links pl
                WHERE pl.id IN (:ids)
                  AND NOT %s
                  AND EXISTS (
                      SELECT 1
                      FROM archive_payment_links archived
                      WHERE archived.id = pl.id
                  )
                """.formatted(ARCHIVE_DELETE_BLOCKER_SQL);
        return jdbc.update(sql, Map.of("ids", ids));
    }

    private static String archiveFinalBlockerSql(String manualEvidenceBlockerSql) {
        return """
                (
                    %s
                    OR EXISTS (
                        SELECT 1
                        FROM payment_success_notification_retry_claims notification_claim
                        WHERE notification_claim.payment_link_id = pl.id
                    )
                    OR %s
                    OR %s
                )
                """.formatted(
                ARCHIVE_STATE_BLOCKER_SQL,
                CONTRACTOR_ARCHIVE_SELECTION_BLOCKER_SQL,
                manualEvidenceBlockerSql
        );
    }

    private MapSqlParameterSource filterParams(
            String statusFilter,
            String search,
            Long searchId,
            LocalDate from,
            LocalDate to,
            boolean excludePrivilegedTargets
    ) {
        String normalizedSearch = normalize(search);
        return new MapSqlParameterSource()
                .addValue("statusFilter", normalizeStatusFilter(statusFilter))
                .addValue("searchText", normalizedSearch.isBlank() ? null : "%" + normalizedSearch.toLowerCase(Locale.ROOT) + "%")
                .addValue("searchId", searchId)
                .addValue("from", from == null ? null : Timestamp.valueOf(from.atStartOfDay()))
                .addValue("to", to == null ? null : Timestamp.valueOf(to.plusDays(1).atStartOfDay()))
                .addValue("excludePrivilegedTargets", excludePrivilegedTargets);
    }

    private String filterWhereClause() {
        return """
                WHERE (:from IS NULL OR apl.created_at >= :from)
                  AND (:to IS NULL OR apl.created_at < :to)
                  AND (
                    :excludePrivilegedTargets = FALSE
                    OR NOT EXISTS (
                      SELECT 1
                      FROM contractor_payment_allocations allocation
                      JOIN users_roles recipient_user_role
                        ON recipient_user_role.user_id = allocation.recipient_user_id
                      JOIN roles recipient_role
                        ON recipient_role.id = recipient_user_role.role_id
                      WHERE allocation.id = apl.contractor_allocation_id
                        AND recipient_role.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
                    )
                  )
                  AND (
                    :statusFilter = 'all'
                    OR (:statusFilter = 'active' AND apl.status IN ('CREATED', 'INITIATED', 'AUTHORIZED', 'WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED'))
                    OR (:statusFilter = 'paid' AND apl.status IN ('AUTHORIZED', 'TEST_CONFIRMED', 'CONFIRMED', 'AMOUNT_MISMATCH'))
                    OR (:statusFilter = 'refunded' AND apl.status IN ('REVERSED', 'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED', 'CANCELED'))
                    OR (:statusFilter = 'failed' AND apl.status IN ('REJECTED', 'FAILED', 'NEEDS_RECONCILIATION', 'EXPIRED'))
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
                    OR (COALESCE(apl.manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        AND LOWER(COALESCE(apl.manual_phone, '')) LIKE :searchText)
                    OR (COALESCE(apl.manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        AND LOWER(COALESCE(apl.manual_recipient_name, '')) LIKE :searchText)
                    OR LOWER(COALESCE(apl.manual_payment_url, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.manual_payment_button_label, '')) LIKE :searchText
                    OR (COALESCE(apl.manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        AND LOWER(COALESCE(apl.manual_comment, '')) LIKE :searchText)
                    OR LOWER(COALESCE(apl.payment_success_notification_error, '')) LIKE :searchText
                    OR LOWER(COALESCE(apl.last_error, '')) LIKE :searchText
                    OR (:searchId IS NOT NULL AND (apl.id = :searchId OR apl.order_id = :searchId))
                  )
                """;
    }

    private AdminPaymentLinkResponse archivedResponse(ResultSet rs, String publicBaseUrl) throws SQLException {
        long amountKopecks = rs.getLong("amount_kopecks");
        String token = rs.getString("token");
        String manualSource = value(rs, "manual_source");
        boolean contractorRoute = "CONTRACTOR_PAYMENT_PROFILE".equals(manualSource);
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
                manualSource,
                nullableLong(rs, "manual_task_id"),
                contractorRoute ? "" : value(rs, "manual_recipient_name"),
                value(rs, "tbank_terminal_key"),
                value(rs, "tbank_payment_id"),
                value(rs, "tbank_order_id"),
                value(rs, "payer_email"),
                PaymentUrlPolicy.safe(value(rs, "payment_url"), PaymentUrlPolicy.Purpose.TBANK_PAYMENT),
                value(rs, "manual_payment_type"),
                contractorRoute ? "" : value(rs, "manual_phone"),
                contractorRoute ? "" : value(rs, "manual_recipient_name"),
                contractorRoute ? "" : value(rs, "manual_bank_name"),
                contractorRoute
                        ? ""
                        : PaymentUrlPolicy.safe(
                        value(rs, "manual_payment_url"),
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
                ),
                value(rs, "manual_payment_button_label"),
                contractorRoute ? "" : value(rs, "manual_comment"),
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
