package com.hunt.otziv.manager_control.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC-owned delivery ledger; every mutation runs within the workflow's source/card locks. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ManagerClientReplyOperationRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Operation> ROW = (rs, row) -> new Operation(
            rs.getString("operation_token"), rs.getLong("unanswered_item_id"), rs.getLong("concrete_item_id"),
            rs.getString("request_hash"), rs.getString("state"), rs.getTimestamp("prepared_at").toLocalDateTime(),
            rs.getString("delivery_snapshot"), rs.getString("provider_message_id"), rs.getBoolean("source_applied"));

    public Optional<Operation> findBlockingForUpdate(Long itemId) {
        return one(jdbc.query("SELECT * FROM manager_client_reply_operations WHERE blocking_item_id=? FOR UPDATE", ROW, itemId));
    }

    public Optional<Operation> findRequestForUpdate(Long itemId, String requestHash) {
        return one(jdbc.query("SELECT * FROM manager_client_reply_operations WHERE unanswered_item_id=? AND request_hash=? FOR UPDATE", ROW, itemId, requestHash));
    }

    public Optional<Operation> findTokenForUpdate(String token) {
        return one(jdbc.query("SELECT * FROM manager_client_reply_operations WHERE operation_token=? FOR UPDATE", ROW, token));
    }

    public Operation create(Long itemId, Long cardId, String requestHash, LocalDateTime now, String snapshot) {
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO manager_client_reply_operations(operation_token,unanswered_item_id,concrete_item_id,request_hash,state,prepared_at,delivery_snapshot) VALUES(?,?,?,?,'PREPARED',?,CAST(? AS JSON))",
                token, itemId, cardId, requestHash, now, snapshot);
        return new Operation(token, itemId, cardId, requestHash, "PREPARED", now, snapshot, null, false);
    }

    public Operation retryKnownUnsent(Operation operation, Long cardId, LocalDateTime now, String snapshot) {
        int changed = jdbc.update("UPDATE manager_client_reply_operations SET state='PREPARED',concrete_item_id=?,prepared_at=?,delivery_snapshot=CAST(? AS JSON),completed_at=NULL,result_channel=NULL,last_error_code=NULL WHERE operation_token=? AND state='FAILED_KNOWN'",
                cardId, now, snapshot, operation.token());
        requireOne(changed);
        return new Operation(operation.token(), operation.itemId(), cardId, operation.requestHash(), "PREPARED", now, snapshot, null, false);
    }

    public void finish(Operation operation, String state, String channel, String errorCode) {
        finish(operation, state, channel, errorCode, null);
    }

    /** Only the receipt workflow may reopen an uncertain operation after proven non-admission. */
    public Operation resumeAfterKnownUnsentReceipt(Operation operation, String code) {
        requireOne(jdbc.update("UPDATE manager_client_reply_operations SET state='PREPARED',completed_at=NULL,last_error_code=?,resolution_reason='Provider confirmed non-admission' WHERE operation_token=? AND state='UNKNOWN' AND provider_message_id IS NULL",
                code, operation.token()));
        return new Operation(operation.token(), operation.itemId(), operation.cardId(), operation.requestHash(),
                "PREPARED", operation.preparedAt(), operation.snapshot(), null, false);
    }

    public void finish(Operation operation, String state, String channel, String errorCode, String messageId) {
        if (!List.of("UNKNOWN", "SUCCEEDED", "FAILED_KNOWN").contains(state)) throw new IllegalArgumentException("Invalid delivery state");
        if ("SUCCEEDED".equals(state) && (messageId == null || messageId.isBlank() || messageId.length() > 512))
            throw new IllegalArgumentException("Missing delivery proof");
        int changed = jdbc.update("UPDATE manager_client_reply_operations SET state=?,completed_at=?,result_channel=?,last_error_code=?,source_applied=?,provider_message_id=? WHERE operation_token=? AND state='PREPARED'",
                state, LocalDateTime.now(), channel, errorCode, "SUCCEEDED".equals(state), messageId, operation.token());
        requireOne(changed);
    }

    public void confirmDelivery(Operation operation, String messageId, boolean sourceApplied, Long actorId, String reason) {
        if (messageId == null || messageId.isBlank() || messageId.length() > 512) throw new IllegalArgumentException("Missing delivery proof");
        requireOne(jdbc.update("UPDATE manager_client_reply_operations SET state='SUCCEEDED',completed_at=?,result_channel='WhatsApp',last_error_code=NULL,provider_message_id=?,source_applied=?,resolved_by_user_id=?,resolution_reason=? WHERE operation_token=? AND state='UNKNOWN'",
                LocalDateTime.now(), messageId, sourceApplied, actorId, reason, operation.token()));
    }

    private static Optional<Operation> one(List<Operation> rows) { return rows.stream().findFirst(); }
    private static void requireOne(int changed) { if (changed != 1) throw new IllegalStateException("Reply operation state changed"); }

    public record Operation(String token, Long itemId, Long cardId, String requestHash, String state,
                            LocalDateTime preparedAt, String snapshot, String messageId, boolean sourceApplied) { }
}
