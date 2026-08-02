package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class PaymentLinkArchiveMySqlIntegrationTest {

    private static final long ORDER_ID = 17L;
    private static final long LINK_ID = 42L;
    private static final LocalDateTime PAID_CUTOFF = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime FINAL_CUTOFF = LocalDateTime.of(2026, 1, 2, 0, 0);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("payment_link_archive_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private PaymentLinkArchiveRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        initializeSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new PaymentLinkArchiveRepository(new NamedParameterJdbcTemplate(dataSource));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("INSERT INTO orders (order_id) VALUES (?)", ORDER_ID);
    }

    @Test
    void waitsForPaymentOrderLockThenRevalidatesSnapshotWithoutDeadlockOrDelete() throws Exception {
        insertOldLink(LINK_ID, "CONFIRMED");
        List<Long> snapshotIds = repository.findArchiveCandidateIds(PAID_CUTOFF, FINAL_CUTOFF, 10);
        assertThat(snapshotIds).containsExactly(LINK_ID);

        CountDownLatch paymentUpdatedAndHoldingLocks = new CountDownLatch(1);
        CountDownLatch releasePayment = new CountDownLatch(1);
        CountDownLatch archiveAboutToLockOrder = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> payment = CompletableFuture.runAsync(() -> transaction.execute(status -> {
                jdbc.queryForObject(
                        "SELECT order_id FROM orders WHERE order_id = ? FOR UPDATE",
                        Long.class,
                        ORDER_ID
                );
                jdbc.update("UPDATE payment_links SET status = 'INITIATED' WHERE id = ?", LINK_ID);
                paymentUpdatedAndHoldingLocks.countDown();
                await(releasePayment, "payment release");
                return null;
            }), executor);
            await(paymentUpdatedAndHoldingLocks, "payment transaction update");

            CompletableFuture<List<Long>> archive = CompletableFuture.supplyAsync(
                    () -> transaction.execute(status -> {
                        List<Long> orderIds = repository.findOrderIdsForPaymentLinkIds(snapshotIds);
                        archiveAboutToLockOrder.countDown();
                        assertThat(repository.lockOrderIdsForArchive(orderIds)).containsExactly(ORDER_ID);
                        return repository.findArchiveCandidateIdsForUpdate(
                                snapshotIds,
                                orderIds,
                                PAID_CUTOFF,
                                FINAL_CUTOFF
                        );
                    }),
                    executor
            );
            await(archiveAboutToLockOrder, "archive order-lock attempt");

            assertThatThrownBy(() -> archive.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releasePayment.countDown();

            payment.get(10, TimeUnit.SECONDS);
            assertThat(archive.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM payment_links WHERE id = ?",
                    String.class,
                    LINK_ID
            )).isEqualTo("INITIATED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_links WHERE id = ?",
                    Integer.class,
                    LINK_ID
            )).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM archive_payment_links WHERE id = ?",
                    Integer.class,
                    LINK_ID
            )).isZero();
        } finally {
            releasePayment.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void candidateQueryExcludesNonTerminalStatusesWithoutNoncesAndSelectsOldConfirmed() {
        List<String> blockedStatuses = List.of(
                "INITIATED",
                "AUTHORIZED",
                "AMOUNT_MISMATCH",
                "PARTIAL_REVERSED",
                "PARTIAL_REFUNDED",
                "WAITING_MANUAL_PAYMENT",
                "MANUAL_REPORTED"
        );
        long id = 100L;
        for (String status : blockedStatuses) {
            insertOldLink(id++, status);
        }
        long confirmedId = id;
        insertOldLink(confirmedId, "CONFIRMED");

        assertThat(repository.findArchiveCandidateIds(PAID_CUTOFF, FINAL_CUTOFF, 100))
                .containsExactly(confirmedId);
    }

    @Test
    void expiredClaimOnRefundedLinkIsCleanedAfterCanonicalLocksAndNoLongerBlocksDelete() {
        insertOldLink(LINK_ID, "REFUNDED");
        insertNotificationClaim(LINK_ID, false);

        List<Long> snapshotIds = repository.findArchiveCandidateIds(PAID_CUTOFF, FINAL_CUTOFF, 10);
        assertThat(snapshotIds).containsExactly(LINK_ID);
        List<Long> orderIds = repository.findOrderIdsForPaymentLinkIds(snapshotIds);

        transaction.execute(status -> {
            assertThat(repository.lockOrderIdsForArchive(orderIds)).containsExactly(ORDER_ID);
            List<Long> lockedIds = repository.findArchiveCandidateIdsForUpdate(
                    snapshotIds,
                    orderIds,
                    PAID_CUTOFF,
                    FINAL_CUTOFF
            );
            assertThat(lockedIds).containsExactly(LINK_ID);
            assertThat(repository.hasLiveArchiveBlockerForOrder(ORDER_ID)).isTrue();
            assertThat(repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(lockedIds))
                    .isEqualTo(1);
            assertThat(repository.hasLiveArchiveBlockerForOrder(ORDER_ID)).isFalse();
            jdbc.update("INSERT INTO archive_payment_links (id) VALUES (?)", LINK_ID);
            assertThat(repository.deleteLiveIds(lockedIds)).isEqualTo(1);
            return null;
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_success_notification_retry_claims WHERE payment_link_id = ?",
                Integer.class,
                LINK_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_links WHERE id = ?",
                Integer.class,
                LINK_ID
        )).isZero();
    }

    @Test
    void liveClaimRemainsADeleteBlockerEvenWhenRefundedLinkIsOtherwiseEligible() {
        insertOldLink(LINK_ID, "REFUNDED");
        insertNotificationClaim(LINK_ID, true);

        assertThat(repository.findArchiveCandidateIds(PAID_CUTOFF, FINAL_CUTOFF, 10)).isEmpty();
        transaction.execute(status -> {
            assertThat(repository.lockOrderIdsForArchive(List.of(ORDER_ID))).containsExactly(ORDER_ID);
            List<Long> lockedIds = repository.findLiveIdsByOrderIdForUpdate(ORDER_ID);
            assertThat(lockedIds).containsExactly(LINK_ID);
            assertThat(repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(lockedIds))
                    .isZero();
            assertThat(repository.hasLiveArchiveBlockerForOrder(ORDER_ID)).isTrue();
            jdbc.update("INSERT INTO archive_payment_links (id) VALUES (?)", LINK_ID);
            assertThat(repository.deleteLiveIds(lockedIds)).isZero();
            return null;
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_success_notification_retry_claims WHERE payment_link_id = ?",
                Integer.class,
                LINK_ID
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_links WHERE id = ?",
                Integer.class,
                LINK_ID
        )).isEqualTo(1);
    }

    @Test
    void expiredClaimForStillRetryableConfirmedLinkIsPreservedForReclaim() {
        insertOldLink(LINK_ID, "CONFIRMED");
        jdbc.update("""
                UPDATE payment_links
                SET payment_success_notification_retry_eligible = 1,
                    payment_success_notified_at = NULL
                WHERE id = ?
                """, LINK_ID);
        insertNotificationClaim(LINK_ID, false);

        assertThat(repository.findArchiveCandidateIds(PAID_CUTOFF, FINAL_CUTOFF, 10)).isEmpty();
        transaction.execute(status -> {
            assertThat(repository.lockOrderIdsForArchive(List.of(ORDER_ID))).containsExactly(ORDER_ID);
            List<Long> lockedIds = repository.findLiveIdsByOrderIdForUpdate(ORDER_ID);
            assertThat(repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(lockedIds))
                    .isZero();
            assertThat(repository.hasLiveArchiveBlockerForOrder(ORDER_ID)).isTrue();
            return null;
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_success_notification_retry_claims WHERE payment_link_id = ?",
                Integer.class,
                LINK_ID
        )).isEqualTo(1);
    }

    private void insertOldLink(long id, String status) {
        jdbc.update("""
                INSERT INTO payment_links (
                    id,
                    order_id,
                    status,
                    receipt_status,
                    payment_success_notification_retry_eligible,
                    created_at,
                    updated_at,
                    paid_at
                ) VALUES (?, ?, ?, 'DONE', 0, '2025-01-01 00:00:00.000000',
                          '2025-01-01 00:00:00.000000', '2025-01-01 00:00:00.000000')
                """, id, ORDER_ID, status);
    }

    private void insertNotificationClaim(long paymentLinkId, boolean live) {
        jdbc.update("""
                INSERT INTO payment_success_notification_retry_claims (
                    payment_link_id,
                    processing_token,
                    processing_owner,
                    processing_started_at,
                    processing_lease_until
                ) VALUES (
                    ?,
                    '00000000-0000-0000-0000-000000000042',
                    'test-node',
                    TIMESTAMPADD(SECOND, -2, CURRENT_TIMESTAMP(6)),
                    TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))
                )
                """, paymentLinkId, live ? 60 : -1);
    }

    private void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(operation + " timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", exception);
        }
    }

    private void initializeSchema(DataSource dataSource) {
        JdbcTemplate setup = new JdbcTemplate(dataSource);
        setup.execute("DROP TABLE IF EXISTS payment_success_notification_retry_claims");
        setup.execute("DROP TABLE IF EXISTS archive_payment_links");
        setup.execute("DROP TABLE IF EXISTS payment_links");
        setup.execute("DROP TABLE IF EXISTS orders");
        setup.execute("""
                CREATE TABLE orders (
                    order_id BIGINT NOT NULL,
                    PRIMARY KEY (order_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL,
                    order_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    receipt_status VARCHAR(32) NULL,
                    bank_init_nonce VARCHAR(64) NULL,
                    bank_cancel_nonce VARCHAR(64) NULL,
                    bank_cancel_origin_status VARCHAR(32) NULL,
                    payment_success_notified_at DATETIME(6) NULL,
                    payment_success_notification_retry_eligible TINYINT(1) NOT NULL DEFAULT 0,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    paid_at DATETIME(6) NULL,
                    PRIMARY KEY (id),
                    KEY idx_payment_links_order (order_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE archive_payment_links (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE payment_success_notification_retry_claims (
                    payment_link_id BIGINT NOT NULL,
                    processing_token CHAR(36) NOT NULL,
                    processing_owner VARCHAR(128) NOT NULL,
                    processing_started_at DATETIME(6) NOT NULL,
                    processing_lease_until DATETIME(6) NOT NULL,
                    PRIMARY KEY (payment_link_id),
                    CONSTRAINT fk_test_payment_notification_claim_link
                        FOREIGN KEY (payment_link_id) REFERENCES payment_links (id)
                        ON DELETE CASCADE,
                    CONSTRAINT ck_test_payment_notification_claim_lease
                        CHECK (processing_lease_until > processing_started_at)
                ) ENGINE=InnoDB
                """);
    }
}
