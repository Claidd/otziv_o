package com.hunt.otziv.client_messages.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The archive transaction freezes notifications. A lost result permits receipt lookup only, never another send. */
@Service @RequiredArgsConstructor @Slf4j
public class ReviewArchiveNotifications {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CredentialCipher cipher;
    private final ClientMessageDelivery delivery;
    private final TelegramNotifications telegram;
    private final ClientMessageTransactionRunner transactions;
    private final OutboundMessagePolicy settings;

    public record Envelope(ClientMessageDelivery.Target target, String clientId, String groupId, String text, Long staffChatId) { }
    record Claim(String operationId, String channel, String envelope, String token, boolean receiptOnly, LocalDateTime createdAt) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordArchive(long stateId, long orderId, String target, int sentCount, Object snapshot,
                              LocalDateTime offerNext, String offerStatus) {
        jdbc.update("""
            INSERT INTO review_silence_archives(state_id,order_id,target_key,sent_count,source_snapshot,offer_next_at,offer_status)
            VALUES(?,?,?,?,?,?,?)
            """, stateId,orderId,target,sentCount,encode(snapshot),offerNext,offerStatus);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long stateId, String recipient, Envelope envelope) {
        String operation = "review-archive:" + stateId + ":" + recipient;
        jdbc.update("""
            INSERT INTO review_archive_notifications(operation_id,source_state_id,channel,envelope)
            VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id
            """, operation,stateId,envelope.staffChatId() == null ? "CLIENT" : "STAFF",encode(envelope));
        String stored = jdbc.queryForObject("SELECT envelope FROM review_archive_notifications WHERE operation_id=? FOR UPDATE",String.class,operation);
        try {
            if (!envelope.equals(json.readValue(cipher.decrypt(stored),Envelope.class)))
                throw new IllegalStateException("Archive notification is immutable");
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read archive notification",failure); }
    }

    private String encode(Object value) {
        try { return cipher.encrypt(json.writeValueAsString(value)); }
        catch (Exception failure) { throw new IllegalStateException("Cannot freeze archive notification", failure); }
    }

    /** Correct the historical date shown by the initial rollout, without rearming any delivery. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT90S")
    public void correctHistoricalOfferDates() {
        for (Long id : jdbc.query("SELECT state_id FROM review_silence_archives WHERE offer_next_at<created_at LIMIT 20",
                (rs,row) -> rs.getLong(1))) {
            try { transactions.runInNewTransaction(() -> correctHistoricalOfferDate(id)); }
            catch (RuntimeException failure) { log.warn("Archive offer date correction pending: stateId={}",id,failure); }
        }
    }

    private void correctHistoricalOfferDate(long id) {
        record Correction(long orderId, LocalDateTime previous, LocalDateTime due, int sent) { }
        var rows = jdbc.query("""
            SELECT a.order_id,a.offer_next_at,q.next_attempt_at,q.sent_count FROM review_silence_archives a
            JOIN scheduled_client_message_state s ON s.state_id=a.state_id
            JOIN scheduled_client_message_state q ON q.state_id=(SELECT MAX(o.state_id)
                FROM scheduled_client_message_state o WHERE o.company_id=s.company_id
                  AND o.scenario='ARCHIVE_REORDER_OFFER' AND o.state_status='ACTIVE')
            WHERE a.state_id=? AND a.offer_next_at<a.created_at AND q.next_attempt_at>CURRENT_TIMESTAMP(6)
              AND q.delivery_status IS NULL AND q.delivery_envelope IS NULL
              AND (q.locked_until IS NULL OR q.locked_until<CURRENT_TIMESTAMP(6)) FOR UPDATE
            """, (rs,row) -> new Correction(rs.getLong(1),rs.getTimestamp(2).toLocalDateTime(),
                rs.getTimestamp(3).toLocalDateTime(),rs.getInt(4)),id);
        if (rows.isEmpty()) return;
        var row=rows.getFirst();
        String text="Уточнение по заказу №"+row.orderId()+": в уведомлении об архивировании была указана старая дата компании. "
                + (row.sent()>0 ? "Предложение нового заказа уже отправлено; повтор сейчас не отправляется. " : "Предложение ещё не отправлено. ")
                + "Следующее предложение в очереди на "+row.due().atZone(java.time.ZoneId.systemDefault())
                    .withZoneSameInstant(java.time.ZoneId.of("Asia/Irkutsk")).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                + " (Иркутск). Оплата и история заказа сохранены.";
        jdbc.update("""
            INSERT INTO client_reminder_restart_audit(request_key,subject_type,subject_id,old_operation_id,
              actor,reason,previous_snapshot,next_attempt_at)
            SELECT 'archive-offer-date-correction','ARCHIVE_OFFER',state_id,CONCAT('archive-offer-date:',state_id),
              'system','Correct historical offer date; delivery unchanged',
              JSON_OBJECT('offer_next_at',offer_next_at,'offer_status',offer_status),? FROM review_silence_archives WHERE state_id=?
            """,row.due(),id);
        jdbc.update("UPDATE review_silence_archives SET offer_next_at=?,offer_status=? WHERE state_id=?",row.due(),text,id);
        for (String encrypted : jdbc.query("SELECT envelope FROM review_archive_notifications WHERE source_state_id=? AND channel='STAFF'",
                (rs,n) -> rs.getString(1),id)) {
            try {
                var old=json.readValue(cipher.decrypt(encrypted),Envelope.class);
                if(old.staffChatId()==null) throw new IllegalStateException("Missing staff recipient");
                enqueue(id,"schedule-correction:staff:"+old.staffChatId(),new Envelope(null,null,null,text,old.staffChatId()));
            } catch(java.io.IOException failure) { throw new IllegalStateException("Cannot read archive recipient",failure); }
        }
    }

    @Scheduled(fixedDelayString = "${client-messages.archive-notice-delay:PT15S}", initialDelayString = "PT30S")
    public void drain() {
        jdbc.update("""
            UPDATE review_archive_notifications SET state='UNKNOWN',error_code='telegram_outcome_unknown',claim_token=NULL
            WHERE channel='STAFF' AND state='DISPATCHING' AND next_attempt_at<=CURRENT_TIMESTAMP(6)
            """);
        for (String id : jdbc.query("""
            SELECT operation_id FROM review_archive_notifications
            WHERE state IN ('READY','DISPATCHING','UNKNOWN') AND next_attempt_at<=CURRENT_TIMESTAMP(6)
              AND (channel='CLIENT' OR state='READY') ORDER BY next_attempt_at,operation_id LIMIT 20
            """, (rs,row) -> rs.getString(1))) {
            try { deliver(id); }
            catch (RuntimeException failure) { log.warn("Archive notification remains queued: operation={}",id,failure); }
        }
    }

    void deliver(String id) {
        if (!settings.clientMessagesEnabled()) return;
        Claim claim = transactions.callInNewTransaction(() -> {
            var rows = jdbc.query("""
                SELECT operation_id,channel,envelope,state,created_at FROM review_archive_notifications
                WHERE operation_id=? AND state IN ('READY','DISPATCHING','UNKNOWN')
                  AND next_attempt_at<=CURRENT_TIMESTAMP(6) FOR UPDATE
                """, (rs,row) -> new Claim(rs.getString(1),rs.getString(2),rs.getString(3),UUID.randomUUID().toString(),
                    !"READY".equals(rs.getString(4)),rs.getTimestamp(5).toLocalDateTime()), id);
            if (rows.isEmpty()) return null;
            Claim result = rows.getFirst();
            if (result.receiptOnly() && "STAFF".equals(result.channel())) return null;
            jdbc.update("""
                UPDATE review_archive_notifications SET state=?,claim_token=?,attempts=attempts+1,
                  next_attempt_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=?
                """,result.receiptOnly() ? "UNKNOWN" : "DISPATCHING",result.token(),id);
            return result;
        });
        if (claim == null) return;
        ClientMessageSendResult outcome;
        try {
            Envelope envelope = json.readValue(cipher.decrypt(claim.envelope()), Envelope.class);
            if (claim.receiptOnly()) outcome = delivery.recoverRecordedOutcomeFromChat(id,claim.createdAt());
            else if (envelope.staffChatId() != null) outcome = telegram.sendTextOnce(envelope.staffChatId(),envelope.text())
                    ? ClientMessageSendResult.sent("telegram") : ClientMessageSendResult.failed("telegram_unknown","Outcome unknown");
            else outcome = delivery.deliverWithOperationId(envelope.target(),envelope.clientId(),envelope.groupId(),
                        envelope.text(),null,id);
        } catch (Exception failure) { outcome = ClientMessageSendResult.failed("notification_unknown","Outcome unknown"); }
        ClientMessageSendResult result = outcome;
        transactions.runInNewTransaction(() -> jdbc.update("""
            UPDATE review_archive_notifications SET state=?,error_code=?,claim_token=NULL,
              next_attempt_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=? AND claim_token=?
            """, result != null && result.sent() ? "SENT"
                : !claim.receiptOnly() && "CLIENT".equals(claim.channel()) && ClientMessageDelivery.isKnownUnsent(result) ? "READY" : "UNKNOWN",
                result == null ? "outcome_missing" : result.errorCode(),id,claim.token()));
    }
}
