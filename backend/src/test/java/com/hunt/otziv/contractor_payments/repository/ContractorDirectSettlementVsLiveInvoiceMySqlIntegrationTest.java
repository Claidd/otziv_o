package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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

/**
 * Database-model test for the critical section shared by a direct settlement and
 * LIVE invoice routing. The invoice deliberately holds its source mutex while
 * both transactions race for PHASE -> profile, matching the production lock
 * graph. The last available kopecks may be consumed once, never twice.
 */
@Testcontainers(disabledWithoutDocker = true)
class ContractorDirectSettlementVsLiveInvoiceMySqlIntegrationTest {

    private static final long LAST_AVAILABLE_KOPECKS = 100_000L;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    )
            .withDatabaseName("contractor_direct_vs_live_invoice")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transaction = repeatableReadTransaction(dataSource);

        jdbc.execute("DROP TABLE IF EXISTS contractor_routing_outcomes");
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_allocations");
        jdbc.execute("DROP TABLE IF EXISTS contractor_direct_settlements");
        jdbc.execute("DROP TABLE IF EXISTS payment_links");
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_profiles");
        jdbc.execute("DROP TABLE IF EXISTS contractor_payment_accounting_phase");

        jdbc.execute("""
                CREATE TABLE contractor_payment_accounting_phase (
                    id BIGINT NOT NULL,
                    mode VARCHAR(16) NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE contractor_payment_profiles (
                    id BIGINT NOT NULL,
                    opening_balance_kopecks BIGINT NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL,
                    route_recipient VARCHAR(24) NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE contractor_direct_settlements (
                    id BIGINT NOT NULL,
                    profile_id BIGINT NOT NULL,
                    amount_kopecks BIGINT NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE contractor_payment_allocations (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    recipient_profile_id BIGINT NOT NULL,
                    mode VARCHAR(16) NOT NULL,
                    source_type VARCHAR(32) NOT NULL,
                    source_id BIGINT NOT NULL,
                    amount_kopecks BIGINT NOT NULL,
                    confirmed_kopecks BIGINT NOT NULL,
                    returned_kopecks BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    PRIMARY KEY (id),
                    INDEX idx_capacity (recipient_profile_id, mode, id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE contractor_routing_outcomes (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    operation_name VARCHAR(32) NOT NULL,
                    contractor_accepted BOOLEAN NOT NULL,
                    outcome_code VARCHAR(48) NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);

        jdbc.update("INSERT INTO contractor_payment_accounting_phase (id, mode) VALUES (1, 'LIVE')");
        jdbc.update(
                "INSERT INTO contractor_payment_profiles (id, opening_balance_kopecks) VALUES (1, ?)",
                LAST_AVAILABLE_KOPECKS
        );
        jdbc.update("INSERT INTO payment_links (id, route_recipient) VALUES (201, NULL)");
    }

    @Test
    void directSettlementAndLiveInvoiceHaveExactlyOneWinnerWithoutDeadlockOrOversubscription()
            throws Exception {
        CountDownLatch invoiceSourceLocked = new CountDownLatch(1);
        CyclicBarrier startCapacityRace = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Attempt> invoice = CompletableFuture.supplyAsync(
                    () -> attemptLiveInvoice(invoiceSourceLocked, startCapacityRace),
                    executor
            );
            CompletableFuture<Attempt> direct = CompletableFuture.supplyAsync(() -> {
                await(invoiceSourceLocked, "LIVE invoice source lock");
                return attemptDirectSettlement(startCapacityRace);
            }, executor);

            CompletableFuture.allOf(direct, invoice).get(15, TimeUnit.SECONDS);
            List<Attempt> attempts = List.of(
                    direct.join(),
                    invoice.join()
            );

            assertThat(attempts).filteredOn(Attempt::accepted).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.accepted()).hasSize(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contractor_routing_outcomes",
                    Integer.class
            )).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contractor_routing_outcomes WHERE contractor_accepted = TRUE",
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contractor_payment_allocations",
                    Integer.class
            )).isEqualTo(1);
            assertThat(totalCapacityExposure()).isEqualTo(LAST_AVAILABLE_KOPECKS);

            Attempt directAttempt = attempts.stream()
                    .filter(attempt -> attempt.operation() == Operation.DIRECT_SETTLEMENT)
                    .findFirst()
                    .orElseThrow();
            Attempt invoiceAttempt = attempts.stream()
                    .filter(attempt -> attempt.operation() == Operation.LIVE_INVOICE)
                    .findFirst()
                    .orElseThrow();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contractor_direct_settlements",
                    Integer.class
            )).isEqualTo(directAttempt.accepted() ? 1 : 0);
            assertThat(jdbc.queryForObject(
                    "SELECT route_recipient FROM payment_links WHERE id = 201",
                    String.class
            )).isEqualTo(invoiceAttempt.accepted() ? "CONTRACTOR" : "OWNER");
        } finally {
            executor.shutdownNow();
        }
    }

    private Attempt attemptLiveInvoice(
            CountDownLatch invoiceSourceLocked,
            CyclicBarrier startCapacityRace
    ) {
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.queryForObject(
                    "SELECT id FROM payment_links WHERE id = 201 FOR UPDATE",
                    Long.class
            );
            invoiceSourceLocked.countDown();
            await(startCapacityRace, "capacity race start");

            lockPhaseThenProfile();
            if (availableKopecks() < LAST_AVAILABLE_KOPECKS) {
                jdbc.update("UPDATE payment_links SET route_recipient = 'OWNER' WHERE id = 201");
                recordOutcome(Operation.LIVE_INVOICE, false, "OWNER_FALLBACK");
                return new Attempt(Operation.LIVE_INVOICE, false);
            }
            jdbc.update("UPDATE payment_links SET route_recipient = 'CONTRACTOR' WHERE id = 201");
            insertAllocation("PAYMENT_LINK", 201L, 0L, "RESERVED");
            recordOutcome(Operation.LIVE_INVOICE, true, "CONTRACTOR_RESERVED");
            return new Attempt(Operation.LIVE_INVOICE, true);
        }));
    }

    private Attempt attemptDirectSettlement(CyclicBarrier startCapacityRace) {
        return Objects.requireNonNull(transaction.execute(status -> {
            await(startCapacityRace, "capacity race start");
            lockPhaseThenProfile();
            if (availableKopecks() < LAST_AVAILABLE_KOPECKS) {
                recordOutcome(Operation.DIRECT_SETTLEMENT, false, "INSUFFICIENT_CAPACITY");
                return new Attempt(Operation.DIRECT_SETTLEMENT, false);
            }
            jdbc.update("""
                    INSERT INTO contractor_direct_settlements (id, profile_id, amount_kopecks)
                    VALUES (101, 1, ?)
                    """, LAST_AVAILABLE_KOPECKS);
            insertAllocation(
                    "DIRECT_SETTLEMENT",
                    101L,
                    LAST_AVAILABLE_KOPECKS,
                    "CONFIRMED"
            );
            recordOutcome(Operation.DIRECT_SETTLEMENT, true, "DIRECT_CONFIRMED");
            return new Attempt(Operation.DIRECT_SETTLEMENT, true);
        }));
    }

    /** Both production paths share this exact critical lock order. */
    private void lockPhaseThenProfile() {
        String mode = jdbc.queryForObject(
                "SELECT mode FROM contractor_payment_accounting_phase WHERE id = 1 FOR UPDATE",
                String.class
        );
        if (!"LIVE".equals(mode)) {
            throw new IllegalStateException("Accounting phase unexpectedly changed");
        }
        jdbc.queryForObject(
                "SELECT id FROM contractor_payment_profiles WHERE id = 1 FOR UPDATE",
                Long.class
        );
    }

    private long availableKopecks() {
        long accrued = jdbc.queryForObject(
                "SELECT opening_balance_kopecks FROM contractor_payment_profiles WHERE id = 1",
                Long.class
        );
        CapacityTotals totals = jdbc.queryForObject("""
                SELECT
                    COALESCE(SUM(GREATEST(0, confirmed_kopecks - returned_kopecks)), 0),
                    COALESCE(SUM(
                        CASE
                            WHEN status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                                THEN GREATEST(
                                    0,
                                    amount_kopecks
                                        - GREATEST(0, confirmed_kopecks - returned_kopecks)
                                )
                            ELSE 0
                        END
                    ), 0)
                FROM contractor_payment_allocations
                WHERE recipient_profile_id = 1
                  AND mode = 'LIVE'
                FOR UPDATE
                """, (resultSet, rowNumber) -> new CapacityTotals(
                resultSet.getLong(1),
                resultSet.getLong(2)
        ));
        long netPaid = Math.max(0L, totals.confirmedKopecks());
        return Math.max(0L, accrued - netPaid - totals.outstandingKopecks());
    }

    private void insertAllocation(
            String sourceType,
            long sourceId,
            long confirmedKopecks,
            String status
    ) {
        jdbc.update("""
                INSERT INTO contractor_payment_allocations (
                    recipient_profile_id,
                    mode,
                    source_type,
                    source_id,
                    amount_kopecks,
                    confirmed_kopecks,
                    returned_kopecks,
                    status
                ) VALUES (1, 'LIVE', ?, ?, ?, ?, 0, ?)
                """, sourceType, sourceId, LAST_AVAILABLE_KOPECKS, confirmedKopecks, status);
    }

    private void recordOutcome(Operation operation, boolean accepted, String outcomeCode) {
        jdbc.update("""
                INSERT INTO contractor_routing_outcomes (
                    operation_name,
                    contractor_accepted,
                    outcome_code
                ) VALUES (?, ?, ?)
                """, operation.name(), accepted, outcomeCode);
    }

    private long totalCapacityExposure() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    GREATEST(0, confirmed_kopecks - returned_kopecks)
                    + CASE
                        WHEN status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                            THEN GREATEST(
                                0,
                                amount_kopecks
                                    - GREATEST(0, confirmed_kopecks - returned_kopecks)
                            )
                        ELSE 0
                    END
                ), 0)
                FROM contractor_payment_allocations
                WHERE recipient_profile_id = 1
                  AND mode = 'LIVE'
                """, Long.class);
    }

    private TransactionTemplate repeatableReadTransaction(DataSource dataSource) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    private void await(CountDownLatch latch, String checkpoint) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + checkpoint);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + checkpoint, exception);
        }
    }

    private void await(CyclicBarrier barrier, String checkpoint) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed while waiting for " + checkpoint, exception);
        }
    }

    private enum Operation {
        DIRECT_SETTLEMENT,
        LIVE_INVOICE
    }

    private record Attempt(Operation operation, boolean accepted) {
    }

    private record CapacityTotals(long confirmedKopecks, long outstandingKopecks) {
    }
}
