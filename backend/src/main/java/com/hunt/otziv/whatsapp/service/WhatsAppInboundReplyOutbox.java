package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

/** External replies are dispatched only after the inbound business transaction commits. */
@Service
@Slf4j
public class WhatsAppInboundReplyOutbox {
    private final JdbcTemplate jdbc;
    private final WhatsAppBusinessOperations operations;
    private final WhatsAppService transport;
    private final TransactionTemplate transaction;

    public WhatsAppInboundReplyOutbox(JdbcTemplate jdbc, WhatsAppBusinessOperations operations,
            WhatsAppService transport, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.operations = operations; this.transport = transport;
        this.transaction = new TransactionTemplate(manager);
    }

    @Transactional
    public void enqueue(String operationId, String clientId, String groupId, String message) {
        operations.freeze(operationId, clientId, "send-group", groupId, message);
        jdbc.update("INSERT INTO whatsapp_inbound_reply_outbox(operation_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE operation_id=operation_id", operationId);
    }

    @Scheduled(fixedDelayString = "${whatsapp.webhook.reply-outbox-delay-ms:5000}")
    public void dispatchDue() {
        for (int i = 0; i < 20; i++) {
            String id = transaction.execute(status -> {
                List<String> ids = jdbc.queryForList("SELECT operation_id FROM whatsapp_inbound_reply_outbox "
                        + "WHERE (state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(6)) "
                        + "OR (state='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6)) "
                        + "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", String.class);
                if (ids.isEmpty()) return null;
                String selected = ids.getFirst();
                jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET state='PROCESSING', attempts=attempts+1, "
                        + "lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE operation_id=?", selected);
                return selected;
            });
            if (id == null) return;
            try {
                var frozen = operations.requireFrozen(id);
                var result = WhatsAppSendResult.parse(transport.sendMessageToGroup(
                        frozen.clientId(), frozen.destination(), frozen.message(), frozen.operationId()));
                if (result.isOk()) {
                    jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET state='COMPLETE', completed_at=CURRENT_TIMESTAMP(6), "
                            + "lease_until=NULL WHERE operation_id=?", id);
                } else {
                    // UNKNOWN retains the same operation ID. The gateway ledger forbids
                    // another provider call after an ambiguous first dispatch.
                    retry(id);
                }
            } catch (RuntimeException error) {
                log.warn("WhatsApp inbound reply dispatch deferred: operationId={}, exceptionType={}", id, error.getClass().getSimpleName());
                retry(id);
            }
        }
    }

    private void retry(String id) {
        jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET state='PENDING', "
                + "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE), lease_until=NULL "
                + "WHERE operation_id=? AND state<>'COMPLETE'", id);
    }
}
