package com.hunt.otziv.manager_control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
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

    record RecordedControl(DeliveryOperation delivery, long cardId, LocalDateTime createdAt,
                           LocalDateTime updatedAt, ManagerControlClientSendWorkflow.PreparedClientMessage source) { }

    /** Same order across daily copies. The encrypted snapshot supplies the cycle and authority fence. */
    List<RecordedControl> controlHistory(long orderId) {
        return controlHistory(orderId, false);
    }

    List<RecordedControl> controlHistory(long orderId, boolean lock) {
        return controlHistory(orderId, lock, false);
    }

    /** Settlement must see old successful successors and every intervening pending operation. */
    List<RecordedControl> controlHistoryForSettlement(long orderId) {
        return controlHistory(orderId, true, true);
    }

    private List<RecordedControl> controlHistory(long orderId, boolean lock, boolean includeSettledHistory) {
        return jdbc.query("""
                SELECT q.operation_id,q.card_id,q.state,q.attempts,q.error_code,q.created_at,q.updated_at,q.envelope_ciphertext
                FROM manager_client_message_queue q
                WHERE q.command_kind='CONTROL' AND q.card_id IN (SELECT c.control_entity_id
                    FROM manager_daily_control_concrete_items c WHERE c.entity_id=? AND c.entity_type IN ('ORDER','WORKER_ORDER_NEW'))
                  AND (? OR q.state IN ('QUEUED','SENDING','RETRYABLE','UNKNOWN')
                    OR q.error_code IN ('finalization_required','context_changed')
                    OR (q.state='SENT' AND q.updated_at>=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 DAY)))
                ORDER BY q.created_at DESC,q.operation_id DESC
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> {
            String id = rs.getString(1);
            String encrypted = rs.getString(8);
            if (encrypted == null || !encrypted.startsWith("enc:")) throw new IllegalStateException("Manager command encryption is missing");
            var command = decode(cipher.decrypt(encrypted), Command.class);
            if (!Objects.equals(command.operationId(), id) || command.cardId() != rs.getLong(2)
                    || !"CONTROL".equals(command.kind())) throw new IllegalStateException("Manager command identity mismatch");
            var source = decode(command.preparedJson(), ManagerControlClientSendWorkflow.PreparedClientMessage.class);
            if (!Objects.equals(source.deliveryToken(), id) || !Objects.equals(source.concreteItemId(), rs.getLong(2)))
                throw new IllegalStateException("Manager command source identity mismatch");
            return new RecordedControl(new DeliveryOperation(id, rs.getString(3), rs.getInt(4), rs.getString(5)),
                    rs.getLong(2), rs.getTimestamp(6).toLocalDateTime(), rs.getTimestamp(7).toLocalDateTime(), source);
        }, orderId, includeSettledHistory);
    }

    DeliveryOperation blockingForCard(long cardId) {
        return jdbc.query("""
                SELECT operation_id,state,attempts,error_code FROM manager_client_message_queue
                WHERE card_id=? AND (state IN ('QUEUED','SENDING','RETRYABLE','UNKNOWN')
                  OR error_code IN ('finalization_required','context_changed'))
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> new DeliveryOperation(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), cardId)
                .stream().findFirst().orElse(null);
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
                WHERE (state IN ('QUEUED','RETRYABLE','SENDING','UNKNOWN')
                  OR (state='SENT' AND error_code='finalization_required')) AND next_attempt_at<=CURRENT_TIMESTAMP(6)
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))
                ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Claim(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), UUID.randomUUID().toString()));
        if (claims.isEmpty()) return Optional.empty();
        Claim claim = claims.getFirst();
        jdbc.update("""
                UPDATE manager_client_message_queue SET state=CASE WHEN state='SENT' THEN 'SENT' ELSE 'SENDING' END,
                    claim_token=?,attempts=attempts+?,
                    lease_until=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=?
                """, claim.token(), claim.mayDispatch() ? 1 : 0, claim.operationId());
        return Optional.of(claim);
    }

    /** Explicit receipt check never claims a command that is eligible for dispatch. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimReceipt(String operationId) {
        var rows = jdbc.query("""
                SELECT operation_id,card_id,command_kind,state,attempts FROM manager_client_message_queue
                WHERE operation_id=? AND (state IN ('UNKNOWN','SENDING') OR (state='SENT' AND error_code='finalization_required'))
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6)) FOR UPDATE
                """, (rs, row) -> new Claim(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getInt(5),UUID.randomUUID().toString()), operationId);
        if (rows.isEmpty()) return Optional.empty();
        var claim = rows.getFirst();
        jdbc.update("UPDATE manager_client_message_queue SET claim_token=?,lease_until=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=?",
                claim.token(), operationId);
        return Optional.of(claim);
    }

    LocalDateTime createdAt(String operationId) {
        return jdbc.query("SELECT created_at FROM manager_client_message_queue WHERE operation_id=?",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime(), operationId).stream().findFirst().orElse(null);
    }

    /** A new periodic reminder supersedes the command, never its unknown delivery outcome. */
    @Transactional(propagation = Propagation.MANDATORY)
    void supersedeStaleReminder(RecordedControl row, String actor, LocalDateTime nextAt) {
        supersedeStaleReminder(row, actor, nextAt, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void supersedeCancelledReservation(RecordedControl row, String actor, LocalDateTime nextAt,
            com.hunt.otziv.client_messages.api.PeriodicReminderRestart.ReservedReminderCheck reminder) {
        if (reminder == null || reminder.stateId() <= 0 || reminder.targetKey() == null
                || !"FAILED".equals(row.delivery().status()) || !"context_changed".equals(row.delivery().errorCode()))
            throw new IllegalArgumentException("A cancelled manual command and its current reminder are required");
        supersedeStaleReminder(row, actor, nextAt, reminder);
    }

    private void supersedeStaleReminder(RecordedControl row, String actor, LocalDateTime nextAt,
            com.hunt.otziv.client_messages.api.PeriodicReminderRestart.ReservedReminderCheck reminder) {
        String operation = row.delivery().operationId();
        int audited = jdbc.update("""
                INSERT INTO client_reminder_restart_audit
                  (request_key,subject_type,subject_id,old_operation_id,actor,reason,previous_snapshot,next_attempt_at)
                SELECT ?, 'MANUAL', card_id, operation_id, ?, ?,
                  JSON_OBJECT('state',state,'error',error_code,'attempts',attempts,'createdAt',created_at,
                    'updatedAt',updated_at,'envelopeCiphertext',envelope_ciphertext,
                    'scheduledStateId',?,'scheduledTargetKey',?,'orderGeneration',?,'dispatchGeneration',?), ?
                FROM manager_client_message_queue WHERE operation_id=?
                  AND ((state='UNKNOWN' AND error_code='whatsapp_history_checked_no_receipt')
                    OR (state='FAILED' AND error_code='context_changed'))
                  AND (? OR (state='FAILED' AND error_code='context_changed'))
                  AND created_at<=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 DAY)
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))
                """, "manual-restart:" + operation, actor,
                reminder == null ? "periodic_reminder_restarted_after_history_check" : "periodic_reminder_resumed_after_cancelled_manual",
                reminder == null ? null : reminder.stateId(), reminder == null ? null : reminder.targetKey(),
                reminder == null ? null : reminder.order().generation(), reminder == null ? null : reminder.dispatchGeneration(),
                nextAt, operation, reminder == null);
        if (audited != 1) throw new IllegalStateException("Manual reminder changed during reconciliation");
        int changed = jdbc.update("""
                UPDATE manager_client_message_queue SET state='SUPERSEDED',error_code='old_reminder_superseded',
                  claim_token=NULL,lease_until=NULL WHERE operation_id=?
                    AND ((state='UNKNOWN' AND error_code='whatsapp_history_checked_no_receipt')
                      OR (state='FAILED' AND error_code='context_changed'))
                """, operation);
        if (changed != 1) throw new IllegalStateException("Manual reminder restart lost ownership");
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

    /** Retire bookkeeping only. Preserve proof, original timestamps, and every business/card field. */
    @Transactional(propagation = Propagation.MANDATORY)
    void settleSuperseded(Claim claim) {
        settleConfirmed(claim, "superseded_by_confirmed_contact");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void settleCompletedCycle(Claim claim) {
        settleConfirmed(claim, "source_cycle_completed");
    }

    private void settleConfirmed(Claim claim, String code) {
        int changed = jdbc.update("""
                UPDATE manager_client_message_queue
                SET error_code=?,claim_token=NULL,lease_until=NULL
                WHERE operation_id=? AND claim_token=? AND state='SENT' AND error_code='finalization_required'
                  AND lease_until>CURRENT_TIMESTAMP(6)
                """, code, claim.operationId(), claim.token());
        if (changed != 1) throw new IllegalStateException("Confirmed settlement claim changed");
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
