package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.DeliveryQueueHealth;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stored in the same transaction as the inbound receipt, before acknowledging the gateway. */
@Repository @RequiredArgsConstructor
public class WhatsAppUnlinkedEventStore implements DeliveryQueueHealth {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public void defer(WhatsAppGroupReplyDTO reply,String reason) {
        try {
            jdbc.update("""
                INSERT INTO whatsapp_unlinked_events(receipt_key,client_id,group_id,payload_json,reason)
                VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE reason=VALUES(reason)
                """,WhatsAppGroupWebhookDeduplicator.key(reply),reply.getClientId(),reply.getGroupId(),json.writeValueAsString(reply),reason);
        } catch(JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid inbound message",invalid); }
    }
    public void resolved(WhatsAppGroupReplyDTO reply) {
        jdbc.update("UPDATE whatsapp_unlinked_events SET completed_at=NOW(6) WHERE receipt_key=? AND completed_at IS NULL",
                WhatsAppGroupWebhookDeduplicator.key(reply));
    }
    public List<String> due() {
        return jdbc.queryForList("SELECT receipt_key FROM whatsapp_unlinked_events WHERE completed_at IS NULL AND next_attempt_at<=NOW(6) ORDER BY next_attempt_at,receipt_key LIMIT 20",String.class);
    }
    public WhatsAppGroupReplyDTO lockDue(String key) {
        var payloads=jdbc.query("SELECT payload_json FROM whatsapp_unlinked_events WHERE receipt_key=? AND completed_at IS NULL AND next_attempt_at<=NOW(6) FOR UPDATE",
                (rs,n)->rs.getString(1),key);
        if(payloads.isEmpty()) return null;
        jdbc.update("UPDATE whatsapp_unlinked_events SET attempts=LEAST(attempts+1,1000000),next_attempt_at=DATE_ADD(NOW(6),INTERVAL 30 MINUTE) WHERE receipt_key=?",key);
        try {return json.readValue(payloads.getFirst(),WhatsAppGroupReplyDTO.class);}
        catch(JsonProcessingException invalid) {throw new IllegalArgumentException("Invalid saved inbound message",invalid);}
    }
    public void postponeFailed(String key) {
        jdbc.update("UPDATE whatsapp_unlinked_events SET next_attempt_at=DATE_ADD(NOW(6),INTERVAL 30 MINUTE) WHERE receipt_key=? AND completed_at IS NULL AND next_attempt_at<=NOW(6)",key);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> unresolvedGroups() {
        return jdbc.queryForList("SELECT client_id,group_id,reason,COUNT(*) AS events,MIN(created_at) AS first_seen_at FROM whatsapp_unlinked_events WHERE completed_at IS NULL GROUP BY client_id,group_id,reason ORDER BY first_seen_at LIMIT 100");
    }
    @Override public String queueName() {return "whatsapp_unlinked";}
    @Override @Transactional(readOnly=true,timeout=3)
    public List<Row> deliveryQueueHealth() {
        return jdbc.query("SELECT COUNT(*),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),NOW(6))) FROM whatsapp_unlinked_events WHERE completed_at IS NULL",
                (rs,n)->new Row(State.QUEUED,rs.getLong(1),rs.getDouble(2),0));
    }
}
