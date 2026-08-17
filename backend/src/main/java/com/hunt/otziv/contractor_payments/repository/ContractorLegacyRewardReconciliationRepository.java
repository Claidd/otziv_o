package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable snapshots for legacy reward classification. No FK points at the
 * mutable legacy rows: deleted orders and their signed evidence must remain
 * inspectable after normal retention jobs run.
 */
@Repository
@RequiredArgsConstructor
public class ContractorLegacyRewardReconciliationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DbNow dbNow() {
        return jdbc.queryForObject(
                "SELECT CURRENT_DATE AS business_date, CURRENT_TIMESTAMP(6) AS db_now",
                Map.of(),
                (rs, row) -> new DbNow(
                        rs.getDate("business_date").toLocalDate(),
                        rs.getTimestamp("db_now").toLocalDateTime()
                )
        );
    }

    public List<CandidateRow> findCandidates(LocalDate startDate) {
        return jdbc.query("""
                SELECT grouped.*
                FROM (
                SELECT candidate.*,
                       MAX(
                           CASE
                               WHEN candidate.zp_source IS NULL
                                    OR TRIM(candidate.zp_source) = '' THEN 1
                               WHEN CAST(candidate.zp_source AS BINARY) IN (
                                   CAST('ORDER_MANAGER_REWARD' AS BINARY),
                                   CAST('ORDER_SPECIALIST_REWARD' AS BINARY),
                                   CAST('PERFORMER_PRODUCT_REWARD' AS BINARY)
                               ) THEN CASE WHEN candidate.dated_pre_cutoff = 1 THEN 0 ELSE 1 END
                               WHEN CAST(candidate.zp_source AS BINARY) IN (
                                   CAST('ORDER_COMPLETION_MANAGER' AS BINARY),
                                   CAST('ORDER_COMPLETION_SPECIALIST' AS BINARY),
                                   CAST('PERFORMER_PRODUCT_COMPLETION' AS BINARY)
                               ) THEN 0
                               WHEN EXISTS (
                                   SELECT 1
                                   FROM bad_review_tasks classified_task
                                   WHERE classified_task.bad_review_task_order = candidate.zp_order
                                     AND CAST(candidate.zp_source AS BINARY) IN (
                                         CAST(CONCAT('BAD_REVIEW_DONE_MANAGER:',
                                             classified_task.bad_review_task_id) AS BINARY),
                                         CAST(CONCAT('BAD_REVIEW_DONE_SPECIALIST:',
                                             classified_task.bad_review_task_id) AS BINARY),
                                         CAST(CONCAT('BAD_REVIEW_CANCEL_MANAGER:',
                                             classified_task.bad_review_task_id) AS BINARY),
                                         CAST(CONCAT('BAD_REVIEW_CANCEL_SPECIALIST:',
                                             classified_task.bad_review_task_id) AS BINARY)
                                     )
                               ) THEN 0
                               ELSE 1
                           END
                       ) OVER (PARTITION BY candidate.zp_order) AS group_requires_reconciliation
                FROM (
                SELECT z.zp_id, z.zp_order, z.zp_user, z.zp_profession, z.zp_sum,
                       z.zp_amount, z.zp_date, z.zp_updated_at, z.zp_active,
                       z.zp_source, z.zp_contractor_role, z.zp_attribution_final,
                       z.zp_reward_basis, z.zp_attribution_snapshot,
                       CASE WHEN w.worker_id IS NOT NULL AND m.manager_id IS NULL THEN 'SPECIALIST'
                            WHEN m.manager_id IS NOT NULL AND w.worker_id IS NULL THEN 'MANAGER' END AS inferred_role,
                       CASE WHEN o.order_id IS NULL THEN 0 ELSE 1 END AS order_exists,
                       CASE WHEN o.order_id IS NOT NULL
                                  AND o.order_amount > 0
                                  AND z.zp_date IS NOT NULL
                                  AND z.zp_date < :startDate
                                  AND (SELECT COUNT(*)
                                       FROM order_details d
                                       JOIN reviews r ON r.review_order_details = d.order_detail_id
                                       WHERE d.order_detail_order = o.order_id
                                         AND r.review_publish = 1) = o.order_amount
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM order_details d2
                                      JOIN reviews r2 ON r2.review_order_details = d2.order_detail_id
                                      WHERE d2.order_detail_order = o.order_id
                                        AND r2.review_publish = 1
                                        AND (r2.review_publish_date IS NULL
                                             OR r2.review_publish_date >= :startDate)
                                  )
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM bad_review_tasks completed_task
                                      WHERE completed_task.bad_review_task_order = o.order_id
                                        AND completed_task.bad_review_task_status = 'DONE'
                                        AND (
                                            completed_task.bad_review_task_completed_date IS NULL
                                            OR completed_task.bad_review_task_completed_date >= :startDate
                                        )
                                  )
                            THEN 1 ELSE 0 END AS dated_pre_cutoff,
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM review_recovery_tasks rt
                           JOIN review_recovery_batches rb
                             ON rb.review_recovery_batch_id = rt.review_recovery_task_batch
                           WHERE rt.review_recovery_task_order = z.zp_order
                             AND rt.review_recovery_task_status = 'PLANNED'
                             AND rb.review_recovery_batch_status = 'OPEN'
                       ) THEN 1 ELSE 0 END AS active_recovery
                FROM zp z
                LEFT JOIN workers w
                  ON w.worker_id = z.zp_profession AND w.user_id = z.zp_user
                LEFT JOIN managers m
                  ON m.manager_id = z.zp_profession AND m.user_id = z.zp_user
                LEFT JOIN orders o ON o.order_id = z.zp_order
                WHERE z.zp_active = 1
                  AND z.zp_order > 0
                ) candidate
                ) grouped
                WHERE grouped.group_requires_reconciliation = 1
                ORDER BY grouped.zp_order, grouped.zp_id
                """, Map.of("startDate", startDate), this::mapCandidate);
    }

    public long insertRun(
            LocalDate startDate,
            String snapshotHash,
            int autoOrders,
            int autoRows,
            int manualOrders,
            int manualRows,
            LocalDateTime expiresAt,
            String actor
    ) {
        org.springframework.jdbc.support.GeneratedKeyHolder keys =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO contractor_legacy_reward_reconciliation_runs (
                    reconciliation_start_date, reconciliation_status,
                    reconciliation_snapshot_hash, reconciliation_auto_order_count,
                    reconciliation_auto_row_count, reconciliation_manual_order_count,
                    reconciliation_manual_row_count, reconciliation_expires_at,
                    reconciliation_created_by
                ) VALUES (
                    :startDate, 'PREPARED', :snapshotHash, :autoOrders,
                    :autoRows, :manualOrders, :manualRows, :expiresAt, :actor
                )
                """, new MapSqlParameterSource()
                        .addValue("startDate", startDate)
                        .addValue("snapshotHash", snapshotHash)
                        .addValue("autoOrders", autoOrders)
                        .addValue("autoRows", autoRows)
                        .addValue("manualOrders", manualOrders)
                        .addValue("manualRows", manualRows)
                        .addValue("expiresAt", Timestamp.valueOf(expiresAt))
                        .addValue("actor", actor), keys, new String[]{"reconciliation_run_id"});
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Legacy reconciliation run id was not generated");
        }
        return key.longValue();
    }

    public void insertItems(long runId, List<SnapshotItem> items) {
        for (SnapshotItem item : items) {
            CandidateRow row = item.row();
            jdbc.update("""
                    INSERT INTO contractor_legacy_reward_reconciliation_items (
                        reconciliation_run_id, reconciliation_order_id, reconciliation_zp_id,
                        reconciliation_kind, reconciliation_status,
                        reconciliation_evidence_category, reconciliation_group_hash,
                        original_zp_user, original_zp_profession, original_zp_sum,
                        original_zp_amount, original_zp_date, original_zp_updated_at,
                        original_zp_active, original_zp_source,
                        original_zp_contractor_role, original_zp_attribution_final,
                        original_zp_reward_basis, original_zp_attribution_snapshot_hash,
                        target_zp_source, target_zp_contractor_role,
                        target_zp_attribution_final
                    ) VALUES (
                        :runId, :orderId, :zpId, :kind, 'PENDING', :category, :groupHash,
                        :userId, :professionId, :amount, :units, :occurredOn, :updatedAt,
                        1, :originalSource, :originalRole, :originalFinal, :rewardBasis,
                        :attributionHash, :targetSource, :targetRole, 1
                    )
                    """, new MapSqlParameterSource()
                            .addValue("runId", runId)
                            .addValue("orderId", row.orderId())
                            .addValue("zpId", row.zpId())
                            .addValue("kind", item.kind())
                            .addValue("category", item.evidenceCategory())
                            .addValue("groupHash", item.groupHash())
                            .addValue("userId", row.userId())
                            .addValue("professionId", row.professionId())
                            .addValue("amount", row.amount())
                            .addValue("units", row.units())
                            .addValue("occurredOn", row.occurredOn())
                            .addValue("updatedAt", row.updatedAt())
                            .addValue("originalSource", row.source())
                            .addValue("originalRole", row.role())
                            .addValue("originalFinal", row.attributionFinal())
                            .addValue("rewardBasis", row.rewardBasis())
                            .addValue("attributionHash", item.attributionSnapshotHash())
                            .addValue("targetSource", item.targetSource())
                            .addValue("targetRole", item.targetRole() == null
                                    ? null : item.targetRole().name()));
        }
    }

    public RunRow findLatestRun() {
        List<RunRow> rows = jdbc.query("""
                SELECT * FROM contractor_legacy_reward_reconciliation_runs
                ORDER BY reconciliation_run_id DESC LIMIT 1
                """, Map.of(), this::mapRun);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RunRow lockRun(long runId) {
        List<RunRow> rows = jdbc.query("""
                SELECT * FROM contractor_legacy_reward_reconciliation_runs
                WHERE reconciliation_run_id = :runId FOR UPDATE
                """, Map.of("runId", runId), this::mapRun);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ItemRow> lockItems(long runId, String kind, Long orderId) {
        String orderFilter = orderId == null ? "" : " AND reconciliation_order_id = :orderId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("kind", kind)
                .addValue("orderId", orderId);
        return jdbc.query("""
                SELECT * FROM contractor_legacy_reward_reconciliation_items
                WHERE reconciliation_run_id = :runId
                  AND reconciliation_kind = :kind
                """ + orderFilter + """
                ORDER BY reconciliation_order_id, reconciliation_zp_id
                FOR UPDATE
                """, params, this::mapItem);
    }

    public int lockExistingOrders(Iterable<Long> orderIds) {
        List<Long> ids = new java.util.ArrayList<>();
        orderIds.forEach(id -> {
            if (id != null && id > 0) {
                ids.add(id);
            }
        });
        List<Long> lockedIds = ids.stream().distinct().sorted().toList();
        if (lockedIds.isEmpty()) {
            return 0;
        }
        return jdbc.query("""
                SELECT order_id FROM orders
                WHERE order_id IN (:orderIds)
                ORDER BY order_id
                FOR UPDATE
                """, Map.of("orderIds", lockedIds), (rs, row) -> rs.getLong("order_id")).size();
    }

    public List<ItemRow> findManualItems(long runId) {
        return jdbc.query("""
                SELECT * FROM contractor_legacy_reward_reconciliation_items
                WHERE reconciliation_run_id = :runId
                  AND reconciliation_kind = 'MANUAL'
                ORDER BY reconciliation_order_id, reconciliation_zp_id
                """, Map.of("runId", runId), this::mapItem);
    }

    public List<AttestationRow> findAppliedManualAttestations(Long orderId, LocalDate cutoff) {
        return jdbc.query("""
                SELECT i.reconciliation_run_id, i.reconciliation_order_id,
                       i.reconciliation_zp_id, i.reconciliation_group_hash,
                       i.original_zp_user, i.original_zp_profession, i.original_zp_sum,
                       i.original_zp_amount, i.original_zp_date, i.original_zp_active,
                       i.original_zp_reward_basis, i.original_zp_attribution_snapshot_hash,
                       i.target_zp_source, i.target_zp_contractor_role,
                       i.target_zp_attribution_final, i.manual_completed_on,
                       r.reconciliation_snapshot_hash
                FROM contractor_legacy_reward_reconciliation_items i
                JOIN contractor_legacy_reward_reconciliation_runs r
                  ON r.reconciliation_run_id = i.reconciliation_run_id
                WHERE i.reconciliation_order_id = :orderId
                  AND i.reconciliation_kind = 'MANUAL'
                  AND i.reconciliation_status = 'APPLIED'
                  AND i.manual_completed_on < :cutoff
                  AND i.resolved_at IS NOT NULL
                  AND NULLIF(TRIM(i.resolved_by), '') IS NOT NULL
                  AND NULLIF(TRIM(i.manual_evidence_reference), '') IS NOT NULL
                  AND NULLIF(TRIM(i.resolution_reason), '') IS NOT NULL
                ORDER BY i.reconciliation_run_id DESC, i.reconciliation_zp_id
                """, Map.of("orderId", orderId, "cutoff", cutoff), this::mapAttestation);
    }

    public int casApply(ItemRow item) {
        return jdbc.update("""
                UPDATE zp
                SET zp_source = :targetSource,
                    zp_contractor_role = :targetRole,
                    zp_attribution_final = :targetFinal
                WHERE zp_id = :zpId
                  AND zp_order = :orderId
                  AND zp_user = :userId
                  AND zp_profession = :professionId
                  AND zp_sum <=> :amount
                  AND zp_amount = :units
                  AND zp_date <=> :occurredOn
                  AND zp_updated_at <=> :updatedAt
                  AND zp_active = :active
                  AND zp_source <=> :originalSource
                  AND zp_contractor_role <=> :originalRole
                  AND zp_attribution_final = :originalFinal
                  AND zp_reward_basis <=> :rewardBasis
                  AND SHA2(COALESCE(zp_attribution_snapshot, ''), 256) <=> :attributionHash
                  AND (
                      (:targetRole = 'SPECIALIST' AND EXISTS (
                          SELECT 1 FROM workers w
                          WHERE w.worker_id = zp_profession AND w.user_id = zp_user
                      ))
                      OR (:targetRole = 'MANAGER' AND EXISTS (
                          SELECT 1 FROM managers m
                          WHERE m.manager_id = zp_profession AND m.user_id = zp_user
                      ))
                  )
                """, item.params());
    }

    public boolean exactOriginalSnapshot(ItemRow item) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM zp
                WHERE zp_id = :zpId
                  AND zp_order = :orderId
                  AND zp_user = :userId
                  AND zp_profession = :professionId
                  AND zp_sum <=> :amount
                  AND zp_amount = :units
                  AND zp_date <=> :occurredOn
                  AND zp_updated_at <=> :updatedAt
                  AND zp_active = :active
                  AND zp_source <=> :originalSource
                  AND zp_contractor_role <=> :originalRole
                  AND zp_attribution_final = :originalFinal
                  AND zp_reward_basis <=> :rewardBasis
                  AND SHA2(COALESCE(zp_attribution_snapshot, ''), 256) <=> :attributionHash
                  AND (
                      (:targetRole = 'SPECIALIST' AND EXISTS (
                          SELECT 1 FROM workers w
                          WHERE w.worker_id = zp_profession AND w.user_id = zp_user
                      ))
                      OR (:targetRole = 'MANAGER' AND EXISTS (
                          SELECT 1 FROM managers m
                          WHERE m.manager_id = zp_profession AND m.user_id = zp_user
                      ))
                  )
                """, item.params(), Integer.class);
        return count != null && count == 1;
    }

    public int countActiveRows(long orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM zp WHERE zp_order = :orderId AND zp_active = 1",
                Map.of("orderId", orderId),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    public void markAutoItemsApplied(long runId, String actor, String reason, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId).addValue("actor", actor)
                .addValue("reason", reason).addValue("now", Timestamp.valueOf(now));
        jdbc.update("""
                UPDATE contractor_legacy_reward_reconciliation_items
                SET reconciliation_status = 'APPLIED', resolved_at = :now,
                    resolved_by = :actor, resolution_reason = :reason
                WHERE reconciliation_run_id = :runId
                  AND reconciliation_kind = 'AUTO'
                  AND reconciliation_status = 'PENDING'
                """, params);
        jdbc.update("""
                UPDATE contractor_legacy_reward_reconciliation_runs
                SET reconciliation_status = 'AUTO_APPLIED',
                    reconciliation_auto_applied_at = :now,
                    reconciliation_auto_applied_by = :actor,
                    reconciliation_auto_reason = :reason,
                    reconciliation_row_version = reconciliation_row_version + 1
                WHERE reconciliation_run_id = :runId
                """, params);
    }

    public void markManualItemsApplied(
            long runId,
            long orderId,
            LocalDate completedOn,
            String evidenceReference,
            String reason,
            String actor,
            LocalDateTime now
    ) {
        jdbc.update("""
                UPDATE contractor_legacy_reward_reconciliation_items
                SET reconciliation_status = 'APPLIED',
                    manual_completed_on = :completedOn,
                    manual_evidence_reference = :evidence,
                    resolution_reason = :reason,
                    resolved_at = :now,
                    resolved_by = :actor
                WHERE reconciliation_run_id = :runId
                  AND reconciliation_order_id = :orderId
                  AND reconciliation_kind = 'MANUAL'
                  AND reconciliation_status = 'PENDING'
                """, new MapSqlParameterSource()
                        .addValue("runId", runId).addValue("orderId", orderId)
                        .addValue("completedOn", completedOn).addValue("evidence", evidenceReference)
                        .addValue("reason", reason).addValue("now", Timestamp.valueOf(now))
                        .addValue("actor", actor));
    }

    public int countPending(long runId, String kind) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contractor_legacy_reward_reconciliation_items
                WHERE reconciliation_run_id = :runId
                  AND reconciliation_kind = :kind
                  AND reconciliation_status = 'PENDING'
                """, Map.of("runId", runId, "kind", kind), Integer.class);
        return count == null ? 0 : count;
    }

    private CandidateRow mapCandidate(ResultSet rs, int row) throws SQLException {
        String inferred = rs.getString("inferred_role");
        String existing = rs.getString("zp_contractor_role");
        return new CandidateRow(
                rs.getLong("zp_id"), rs.getLong("zp_order"), rs.getLong("zp_user"),
                rs.getLong("zp_profession"), rs.getBigDecimal("zp_sum"),
                rs.getInt("zp_amount"), localDate(rs.getDate("zp_date")),
                localDateTime(rs.getTimestamp("zp_updated_at")), rs.getBoolean("zp_active"),
                rs.getString("zp_source"),
                existing,
                rs.getBoolean("zp_attribution_final"), rs.getBigDecimal("zp_reward_basis"),
                rs.getString("zp_attribution_snapshot"),
                inferred == null ? null : ContractorRole.valueOf(inferred),
                rs.getBoolean("order_exists"), rs.getBoolean("dated_pre_cutoff"),
                rs.getBoolean("active_recovery")
        );
    }

    private RunRow mapRun(ResultSet rs, int row) throws SQLException {
        Timestamp applied = rs.getTimestamp("reconciliation_auto_applied_at");
        return new RunRow(
                rs.getLong("reconciliation_run_id"),
                rs.getDate("reconciliation_start_date").toLocalDate(),
                rs.getString("reconciliation_status"),
                rs.getString("reconciliation_snapshot_hash"),
                rs.getInt("reconciliation_auto_order_count"),
                rs.getInt("reconciliation_auto_row_count"),
                rs.getInt("reconciliation_manual_order_count"),
                rs.getInt("reconciliation_manual_row_count"),
                rs.getTimestamp("reconciliation_created_at").toLocalDateTime(),
                rs.getTimestamp("reconciliation_expires_at").toLocalDateTime(),
                rs.getString("reconciliation_created_by"),
                applied == null ? null : applied.toLocalDateTime(),
                rs.getLong("reconciliation_row_version")
        );
    }

    private ItemRow mapItem(ResultSet rs, int row) throws SQLException {
        Date completed = rs.getDate("manual_completed_on");
        return new ItemRow(
                rs.getLong("reconciliation_item_id"), rs.getLong("reconciliation_run_id"),
                rs.getLong("reconciliation_order_id"), rs.getLong("reconciliation_zp_id"),
                rs.getString("reconciliation_kind"), rs.getString("reconciliation_status"),
                rs.getString("reconciliation_evidence_category"), rs.getString("reconciliation_group_hash"),
                rs.getLong("original_zp_user"), rs.getLong("original_zp_profession"),
                rs.getBigDecimal("original_zp_sum"), rs.getInt("original_zp_amount"),
                localDate(rs.getDate("original_zp_date")),
                localDateTime(rs.getTimestamp("original_zp_updated_at")),
                rs.getBoolean("original_zp_active"), rs.getString("original_zp_source"),
                rs.getString("original_zp_contractor_role"),
                rs.getBoolean("original_zp_attribution_final"),
                rs.getBigDecimal("original_zp_reward_basis"),
                rs.getString("original_zp_attribution_snapshot_hash"),
                rs.getString("target_zp_source"), rs.getString("target_zp_contractor_role"),
                rs.getBoolean("target_zp_attribution_final"),
                completed == null ? null : completed.toLocalDate(),
                rs.getString("manual_evidence_reference")
        );
    }

    private AttestationRow mapAttestation(ResultSet rs, int row) throws SQLException {
        return new AttestationRow(
                rs.getLong("reconciliation_run_id"), rs.getLong("reconciliation_order_id"),
                rs.getLong("reconciliation_zp_id"), rs.getString("reconciliation_group_hash"),
                rs.getLong("original_zp_user"), rs.getLong("original_zp_profession"),
                rs.getBigDecimal("original_zp_sum"), rs.getInt("original_zp_amount"),
                localDate(rs.getDate("original_zp_date")), rs.getBoolean("original_zp_active"),
                rs.getBigDecimal("original_zp_reward_basis"),
                rs.getString("original_zp_attribution_snapshot_hash"),
                rs.getString("target_zp_source"), rs.getString("target_zp_contractor_role"),
                rs.getBoolean("target_zp_attribution_final"),
                rs.getDate("manual_completed_on").toLocalDate(),
                rs.getString("reconciliation_snapshot_hash")
        );
    }

    private LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    public record DbNow(LocalDate businessDate, LocalDateTime now) {}

    public record CandidateRow(
            long zpId, long orderId, long userId, long professionId,
            BigDecimal amount, int units, LocalDate occurredOn, LocalDateTime updatedAt,
            boolean active, String source, String role, boolean attributionFinal,
            BigDecimal rewardBasis, String attributionSnapshot, ContractorRole inferredRole,
            boolean orderExists, boolean datedPreCutoff, boolean activeRecovery
    ) {}

    public record SnapshotItem(
            CandidateRow row, String kind, String evidenceCategory, String groupHash,
            String targetSource, ContractorRole targetRole, String attributionSnapshotHash
    ) {}

    public record RunRow(
            long id, LocalDate startDate, String status, String snapshotHash,
            int autoOrders, int autoRows, int manualOrders, int manualRows,
            LocalDateTime createdAt, LocalDateTime expiresAt, String createdBy,
            LocalDateTime autoAppliedAt, long version
    ) {}

    public record ItemRow(
            long id, long runId, long orderId, long zpId, String kind, String status,
            String evidenceCategory, String groupHash, long userId, long professionId,
            BigDecimal amount, int units, LocalDate occurredOn, LocalDateTime updatedAt,
            boolean active, String originalSource, String originalRole, boolean originalFinal,
            BigDecimal rewardBasis, String attributionSnapshotHash,
            String targetSource, String targetRole, boolean targetFinal,
            LocalDate completedOn, String evidenceReference
    ) {
        public MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("zpId", zpId).addValue("orderId", orderId)
                    .addValue("userId", userId).addValue("professionId", professionId)
                    .addValue("amount", amount).addValue("units", units)
                    .addValue("occurredOn", occurredOn)
                    .addValue("updatedAt", updatedAt)
                    .addValue("active", active).addValue("originalSource", originalSource)
                    .addValue("originalRole", originalRole).addValue("originalFinal", originalFinal)
                    .addValue("rewardBasis", rewardBasis)
                    .addValue("attributionHash", attributionSnapshotHash)
                    .addValue("targetSource", targetSource).addValue("targetRole", targetRole)
                    .addValue("targetFinal", targetFinal);
        }
    }

    public record AttestationRow(
            long runId, long orderId, long zpId, String groupHash,
            long userId, long professionId, BigDecimal amount, int units,
            LocalDate occurredOn, boolean active, BigDecimal rewardBasis,
            String attributionSnapshotHash, String targetSource, String targetRole,
            boolean targetFinal, LocalDate completedOn, String snapshotHash
    ) {}
}
