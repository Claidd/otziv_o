package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

@Testcontainers
class ManagerProblemDiagnosisStoreMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_problem_evidence").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private ManagerProblemDiagnosisStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource data = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(data);
        jdbc.execute("DROP TABLE IF EXISTS manager_problem_evidence_versions");
        jdbc.execute("DROP TABLE IF EXISTS manager_problem_cases");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_337__manager_problem_diagnostics.sql")).execute(data);
        store = new ManagerProblemDiagnosisStore(jdbc, new ObjectMapper(), new DataSourceTransactionManager(data));
    }

    @Test
    void concurrentDuplicateCaptureCreatesOneImmutableVersion() {
        var first = CompletableFuture.supplyAsync(() -> store.capture(evidence("COLLECTING", false)));
        var second = CompletableFuture.supplyAsync(() -> store.capture(evidence("COLLECTING", false)));
        assertThat(first.join().version()).isEqualTo(1);
        assertThat(second.join().version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_problem_evidence_versions", Integer.class)).isEqualTo(1);
    }

    @Test
    void changedFactsSupersedePendingWorkAndRejectItsLateResult() {
        store.capture(evidence("COLLECTING", false));
        var old = store.claim();
        var changed = store.capture(evidence("NEEDS_ATTENTION", false));
        assertThat(changed.version()).isEqualTo(2);
        assertThat(store.complete(old, diagnosis(), "deepseek-flash", 12, 34)).isFalse();
        assertThat(store.read(old.caseId(), 1).facts().getFirst().value()).isEqualTo("COLLECTING");
        assertThat(store.read(old.caseId(), 1).status()).isEqualTo("SUPERSEDED");
        assertThat(store.read(old.caseId(), 2).diagnosis()).isNull();
    }

    @Test
    void slowOldSnapshotCannotRollBackNewerFacts() {
        var old = evidence("COLLECTING", false);
        var stale = new ManagerProblemEvidenceCollector.Evidence(old.caseKey(), old.managerId(), old.sourceTaskId(), old.closed(), old.facts(), LocalDateTime.now().minusMinutes(1));
        var current = store.capture(evidence("NEEDS_ATTENTION", false));
        var result = store.capture(stale);
        assertThat(result.version()).isEqualTo(current.version());
        assertThat(result.facts().getFirst().value()).isEqualTo("NEEDS_ATTENTION");
    }

    @Test
    void indistinguishableObservationTimestampsCannotReplaceCurrentFacts() {
        var current = evidence("NEEDS_ATTENTION", false);
        var saved = store.capture(current);
        var oldFacts = evidence("COLLECTING", false);
        var sameTime = new ManagerProblemEvidenceCollector.Evidence(oldFacts.caseKey(), oldFacts.managerId(), oldFacts.sourceTaskId(), oldFacts.closed(), oldFacts.facts(), current.observedAt());
        assertThat(store.capture(sameTime).version()).isEqualTo(saved.version());
        assertThat(store.read(saved.caseId(), saved.version()).facts().getFirst().value()).isEqualTo("NEEDS_ATTENTION");
    }

    @Test
    void recheckingUnchangedFactsRefreshesFreshnessWithoutStarvingQueuedVersion() {
        var first = store.capture(evidence("COLLECTING", false));
        var different = evidence("READY", false);
        store.capture(new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:212", 2, 1000, false, different.facts(), LocalDateTime.now()));
        var refreshed = store.capture(evidence("COLLECTING", false));
        assertThat(refreshed.version()).isEqualTo(first.version());
        assertThat(refreshed.observedAt()).isAfter(first.observedAt());
        assertThat(store.claim().caseId()).isEqualTo(first.caseId());
    }

    @Test
    void expiredLeaseCanBeRecoveredButOldWorkerCannotPublish() {
        var captured = store.capture(evidence("COLLECTING", false));
        var expired = store.claim();
        jdbc.update("UPDATE manager_problem_evidence_versions SET lease_until=?", LocalDateTime.now().minusMinutes(1));
        var recovered = store.claim();
        assertThat(recovered.leaseToken()).isNotEqualTo(expired.leaseToken());
        assertThat(store.complete(expired, diagnosis(), "old", 1, 1)).isFalse();
        assertThat(store.complete(recovered, diagnosis(), "deepseek-flash", 10, 20)).isTrue();
        assertThat(store.read(captured.caseId(), 1).model()).isEqualTo("deepseek-flash");
        assertThat(store.claim()).isNull();
    }

    @Test
    void oneWorkerClaimsVersionAndFailureBacksOffWithoutLosingFacts() {
        store.capture(evidence("COLLECTING", false));
        var claim = store.claim();
        assertThat(store.claim()).isNull();
        store.retry(claim, "api_key=neverPersistMe");
        assertThat(store.claim()).isNull();
        var retried = store.read(claim.caseId(), claim.version());
        assertThat(retried.status()).isEqualTo("RETRY");
        assertThat(retried.facts()).isEqualTo(claim.facts());
        assertThat(jdbc.queryForObject("SELECT last_error FROM manager_problem_evidence_versions", String.class)).doesNotContain("neverPersistMe");
    }

    @Test
    void reopeningStartsNewEpisodeAndPreservesEarlierDiagnosis() {
        var initial = store.capture(evidence("COLLECTING", false));
        store.complete(store.claim(), diagnosis(), "deepseek-flash", 1, 2);
        store.capture(evidence("PAID", true));
        var reopened = store.capture(evidence("NEEDS_ATTENTION", false));
        assertThat(reopened.episode()).isEqualTo(2);
        assertThat(reopened.version()).isEqualTo(3);
        assertThat(store.read(initial.caseId(), 1).diagnosis()).isNotNull();
    }

    private static ManagerProblemEvidenceCollector.Evidence evidence(String status, boolean closed) {
        return new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 999, closed,
                List.of(new Fact("invoice:189:status", "invoice", "status", status)), LocalDateTime.now());
    }
    private static Diagnosis diagnosis() {
        return new Diagnosis("Сверить состояние", List.of(), List.of(), List.of(), List.of(), List.of("Проверить журнал"));
    }
}
