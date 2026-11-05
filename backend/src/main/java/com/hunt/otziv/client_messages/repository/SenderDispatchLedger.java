package com.hunt.otziv.client_messages.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Per sending-account dispatch ledger shared by the autoresponder family and offer campaigns. */
@Repository
@RequiredArgsConstructor
public class SenderDispatchLedger {

    public static final String SENT = "SENT";
    public static final String WAITING = "WAITING";

    private final NamedParameterJdbcTemplate jdbc;

    /** Latest timestamp per (event, lane) for one account; keys look like {@code SENT:CAMPAIGN}. */
    public Map<String, LocalDateTime> latest(String account) {
        Map<String, LocalDateTime> latest = new java.util.HashMap<>();
        jdbc.query("""
                SELECT event, lane, MAX(occurred_at) AS occurred_at FROM client_message_sender_dispatches
                WHERE sender_account = :account
                GROUP BY event, lane
                """, Map.of("account", account), row -> {
            Timestamp at = row.getTimestamp("occurred_at");
            if (at != null) latest.put(row.getString("event") + ":" + row.getString("lane"), at.toLocalDateTime());
        });
        return latest;
    }

    public void record(String account, String lane, String event, LocalDateTime occurredAt) {
        jdbc.update("""
                INSERT INTO client_message_sender_dispatches (sender_account, lane, event, occurred_at)
                VALUES (:account, :lane, :event, :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("account", account)
                .addValue("lane", lane)
                .addValue("event", event)
                .addValue("occurredAt", Timestamp.valueOf(occurredAt)));
    }

    public int purgeOlderThan(LocalDateTime cutoff) {
        return jdbc.update("""
                DELETE FROM client_message_sender_dispatches
                WHERE occurred_at < :cutoff
                ORDER BY occurred_at
                LIMIT 500
                """, Map.of("cutoff", Timestamp.valueOf(cutoff)));
    }
}
