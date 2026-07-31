package com.hunt.otziv.notification_media.service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ThematicNotificationDispatchStore {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";

    private final NamedParameterJdbcTemplate jdbc;

    public boolean claim(String eventCode, long recipientUserId, LocalDate date, int maxPerDay) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventCode", eventCode)
                .addValue("recipientUserId", recipientUserId)
                .addValue("dispatchDate", Date.valueOf(date));
        Integer sentToday = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM thematic_notification_dispatches
                WHERE recipient_user_id = :recipientUserId
                  AND dispatch_date = :dispatchDate
                  AND status IN ('PENDING', 'SENT')
                """, params, Integer.class);
        if (sentToday != null && sentToday >= Math.max(1, maxPerDay)) {
            return false;
        }
        return jdbc.update("""
                INSERT IGNORE INTO thematic_notification_dispatches (
                    event_code,
                    recipient_user_id,
                    dispatch_date,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (
                    :eventCode,
                    :recipientUserId,
                    :dispatchDate,
                    'PENDING',
                    NOW(6),
                    NOW(6)
                )
                """, params) > 0;
    }

    public void markSent(String eventCode, long recipientUserId, LocalDate date) {
        jdbc.update("""
                UPDATE thematic_notification_dispatches
                SET status = 'SENT',
                    sent_at = :sentAt,
                    updated_at = :sentAt
                WHERE event_code = :eventCode
                  AND recipient_user_id = :recipientUserId
                  AND dispatch_date = :dispatchDate
                  AND status = 'PENDING'
                """, dispatchParams(eventCode, recipientUserId, date)
                .addValue("sentAt", LocalDateTime.now()));
    }

    public void release(String eventCode, long recipientUserId, LocalDate date) {
        jdbc.update("""
                DELETE FROM thematic_notification_dispatches
                WHERE event_code = :eventCode
                  AND recipient_user_id = :recipientUserId
                  AND dispatch_date = :dispatchDate
                  AND status = 'PENDING'
                """, dispatchParams(eventCode, recipientUserId, date));
    }

    public Map<Long, Long> activePublicationCounts(Collection<Long> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("""
                SELECT worker_id, COUNT(*) AS active_count
                FROM worker_work_item_lifecycle
                WHERE worker_id IN (:workerIds)
                  AND active = b'1'
                  AND excluded = b'0'
                  AND section_code = 'publish'
                GROUP BY worker_id
                """, new MapSqlParameterSource("workerIds", workerIds), (rs, rowNum) ->
                Map.entry(rs.getLong("worker_id"), rs.getLong("active_count"))
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void deleteBefore(LocalDate cutoff) {
        jdbc.update("""
                DELETE FROM thematic_notification_dispatches
                WHERE dispatch_date < :cutoff
                """, new MapSqlParameterSource("cutoff", Date.valueOf(cutoff)));
    }

    private MapSqlParameterSource dispatchParams(String eventCode, long recipientUserId, LocalDate date) {
        return new MapSqlParameterSource()
                .addValue("eventCode", eventCode)
                .addValue("recipientUserId", recipientUserId)
                .addValue("dispatchDate", Date.valueOf(date));
    }
}
