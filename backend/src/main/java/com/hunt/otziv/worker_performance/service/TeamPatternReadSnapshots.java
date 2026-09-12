package com.hunt.otziv.worker_performance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.worker_performance.service.TeamPatternAnalysisService.WorkerPatternSubject;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Per-worker historical aggregates only; authorization and team comparisons stay in the request. */
@Service
@RequiredArgsConstructor
public class TeamPatternReadSnapshots {
    public static final int MAX_AGE_SECONDS = 60;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    @Value("${otziv.projection.team-pattern.enabled:true}")
    private boolean enabled = true;

    public record Day(LocalDate date, long publications, long blockedAccounts, long recoveries, long networkEpisodes) {}
    public record Facts(long publications, long blockedAccounts, long recoveries, long networkEpisodes, long networkAttempts, List<Day> days) {}
    public record Batch(Map<Long, Facts> byUserId, Instant generatedAt) {}
    public boolean enabled() { return enabled; }

    @Transactional(readOnly = true)
    public Optional<Batch> fresh(List<WorkerPatternSubject> authorized, LocalDate month, LocalDate from, LocalDate to) {
        if (!enabled || authorized.isEmpty()) return Optional.empty();
        Map<Long, Long> allowed = new HashMap<>();
        authorized.forEach(subject -> allowed.put(subject.workerId(), subject.userId()));
        Map<Long, Facts> values = new HashMap<>();
        List<Instant> generated = new ArrayList<>();
        try {
            jdbc.query("""
                    SELECT worker_id, user_id, generated_at_utc, payload FROM worker_team_pattern_snapshots
                    WHERE worker_id IN (:ids) AND month_start = :month AND from_date = :from AND to_exclusive = :to
                      AND generated_at_utc >= TIMESTAMPADD(SECOND, -60, UTC_TIMESTAMP(6))
                      AND generated_at_utc <= UTC_TIMESTAMP(6)
                    """, new MapSqlParameterSource("ids", allowed.keySet()).addValue("month", month)
                    .addValue("from", from).addValue("to", to), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                long workerId = row.getLong("worker_id"), userId = row.getLong("user_id");
                if (!Objects.equals(allowed.get(workerId), userId)) return;
                try {
                    Facts facts = json.readValue(row.getString("payload"), Facts.class);
                    if (!valid(facts, from, to)) return;
                    values.put(userId, facts);
                    generated.add(row.getTimestamp("generated_at_utc").toLocalDateTime().toInstant(ZoneOffset.UTC));
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    // A broken derivative row never replaces the canonical calculation.
                }
            });
        } catch (org.springframework.dao.DataAccessException unavailable) {
            metrics.counter("otziv.projection.read", "projection", "team-pattern", "result", "unavailable").increment();
            return Optional.empty();
        }
        boolean complete = values.size() == authorized.size();
        metrics.counter("otziv.projection.read", "projection", "team-pattern", "result", complete ? "fresh" : "fallback").increment();
        return complete ? Optional.of(new Batch(Map.copyOf(values), generated.stream().min(Comparator.naturalOrder()).orElseThrow())) : Optional.empty();
    }

    private boolean valid(Facts facts, LocalDate from, LocalDate to) {
        if (facts == null || facts.days() == null || facts.days().size() > 31 || facts.networkAttempts() < 0) return false;
        long publications = 0, blocks = 0, recoveries = 0, network = 0;
        Set<LocalDate> dates = new HashSet<>();
        try {
            for (Day day : facts.days()) {
                if (day == null || day.date() == null || day.date().isBefore(from) || !day.date().isBefore(to)
                        || !dates.add(day.date()) || day.publications() < 0 || day.blockedAccounts() < 0 || day.recoveries() < 0 || day.networkEpisodes() < 0) return false;
                publications = Math.addExact(publications, day.publications()); blocks = Math.addExact(blocks, day.blockedAccounts());
                recoveries = Math.addExact(recoveries, day.recoveries()); network = Math.addExact(network, day.networkEpisodes());
            }
        } catch (ArithmeticException overflow) { return false; }
        return publications == facts.publications() && blocks == facts.blockedAccounts() && recoveries == facts.recoveries() && network == facts.networkEpisodes();
    }

    /** Capture before reading sources, using the same database clock used by freshness checks. */
    public LocalDateTime captureTime() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Map.of(), LocalDateTime.class); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(List<WorkerPatternSubject> subjects, LocalDate month, LocalDate from, LocalDate to,
                     LocalDateTime capturedAt, Map<Long, Facts> values) {
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (var subject : subjects) {
            Facts facts = values.get(subject.userId());
            if (!valid(facts, from, to)) throw new IllegalStateException("Invalid team pattern aggregate");
            try {
                batch.add(new MapSqlParameterSource("worker", subject.workerId()).addValue("user", subject.userId())
                        .addValue("month", month).addValue("from", from).addValue("to", to).addValue("generated", capturedAt)
                        .addValue("payload", json.writeValueAsString(facts)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Cannot encode team pattern aggregate", invalid); }
        }
        jdbc.batchUpdate("""
                INSERT INTO worker_team_pattern_snapshots(worker_id, month_start, user_id, from_date, to_exclusive, generated_at_utc, payload)
                VALUES (:worker, :month, :user, :from, :to, :generated, :payload)
                ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), from_date=VALUES(from_date), to_exclusive=VALUES(to_exclusive),
                    generated_at_utc=VALUES(generated_at_utc), payload=VALUES(payload)
                """, batch.toArray(MapSqlParameterSource[]::new));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanup(LocalDate oldestMonth) {
        jdbc.update("DELETE FROM worker_team_pattern_snapshots WHERE month_start < :oldest", Map.of("oldest", oldestMonth));
    }
}
