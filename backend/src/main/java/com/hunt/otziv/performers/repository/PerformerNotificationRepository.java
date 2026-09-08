package com.hunt.otziv.performers.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PerformerNotificationRepository {
    private final JdbcTemplate jdbc;

    public OperationalSnapshot operationalSnapshot() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(status='PENDING'),0),COALESCE(SUM(status='PROCESSING'),0),
                  COALESCE(SUM(status='UNKNOWN'),0),COALESCE(SUM(status='BLOCKED'),0),
                  COALESCE(SUM(status='LEGACY_UNKNOWN'),0),
                  GREATEST(0,COALESCE(TIMESTAMPDIFF(SECOND,MIN(CASE WHEN status='PENDING' THEN due_at END),CURRENT_TIMESTAMP(6)),0)),
                  GREATEST(0,COALESCE(TIMESTAMPDIFF(SECOND,MIN(CASE WHEN status='UNKNOWN' THEN updated_at END),CURRENT_TIMESTAMP(6)),0)),
                  COALESCE(SUM(status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6)),0)
                FROM performer_notification_intents
                WHERE status IN ('PENDING','PROCESSING','UNKNOWN','BLOCKED','LEGACY_UNKNOWN')
                """,(rs,row) -> new OperationalSnapshot(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),
                        rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8)));
    }

    public record OperationalSnapshot(long pending,long processing,long unknown,long blocked,long legacyUnknown,
                                      long oldestPendingSeconds,long oldestUnknownSeconds,long expiredLeases) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(Long assignmentId, Long offerId, String type, long generation, LocalDateTime due) {
        String key = type + ":" + (offerId == null ? assignmentId : offerId) + ":" + generation;
        boolean inserted = jdbc.update("""
                INSERT INTO performer_notification_intents
                (operation_key, assignment_id, offer_id, notification_type, generation, due_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE notification_id = notification_id
                """, key, assignmentId, offerId, type, generation, due) == 1;
        if ("READY".equals(type) && offerId == null) {
            // Caller holds the canonical assignment lock and flushes its generation
            // first. Duplicate insertion repairs the marker too, without requeueing
            // an existing UNKNOWN/BLOCKED/CANCELLED intent. JPA never writes this column.
            jdbc.update("""
                    UPDATE review_performer_assignments a
                    JOIN performer_notification_intents n ON n.operation_key = ?
                      AND n.assignment_id = a.assignment_id AND n.notification_type = 'READY'
                      AND n.offer_id IS NULL AND n.generation = a.publication_generation
                    SET a.readiness_intent_generation = a.publication_generation
                    WHERE a.assignment_id = ? AND a.publication_generation = ?
                      AND a.readiness_intent_generation < a.publication_generation
                    """, key, assignmentId, generation);
        }
        return inserted;
    }

    public List<Long> readyAssignmentIds(int limit) {
        return jdbc.query("""
                SELECT a.assignment_id FROM review_performer_assignments a
                WHERE a.status = 'WAITING_PUBLICATION' AND a.ready_notification_pending = 1
                  AND a.publication_generation > 0
                  AND a.publish_available_at <= CURRENT_TIMESTAMP(6)
                  AND NOT EXISTS (SELECT 1 FROM performer_notification_intents n
                    WHERE n.operation_key = CONCAT('READY:', a.assignment_id, ':', a.publication_generation))
                ORDER BY a.publish_available_at, a.assignment_id LIMIT ?
                """, (rs, row) -> rs.getLong(1), limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Intent> claim(int leaseSeconds) {
        List<Intent> due = jdbc.query("""
                SELECT * FROM performer_notification_intents
                WHERE status = 'PENDING' AND due_at <= CURRENT_TIMESTAMP(6)
                ORDER BY due_at, notification_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, this::map);
        if (due.isEmpty()) return Optional.empty();
        Intent item = due.getFirst();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE performer_notification_intents SET status = 'PROCESSING', processing_token = ?,
                    lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), attempts = attempts + 1,
                    updated_at = CURRENT_TIMESTAMP(6) WHERE notification_id = ?
                """, token, leaseSeconds, item.id());
        return get(item.id());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int expireClaims(int limit) {
        // A crashed sender may already have sent its message. NEVER automatically requeue.
        return jdbc.update("""
                UPDATE performer_notification_intents SET status = 'UNKNOWN', outcome_code = 'lease_expired',
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6)
                ORDER BY lease_until, notification_id LIMIT ?
                """, limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finish(Intent item, String status, Integer messageId, String code) {
        return jdbc.update("""
                UPDATE performer_notification_intents SET status = ?, telegram_message_id = ?, outcome_code = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE notification_id = ? AND status = 'PROCESSING' AND processing_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, status, messageId, code, item.id(), item.token()) == 1;
    }

    public boolean owns(Intent item) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*) = 1 FROM performer_notification_intents
                WHERE notification_id = ? AND status = 'PROCESSING' AND processing_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, Boolean.class, item.id(), item.token()));
    }

    public Optional<Intent> get(Long id) {
        return jdbc.query("SELECT * FROM performer_notification_intents WHERE notification_id = ?", this::map, id)
                .stream().findFirst();
    }

    public List<Intent> unresolved(int limit) {
        return jdbc.query("""
                SELECT * FROM performer_notification_intents
                WHERE status IN ('UNKNOWN', 'BLOCKED', 'LEGACY_UNKNOWN')
                ORDER BY notification_id LIMIT ?
                """, this::map, limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resolve(Intent expected, String target, Integer messageId, String actor, String reason, String action) {
        int changed = jdbc.update("""
                UPDATE performer_notification_intents SET status = ?, telegram_message_id = ?,
                    processing_token = NULL, lease_until = NULL, due_at = CURRENT_TIMESTAMP(6),
                    outcome_code = 'manual_resolution', updated_at = CURRENT_TIMESTAMP(6)
                WHERE notification_id = ? AND status = ? AND attempts = ?
                  AND updated_at = ?
                """, target, messageId, expected.id(), expected.status(), expected.attempts(), expected.updatedAt());
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO performer_notification_resolutions (notification_id, action, actor, reason)
                    VALUES (?, ?, ?, ?)
                    """, expected.id(), action, actor, reason);
        }
        return changed == 1;
    }

    public LocalDateTime now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private Intent map(ResultSet rs, int row) throws SQLException {
        return new Intent(rs.getLong("notification_id"), rs.getLong("assignment_id"),
                rs.getObject("offer_id", Long.class), rs.getString("notification_type"), rs.getLong("generation"),
                rs.getString("status"), rs.getString("processing_token"), rs.getInt("attempts"),
                rs.getObject("telegram_message_id", Integer.class), rs.getString("outcome_code"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    public record Intent(Long id, Long assignmentId, Long offerId, String type, long generation,
                         String status, String token, int attempts, Integer messageId, String outcome,
                         LocalDateTime updatedAt) {}
}
