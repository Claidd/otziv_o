package com.hunt.otziv.manager_control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The manager-control owner commits the command together with its card fence. */
@Service
@RequiredArgsConstructor
public class ManagerClientMessageQueue implements com.hunt.otziv.client_messages.api.DeliveryQueueHealth {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CredentialCipher cipher;

    @Override public String queueName() { return "manager_client"; }
    @Override @Transactional(readOnly = true, timeout = 3)
    public java.util.List<Row> deliveryQueueHealth() {
        return jdbc.query("SELECT state,COUNT(*),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(6))),"
                + "SUM(CASE WHEN lease_until<CURRENT_TIMESTAMP(6) THEN 1 ELSE 0 END) FROM manager_client_message_queue "
                + "WHERE state IN ('QUEUED','SENDING','RETRYABLE','UNKNOWN','FAILED') GROUP BY state",
                (rs, n) -> new Row(State.valueOf(rs.getString(1)), rs.getLong(2), rs.getDouble(3), rs.getLong(4)));
    }

    record Command(String operationId, long cardId, String kind, String preparedJson, Actor actor) { }
    record Claim(String operationId, long cardId, String kind, String previousState, int attempts, String token) {
        boolean mayDispatch() { return "QUEUED".equals(previousState) || "RETRYABLE".equals(previousState); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Command command) {
        if (!cipher.isEnabled()) throw new IllegalStateException("Manager command encryption is required");
        if (!List.of("CONTROL", "REPLY").contains(command.kind()) || command.cardId() <= 0
                || command.actor() == null || command.preparedJson() == null) throw new IllegalArgumentException("Invalid manager command");
        jdbc.update("""
                INSERT INTO manager_client_message_queue(operation_id,card_id,command_kind,envelope_ciphertext)
                VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id
                """, command.operationId(), command.cardId(), command.kind(), cipher.encrypt(encode(command)));
        if (!Objects.equals(command, snapshot(command.operationId()))) throw new IllegalStateException("Manager command envelope conflict");
    }

    Command snapshot(String operationId) {
        return jdbc.query("SELECT card_id,command_kind,envelope_ciphertext FROM manager_client_message_queue WHERE operation_id=?", (rs, row) -> {
            String encrypted = rs.getString(3);
            if (encrypted == null || !encrypted.startsWith("enc:")) throw new IllegalStateException("Manager command encryption is missing");
            Command command = decode(cipher.decrypt(encrypted), Command.class);
            if (!Objects.equals(command.operationId(), operationId) || command.cardId() != rs.getLong(1)
                    || !Objects.equals(command.kind(), rs.getString(2))) throw new IllegalStateException("Manager command identity mismatch");
            return command;
        }, operationId).stream().findFirst().orElseThrow();
    }

    public DeliveryOperation status(long cardId, String operationId) {
        return jdbc.query("SELECT state,attempts,error_code FROM manager_client_message_queue WHERE operation_id=? AND card_id=?",
                (rs, row) -> new DeliveryOperation(operationId, rs.getString(1), rs.getInt(2), rs.getString(3)), operationId, cardId)
                .stream().findFirst().orElse(null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void retryKnownUnsent(String operationId) {
        int changed = jdbc.update("UPDATE manager_client_message_queue SET state='QUEUED',attempts=0,error_code=NULL,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND state='FAILED'", operationId);
        if (changed != 1) throw new IllegalStateException("Manager operation is not a confirmed failure");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim() {
        var claims = jdbc.query("""
                SELECT operation_id,card_id,command_kind,state,attempts FROM manager_client_message_queue
                WHERE state IN ('QUEUED','RETRYABLE','SENDING','UNKNOWN') AND next_attempt_at<=CURRENT_TIMESTAMP(6)
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))
                ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Claim(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), UUID.randomUUID().toString()));
        if (claims.isEmpty()) return Optional.empty();
        Claim claim = claims.getFirst();
        jdbc.update("""
                UPDATE manager_client_message_queue SET state='SENDING',claim_token=?,attempts=attempts+?,
                    lease_until=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=?
                """, claim.token(), claim.mayDispatch() ? 1 : 0, claim.operationId());
        return Optional.of(claim);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean owns(Claim claim) {
        return jdbc.query("SELECT claim_token FROM manager_client_message_queue WHERE operation_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), claim.operationId()).stream().anyMatch(claim.token()::equals);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(Claim claim, String state, String code, int delaySeconds) {
        if (code != null && !code.matches("[a-z0-9_:-]{1,100}")) code = "unconfirmed_result";
        jdbc.update("""
                UPDATE manager_client_message_queue SET state=?,error_code=?,claim_token=NULL,lease_until=NULL,
                    attempts=GREATEST(0,attempts-?),next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6)
                WHERE operation_id=? AND claim_token=?
                """, state, code, "live_disabled".equals(code) && claim.mayDispatch() ? 1 : 0, delaySeconds, claim.operationId(), claim.token());
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("Manager command cannot be serialized", invalid); }
    }
    <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception invalid) { throw new IllegalStateException("Manager command cannot be read", invalid); }
    }
}
