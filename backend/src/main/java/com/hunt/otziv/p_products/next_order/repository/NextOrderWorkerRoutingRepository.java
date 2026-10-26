package com.hunt.otziv.p_products.next_order.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NextOrderWorkerRoutingRepository {
    private final JdbcTemplate jdbc;

    public Long lockCompanyManager(long companyId) {
        return jdbc.queryForObject("""
                SELECT company_manager FROM companies WHERE company_id = ? FOR UPDATE
                """, Long.class, companyId);
    }

    // Use current reads even if loading the historical source established an
    // older REPEATABLE READ snapshot before a concurrent transfer committed.
    public List<Membership> lockMemberships(long companyId, long managerId) {
        return jdbc.query("""
                SELECT wc.worker_id, u.active,
                       EXISTS (SELECT 1 FROM managers_users mu
                               WHERE mu.user_id = u.id AND mu.manager_id = ?)
                       AND NOT EXISTS (SELECT 1 FROM managers_users other_manager
                                       WHERE other_manager.user_id = u.id
                                         AND other_manager.manager_id <> ?) AS manager_matches
                FROM workers_companies wc
                JOIN workers w ON w.worker_id = wc.worker_id
                JOIN users u ON u.id = w.user_id
                WHERE wc.company_id = ?
                ORDER BY wc.worker_id
                FOR UPDATE
                """, (rs, row) -> new Membership(rs.getLong("worker_id"),
                rs.getBoolean("active"), rs.getBoolean("manager_matches")),
                managerId, managerId, companyId);
    }

    public List<Transfer> lockAppliedTransfers(long companyId) {
        return jdbc.query("""
                SELECT source_worker_id, target_worker_id
                FROM workload_transfer_executions
                WHERE company_id = ? AND status = 'APPLIED'
                ORDER BY applied_at DESC, workload_transfer_execution_id DESC
                FOR UPDATE
                """, (rs, row) -> new Transfer(rs.getLong("source_worker_id"),
                rs.getLong("target_worker_id")), companyId);
    }

    public void auditReroute(long requestId, long sourceOrderId, long createdOrderId,
            long sourceWorkerId, long targetWorkerId) {
        jdbc.update("""
                INSERT INTO business_audit_events
                    (created_at, actor, source, action, entity_type, entity_id,
                     order_id, old_value, new_value, details)
                VALUES (CURRENT_TIMESTAMP(6), 'system', 'next_order_automation',
                        'next_order_worker_rerouted', 'order', ?, ?, ?, ?, ?)
                """, Long.toString(createdOrderId), createdOrderId,
                Long.toString(sourceWorkerId), Long.toString(targetWorkerId),
                "requestId=" + requestId + ";sourceOrderId=" + sourceOrderId);
    }

    public record Membership(long workerId, boolean active, boolean managerMatches) {}
    public record Transfer(long sourceWorkerId, long targetWorkerId) {}
}
