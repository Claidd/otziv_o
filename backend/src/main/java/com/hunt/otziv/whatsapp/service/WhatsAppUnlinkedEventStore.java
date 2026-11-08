package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.DeliveryQueueHealth;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        // Oldest message first: replaying out of order could reopen an already answered thread.
        return jdbc.queryForList("SELECT receipt_key FROM whatsapp_unlinked_events WHERE completed_at IS NULL AND next_attempt_at<=NOW(6) ORDER BY created_at,receipt_key LIMIT 20",String.class);
    }
    public WhatsAppGroupReplyDTO lockDue(String key) {
        var payloads=jdbc.query("SELECT payload_json FROM whatsapp_unlinked_events WHERE receipt_key=? AND completed_at IS NULL AND next_attempt_at<=NOW(6) FOR UPDATE",
                (rs,n)->rs.getString(1),key);
        if(payloads.isEmpty()) return null;
        // 30 min, 1 h, 2 h ... capped at a day: a group that never gets linked stops costing a full
        // company scan every half hour.
        jdbc.update("UPDATE whatsapp_unlinked_events SET attempts=LEAST(attempts+1,1000000),"
                + "next_attempt_at=TIMESTAMPADD(MINUTE,LEAST(1440,30*POW(2,LEAST(attempts,6))),NOW(6)) WHERE receipt_key=?",key);
        try {return json.readValue(payloads.getFirst(),WhatsAppGroupReplyDTO.class);}
        catch(JsonProcessingException invalid) {throw new IllegalArgumentException("Invalid saved inbound message",invalid);}
    }
    public void postponeFailed(String key) {
        jdbc.update("UPDATE whatsapp_unlinked_events SET next_attempt_at=DATE_ADD(NOW(6),INTERVAL 30 MINUTE) WHERE receipt_key=? AND completed_at IS NULL AND next_attempt_at<=NOW(6)",key);
    }
    /**
     * Inbound messages from groups that were never linked are personal data kept only while they
     * can still be attributed: after 30 days they are closed and their payload dropped; closed
     * rows are deleted after 90 days.
     */
    public int expireStale() {
        int expired=jdbc.update("""
            UPDATE whatsapp_unlinked_events SET completed_at=NOW(6),payload_json='{}',reason='expired'
            WHERE completed_at IS NULL AND created_at<DATE_SUB(NOW(6),INTERVAL 30 DAY)
            ORDER BY created_at LIMIT 500
            """);
        jdbc.update("DELETE FROM whatsapp_unlinked_events WHERE completed_at<DATE_SUB(NOW(6),INTERVAL 90 DAY) ORDER BY completed_at LIMIT 500");
        return expired;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> unresolvedGroups() {
        return jdbc.queryForList("SELECT client_id,group_id,reason,COUNT(*) AS events,MIN(created_at) AS first_seen_at FROM whatsapp_unlinked_events WHERE completed_at IS NULL GROUP BY client_id,group_id,reason ORDER BY first_seen_at LIMIT 100");
    }
    /**
     * A group gets at most one admin notice per pause: messages that arrive meanwhile are summed up in
     * the next notice instead of one notice each.
     */
    public static final int NOTICE_PAUSE_HOURS=4;
    public record NoticeGroup(String clientId,String groupId) {}
    /** The newest message not yet covered by a notice, how many such messages there are, the group's backlog. */
    public record NoticeBatch(WhatsAppGroupReplyDTO latest,String latestReason,LocalDateTime latestAt,long newEvents,
                              long openEvents,LocalDateTime firstSeenAt,LocalDateTime nextAttemptAt) {}

    /** Groups with open messages that no notice covered yet and that are past the group's pause, oldest first. */
    public List<NoticeGroup> noticeDueGroups(int limit) {
        return jdbc.query("""
            SELECT e.client_id,e.group_id FROM whatsapp_unlinked_events e
            WHERE e.completed_at IS NULL AND e.admin_notified_at IS NULL
              AND NOT EXISTS(SELECT 1 FROM whatsapp_unlinked_events n WHERE n.client_id=e.client_id AND n.group_id=e.group_id
                             AND n.admin_notified_at>DATE_SUB(NOW(6),INTERVAL ? HOUR))
            GROUP BY e.client_id,e.group_id ORDER BY MIN(e.created_at) LIMIT ?
            """,(rs,n)->new NoticeGroup(rs.getString(1),rs.getString(2)),NOTICE_PAUSE_HOURS,limit);
    }
    /** The batch the next notice of the group covers: its open messages that no notice covered yet. */
    public Optional<NoticeBatch> noticeBatch(NoticeGroup group) {return batch(group,true);}
    /** The group's newest open message whether or not a notice covered it, for a preview. */
    public Optional<NoticeBatch> latestBatch(NoticeGroup group) {return batch(group,false);}
    private Optional<NoticeBatch> batch(NoticeGroup group,boolean onlyNew) {
        var latest=jdbc.query("""
            SELECT payload_json,reason,created_at FROM whatsapp_unlinked_events
            WHERE client_id=? AND group_id=? AND completed_at IS NULL AND (admin_notified_at IS NULL OR ?=0)
            ORDER BY created_at DESC,receipt_key DESC LIMIT 1
            """,(rs,n)->new Latest(readSaved(rs.getString(1)),rs.getString(2),rs.getObject(3,LocalDateTime.class)),
                group.clientId(),group.groupId(),onlyNew?1:0);
        if(latest.isEmpty()) return Optional.empty();
        var row=latest.getFirst();
        return jdbc.query("""
            SELECT SUM(admin_notified_at IS NULL),COUNT(*),MIN(created_at),GREATEST(MIN(next_attempt_at),NOW(6))
            FROM whatsapp_unlinked_events WHERE client_id=? AND group_id=? AND completed_at IS NULL
            """,(rs,n)->new NoticeBatch(row.reply(),row.reason(),row.at(),rs.getLong(1),
                rs.getLong(2),rs.getObject(3,LocalDateTime.class),rs.getObject(4,LocalDateTime.class)),
                group.clientId(),group.groupId()).stream().findFirst();
    }
    private record Latest(WhatsAppGroupReplyDTO reply,String reason,LocalDateTime at) {}
    /** After an admin chat accepted the notice: it covers the group's messages up to the one it showed. */
    public int markNotified(NoticeGroup group,LocalDateTime through) {
        return jdbc.update("UPDATE whatsapp_unlinked_events SET admin_notified_at=NOW(6) WHERE client_id=? AND group_id=? AND admin_notified_at IS NULL AND created_at<=?",
                group.clientId(),group.groupId(),through);
    }
    /**
     * Open messages whose notice is due (the group is past its pause) and not delivered: the age counts
     * from the moment the notice became due, so a group waiting out its pause is not a delayed notice.
     */
    @Transactional(readOnly=true,timeout=3)
    public List<Row> noticeQueueHealth() {
        return jdbc.query("""
            SELECT COUNT(*),COALESCE(MAX(GREATEST(0,TIMESTAMPDIFF(SECOND,d.due_at,NOW(6)))),0) FROM (
              SELECT GREATEST(e.created_at,COALESCE(DATE_ADD(g.last_at,INTERVAL ? HOUR),e.created_at)) AS due_at
              FROM whatsapp_unlinked_events e
              LEFT JOIN (SELECT client_id,group_id,MAX(admin_notified_at) AS last_at FROM whatsapp_unlinked_events
                         WHERE admin_notified_at IS NOT NULL GROUP BY client_id,group_id) g
                ON g.client_id=e.client_id AND g.group_id=e.group_id
              WHERE e.completed_at IS NULL AND e.admin_notified_at IS NULL) d
            WHERE d.due_at<=NOW(6)
            """,(rs,n)->new Row(State.QUEUED,rs.getLong(1),rs.getDouble(2),0),NOTICE_PAUSE_HOURS);
    }
    private WhatsAppGroupReplyDTO readSaved(String payload) {
        try {return json.readValue(payload,WhatsAppGroupReplyDTO.class);}
        catch(JsonProcessingException invalid) {throw new IllegalArgumentException("Invalid saved inbound message",invalid);}
    }

    @Override public String queueName() {return "whatsapp_unlinked";}
    @Override @Transactional(readOnly=true,timeout=3)
    public List<Row> deliveryQueueHealth() {
        return jdbc.query("SELECT COUNT(*),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),NOW(6))) FROM whatsapp_unlinked_events WHERE completed_at IS NULL",
                (rs,n)->new Row(State.QUEUED,rs.getLong(1),rs.getDouble(2),0));
    }
}
