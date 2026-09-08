package com.hunt.otziv.p_products.status.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService.PreparedPublicationProgress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Orders owns both the publication event and its durable post-commit work. */
@Service
public class OrderPublicationOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrderNotificationOccurrences occurrences;

    public OrderPublicationOutbox(JdbcTemplate jdbc, ObjectMapper json, OrderNotificationOccurrences occurrences) {
        this.jdbc = jdbc; this.json = json; this.occurrences = occurrences;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(long orderId, String occurrence, PreparedPublicationProgress progress) {
        if (orderId <= 0 || occurrence == null || occurrence.isBlank() || occurrence.length() > 180)
            throw new IllegalArgumentException("Invalid publication event");
        if (progress != null && (progress.orderId() != orderId || !progress.kind().equals("progress:" + occurrence)
                || progress.operationId() == null)) throw new IllegalArgumentException("Publication envelope belongs to another event");
        String envelope;
        try { envelope = progress == null ? null : json.writeValueAsString(progress); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot freeze publication envelope", invalid); }
        jdbc.update("""
                INSERT INTO order_publication_client_updates
                    (order_id, publication_occurrence, operation_id, delivery_envelope, delivery_state)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id=id
                """, orderId, occurrence, progress == null ? null : progress.operationId(), envelope,
                progress == null ? "SKIPPED" : "READY");
        var existing = jdbc.queryForObject("""
                SELECT operation_id,delivery_envelope FROM order_publication_client_updates
                WHERE order_id=? AND publication_occurrence=?
                """, (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, orderId, occurrence);
        if (existing == null || !java.util.Objects.equals(existing[0], progress == null ? null : progress.operationId())
                || !java.util.Objects.equals(existing[1], envelope))
            throw new IllegalStateException("Publication event already has a different immutable envelope");
    }

    public List<Long> dueDeliveries(int limit) {
        return jdbc.query("""
                SELECT cur.id FROM order_publication_client_updates cur
                WHERE cur.delivery_state IN ('READY','DISPATCHING','UNKNOWN') AND cur.next_attempt_at<=CURRENT_TIMESTAMP(6)
                    AND NOT EXISTS (SELECT 1 FROM order_publication_client_updates earlier
                        WHERE earlier.order_id=cur.order_id AND earlier.id<cur.id
                            AND earlier.delivery_state IN ('READY','DISPATCHING','UNKNOWN'))
                ORDER BY cur.next_attempt_at,cur.id LIMIT ?
                """, (rs, row) -> rs.getLong(1), bounded(limit));
    }

    public List<Long> dueCompletions(int limit) {
        return jdbc.query("""
                SELECT id FROM order_publication_client_updates
                WHERE completion_done=FALSE AND completion_next_attempt_at<=CURRENT_TIMESTAMP(6)
                ORDER BY completion_next_attempt_at,id LIMIT ?
                """, (rs, row) -> rs.getLong(1), bounded(limit));
    }

    public List<Long> dueBilling(int limit) {
        return jdbc.query("""
                SELECT id FROM order_publication_client_updates
                WHERE completion_done=TRUE AND billing_done=FALSE AND billing_next_attempt_at<=CURRENT_TIMESTAMP(6)
                ORDER BY billing_next_attempt_at,id LIMIT ?
                """, (rs, row) -> rs.getLong(1), bounded(limit));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finishBilling(long id, boolean complete) {
        jdbc.update("""
                UPDATE order_publication_client_updates SET billing_done=?,
                    billing_next_attempt_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6))
                WHERE id=? AND completion_done=TRUE AND billing_done=FALSE
                """, complete, id);
    }

    public Optional<Long> orderId(long id) {
        return jdbc.query("SELECT order_id FROM order_publication_client_updates WHERE id=?",
                (rs, row) -> rs.getLong(1), id).stream().findFirst();
    }

    /** Caller locks the Order first. Completion and its marker share that same transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockCompletion(long id) {
        return jdbc.query("""
                SELECT completion_done FROM order_publication_client_updates
                WHERE id=? AND completion_next_attempt_at<=CURRENT_TIMESTAMP(6) FOR UPDATE
                """, (rs, row) -> !rs.getBoolean(1), id).stream().findFirst().orElse(false);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void completeOrder(long orderId) {
        // The caller holds Order, so every committed publication already reflected
        // in the current counter is covered by this one completion evaluation.
        // No later pending event may rearm an invoice which this evaluation scheduled.
        jdbc.update("UPDATE order_publication_client_updates SET completion_done=TRUE WHERE order_id=? AND completion_done=FALSE", orderId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void deferOrderCompletion(long orderId) {
        jdbc.update("""
                UPDATE order_publication_client_updates
                SET completion_next_attempt_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6))
                WHERE order_id=? AND completion_done=FALSE
                """, orderId);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void completionFailed(long id) {
        jdbc.update("""
                UPDATE order_publication_client_updates
                SET completion_next_attempt_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)),last_error_code='completion_failed'
                WHERE id=? AND completion_done=FALSE
                """, id);
    }

    /** A committed DISPATCHING barrier is never reclaimed as permission to send. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(long id, boolean sendEnabled) {
        var eligible = jdbc.query("""
                SELECT cur.id,cur.order_id,cur.publication_occurrence,cur.operation_id,cur.delivery_envelope,cur.delivery_state
                FROM order_publication_client_updates cur
                WHERE cur.id=? AND cur.delivery_state IN ('READY','DISPATCHING','UNKNOWN')
                    AND cur.next_attempt_at<=CURRENT_TIMESTAMP(6)
                    AND NOT EXISTS (SELECT 1 FROM order_publication_client_updates earlier
                        WHERE earlier.order_id=cur.order_id AND earlier.id<cur.id
                            AND earlier.delivery_state IN ('READY','DISPATCHING','UNKNOWN')) FOR UPDATE
                """, (rs, row) -> new Claim(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), UUID.randomUUID().toString(), !"READY".equals(rs.getString(6))), id);
        if (eligible.isEmpty()) return Optional.empty();
        Claim claim = eligible.getFirst();
        if (!claim.receiptOnly() && !sendEnabled) {
            jdbc.update("""
                    UPDATE order_publication_client_updates SET next_attempt_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),
                        last_error_code='delivery_paused' WHERE id=?
                    """, id);
            return Optional.empty();
        }
        jdbc.update("""
                UPDATE order_publication_client_updates SET delivery_state=?,claim_token=?,
                    next_attempt_at=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)),attempt_count=attempt_count+1
                WHERE id=?
                """, claim.receiptOnly() ? "UNKNOWN" : "DISPATCHING", claim.token(), id);
        return Optional.of(claim);
    }

    /** Decode after the claim/backoff commit, so corrupt rows cannot monopolize a bounded scan. */
    public PreparedPublicationProgress decode(Claim claim) {
        try {
            var prepared = json.readValue(claim.envelope(), PreparedPublicationProgress.class);
            if (prepared == null || prepared.orderId() != claim.orderId()
                    || !java.util.Objects.equals(prepared.operationId(), claim.operationId())
                    || !java.util.Objects.equals(prepared.kind(), "progress:" + claim.occurrence()))
                throw new IllegalArgumentException("Publication snapshot identity mismatch");
            return prepared;
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("Invalid publication snapshot", malformed);
        }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean pauseBeforeProvider(Claim claim) {
        if (claim.receiptOnly()) return false;
        return jdbc.update("""
                UPDATE order_publication_client_updates SET delivery_state='READY',claim_token=NULL,
                    next_attempt_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),last_error_code='delivery_paused'
                WHERE id=? AND claim_token=? AND delivery_state='DISPATCHING'
                """, claim.id(), claim.token()) == 1;
    }

    /** A deleted Order cannot receive a new progress send; existing receipts remain recoverable. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean skipDeletedOrderBeforeProvider(Claim claim) {
        if (claim.receiptOnly()) return false;
        return jdbc.update("""
                UPDATE order_publication_client_updates SET delivery_state='SKIPPED',claim_token=NULL,
                    last_error_code='order_deleted'
                WHERE id=? AND claim_token=? AND delivery_state='DISPATCHING'
                """, claim.id(), claim.token()) == 1;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean finish(Claim claim, ClientMessageSendResult result) {
        boolean sent = confirmed(result);
        // Lock occurrence before updating outbox, matching the producer's lock order.
        // If the guarded update loses, roll back this confirmation as well.
        if (sent) occurrences.confirmInCurrentTransaction(claim.orderId(), "progress:" + claim.occurrence(), claim.operationId());
        String state = sent ? "SENT" : !claim.receiptOnly() && ClientMessageDelivery.isKnownUnsent(result) ? "READY" : "UNKNOWN";
        int changed = jdbc.update("""
                UPDATE order_publication_client_updates SET delivery_state=?,claim_token=NULL,
                    next_attempt_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)),last_error_code=?,provider_channel=?,provider_message_id=?
                WHERE id=? AND claim_token=? AND delivery_state IN ('DISPATCHING','UNKNOWN')
                """, state, errorCode(result), sent ? result.channel() : null, sent ? result.messageId() : null, claim.id(), claim.token());
        if (changed == 0 && sent) throw new IllegalStateException("Obsolete publication delivery claim");
        return changed == 1;
    }

    public static boolean confirmed(ClientMessageSendResult result) {
        return result != null && result.sent() && result.messageId() != null && !result.messageId().isBlank();
    }
    private static String errorCode(ClientMessageSendResult result) {
        if (confirmed(result)) return null;
        String code = result == null || result.errorCode() == null ? "operation_unknown" : result.errorCode();
        return code.substring(0, Math.min(code.length(), 96));
    }
    private static int bounded(int limit) { return Math.max(1, Math.min(limit, 100)); }
    public record Claim(long id, long orderId, String occurrence, String operationId, String envelope, String token, boolean receiptOnly) {}
}
