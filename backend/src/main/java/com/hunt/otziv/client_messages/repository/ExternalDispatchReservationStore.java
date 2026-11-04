package com.hunt.otziv.client_messages.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reservations of background sends made outside the autoresponder state table. */
@Repository
@RequiredArgsConstructor
public class ExternalDispatchReservationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public void insert(String purpose, String sourceKind, long sourceId, String channel,
                       boolean countsDailyLimit, LocalDateTime reservedAt) {
        jdbc.update("""
                INSERT INTO client_message_external_dispatches
                    (purpose, source_kind, source_id, channel, counts_daily_limit, reserved_at)
                VALUES (:purpose, :sourceKind, :sourceId, :channel, :countsDailyLimit, :reservedAt)
                """, new MapSqlParameterSource()
                .addValue("purpose", purpose)
                .addValue("sourceKind", sourceKind)
                .addValue("sourceId", sourceId)
                .addValue("channel", channel)
                .addValue("countsDailyLimit", countsDailyLimit)
                .addValue("reservedAt", Timestamp.valueOf(reservedAt)));
    }

    public long countDailyLimitedSince(LocalDateTime since) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM client_message_external_dispatches
                WHERE counts_daily_limit = 1 AND reserved_at >= :since
                """, Map.of("since", Timestamp.valueOf(since)), Long.class);
        return count == null ? 0 : count;
    }

    public Optional<LocalDateTime> latestReservedAt(String channel) {
        List<Timestamp> latest = jdbc.queryForList("""
                SELECT MAX(reserved_at) FROM client_message_external_dispatches
                WHERE channel = :channel
                """, Map.of("channel", channel == null ? "ANY" : channel), Timestamp.class);
        return latest.stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toLocalDateTime);
    }

    public int purgeOlderThan(LocalDateTime cutoff) {
        return jdbc.update("""
                DELETE FROM client_message_external_dispatches
                WHERE reserved_at < :cutoff
                ORDER BY reserved_at
                LIMIT 500
                """, Map.of("cutoff", Timestamp.valueOf(cutoff)));
    }
}
