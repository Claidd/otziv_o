package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.LocalDateTime;
import java.time.LocalDate;
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
        jdbc.execute("DROP TABLE IF EXISTS manager_daily_control_concrete_items");
        jdbc.execute("DROP TABLE IF EXISTS manager_daily_controls");
        jdbc.execute("CREATE TABLE manager_daily_controls(daily_control_id BIGINT PRIMARY KEY,manager_id BIGINT,control_date DATE)");
        jdbc.execute("CREATE TABLE manager_daily_control_concrete_items(control_entity_id BIGINT PRIMARY KEY,control_id BIGINT,item_status VARCHAR(30))");
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

    @Test
    void upgradedPromptQueuesUnchangedFactsOnceWithoutOverwritingThePreviousAnalysis() {
        var first = store.capture(evidence("COLLECTING", false));
        store.complete(store.claim(), diagnosis(), "deepseek-flash", 1, 2);
        jdbc.update("UPDATE manager_problem_evidence_versions SET prompt_version='manager-problem-evidence-v2'");
        var upgraded = store.capture(evidence("COLLECTING", false));
        assertThat(upgraded.version()).isEqualTo(2);
        assertThat(upgraded.status()).isEqualTo("QUEUED");
        assertThat(upgraded.promptVersion()).isEqualTo(PROMPT_VERSION);
        assertThat(upgraded.facts()).isEqualTo(first.facts());
        assertThat(store.read(first.caseId(), 1).diagnosis()).isNotNull();
        assertThat(store.capture(evidence("COLLECTING", false)).version()).isEqualTo(2);
    }

    @Test
    void expiredWorkerCannotPublishEvenBeforeAnotherWorkerReclaimsItsLease() {
        store.capture(evidence("COLLECTING", false));
        var expired = store.claim();
        jdbc.update("UPDATE manager_problem_evidence_versions SET lease_until=?", LocalDateTime.now().minusMinutes(1));
        assertThat(store.complete(expired, diagnosis(), "deepseek-flash", 1, 1)).isFalse();
        assertThat(store.read(expired.caseId(), expired.version()).diagnosis()).isNull();
    }

    @Test
    void unavailableModelBacksOffButCanRecoverWithoutChangingTheEvidence() {
        var first = store.capture(evidence("COLLECTING", false));
        store.claim();
        jdbc.update("UPDATE manager_problem_evidence_versions SET attempt_count=5");
        var running = store.read(first.caseId(), 1);
        store.retry(running, "provider unavailable");
        assertThat(store.read(first.caseId(), 1).status()).isEqualTo("UNAVAILABLE");
        assertThat(store.claim()).isNull();
        jdbc.update("UPDATE manager_problem_evidence_versions SET next_attempt_at=?", LocalDateTime.now().minusSeconds(1));
        var recovered = store.claim();
        assertThat(recovered.attempts()).isEqualTo(6);
        assertThat(store.complete(recovered, diagnosis(), "deepseek-flash", 1, 2)).isTrue();
        assertThat(store.read(first.caseId(), 1).status()).isEqualTo("READY");
    }

    @Test
    void currentOpenInvoicePrecedesOlderBackfillAndManagersTakeTurns() {
        var historical = queue(2, 10, LocalDate.of(2026, 7, 16), "OPEN");
        var current2 = queue(2, 20, LocalDate.of(2026, 10, 23), "OPEN");
        var current2b = queue(2, 21, LocalDate.of(2026, 10, 23), "OPEN");
        var current3 = queue(3, 30, LocalDate.of(2026, 10, 23), "OPEN");
        var deferred = queue(3, 31, LocalDate.of(2026, 10, 23), "DEFERRED");

        var first = store.claim(true, 0);
        assertThat(first.caseId()).isEqualTo(current2.caseId());
        var second = store.claim(true, first.managerId());
        assertThat(second.caseId()).isEqualTo(current3.caseId());
        assertThat(store.claim(true, second.managerId()).caseId()).isEqualTo(current2b.caseId());
        // The background slot serves the oldest historical work, not a deferred/current preference.
        assertThat(store.claim(false, 2).caseId()).isEqualTo(historical.caseId());
        assertThat(store.claim(false, 2).caseId()).isEqualTo(deferred.caseId());
    }

    @Test
    void priorityDoesNotBypassRetryDueTimeOrAnalyzeClosedCases() {
        var closed = store.capture(new ManagerProblemEvidenceCollector.Evidence("closed", 2, 1, true,
                evidence("PAID", true).facts(), LocalDateTime.now()));
        var old = queue(2, 10, LocalDate.of(2026, 7, 16), "OPEN");
        var current = queue(2, 20, LocalDate.of(2026, 10, 23), "OPEN");
        jdbc.update("UPDATE manager_problem_evidence_versions SET status='RETRY',next_attempt_at=? WHERE case_id=?",
                LocalDateTime.now().plusHours(1), current.caseId());
        assertThat(store.claim(true, 0).caseId()).isEqualTo(old.caseId());
        assertThat(store.claim(true, 0)).isNull();
        assertThat(store.read(closed.caseId(), 1).status()).isEqualTo("QUEUED");
    }

    @Test
    void closedSourceCannotConsumeBackgroundBudgetWhenAfterCommitRefreshWasUnavailable() {
        var historical = queue(2, 10, LocalDate.of(2026, 7, 16), "OPEN");
        queue(2, 20, LocalDate.of(2026, 10, 23), "OPEN");
        jdbc.update("UPDATE manager_daily_control_concrete_items SET item_status='RESOLVED' WHERE control_entity_id=10");
        assertThat(store.claim(false, 0).caseId()).isNotEqualTo(historical.caseId());
        assertThat(store.claim(false, 0)).isNull();
        assertThat(store.read(historical.caseId(), 1).facts()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT closed FROM manager_problem_cases WHERE case_id=?", Boolean.class, historical.caseId())).isFalse();
    }

    @Test
    void receiptChecksKeepImmutableSnapshotsAndOriginalQueueAge() {
        var firstEvidence = receiptEvidence("2026-10-23T09:00:00");
        var first = store.capture(firstEvidence);
        LocalDateTime originalDue = LocalDateTime.now().minusHours(2).withNano(0);
        jdbc.update("UPDATE manager_problem_evidence_versions SET next_attempt_at=? WHERE case_id=?", originalDue, first.caseId());
        var competing = queue(3, 30, LocalDate.of(2026, 7, 16), "DEFERRED");
        var next = store.capture(receiptEvidence("2026-10-23T09:16:00"));
        var latest = store.capture(receiptEvidence("2026-10-23T09:32:00"));

        assertThat(latest.version()).isEqualTo(3);
        assertThat(store.read(first.caseId(), 1).facts()).isEqualTo(firstEvidence.facts());
        assertThat(store.read(first.caseId(), 1).status()).isEqualTo("SUPERSEDED");
        assertThat(store.read(next.caseId(), 2).status()).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM manager_problem_evidence_versions WHERE case_id=? AND evidence_version=3",
                LocalDateTime.class, first.caseId())).isEqualTo(originalDue);
        assertThat(store.claim(false, 0).version()).isEqualTo(3);
        assertThat(store.claim(false, 0).caseId()).isEqualTo(competing.caseId());
    }

    @Test
    void changedFactsPreserveRetryBackoff() {
        var first = store.capture(evidence("COLLECTING", false));
        var running = store.claim();
        store.retry(running, "provider unavailable");
        LocalDateTime due = jdbc.queryForObject("SELECT next_attempt_at FROM manager_problem_evidence_versions WHERE case_id=?", LocalDateTime.class, first.caseId());
        var changed = store.capture(evidence("NEEDS_ATTENTION", false));
        assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.facts()).isEqualTo(evidence("NEEDS_ATTENTION", false).facts());
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM manager_problem_evidence_versions WHERE case_id=? AND evidence_version=2",
                LocalDateTime.class, first.caseId())).isEqualTo(due);
        assertThat(store.claim(true, 0)).isNull();
    }

    private ManagerProblemDiagnosisStore.Stored queue(long manager, long source, LocalDate day, String status) {
        jdbc.update("INSERT INTO manager_daily_controls VALUES(?,?,?)", source, manager, day);
        jdbc.update("INSERT INTO manager_daily_control_concrete_items VALUES(?,?,?)", source, source, status);
        return store.capture(new ManagerProblemEvidenceCollector.Evidence(manager + ":COMMON_INVOICE:" + source, manager, source, false,
                List.of(new Fact("invoice:" + source + ":status", "invoice", "status", "COLLECTING")), LocalDateTime.now()));
    }

    private static ManagerProblemEvidenceCollector.Evidence receiptEvidence(String checkedAt) {
        return new ManagerProblemEvidenceCollector.Evidence("2:AUTOMATION_FAILURE:3938", 2, 999, false,
                List.of(new Fact("automation:3938:delivery_status", "automation", "delivery_status", "UNKNOWN"),
                        new Fact("automation:3938:delivery_recovery_checked_at", "automation", "delivery_recovery_checked_at", checkedAt)), LocalDateTime.now());
    }

    private static ManagerProblemEvidenceCollector.Evidence evidence(String status, boolean closed) {
        return new ManagerProblemEvidenceCollector.Evidence("2:COMMON_INVOICE:189", 2, 999, closed,
                List.of(new Fact("invoice:189:status", "invoice", "status", status)), LocalDateTime.now());
    }
    private static Diagnosis diagnosis() {
        return new Diagnosis("Сверить состояние", List.of(), List.of(), List.of(), List.of(), List.of("Проверить журнал"));
    }
}
