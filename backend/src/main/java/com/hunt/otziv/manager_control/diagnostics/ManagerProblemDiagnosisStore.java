package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;

import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

@Repository
@RequiredArgsConstructor
public class ManagerProblemDiagnosisStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    public Stored capture(ManagerProblemEvidenceCollector.Evidence evidence) {
        return transactions().execute(ignored -> {
            LocalDateTime now = LocalDateTime.now();
            // DATETIME(6) cannot distinguish observations inside one microsecond. Keep the current
            // snapshot on a tie rather than letting a late older read roll current facts backwards.
            LocalDateTime observedAt = evidence.observedAt().truncatedTo(ChronoUnit.MICROS);
            String json = json(evidence.facts());
            String hash = hash(json);
            jdbc.update("""
                    INSERT INTO manager_problem_cases(case_key,manager_id,source_task_id,current_version,episode,closed,created_at,updated_at)
                    VALUES(?,?,?,0,1,FALSE,?,?) ON DUPLICATE KEY UPDATE case_key=VALUES(case_key)
                    """, evidence.caseKey(), evidence.managerId(), evidence.sourceTaskId(), now, now);
            Map<String, Object> head = jdbc.queryForMap("SELECT * FROM manager_problem_cases WHERE case_key=? FOR UPDATE", evidence.caseKey());
            long caseId = num(head, "case_id"), version = num(head, "current_version"), episode = num(head, "episode");
            Object lastObserved = head.get("updated_at");
            LocalDateTime previousObservation = lastObserved instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) lastObserved;
            if (version > 0 && !observedAt.isAfter(previousObservation)) return read(caseId, version);
            if (hash.equals(head.get("evidence_hash"))) {
                jdbc.update("UPDATE manager_problem_cases SET updated_at=? WHERE case_id=?", observedAt, caseId);
                return read(caseId, version);
            }
            if ((Boolean.TRUE.equals(head.get("closed")) || "1".equals(String.valueOf(head.get("closed")))) && !evidence.closed()) episode++;
            jdbc.update("""
                    UPDATE manager_problem_evidence_versions SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL
                    WHERE case_id=? AND evidence_version=? AND status IN ('QUEUED','RETRY','UNAVAILABLE','RUNNING')
                    """, caseId, version);
            version++;
            jdbc.update("""
                    INSERT INTO manager_problem_evidence_versions(case_id,evidence_version,episode,observed_at,evidence_json,status,prompt_version,next_attempt_at)
                    VALUES(?,?,?,?,?,'QUEUED',?,?)
                    """, caseId, version, episode, observedAt, json, PROMPT_VERSION, now);
            jdbc.update("""
                    UPDATE manager_problem_cases SET source_task_id=?,current_version=?,episode=?,closed=?,evidence_hash=?,updated_at=? WHERE case_id=?
                    """, evidence.sourceTaskId(), version, episode, evidence.closed(), hash, observedAt, caseId);
            return read(caseId, version);
        });
    }

    public Stored latestForSource(Long sourceTaskId) {
        List<Map<String, Object>> heads = jdbc.queryForList("""
                SELECT c.case_id,c.current_version FROM manager_problem_cases c
                JOIN manager_daily_control_concrete_items i ON i.control_entity_id=?
                JOIN manager_daily_controls d ON d.daily_control_id=i.control_id
                WHERE c.manager_id=d.manager_id AND (c.source_task_id=i.control_entity_id OR c.case_key=CONCAT(d.manager_id,':',
                    CASE WHEN i.entity_type LIKE 'COMMON_INVOICE%' THEN 'COMMON_INVOICE' WHEN i.entity_type LIKE 'CLIENT_CHAT%' THEN 'CLIENT_CHAT' ELSE i.entity_type END,':',COALESCE(CAST(i.entity_id AS CHAR),i.entity_key)))
                LIMIT 1
                """, sourceTaskId);
        return heads.isEmpty() ? null : read(num(heads.getFirst(), "case_id"), num(heads.getFirst(), "current_version"));
    }

    public Stored claim() {
        return transactions().execute(ignored -> {
            LocalDateTime now = LocalDateTime.now();
            List<Map<String, Object>> candidates = jdbc.queryForList("""
                    SELECT c.case_id,c.current_version AS evidence_version FROM manager_problem_cases c
                    WHERE EXISTS (SELECT 1 FROM manager_problem_evidence_versions v
                      WHERE v.case_id=c.case_id AND v.evidence_version=c.current_version
                        AND ((v.status IN ('QUEUED','RETRY','UNAVAILABLE') AND v.next_attempt_at<=?)
                          OR (v.status='RUNNING' AND v.lease_until<?)))
                    ORDER BY (SELECT current_job.next_attempt_at FROM manager_problem_evidence_versions current_job
                              WHERE current_job.case_id=c.case_id AND current_job.evidence_version=c.current_version),c.case_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, now, now);
            if (candidates.isEmpty()) return null;
            long id = num(candidates.getFirst(), "case_id"), version = num(candidates.getFirst(), "evidence_version");
            String lease = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE manager_problem_evidence_versions SET status='RUNNING',lease_token=?,lease_until=?,attempt_count=attempt_count+1
                    WHERE case_id=? AND evidence_version=?
                    """, lease, now.plusMinutes(3), id, version);
            return read(id, version);
        });
    }

    /** Compare current version AND lease: an expired worker or obsolete diagnosis can never replace current evidence. */
    public boolean complete(Stored job, Diagnosis diagnosis, String model, int inputTokens, int outputTokens) {
        int changed = jdbc.update("""
                UPDATE manager_problem_evidence_versions v JOIN manager_problem_cases c ON c.case_id=v.case_id
                SET v.status=?,v.diagnosis_json=?,v.model=?,v.input_tokens=?,v.output_tokens=?,v.completed_at=?,v.lease_token=NULL,v.lease_until=NULL,v.last_error=NULL
                WHERE v.case_id=? AND v.evidence_version=? AND c.current_version=v.evidence_version AND v.status='RUNNING' AND v.lease_token=?
                """, diagnosis.missingEvidence().isEmpty() ? "READY" : "NEEDS_CONTEXT", json(diagnosis), model, inputTokens, outputTokens,
                LocalDateTime.now(), job.caseId(), job.version(), job.leaseToken());
        return changed == 1;
    }

    public void retry(Stored job, String error) {
        int minutes = job.attempts() >= 5 ? 360 : Math.min(60, 1 << Math.min(job.attempts(), 5));
        jdbc.update("""
                UPDATE manager_problem_evidence_versions SET status=?,next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL
                WHERE case_id=? AND evidence_version=? AND status='RUNNING' AND lease_token=?
                """, job.attempts() >= 5 ? "UNAVAILABLE" : "RETRY", LocalDateTime.now().plusMinutes(minutes),
                DiagnosticRedactor.redact(error).substring(0, Math.min(DiagnosticRedactor.redact(error).length(), 500)), job.caseId(), job.version(), job.leaseToken());
    }

    Stored read(long id, long version) {
        return jdbc.queryForObject("""
                SELECT v.*,c.manager_id,CASE WHEN c.current_version=v.evidence_version THEN c.updated_at ELSE v.observed_at END AS verified_at
                FROM manager_problem_evidence_versions v JOIN manager_problem_cases c ON c.case_id=v.case_id
                WHERE v.case_id=? AND v.evidence_version=?
                """, (rs, row) -> {
            try {
                return new Stored(id, version, rs.getLong("episode"), rs.getLong("manager_id"), rs.getString("status"),
                        rs.getObject("verified_at", LocalDateTime.class), mapper.readValue(rs.getString("evidence_json"), new TypeReference<List<Fact>>() { }),
                        rs.getString("diagnosis_json") == null ? null : mapper.readValue(rs.getString("diagnosis_json"), Diagnosis.class),
                        rs.getString("model"), rs.getString("prompt_version"), rs.getString("lease_token"), rs.getInt("attempt_count"));
            } catch (Exception e) { throw new IllegalStateException("Invalid stored diagnostic schema", e); }
        }, id, version);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize diagnostic evidence", e); }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static long num(Map<String, Object> row, String name) { return ((Number) row.get(name)).longValue(); }
    public record Stored(long caseId, long version, long episode, long managerId, String status, LocalDateTime observedAt,
                         List<Fact> facts, Diagnosis diagnosis, String model, String promptVersion, String leaseToken, int attempts) { }
}
