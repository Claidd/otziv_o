package com.hunt.otziv.l_lead.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short SQL transactions. Network calls never hold these locks. */
@Repository
public class LeadCommandRepository {
    private final JdbcTemplate jdbc;
    public LeadCommandRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String sourceIdentity() { return jdbc.queryForObject("SELECT source_id FROM lead_command_source WHERE singleton_id=1",String.class); }

    /** Caller has first flushed/locked Lead. Retained through the outer business commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Stream lockStream(long leadId) {
        jdbc.update("INSERT INTO lead_command_stream(lead_id,last_version) VALUES(?,0) ON DUPLICATE KEY UPDATE lead_id=lead_id", leadId);
        long last = jdbc.queryForObject("SELECT last_version FROM lead_command_stream WHERE lead_id=? FOR UPDATE", Long.class, leadId);
        String source = jdbc.queryForObject("SELECT source_id FROM lead_command_source WHERE singleton_id=1", String.class);
        return new Stream(source, Math.addExact(last, 1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> pendingImport(long leadId) {
        return jdbc.query("""
                SELECT command_id FROM lead_command_queue WHERE lead_id=? AND command_kind='IMPORT'
                  AND delivery_state IN ('READY','PROCESSING','UNKNOWN','DEAD','QUARANTINED','LEGACY')
                ORDER BY id LIMIT 1 FOR UPDATE
                """, (rs,n) -> rs.getString(1), leadId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> manualRequest(long leadId, String requestId) {
        jdbc.update("INSERT INTO lead_command_manual_requests(request_id,lead_id) VALUES(?,?) ON DUPLICATE KEY UPDATE request_id=request_id",requestId,leadId);
        return jdbc.queryForObject("SELECT lead_id,command_id FROM lead_command_manual_requests WHERE request_id=? FOR UPDATE",
                (rs,n) -> {
                    if (rs.getLong(1) != leadId) throw new IllegalArgumentException("LEAD_REQUEST_ID_CONFLICT");
                    return Optional.ofNullable(rs.getString(2));
                }, requestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finishManualRequest(String requestId, String commandId) {
        if (requestId != null) jdbc.update("UPDATE lead_command_manual_requests SET command_id=? WHERE request_id=?",commandId,requestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueVersioned(long leadId, String phone, String kind, String json,
            Stream stream, String commandId, String hash) {
        int changed = jdbc.update("UPDATE lead_command_stream SET last_version=? WHERE lead_id=? AND last_version=?",
                stream.version(),leadId,stream.version()-1);
        if (changed != 1) throw new IllegalStateException("LEAD_STREAM_VERSION_CONFLICT");
        jdbc.update("""
                INSERT INTO lead_command_queue
                (lead_id,telephone_lead,payload_json,retry_count,created_at,last_attempt_at,
                 command_id,command_kind,payload_version,delivery_state,next_attempt_at,source_id,entity_version,payload_hash)
                VALUES(?,?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?,1,'READY',UTC_TIMESTAMP(6),?,?,?)
                """,leadId,phone,json,commandId,kind,stream.sourceId(),stream.version(),hash);
    }

    public record Stream(String sourceId, long version) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long leadId, String phone, String kind, String json) {
        jdbc.update("""
                INSERT INTO lead_command_queue
                (lead_id, telephone_lead, payload_json, retry_count, created_at, last_attempt_at,
                 command_id, command_kind, payload_version, delivery_state, next_attempt_at)
                VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?, 1, 'READY', UTC_TIMESTAMP(6))
                """, leadId, phone, json, UUID.randomUUID().toString(), kind);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<Claim> claim(int maxAttempts) {
        List<Claim> rows = jdbc.query("""
                SELECT q.id, q.command_id, q.command_kind, q.payload_version, q.payload_json, q.retry_count,
                       q.source_id,q.entity_version,q.payload_hash
                FROM lead_command_queue q
                WHERE q.delivery_state = 'READY' AND q.retry_count < ?
                  AND q.next_attempt_at <= UTC_TIMESTAMP(6)
                  AND NOT EXISTS (SELECT 1 FROM lead_command_queue earlier
                      WHERE earlier.blocking_lead_id = q.lead_id AND earlier.id < q.id)
                ORDER BY q.next_attempt_at, q.id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Claim(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), rs.getInt(6) + 1, UUID.randomUUID().toString(),
                        rs.getString(7),rs.getObject(8,Long.class),rs.getString(9)), maxAttempts);
        if (rows.isEmpty()) return Optional.empty();
        Claim claim = rows.getFirst();
        jdbc.update("""
                UPDATE lead_command_queue SET delivery_state='PROCESSING', processing_token=?,
                  lease_until=TIMESTAMPADD(SECOND, 120, UTC_TIMESTAMP(6)),
                  retry_count=retry_count+1, last_attempt_at=UTC_TIMESTAMP(6) WHERE id=?
                """, claim.token(), claim.id());
        return Optional.of(claim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean complete(Claim claim, String state, String reason, int delaySeconds) {
        if (!List.of("SUCCEEDED", "UNKNOWN", "READY", "DEAD", "QUARANTINED").contains(state)) {
            throw new IllegalArgumentException("Unsupported completion state");
        }
        return jdbc.update("""
                UPDATE lead_command_queue SET delivery_state=?, last_error=?, processing_token=NULL,
                    lease_until=NULL, next_attempt_at=TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6)),
                    completed_at=CASE WHEN ?='SUCCEEDED' THEN UTC_TIMESTAMP(6) ELSE NULL END
                WHERE id=? AND processing_token=? AND delivery_state='PROCESSING'
                  AND lease_until > UTC_TIMESTAMP(6)
                """, state, reason, delaySeconds, state, claim.id(), claim.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public int recoverExpired(int maxAttempts) {
        // A timeout is not evidence that the receiver did not commit the operation.
        int expired = jdbc.update("""
                UPDATE lead_command_queue SET delivery_state='UNKNOWN', last_error='CLAIM_EXPIRED',
                    processing_token=NULL, lease_until=NULL
                WHERE delivery_state='PROCESSING' AND lease_until <= UTC_TIMESTAMP(6) LIMIT 100
                """);
        return expired + jdbc.update("""
                UPDATE lead_command_queue SET delivery_state='DEAD', last_error='ATTEMPTS_EXHAUSTED'
                WHERE delivery_state='READY' AND retry_count >= ? LIMIT 100
                """, maxAttempts);
    }

    public List<Legacy> legacyBatch(int limit) {
        return legacyBatch(0, Long.MAX_VALUE, limit);
    }

    /** A fixed high-water mark makes a read-only traversal finite and resumable. */
    public long legacyHighWaterMark() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM lead_command_queue WHERE delivery_state='LEGACY'", Long.class);
    }

    public List<Legacy> legacyBatch(long afterId, long throughId, int limit) {
        return jdbc.query("""
                SELECT id, command_kind, payload_version, payload_json, retry_count
                FROM lead_command_queue WHERE delivery_state='LEGACY' AND id > ? AND id <= ? ORDER BY id LIMIT ?
                """, (rs, row) -> new Legacy(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5)), afterId, throughId, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean classifyLegacy(long id, String state, String reason) {
        return jdbc.update("""
                UPDATE lead_command_queue SET delivery_state=?, last_error=?, command_id=COALESCE(command_id, ?)
                WHERE id=? AND delivery_state='LEGACY'
                """, state, reason, UUID.randomUUID().toString(), id) == 1;
    }

    public List<Map<String, Object>> counts() {
        return jdbc.queryForList("SELECT delivery_state, COUNT(*) AS total FROM lead_command_queue GROUP BY delivery_state");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean classifyLegacy(long id, String state, String reason, String actor) {
        boolean changed = classifyLegacy(id, state, reason);
        if (changed) jdbc.update("INSERT INTO lead_command_replay_audit(queue_id,actor,previous_state,resolution,reason) VALUES(?,?,'LEGACY',?,?)",
                id, actor, "CLASSIFY_" + state, reason);
        return changed;
    }

    public List<QueueHealth> health() {
        return jdbc.query("""
                SELECT delivery_state, COUNT(*),
                    GREATEST(0, TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(6)))
                FROM lead_command_queue GROUP BY delivery_state
                """, (rs, row) -> new QueueHealth(rs.getString(1), rs.getLong(2), rs.getLong(3)));
    }

    public record QueueHealth(String state, long count, long oldestSeconds) {}

    /** Due READY occupancy, including commands waiting behind an unresolved FIFO predecessor.
     * This is not the number eligible for claim; future retry dates are excluded. */
    @Transactional(readOnly = true, timeout = 5)
    public DueHealth dueHealth() {
        return jdbc.queryForObject("""
                SELECT COUNT(*), COALESCE(TIMESTAMPDIFF(SECOND,MIN(next_attempt_at),UTC_TIMESTAMP(6)),0)
                FROM lead_command_queue WHERE delivery_state='READY' AND next_attempt_at<=UTC_TIMESTAMP(6)
                """, (rs,row) -> new DueHealth(rs.getLong(1), Math.max(0,rs.getLong(2))));
    }
    public record DueHealth(long count, long oldestSeconds) {}

    @Transactional(timeout = 10)
    public boolean resolve(long id, String resolution, String actor, String reason) {
        if (!List.of("CONFIRMED_DELIVERED", "CONFIRMED_NOT_DELIVERED", "SUPERSEDED").contains(resolution)) {
            throw new IllegalArgumentException("Unsupported resolution");
        }
        List<String> states = jdbc.queryForList("SELECT delivery_state FROM lead_command_queue WHERE id=? FOR UPDATE", String.class, id);
        if (states.isEmpty() || !List.of("UNKNOWN", "DEAD", "QUARANTINED").contains(states.getFirst())) return false;
        if (states.getFirst().equals("QUARANTINED") && !resolution.equals("SUPERSEDED")) return false;
        String state = resolution.equals("CONFIRMED_NOT_DELIVERED") ? "READY" : "SUCCEEDED";
        jdbc.update("INSERT INTO lead_command_replay_audit(queue_id,actor,previous_state,resolution,reason) VALUES(?,?,?,?,?)",
                id, actor, states.getFirst(), resolution, reason);
        jdbc.update("""
                UPDATE lead_command_queue SET delivery_state=?, retry_count=0, next_attempt_at=UTC_TIMESTAMP(6),
                  last_error=NULL, completed_at=CASE WHEN ?='SUCCEEDED' THEN UTC_TIMESTAMP(6) ELSE NULL END WHERE id=?
                """, state, state, id);
        return true;
    }

    public record Claim(long id, String commandId, String kind, int version, String json, int attempt, String token,
            String sourceId,Long entityVersion,String hash) {
        public Claim(long id,String commandId,String kind,int version,String json,int attempt,String token) {
            this(id,commandId,kind,version,json,attempt,token,null,null,null);
        }
    }
    public record Legacy(long id, String kind, int version, String json, int attempts) {}
}
