package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.KeycloakSessionAuthority.Evidence;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class AuthSessionStateRepository {
    private final JdbcTemplate jdbc;

    public OperationalSnapshot operationalSnapshot() {
        return jdbc.queryForObject("""
                SELECT COUNT(*),COALESCE(SUM(phase='PASSWORD_UNKNOWN'),0),
                  COALESCE(SUM(phase='PASSWORD_REQUESTED'),0),
                  COALESCE(SUM(phase IN ('CAPTURE_REQUIRED','DELETE_REQUIRED')),0),
                  GREATEST(0,COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(6)),0)),
                  GREATEST(0,COALESCE(TIMESTAMPDIFF(SECOND,MIN(CASE WHEN phase='PASSWORD_UNKNOWN' THEN updated_at END),CURRENT_TIMESTAMP(6)),0))
                FROM auth_security_mutations
                WHERE phase <> 'COMPLETE'
                """,(rs,row) -> new OperationalSnapshot(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                        rs.getLong(4),rs.getLong(5),rs.getLong(6)));
    }
    public record OperationalSnapshot(long pending,long unknown,long passwordInFlight,long dispatchable,
                                      long oldestPendingSeconds,long oldestUnknownSeconds) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(User user, String reason) {
        if (user.getKeycloakId() == null || user.getKeycloakId().isBlank()) return;
        jdbc.update("""
                INSERT INTO auth_security_mutations(operation_id,user_id,keycloak_subject,auth_epoch,reason,phase)
                VALUES (?,?,?,?,?,'CAPTURE_REQUIRED')
                """, UUID.randomUUID().toString(), user.getId(), user.getKeycloakId(), user.getAuthEpoch(), reason);
    }

    public boolean pending(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM auth_security_mutations WHERE user_id=? AND phase <> 'COMPLETE')
                """, Boolean.class, userId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot lockSnapshot(long id) {
        return jdbc.query("SELECT id,active,keycloak_id,auth_epoch FROM users WHERE id=? FOR UPDATE",
                (rs, row) -> new Snapshot(rs.getLong("id"), rs.getBoolean("active"),
                        rs.getString("keycloak_id"), rs.getLong("auth_epoch")), id).stream().findFirst().orElse(null);
    }

    public Set<String> roles(long id) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT r.name FROM roles r JOIN users_roles ur ON ur.role_id=r.id WHERE ur.user_id=?
                """, String.class, id));
    }

    public Optional<Binding> binding(String subject, String sid) {
        return jdbc.query("""
                SELECT user_id,auth_epoch,credential_created_ms FROM auth_session_bindings
                WHERE keycloak_subject=? AND session_id=?
                """, (rs, row) -> new Binding(rs.getLong(1), rs.getLong(2), rs.getLong(3)), subject, sid)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(long userId, String subject, String sid, long epoch, Evidence evidence) {
        jdbc.update("""
                INSERT INTO auth_session_bindings(keycloak_subject,session_id,user_id,auth_epoch,
                    credential_created_ms,session_started_ms,offline_session) VALUES (?,?,?,?,?,?,?)
                """, subject, sid, userId, epoch, evidence.credentialTime(), evidence.sessionStart(), evidence.offline());
    }

    public Optional<Mutation> generation(long userId, long epoch) {
        return jdbc.query("SELECT * FROM auth_security_mutations WHERE user_id=? AND auth_epoch=?",
                (rs, row) -> map(rs), userId, epoch).stream().findFirst();
    }

    public Optional<Mutation> cutover(long userId) {
        return jdbc.query("""
                SELECT * FROM auth_security_mutations WHERE user_id=? AND reason='SESSION_PROTOCOL_CUTOVER'
                ORDER BY auth_epoch DESC LIMIT 1
                """,(rs,row) -> map(rs),userId).stream().findFirst();
    }

    public Optional<Mutation> mutation(String operation) {
        return jdbc.query("SELECT * FROM auth_security_mutations WHERE operation_id=?",
                (rs, row) -> map(rs), operation).stream().findFirst();
    }

    public List<Mutation> pendingMutations(int limit) {
        return jdbc.query("""
                SELECT * FROM auth_security_mutations WHERE phase <> 'COMPLETE'
                ORDER BY updated_at,operation_id LIMIT ?
                """, (rs, row) -> map(rs), Math.max(1, Math.min(limit, 100)));
    }

    public List<Mutation> dispatchable(int limit) {
        return jdbc.query("""
                SELECT * FROM auth_security_mutations WHERE phase IN ('CAPTURE_REQUIRED','DELETE_REQUIRED')
                ORDER BY updated_at,operation_id LIMIT ?
                """, (rs, row) -> map(rs), Math.max(1, Math.min(limit, 100)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(String operation) {
        jdbc.update("UPDATE auth_security_mutations SET updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND phase<>'COMPLETE'",operation);
    }

    /** An abandoned request is classified as unknown; this never authorizes replay or completion. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void classifyInterruptedPasswords() {
        jdbc.update("""
                UPDATE auth_security_mutations SET phase='PASSWORD_UNKNOWN',updated_at=CURRENT_TIMESTAMP(6)
                WHERE phase='PASSWORD_REQUESTED' AND updated_at < CURRENT_TIMESTAMP(6)-INTERVAL 2 MINUTE
                LIMIT 100
                """);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean phase(String operation, String expected, String target) {
        return jdbc.update("""
                UPDATE auth_security_mutations SET phase=?,updated_at=CURRENT_TIMESTAMP(6)
                WHERE operation_id=? AND phase=?
                """, target, operation, expected) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolution(String operation, String actor, String reason) {
        jdbc.update("""
                UPDATE auth_security_mutations SET resolution_actor=?,resolution_reason=?,updated_at=CURRENT_TIMESTAMP(6)
                WHERE operation_id=?
                """, actor, reason, operation);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addTarget(String operation, Target target) {
        jdbc.update("""
                INSERT INTO auth_revocation_sessions(operation_id,session_id,offline_session) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE session_id=session_id
                """, operation, target.sid(), target.offline());
    }

    public List<Target> targets(String operation) {
        return jdbc.query("SELECT session_id,offline_session FROM auth_revocation_sessions WHERE operation_id=?",
                (rs, row) -> new Target(rs.getString(1), rs.getBoolean(2)), operation);
    }

    private static Mutation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Mutation(rs.getString("operation_id"),rs.getLong("user_id"),
                rs.getString("keycloak_subject"),rs.getLong("auth_epoch"),rs.getString("phase"));
    }

    public record Snapshot(long userId, boolean active, String subject, long epoch) {}
    public record Binding(long userId, long epoch, long credentialTime) {}
    public record Mutation(String id, long userId, String subject, long epoch, String phase) {}
    public record Target(String sid, boolean offline) {}
}
