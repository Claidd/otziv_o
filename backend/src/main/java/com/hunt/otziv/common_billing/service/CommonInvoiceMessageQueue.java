package com.hunt.otziv.common_billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.security.credentials.CredentialCipher;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Billing owns its frozen commands. Claims are short; UNKNOWN is receipt-only. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceMessageQueue implements com.hunt.otziv.client_messages.api.DeliveryQueueHealth {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CredentialCipher cipher;

    @Override public String queueName() { return "common_invoice"; }
    @Override @Transactional(readOnly = true, timeout = 3)
    public List<Row> deliveryQueueHealth() {
        return jdbc.query("SELECT state,COUNT(*),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(6))),"
                + "SUM(CASE WHEN lease_until<CURRENT_TIMESTAMP(6) THEN 1 ELSE 0 END) FROM common_invoice_message_queue "
                + "WHERE state IN ('QUEUED','SENDING','RETRYABLE','UNKNOWN','FAILED') GROUP BY state",
                (rs, n) -> new Row(State.valueOf(rs.getString(1)), rs.getLong(2), rs.getDouble(3), rs.getLong(4)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PreparedCommonInvoiceMessage prepared) {
        if (prepared.alreadyConfirmed()) return;
        if (!cipher.isEnabled()) throw new IllegalStateException("Billing delivery snapshots require encryption");
        String plaintext;
        try { plaintext = json.writeValueAsString(prepared); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid billing delivery snapshot", invalid); }
        jdbc.update("""
                INSERT INTO common_invoice_message_queue(operation_id, invoice_id, envelope_ciphertext)
                VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE operation_id=operation_id
                """, prepared.operationId(), prepared.invoiceId(), cipher.encrypt(plaintext));
        PreparedCommonInvoiceMessage stored = snapshot(prepared.operationId());
        if (!Objects.equals(stored, prepared)) throw new IllegalStateException("Billing operation already has a different envelope");
    }

    Optional<PreparedCommonInvoiceMessage> unresolved(long invoiceId) {
        return jdbc.query("SELECT operation_id FROM common_invoice_message_queue WHERE invoice_id=? AND state<>'SENT' ORDER BY created_at LIMIT 1",
                (rs, row) -> rs.getString(1), invoiceId).stream().findFirst().map(this::snapshot);
    }

    PreparedCommonInvoiceMessage snapshot(String operationId) {
        var row = jdbc.queryForMap("SELECT invoice_id,envelope_ciphertext FROM common_invoice_message_queue WHERE operation_id=?", operationId);
        String encrypted = (String) row.get("envelope_ciphertext");
        if (encrypted == null || !encrypted.startsWith("enc:")) throw new IllegalStateException("Billing snapshot encryption is missing");
        try {
            var prepared = json.readValue(cipher.decrypt(encrypted), PreparedCommonInvoiceMessage.class);
            if (!operationId.equals(prepared.operationId()) || ((Number) row.get("invoice_id")).longValue() != prepared.invoiceId())
                throw new IllegalStateException("Billing snapshot identity mismatch");
            return prepared;
        } catch (Exception invalid) { throw new IllegalStateException("Billing snapshot is unreadable; dispatch blocked", invalid); }
    }

    public DeliveryOperation status(String operationId) {
        if (operationId == null) return null;
        return jdbc.query("SELECT state,attempts,error_code FROM common_invoice_message_queue WHERE operation_id=?",
                (rs, row) -> new DeliveryOperation(operationId, rs.getString(1), rs.getInt(2), rs.getString(3)), operationId)
                .stream().findFirst().orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim() {
        List<Claim> rows = jdbc.query("""
                SELECT operation_id,invoice_id,state,attempts FROM common_invoice_message_queue
                WHERE state IN ('QUEUED','RETRYABLE','SENDING','UNKNOWN') AND next_attempt_at<=CURRENT_TIMESTAMP(6)
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))
                ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Claim(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4), UUID.randomUUID().toString()));
        if (rows.isEmpty()) return Optional.empty();
        Claim claim = rows.getFirst();
        jdbc.update("""
                UPDATE common_invoice_message_queue SET state='SENDING',claim_token=?,
                    attempts=attempts+?,lease_until=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=?
                """, claim.token(), claim.mayDispatch() ? 1 : 0, claim.operationId());
        return Optional.of(claim);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean owns(Claim claim) {
        return jdbc.query("SELECT claim_token FROM common_invoice_message_queue WHERE operation_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), claim.operationId()).stream().anyMatch(claim.token()::equals);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(Claim claim, String state, String errorCode, int delaySeconds) {
        jdbc.update("""
                UPDATE common_invoice_message_queue SET state=?,error_code=?,claim_token=NULL,lease_until=NULL,
                    next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6),
                    attempts=GREATEST(0,attempts-?)
                WHERE operation_id=? AND claim_token=?
                """, state, errorCode, delaySeconds, "live_disabled".equals(errorCode) && claim.mayDispatch() ? 1 : 0, claim.operationId(), claim.token());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void retryKnownUnsent(String operationId) {
        var operation = status(operationId);
        if (operation != null && "FAILED".equals(operation.status())
                && ("context_changed".equals(operation.errorCode()) || "finalization_required".equals(operation.errorCode())))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Сохранённая отправка требует сверки изменившегося счета");
        jdbc.update("UPDATE common_invoice_message_queue SET state='QUEUED',attempts=0,error_code=NULL,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND state='FAILED'", operationId);
    }

    record Claim(String operationId, long invoiceId, String previousState, int previousAttempts, String token) {
        boolean mayDispatch() { return "QUEUED".equals(previousState) || "RETRYABLE".equals(previousState); }
    }
}
