package com.hunt.otziv.client_messages.repository;

import com.hunt.otziv.security.credentials.CredentialCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transaction boundaries belong to messaging's runner; ciphertext is never logged. */
@Repository
@RequiredArgsConstructor
public class NotificationFollowupRepository {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    public record Row(String originalId, String operationId, String kind, long sourceId, String version,
                      String payload, String state, String messageId, String errorCode) { }

    public Optional<Row> find(String original) {
        return jdbc.query("SELECT * FROM client_notification_followups WHERE original_operation_id=?", this::row, original).stream().findFirst();
    }
    public Row reserve(String original, String operation, String kind, long sourceId, String version, String payload) {
        jdbc.update("""
                INSERT INTO client_notification_followups
                  (original_operation_id,operation_id,source_kind,source_id,source_version,payload_ciphertext,delivery_state)
                VALUES (?,?,?,?,?,?,'PENDING')
                ON DUPLICATE KEY UPDATE original_operation_id=original_operation_id
                """, original, operation, kind, sourceId, version, cipher.encrypt(payload));
        return find(original).orElseThrow();
    }
    public boolean claim(String original, int delaySeconds, int gapSeconds) {
        // Same guard-before-delivery order as scheduled business finalization.
        jdbc.queryForObject("SELECT guard_id FROM scheduled_client_message_dispatch_guard WHERE guard_id=1 FOR UPDATE", Integer.class);
        Row current = jdbc.query("SELECT * FROM client_notification_followups WHERE original_operation_id=? FOR UPDATE", this::row, original)
                .stream().findFirst().orElseThrow();
        if (!"PENDING".equals(current.state())) return false;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT created_at <= TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP(6))
                FROM client_notification_followups WHERE original_operation_id=?
                """, Boolean.class, delaySeconds, original))) return false;
        Boolean slot = jdbc.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM client_notification_followups
                    WHERE started_at > TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(6)))
                """, Boolean.class, gapSeconds);
        if (!Boolean.TRUE.equals(slot)) return false;
        return jdbc.update("""
                UPDATE client_notification_followups SET delivery_state='STARTED',started_at=CURRENT_TIMESTAMP(6),
                  updated_at=CURRENT_TIMESTAMP(6),next_receipt_check_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6))
                WHERE original_operation_id=? AND delivery_state='PENDING'
                """, original) == 1;
    }
    public void finish(String original, boolean sent, boolean knownUnsent, String messageId, String code) {
        if (sent) {
            jdbc.update("""
                    UPDATE client_notification_followups SET delivery_state='SENT',provider_message_id=?,last_error_code=NULL,
                        updated_at=CURRENT_TIMESTAMP(6),next_receipt_check_at=NULL
                    WHERE original_operation_id=? AND delivery_state IN ('STARTED','UNKNOWN','SENT')
                      AND (provider_message_id IS NULL OR provider_message_id=?)
                    """, messageId, original, messageId);
        } else {
            jdbc.update("""
                    UPDATE client_notification_followups SET delivery_state=?,last_error_code=?,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE original_operation_id=? AND delivery_state='STARTED'
                    """, knownUnsent ? "PENDING" : "UNKNOWN", code, original);
        }
    }
    public void cancelUnstarted(String original) {
        jdbc.update("""
                UPDATE client_notification_followups SET delivery_state='CANCELED',last_error_code='original_receipt_confirmed',
                    updated_at=CURRENT_TIMESTAMP(6) WHERE original_operation_id=? AND delivery_state='PENDING'
                """, original);
    }
    public boolean markApplied(String original, String operation, String messageId) {
        return jdbc.update("""
                UPDATE client_notification_followups SET owner_applied_at=COALESCE(owner_applied_at,CURRENT_TIMESTAMP(6))
                WHERE original_operation_id=? AND operation_id=? AND delivery_state='SENT' AND provider_message_id=?
                """, original, operation, messageId) == 1;
    }
    public List<String> receiptCandidates() {
        return jdbc.queryForList("""
                SELECT original_operation_id FROM client_notification_followups
                WHERE delivery_state IN ('PENDING','STARTED','UNKNOWN') AND next_receipt_check_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_receipt_check_at,original_operation_id LIMIT 10
                """, String.class);
    }
    public boolean reserveReceiptCheck(String original) {
        return jdbc.update("""
                UPDATE client_notification_followups SET next_receipt_check_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6))
                WHERE original_operation_id=? AND delivery_state IN ('PENDING','STARTED','UNKNOWN')
                  AND next_receipt_check_at <= CURRENT_TIMESTAMP(6)
                """, original) == 1;
    }
    public void holdStaleDispatch(String original) {
        jdbc.update("""
                UPDATE client_notification_followups SET delivery_state='UNKNOWN',last_error_code='dispatch_result_unconfirmed',
                    updated_at=CURRENT_TIMESTAMP(6)
                WHERE original_operation_id=? AND delivery_state='STARTED'
                  AND started_at <= TIMESTAMPADD(MINUTE,-15,CURRENT_TIMESTAMP(6))
                """,original);
    }
    private Row row(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getString("original_operation_id"),rs.getString("operation_id"),rs.getString("source_kind"),
                rs.getLong("source_id"),rs.getString("source_version"),cipher.decrypt(rs.getString("payload_ciphertext")),
                rs.getString("delivery_state"),rs.getString("provider_message_id"),rs.getString("last_error_code"));
    }
}
