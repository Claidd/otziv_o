package com.hunt.otziv.admin.repository;

import com.hunt.otziv.admin.repository.BotImportOriginRepository.Origin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class BotImportOriginMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("bot_import_test").withUsername("root").withPassword("root");

    private JdbcTemplate jdbc;
    private BotImportOriginRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new BotImportOriginRepository(new NamedParameterJdbcTemplate(dataSource));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        for (String table : List.of("bot_import_origins", "bot_import_lock", "bots", "business_audit_events")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE bots (bot_id BIGINT PRIMARY KEY, bot_login VARCHAR(45)) DEFAULT CHARSET=utf8mb4");
        jdbc.execute("""
                CREATE TABLE business_audit_events (entity_type VARCHAR(40), entity_id VARCHAR(80),
                    action VARCHAR(80), details TEXT, created_at DATETIME(6))
                    DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbc.update("INSERT INTO bots VALUES (1, 'Legacy'), (2, 'unknown'), (3, ' LEGACY ')");
        jdbc.update("""
                INSERT INTO business_audit_events VALUES
                    ('bot', '1', 'bot_active_changed', 'bot import initial active value', '2026-08-01 10:30:00'),
                    ('bot', '1', 'bot_active_changed', 'manual update', '2026-07-01 10:00:00'),
                    ('bot', '2', 'bot_active_changed', 'manual update', '2026-07-01 10:00:00')
                """);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1_10_321__bot_import_origins.sql"));
        }
    }

    @Test
    void migrationBackfillsOnlyExplicitImportDatesAndKeepsUnknownFilesNull() {
        assertThat(repository.findOrigins(List.of("legacy"))).containsExactly(
                new Origin("legacy", 1L, LocalDateTime.of(2026, 8, 1, 10, 30), null, null));
        assertThat(repository.findOrigins(List.of("unknown"))).containsExactly(
                new Origin("unknown", 2L, null, null, null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_import_origins", Integer.class)).isEqualTo(2);
    }

    @Test
    void provenanceSurvivesDeletionAndRollsBackWithFailedImport() {
        var origin = new Origin("new", 10L, LocalDateTime.of(2026, 9, 13, 12, 0), "Поставка.xlsx", 8);
        transaction.executeWithoutResult(status -> {
            repository.lockImports();
            jdbc.update("INSERT INTO bots VALUES (10, 'new')");
            repository.save(origin);
        });
        jdbc.update("DELETE FROM bots WHERE bot_id=10");
        assertThat(repository.findOrigins(List.of("new"))).containsExactly(origin);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            repository.lockImports();
            repository.save(new Origin("failed", 11L, null, "failed.csv", 1));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findOrigins(List.of("failed"))).isEmpty();
    }

    @Test
    void findsManualAccountsUsingSameNormalizationWithoutMergingDifferentAccents() {
        jdbc.update("INSERT INTO bots VALUES (10, ' Mixed '), (11, 'café')");
        assertThat(repository.findExistingAccounts(List.of("mixed", "cafe")))
                .containsExactly(new Origin("mixed", 10L, null, null, null));
    }

    @Test
    void simultaneousImportsSeeTheFirstCommittedOriginAfterAcquiringLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempting = new CountDownLatch(1);
        var origin = new Origin("shared", 20L, LocalDateTime.of(2026, 9, 13, 12, 0), "first.csv", 1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                repository.lockImports();
                repository.save(origin);
                entered.countDown();
                await(release);
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> transaction.execute(status -> {
                    secondAttempting.countDown();
                    repository.lockImports();
                    return repository.findOrigins(List.of("shared"));
                }));
                assertThat(secondAttempting.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.isDone()).isFalse();
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                assertThat(second.get(10, TimeUnit.SECONDS)).containsExactly(origin);
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
