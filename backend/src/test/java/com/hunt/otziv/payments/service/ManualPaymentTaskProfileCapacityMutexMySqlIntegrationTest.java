package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ManualPaymentTaskProfileCapacityMutexMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("manual_task_capacity_mutex")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        executor = Executors.newFixedThreadPool(2);
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_profiles");
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_accounting_phase");
        jdbc.execute("""
                CREATE TABLE contractor_payment_accounting_phase (
                    id INT NOT NULL,
                    phase VARCHAR(16) NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.update("""
                INSERT INTO contractor_payment_accounting_phase (id, phase)
                VALUES (1, 'SHADOW')
                """);
        jdbc.execute("""
                CREATE TABLE contractor_payment_profiles (
                    id BIGINT NOT NULL,
                    capacity_position_kopecks BIGINT NOT NULL,
                    manual_task_commitment_kopecks BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.update("""
                INSERT INTO contractor_payment_profiles
                    (id, capacity_position_kopecks, manual_task_commitment_kopecks)
                VALUES (7, 100000, 0)
                """);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentTasksOnSameProfileSerializeAndCannotBothPromiseFullCapacity() throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<Boolean> first = executor.submit(() -> tryCommit(70_000L, firstLocked, releaseFirst));
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> second = executor.submit(() -> tryCommit(
                70_000L, new CountDownLatch(0), new CountDownLatch(0)));
        Thread.sleep(Duration.ofMillis(150));
        assertThat(second.isDone()).as("second writer waits on the same profile mutex").isFalse();

        releaseFirst.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT manual_task_commitment_kopecks
                FROM contractor_payment_profiles
                WHERE id = 7
                """, Long.class)).isEqualTo(70_000L);
    }

    @Test
    void promotionAndConcurrentTaskSettlementSerializeOnPhaseMutex() throws Exception {
        CountDownLatch promotionLocked = new CountDownLatch(1);
        CountDownLatch finishPromotion = new CountDownLatch(1);
        Future<String> promotion = executor.submit(() -> transaction.execute(status -> {
            String phase = jdbc.queryForObject("""
                    SELECT phase FROM contractor_payment_accounting_phase
                    WHERE id = 1 FOR UPDATE
                    """, String.class);
            promotionLocked.countDown();
            await(finishPromotion);
            jdbc.update("""
                    UPDATE contractor_payment_accounting_phase SET phase = 'LIVE' WHERE id = 1
                    """);
            return phase;
        }));
        assertThat(promotionLocked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<String> settlement = executor.submit(() -> transaction.execute(status ->
                jdbc.queryForObject("""
                        SELECT phase FROM contractor_payment_accounting_phase
                        WHERE id = 1 FOR UPDATE
                        """, String.class)));
        Thread.sleep(150L);
        assertThat(settlement.isDone())
                .as("settlement cannot cross an in-flight phase promotion")
                .isFalse();

        finishPromotion.countDown();
        assertThat(promotion.get(10, TimeUnit.SECONDS)).isEqualTo("SHADOW");
        assertThat(settlement.get(10, TimeUnit.SECONDS)).isEqualTo("LIVE");
    }

    private boolean tryCommit(
            long proposedCommitment,
            CountDownLatch locked,
            CountDownLatch continueCommit
    ) {
        Boolean accepted = transaction.execute(status -> {
            var state = jdbc.queryForMap("""
                    SELECT capacity_position_kopecks, manual_task_commitment_kopecks
                    FROM contractor_payment_profiles
                    WHERE id = 7
                    FOR UPDATE
                    """);
            locked.countDown();
            await(continueCommit);
            long capacity = ((Number) state.get("capacity_position_kopecks")).longValue();
            long committed = ((Number) state.get("manual_task_commitment_kopecks")).longValue();
            if (proposedCommitment > capacity - committed) {
                return false;
            }
            assertThat(jdbc.update("""
                    UPDATE contractor_payment_profiles
                    SET manual_task_commitment_kopecks = manual_task_commitment_kopecks + ?
                    WHERE id = 7
                    """, proposedCommitment)).isEqualTo(1);
            return true;
        });
        return Boolean.TRUE.equals(accepted);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for capacity test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
