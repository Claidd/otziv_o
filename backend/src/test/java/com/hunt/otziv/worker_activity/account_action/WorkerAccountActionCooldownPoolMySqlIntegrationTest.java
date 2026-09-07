package com.hunt.otziv.worker_activity.account_action;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkerAccountActionCooldownPoolMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("worker_account_action_cooldown_pool").withUsername("root").withPassword("root");

    private HikariDataSource businessPool;
    private WorkerAccountActionCooldownConnectionPool cooldownPool;
    private JdbcTemplate businessJdbc;
    private DataSourceTransactionManager businessTransactions;
    private WorkerAccountActionCooldownRepository cooldown;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setPoolName("cooldown-business-test");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        businessPool = new HikariDataSource(config);
        businessJdbc = new JdbcTemplate(businessPool);
        businessTransactions = new DataSourceTransactionManager(businessPool);
        businessJdbc.execute("DROP TABLE IF EXISTS worker_account_action_cooldowns");
        businessJdbc.execute("DROP TABLE IF EXISTS app_settings");
        businessJdbc.execute("DROP TABLE IF EXISTS users");
        businessJdbc.execute("DROP TABLE IF EXISTS cooldown_business_rows");
        businessJdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(255) NOT NULL UNIQUE) ENGINE=InnoDB");
        businessJdbc.execute("CREATE TABLE app_settings (setting_key VARCHAR(255) PRIMARY KEY, setting_value VARCHAR(255), updated_at DATETIME(6))");
        businessJdbc.execute("CREATE TABLE cooldown_business_rows (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        businessJdbc.update("INSERT INTO users (id, username) VALUES (1, 'first'), (2, 'second')");
        businessJdbc.update("INSERT INTO cooldown_business_rows (id) VALUES (1), (2)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_10_286__worker_account_action_cooldown.sql"),
                new ClassPathResource("db/migration/V1_10_287__worker_account_action_cooldown_enabled.sql"))
                .execute(businessPool);

        // Exercise the production pool-derivation path, rather than an unpooled substitute.
        cooldownPool = new WorkerAccountActionCooldownConnectionPool(businessPool);
        cooldown = new WorkerAccountActionCooldownRepository(cooldownPool);
    }

    @AfterEach
    void closePools() throws Exception {
        if (cooldownPool != null) cooldownPool.close();
        if (businessPool != null) businessPool.close();
    }

    @Test
    void fullBusinessPoolStillAllowsConcurrentAdmissionAndStatusForSameSpecialist() throws Exception {
        List<CompletedAction> results = runWhileBothBusinessConnectionsAreHeld("first", "first");

        assertThat(results.stream().filter(result -> result.admission().accepted()).count()).isEqualTo(1);
        assertThat(results.get(0).admission().state().availableAt())
                .isEqualTo(results.get(1).admission().state().availableAt());
        for (CompletedAction result : results) {
            assertThat(result.status().availableAt()).isEqualTo(result.admission().state().availableAt());
            assertThat(result.status().durationSeconds()).isEqualTo(60);
            assertThat(result.status().remainingSeconds()).isBetween(1, 60);
        }
    }

    @Test
    void fullBusinessPoolStillReadsFreshPolicyAndAdmitsDifferentSpecialistsIndependently() throws Exception {
        assertThat(cooldown.currentState("first").durationSeconds()).isEqualTo(60);
        businessJdbc.update("UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'worker.account-action.cooldown-seconds'");

        List<CompletedAction> results = runWhileBothBusinessConnectionsAreHeld("first", "second");

        assertThat(results).allSatisfy(result -> {
            assertThat(result.admission().accepted()).isTrue();
            assertThat(result.admission().state().durationSeconds()).isEqualTo(180);
            assertThat(result.status().durationSeconds()).isEqualTo(180);
            assertThat(result.status().remainingSeconds()).isBetween(1, 180);
        });
    }

    @Test
    void fullBusinessPoolCanReadDisabledFlagWithoutLosingConfiguredDurationOrExistingDeadline() throws Exception {
        businessJdbc.update("UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'worker.account-action.cooldown-seconds'");
        var original = cooldown.admit("first");
        assertThat(original.accepted()).isTrue();
        businessJdbc.update("UPDATE app_settings SET setting_value = 'false' WHERE setting_key = 'worker.account-action.cooldown-enabled'");

        List<CompletedAction> results = runWhileBothBusinessConnectionsAreHeld("first", "first");

        assertThat(results).allSatisfy(result -> {
            assertThat(result.admission().accepted()).isTrue();
            for (var state : List.of(result.admission().state(), result.status())) {
                assertThat(state.enabled()).isFalse();
                assertThat(state.durationSeconds()).isEqualTo(180);
                assertThat(state.remainingSeconds()).isZero();
                assertThat(state.availableAt()).isNull();
            }
        });
        assertThat(businessJdbc.queryForObject("SELECT available_at_epoch_millis FROM worker_account_action_cooldowns WHERE worker_user_id = 1", Long.class))
                .isEqualTo(original.state().availableAt().toEpochMilli());
    }

    private List<CompletedAction> runWhileBothBusinessConnectionsAreHeld(String firstUser, String secondUser)
            throws Exception {
        CountDownLatch businessConnectionsHeld = new CountDownLatch(2);
        CountDownLatch startAdmission = new CountDownLatch(1);
        CountDownLatch releaseBusinessTransactions = new CountDownLatch(1);
        CompletableFuture<CompletedAction> firstCompleted = new CompletableFuture<>();
        CompletableFuture<CompletedAction> secondCompleted = new CompletableFuture<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstTransaction = executor.submit(() -> runBusinessTransaction(
                    1, firstUser, businessConnectionsHeld, startAdmission, releaseBusinessTransactions, firstCompleted));
            var secondTransaction = executor.submit(() -> runBusinessTransaction(
                    2, secondUser, businessConnectionsHeld, startAdmission, releaseBusinessTransactions, secondCompleted));
            try {
                assertThat(businessConnectionsHeld.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(businessPool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
                assertThat(businessPool.getHikariPoolMXBean().getIdleConnections()).isZero();
                startAdmission.countDown();

                List<CompletedAction> results = List.of(
                        firstCompleted.get(10, TimeUnit.SECONDS), secondCompleted.get(10, TimeUnit.SECONDS));
                // Both admission and fresh-policy status reads finished before either outer transaction released its connection.
                assertThat(businessPool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
                return results;
            } finally {
                startAdmission.countDown();
                releaseBusinessTransactions.countDown();
                firstTransaction.get(10, TimeUnit.SECONDS);
                secondTransaction.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private void runBusinessTransaction(int rowId, String username, CountDownLatch businessConnectionsHeld,
                                        CountDownLatch startAdmission, CountDownLatch releaseBusinessTransactions,
                                        CompletableFuture<CompletedAction> completed) {
        try {
            new TransactionTemplate(businessTransactions).execute(status -> {
                businessJdbc.queryForObject("SELECT id FROM cooldown_business_rows WHERE id = ? FOR UPDATE", Long.class, rowId);
                businessConnectionsHeld.countDown();
                await(startAdmission);
                var admission = cooldown.admit(username);
                var currentState = cooldown.currentState(username);
                completed.complete(new CompletedAction(admission, currentState));
                await(releaseBusinessTransactions);
                return null;
            });
        } catch (RuntimeException | Error failure) {
            completed.completeExceptionally(failure);
            throw failure;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent cooldown test did not release its barrier");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent cooldown test was interrupted", failure);
        }
    }

    private record CompletedAction(WorkerAccountActionCooldownRepository.Admission admission,
                                   WorkerAccountActionCooldownState status) {}
}
