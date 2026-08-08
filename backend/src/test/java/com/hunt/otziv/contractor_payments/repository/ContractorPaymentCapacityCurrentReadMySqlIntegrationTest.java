package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ContractorPaymentCapacityCurrentReadMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    )
            .withDatabaseName("contractor_capacity_current_read")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transaction = repeatableReadTransaction(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_allocations");
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_profiles");
        jdbc.execute("""
                CREATE TABLE contractor_payment_profiles (
                    id BIGINT NOT NULL,
                    opening_balance_kopecks BIGINT NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE contractor_payment_allocations (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    recipient_profile_id BIGINT NOT NULL,
                    mode VARCHAR(16) NOT NULL,
                    amount_kopecks BIGINT NOT NULL,
                    confirmed_kopecks BIGINT NOT NULL,
                    returned_kopecks BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    PRIMARY KEY (id),
                    INDEX idx_capacity (recipient_profile_id, mode, id)
                ) ENGINE=InnoDB
                """);
        jdbc.update(
                "INSERT INTO contractor_payment_profiles (id, opening_balance_kopecks) VALUES (1, 100000)"
        );
    }

    @Test
    void waiterSeesReservationCommittedWhileItWasWaitingForProfileMutex() throws Exception {
        CountDownLatch profileLocked = new CountDownLatch(1);
        CountDownLatch staleSnapshotCreated = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> firstRoute = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject(
                        "SELECT id FROM contractor_payment_profiles WHERE id = 1 FOR UPDATE",
                        Long.class
                );
                profileLocked.countDown();
                await(staleSnapshotCreated);
                jdbc.update("""
                        INSERT INTO contractor_payment_allocations (
                            recipient_profile_id,
                            mode,
                            amount_kopecks,
                            confirmed_kopecks,
                            returned_kopecks,
                            status
                        ) VALUES (1, 'LIVE', 100000, 0, 0, 'RESERVED')
                        """);
            }), executor);

            CompletableFuture<CapacityObservation> secondRoute = CompletableFuture.supplyAsync(() -> {
                await(profileLocked);
                return transaction.execute(status -> {
                    // Establish the stale REPEATABLE READ snapshot before the
                    // first route commits, exactly as unrelated plain reads in
                    // a real invoice transaction can do.
                    int staleRows = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM contractor_payment_allocations",
                            Integer.class
                    );
                    staleSnapshotCreated.countDown();

                    jdbc.queryForObject(
                            "SELECT id FROM contractor_payment_profiles WHERE id = 1 FOR UPDATE",
                            Long.class
                    );
                    long currentOutstanding = jdbc.queryForObject("""
                            SELECT COALESCE(SUM(
                                GREATEST(
                                    0,
                                    amount_kopecks
                                        - GREATEST(0, confirmed_kopecks - returned_kopecks)
                                )
                            ), 0)
                            FROM contractor_payment_allocations
                            WHERE recipient_profile_id = 1
                              AND mode = 'LIVE'
                              AND status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                            FOR UPDATE
                            """, Long.class);
                    return new CapacityObservation(
                            staleRows,
                            currentOutstanding,
                            Math.max(0L, 100_000L - currentOutstanding)
                    );
                });
            }, executor);

            firstRoute.get(10, TimeUnit.SECONDS);
            CapacityObservation observation = secondRoute.get(10, TimeUnit.SECONDS);

            assertThat(observation.staleSnapshotRows()).isZero();
            assertThat(observation.currentOutstandingKopecks()).isEqualTo(100_000L);
            assertThat(observation.availableKopecks()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private TransactionTemplate repeatableReadTransaction(DataSource dataSource) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Capacity concurrency checkpoint timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Capacity concurrency check interrupted", exception);
        }
    }

    private record CapacityObservation(
            int staleSnapshotRows,
            long currentOutstandingKopecks,
            long availableKopecks
    ) {
    }
}
