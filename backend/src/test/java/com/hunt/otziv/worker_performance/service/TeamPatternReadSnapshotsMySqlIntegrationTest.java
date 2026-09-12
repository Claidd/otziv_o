package com.hunt.otziv.worker_performance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TeamPatternReadSnapshotsMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("team_patterns").withUsername("root").withPassword("local-only");
    static final LocalDate MONTH = LocalDate.of(2026, 8, 1), TO = MONTH.plusMonths(1);
    static final List<TeamPatternAnalysisService.WorkerPatternSubject> SUBJECTS = List.of(
            new TeamPatternAnalysisService.WorkerPatternSubject(1L, 101L, "one"),
            new TeamPatternAnalysisService.WorkerPatternSubject(2L, 102L, "two"));
    static final TeamPatternReadSnapshots.Facts FACTS = new TeamPatternReadSnapshots.Facts(20, 2, 1, 3, 4,
            List.of(new TeamPatternReadSnapshots.Day(MONTH, 20, 2, 1, 3)));
    JdbcTemplate jdbc;
    TeamPatternReadSnapshots snapshots;
    TransactionTemplate transaction;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS worker_team_pattern_snapshots");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_318__team_pattern_read_snapshots.sql")).execute(source);
        snapshots = new TeamPatternReadSnapshots(new NamedParameterJdbcTemplate(source),
                new ObjectMapper().findAndRegisterModules(), new SimpleMeterRegistry());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> snapshots.save(SUBJECTS, MONTH, MONTH, TO, snapshots.captureTime(),
                Map.of(101L, FACTS, 102L, FACTS)));
    }

    @Test void onlyCompleteFreshFactsForCurrentAuthorizedIdentitiesAreAccepted() {
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO).orElseThrow().byUserId())
                .containsExactlyInAnyOrderEntriesOf(Map.of(101L, FACTS, 102L, FACTS));
        assertThat(snapshots.fresh(List.of(SUBJECTS.getFirst()), MONTH, MONTH, TO).orElseThrow().byUserId())
                .containsOnlyKeys(101L);
        assertThat(snapshots.fresh(List.of(new TeamPatternAnalysisService.WorkerPatternSubject(1L, 999L, "changed")), MONTH, MONTH, TO)).isEmpty();
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH.plusDays(1), TO)).isEmpty();
        jdbc.update("DELETE FROM worker_team_pattern_snapshots WHERE worker_id=2");
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO)).isEmpty();
    }

    @Test void oldFutureAndInconsistentRowsFallBackToCanonicalReads() {
        jdbc.update("UPDATE worker_team_pattern_snapshots SET generated_at_utc=TIMESTAMPADD(SECOND,-61,UTC_TIMESTAMP(6)) WHERE worker_id=1");
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO)).isEmpty();
        jdbc.update("UPDATE worker_team_pattern_snapshots SET generated_at_utc=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(6)) WHERE worker_id=1");
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO)).isEmpty();
        jdbc.update("UPDATE worker_team_pattern_snapshots SET generated_at_utc=UTC_TIMESTAMP(6), payload=JSON_SET(payload,'$.publications',999) WHERE worker_id=1");
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO)).isEmpty();
        jdbc.update("UPDATE worker_team_pattern_snapshots SET payload=JSON_OBJECT('days','wrong-type') WHERE worker_id=1");
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO)).isEmpty();
    }

    @Test void failedTransactionCannotPublishPartialReplacementAndMissingTableFallsBack() {
        transaction.executeWithoutResult(status -> {
            var empty = new TeamPatternReadSnapshots.Facts(0,0,0,0,0,List.of());
            snapshots.save(SUBJECTS, MONTH, MONTH, TO, snapshots.captureTime(), Map.of(101L, empty, 102L, empty));
            status.setRollbackOnly();
        });
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO).orElseThrow().byUserId()).containsEntry(101L, FACTS);
        jdbc.execute("DROP TABLE worker_team_pattern_snapshots");
        assertThat(snapshots.fresh(SUBJECTS, MONTH, MONTH, TO)).isEmpty();
    }
}
