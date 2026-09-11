package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.api.WhatsAppQueuedMessages;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.client_messages.api.DeliveryOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

/** Historical table name retained. Both webhook replies and lead/manual commands are durable. */
@Service
@Slf4j
public class WhatsAppInboundReplyOutbox implements WhatsAppQueuedMessages, com.hunt.otziv.client_messages.api.DeliveryQueueHealth {
    private final JdbcTemplate jdbc;
    private final WhatsAppBusinessOperations operations;
    private final WhatsAppService transport;
    private final OutboundMessagePolicy policy;
    private final TransactionTemplate transaction;

    @Override public String queueName() { return "whatsapp_reply"; }
    @Override @Transactional(readOnly = true, timeout = 3)
    public List<Row> deliveryQueueHealth() {
        return jdbc.query("SELECT CASE state WHEN 'PENDING' THEN 'QUEUED' WHEN 'PROCESSING' THEN 'SENDING' ELSE state END,"
                + "COUNT(*),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(6))),"
                + "SUM(CASE WHEN lease_until<CURRENT_TIMESTAMP(6) THEN 1 ELSE 0 END) FROM whatsapp_inbound_reply_outbox "
                + "WHERE state IN ('PENDING','PROCESSING','RETRYABLE','UNKNOWN','FAILED') GROUP BY state",
                (rs, n) -> new Row(State.valueOf(rs.getString(1)), rs.getLong(2), rs.getDouble(3), rs.getLong(4)));
    }

