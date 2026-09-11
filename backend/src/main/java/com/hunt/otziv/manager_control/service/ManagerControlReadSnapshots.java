package com.hunt.otziv.manager_control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Management-owned projection, keyed by manager/date and the committed identity scope. */
@Service
public class ManagerControlReadSnapshots {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public ManagerControlReadSnapshots(NamedParameterJdbcTemplate jdbc, ObjectMapper json,
                                      io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc = jdbc; this.json = json; this.metrics = metrics;
    }
    public record Snapshot(ManagerControlManagerResponse response, LocalDateTime generatedAt) {}

    public long generation(Long managerId, LocalDate date) {
        return jdbc.query("SELECT generation FROM manager_control_read_snapshots WHERE manager_id=:id AND snapshot_date=:date",
                new MapSqlParameterSource("id",managerId).addValue("date",date), (row,index)->row.getLong(1)).stream().findFirst().orElse(0L);
    }

    @Transactional(readOnly = true)
    public Map<Long, Snapshot> fresh(Collection<Long> authorizedManagerIds, LocalDate date, String scope) {
        if (authorizedManagerIds.isEmpty()) return Map.of();
        Map<Long, Snapshot> result = new HashMap<>();
        Set<Long> present = new HashSet<>();
        jdbc.query("""
                SELECT manager_id, generated_at,
                  CASE WHEN access_scope = :scope AND generated_at >= TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP(6))
                       THEN payload ELSE NULL END AS payload,
                  CASE WHEN payload IS NULL OR generated_at IS NULL THEN 'invalidated'
                       WHEN access_scope IS NULL OR access_scope <> :scope THEN 'scope'
                       WHEN generated_at < TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP(6)) THEN 'expired'
                       ELSE 'fresh' END AS freshness
                FROM manager_control_read_snapshots WHERE manager_id IN (:ids) AND snapshot_date = :date
                """, new MapSqlParameterSource("ids", authorizedManagerIds).addValue("date", date).addValue("scope", scope),
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    present.add(row.getLong("manager_id"));
                    String freshness = row.getString("freshness");
                    metrics.counter("otziv.projection.read", "projection", "manager-control", "result", freshness).increment();
                    if (!"fresh".equals(freshness)) return;
                    try { result.put(row.getLong("manager_id"), new Snapshot(json.readValue(row.getString("payload"), ManagerControlManagerResponse.class), row.getTimestamp("generated_at").toLocalDateTime())); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid manager read projection", failure); }
                });
        long missing = authorizedManagerIds.stream().distinct().filter(id -> !present.contains(id)).count();
        if (missing > 0) metrics.counter("otziv.projection.read", "projection", "manager-control", "result", "absent").increment(missing);
        return result;
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void save(LocalDate date, String scope, long expectedGeneration, ManagerControlManagerResponse response) {
        try {
            jdbc.update("""
                    INSERT INTO manager_control_read_snapshots(manager_id,snapshot_date,generation,access_scope,payload,generated_at)
                    VALUES (:id,:date,:generation,:scope,:payload,CURRENT_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE
                      access_scope=IF(generation=:generation,VALUES(access_scope),access_scope),
                      payload=IF(generation=:generation,VALUES(payload),payload),
                      generated_at=IF(generation=:generation,VALUES(generated_at),generated_at)
                    """, new MapSqlParameterSource("id", response.managerId()).addValue("date", date)
                    .addValue("scope", scope).addValue("generation",expectedGeneration).addValue("payload", json.writeValueAsString(response)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Unable to encode manager projection", failure); }
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void invalidate(Collection<Long> managerIds) {
        for (Long id : managerIds) jdbc.update("""
                INSERT INTO manager_control_read_snapshots(manager_id,snapshot_date,generation)
                VALUES (:id,CURRENT_DATE,1)
                ON DUPLICATE KEY UPDATE generation=generation+1,payload=NULL,generated_at=NULL,access_scope=NULL
                """,new MapSqlParameterSource("id",id));
    }

    @Transactional public void cleanup() {
        jdbc.update("DELETE FROM manager_control_read_snapshots WHERE snapshot_date < CURRENT_DATE - INTERVAL 2 DAY", Map.of());
    }
}