    public WhatsAppInboundReplyOutbox(JdbcTemplate jdbc, WhatsAppBusinessOperations operations,
            WhatsAppService transport, PlatformTransactionManager manager, OutboundMessagePolicy policy) {
        this.jdbc = jdbc; this.operations = operations; this.transport = transport; this.policy = policy;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override @Transactional
    public void enqueue(String operationId, String clientId, String groupId, String message) {
        boolean previouslyPrepared = operations.findFrozen(operationId).isPresent();
        operations.freeze(operationId, clientId, "send-group", groupId, message);
        insert(operationId, previouslyPrepared);
    }

    @Transactional
    public DeliveryOperation enqueueManual(String operationId, String actor, String clientId, String phone, String message) {
        operations.requireManualOwner(operationId, actor);
        boolean previouslyPrepared = operations.findFrozen(operationId).isPresent();
        var frozen = operations.freeze(operationId, clientId, "send", phone, message);
        // Compare inside this transaction: a REQUIRES_NEW lookup could not see its new envelope.
        if (!frozen.clientId().equals(clientId) || !frozen.kind().equals("send")
                || !frozen.destination().equals(com.hunt.otziv.whatsapp.dto.WhatsAppDestination.normalize("send", phone))
                || !frozen.message().equals(message)) throw new IllegalArgumentException("operation_payload_conflict");
        insert(operationId, previouslyPrepared);
        return status(operationId);
    }

    private void insert(String operationId, boolean previouslyPrepared) {
        // An envelope created by the old synchronous path is historical evidence, not permission to dispatch.
        jdbc.update("INSERT INTO whatsapp_inbound_reply_outbox(operation_id,state,error_code) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE operation_id=operation_id", operationId, previouslyPrepared ? "UNKNOWN" : "PENDING",
                previouslyPrepared ? "legacy_attempt_requires_receipt" : null);
    }

    public DeliveryOperation manualStatus(String operationId, String actor) {
        operations.requireManualOwner(operationId, actor);
        return status(operationId);
    }

    private DeliveryOperation status(String operationId) {
        return jdbc.query("SELECT state,attempts,error_code FROM whatsapp_inbound_reply_outbox WHERE operation_id=?",
                (rs, row) -> new DeliveryOperation(operationId, switch (rs.getString(1)) {
                    case "PENDING" -> "QUEUED"; case "PROCESSING" -> "SENDING"; case "COMPLETE" -> "SENT";
                    default -> rs.getString(1);
                }, rs.getInt(2), rs.getString(3)), operationId).stream().findFirst().orElse(null);
    }

    private boolean liveEnabled() {
        try { return policy.clientMessagesEnabled(); } catch (RuntimeException unavailable) { return false; }
    }

    @Scheduled(fixedDelayString = "${whatsapp.webhook.reply-outbox-delay-ms:5000}", scheduler="whatsappMessageQueueScheduler")
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void dispatchDue() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("WhatsApp dispatch requires an absent business transaction");
        for (int i = 0; i < 5; i++) {
            Claim claim = transaction.execute(status -> {
                List<Claim> rows = jdbc.query("SELECT operation_id,state,attempts FROM whatsapp_inbound_reply_outbox "
                        + "WHERE state IN ('PENDING','RETRYABLE','PROCESSING','UNKNOWN') AND next_attempt_at<=CURRENT_TIMESTAMP(6) "
                        + "AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6)) "
                        + "ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                        (rs,row) -> new Claim(rs.getString(1),rs.getString(2),rs.getInt(3),UUID.randomUUID().toString()));
                if (rows.isEmpty()) return null;
                var selected = rows.getFirst();
                jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET state='PROCESSING', attempts=attempts+?, "
                        + "claim_token=?,lease_until=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE operation_id=?",
                        selected.mayDispatch() ? 1 : 0, selected.token(), selected.id());
                return selected;
            });
            if (claim == null) return;
            try {
                var frozen = operations.requireFrozen(claim.id());
                if (!claim.mayDispatch()) {
                    var receipt = transport.getOperationStatus(frozen.clientId(), frozen.operationId());
                    String hash = "send-group".equals(frozen.kind())
                            ? WhatsAppOperationEnvelope.groupHash(frozen.clientId(), frozen.destination(), frozen.message())
                            : WhatsAppOperationEnvelope.phoneHash(frozen.clientId(), frozen.destination(), frozen.message());
                    boolean confirmed = receipt != null && claim.id().equals(receipt.operationId()) && "SUCCEEDED".equals(receipt.state())
                            && hash.equals(receipt.envelopeHash()) && receipt.messageId()!=null && !receipt.messageId().isBlank();
                    finish(claim, confirmed ? "COMPLETE" : "UNKNOWN", confirmed ? null : "operation_unknown", 300, false);
                } else if (!liveEnabled()) {
                    finish(claim,"RETRYABLE","live_disabled",300,true);
                } else {
                    String raw = "send-group".equals(frozen.kind())
                            ? transport.sendMessageToGroup(frozen.clientId(), frozen.destination(), frozen.message(), frozen.operationId())
                            : transport.sendMessage(frozen.clientId(), frozen.destination(), frozen.message(), frozen.operationId());
                    var result = WhatsAppSendResult.parse(raw);
                    boolean knownUnsent = (result.hasStatus("error") || result.hasStatus("not_ready"))
                            && com.hunt.otziv.client_messages.api.ClientMessageDelivery.isKnownUnsent(
                            com.hunt.otziv.client_messages.dto.ClientMessageSendResult.failed(result.code(), "provider result"));
                    String next = result.isOk() ? "COMPLETE" : knownUnsent ? claim.attempts()+1<5 ? "RETRYABLE" : "FAILED" : "UNKNOWN";
                    finish(claim,next,result.isOk() ? null : safeCode(result.code()),
                            "UNKNOWN".equals(next) ? 300 : Math.min(300,15 << Math.min(claim.attempts(),4)),false);
                }
            } catch (RuntimeException error) {
                // Includes a lost response or unreadable snapshot. Never authorize a second send.
                log.warn("WhatsApp queued delivery held: exceptionType={}", error.getClass().getSimpleName());
                finish(claim,"UNKNOWN","operation_unknown",300,false);
            }
        }
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[a-zA-Z0-9_.:-]{1,96}") ? code : "operation_unknown";
    }

    private void finish(Claim claim, String state, String errorCode, int delay, boolean paused) {
        jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET state=?,error_code=?, "
                + "next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),lease_until=NULL,claim_token=NULL, "
                + "attempts=GREATEST(0,attempts-?),completed_at=IF(?='COMPLETE',CURRENT_TIMESTAMP(6),completed_at) "
                + "WHERE operation_id=? AND claim_token=?", state,errorCode,delay,paused?1:0,state,claim.id(),claim.token());
    }

    record Claim(String id,String previousState,int attempts,String token) {
        boolean mayDispatch() { return "PENDING".equals(previousState) || "RETRYABLE".equals(previousState); }
    }
}
