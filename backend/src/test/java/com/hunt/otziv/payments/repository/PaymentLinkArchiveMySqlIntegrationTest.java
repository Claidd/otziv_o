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

    @Test
    void allLiveAndArchiveContractorRouteConstraintsRejectPlaintextCommentSnapshots() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_links (
                    id,
                    order_id,
                    status,
                    payment_method,
                    manual_source,
                    manual_comment,
                    receipt_status,
                    payment_success_notification_retry_eligible,
                    created_at,
                    updated_at
                ) VALUES (?, ?, 'WAITING_MANUAL_PAYMENT', 'MANUAL_MOBILE_BANK',
                          'CONTRACTOR_PAYMENT_PROFILE', 'Комментарий с персональными данными',
                          'PENDING', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, LINK_ID, ORDER_ID))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO archive_payment_links (
                    id, manual_source, manual_comment
                ) VALUES (?, 'CONTRACTOR_PAYMENT_PROFILE', 'Архивный персональный комментарий')
                """, LINK_ID + 1))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO common_invoices (
                    invoice_id, payment_route_manual_source, payment_route_manual_comment
                ) VALUES (?, 'CONTRACTOR_PAYMENT_PROFILE', 'Комментарий общего счёта')
                """, LINK_ID + 2))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO archive_common_invoices (
                    invoice_id, payment_route_manual_source, payment_route_manual_comment
                ) VALUES (?, 'CONTRACTOR_PAYMENT_PROFILE', 'Архивный комментарий общего счёта')
                """, LINK_ID + 3))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO common_invoices (
                    invoice_id, payment_route_manual_source, payment_route_instruction_text
                ) VALUES (?, 'CONTRACTOR_PAYMENT_PROFILE', 'Перевести по персональным реквизитам')
                """, LINK_ID + 4))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO archive_common_invoices (
                    invoice_id, payment_route_manual_source, payment_route_instruction_text
                ) VALUES (?, 'CONTRACTOR_PAYMENT_PROFILE', 'Архивная инструкция с реквизитами')
                """, LINK_ID + 5))
                .isInstanceOf(Exception.class);
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
        setup.execute("DROP TABLE IF EXISTS contractor_payment_allocation_events");
        setup.execute("DROP TABLE IF EXISTS archive_common_invoices");
        setup.execute("DROP TABLE IF EXISTS common_invoices");
        setup.execute("DROP TABLE IF EXISTS archive_payment_links");
        setup.execute("DROP TABLE IF EXISTS payment_links");
        setup.execute("DROP TABLE IF EXISTS contractor_payment_allocations");
        setup.execute("DROP TABLE IF EXISTS orders");
        setup.execute("""
                CREATE TABLE orders (
                    order_id BIGINT NOT NULL,
                    PRIMARY KEY (order_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE contractor_payment_allocations (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    mode VARCHAR(16) NOT NULL,
                    source_type VARCHAR(24) NOT NULL,
                    source_id BIGINT NOT NULL,
                    source_generation_snapshot VARCHAR(36) NULL,
                    attempt_no INT NOT NULL DEFAULT 1,
                    recipient_profile_id BIGINT NULL,
                    status VARCHAR(32) NOT NULL,
                    needs_return_amount BOOLEAN NOT NULL DEFAULT FALSE,
                    last_reconciled_at DATETIME(6) NULL,
                    reconcile_claim_token VARCHAR(36) NULL,
                    reconcile_lease_until DATETIME(6) NULL,
                    reconcile_next_retry_at DATETIME(6) NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (id),
                    CONSTRAINT uk_contractor_allocations_source_attempt
                        UNIQUE (mode, source_type, source_id, attempt_no),
                    KEY idx_contractor_allocations_source_generation
                        (mode, source_type, source_id, source_generation_snapshot),
                    KEY idx_contractor_allocations_reconcile
                        (mode, source_type, status, reconcile_next_retry_at,
                         reconcile_lease_until, last_reconciled_at, id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL,
                    order_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    payment_method VARCHAR(32) NULL,
                    manual_source VARCHAR(32) NULL,
                    manual_phone VARCHAR(32) NULL,
                    manual_recipient_name VARCHAR(160) NULL,
                    manual_bank_name VARCHAR(120) NULL,
                    manual_comment VARCHAR(255) NULL,
                    contractor_allocation_id BIGINT NULL,
                    shadow_route_generation VARCHAR(36) NULL,
                    shadow_route_order_id BIGINT NULL,
                    shadow_route_worker_id BIGINT NULL,
                    shadow_route_worker_user_id BIGINT NULL,
                    shadow_route_manager_id BIGINT NULL,
                    shadow_route_manager_user_id BIGINT NULL,
                    shadow_route_amount_kopecks BIGINT NULL,
                    shadow_route_company_routing_allowed BOOLEAN NOT NULL DEFAULT TRUE,
                    shadow_route_prepared_at DATETIME(6) NULL,
                    contractor_evidence_original_link_id BIGINT NULL,
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
                    CONSTRAINT uk_payment_links_contractor_allocation
                        UNIQUE (contractor_allocation_id),
                    CONSTRAINT fk_test_payment_links_order
                        FOREIGN KEY (order_id) REFERENCES orders (order_id),
                    CONSTRAINT fk_payment_links_contractor_allocation
                        FOREIGN KEY (contractor_allocation_id)
                        REFERENCES contractor_payment_allocations (id),
                    CONSTRAINT ck_payment_links_contractor_pii_blank CHECK (
                        COALESCE(manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        OR (
                            COALESCE(TRIM(manual_phone), '') = ''
                            AND COALESCE(TRIM(manual_recipient_name), '') = ''
                            AND COALESCE(TRIM(manual_comment), '') = ''
                        )
                    ),
                    KEY idx_payment_links_order (order_id),
                    KEY idx_payment_links_contractor_shadow_backfill (created_at, id),
                    KEY idx_payment_links_shadow_route_generation (shadow_route_generation),
                    KEY idx_payment_links_contractor_evidence_original
                        (contractor_evidence_original_link_id, id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE contractor_payment_allocation_events (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    allocation_id BIGINT NOT NULL,
                    event_type VARCHAR(32) NOT NULL,
                    amount_kopecks BIGINT NOT NULL DEFAULT 0,
                    status_before VARCHAR(32) NULL,
                    status_after VARCHAR(32) NULL,
                    effective_at DATETIME(6) NOT NULL,
                    observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    reason VARCHAR(255) NULL,
                    external_ref VARCHAR(160) NOT NULL,
                    actor VARCHAR(150) NOT NULL DEFAULT 'system',
                    PRIMARY KEY (id),
                    CONSTRAINT uk_contractor_allocation_event_ref
                        UNIQUE (allocation_id, external_ref),
                    CONSTRAINT fk_contractor_allocation_event_allocation
                        FOREIGN KEY (allocation_id)
                        REFERENCES contractor_payment_allocations (id),
                    KEY idx_contractor_allocation_event_profile_stats
                        (event_type, effective_at, allocation_id),
                    KEY idx_contractor_allocation_event_allocation_time
                        (allocation_id, effective_at, id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE archive_payment_links (
                    id BIGINT NOT NULL,
                    manual_source VARCHAR(32) NULL,
                    manual_phone VARCHAR(32) NULL,
                    manual_recipient_name VARCHAR(160) NULL,
                    manual_comment VARCHAR(255) NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT ck_archive_payment_links_contractor_pii_blank CHECK (
                        COALESCE(manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        OR (
                            COALESCE(TRIM(manual_phone), '') = ''
                            AND COALESCE(TRIM(manual_recipient_name), '') = ''
                            AND COALESCE(TRIM(manual_comment), '') = ''
                        )
                    )
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE common_invoices (
                    invoice_id BIGINT NOT NULL,
                    payment_route_manual_source VARCHAR(32) NULL,
                    payment_route_manual_phone VARCHAR(32) NULL,
                    payment_route_manual_recipient VARCHAR(160) NULL,
                    payment_route_manual_comment VARCHAR(255) NULL,
                    payment_route_instruction_text VARCHAR(1000) NULL,
                    PRIMARY KEY (invoice_id),
                    CONSTRAINT ck_common_invoices_contractor_pii_blank CHECK (
                        COALESCE(payment_route_manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        OR (
                            COALESCE(TRIM(payment_route_manual_phone), '') = ''
                            AND COALESCE(TRIM(payment_route_manual_recipient), '') = ''
                            AND COALESCE(TRIM(payment_route_manual_comment), '') = ''
                            AND COALESCE(TRIM(payment_route_instruction_text), '') = ''
                        )
                    )
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE archive_common_invoices (
                    invoice_id BIGINT NOT NULL,
                    payment_route_manual_source VARCHAR(32) NULL,
                    payment_route_manual_phone VARCHAR(32) NULL,
                    payment_route_manual_recipient VARCHAR(160) NULL,
                    payment_route_manual_comment VARCHAR(255) NULL,
                    payment_route_instruction_text VARCHAR(1000) NULL,
                    PRIMARY KEY (invoice_id),
                    CONSTRAINT ck_archive_common_invoices_contractor_pii_blank CHECK (
                        COALESCE(payment_route_manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
                        OR (
                            COALESCE(TRIM(payment_route_manual_phone), '') = ''
                            AND COALESCE(TRIM(payment_route_manual_recipient), '') = ''
                            AND COALESCE(TRIM(payment_route_manual_comment), '') = ''
                            AND COALESCE(TRIM(payment_route_instruction_text), '') = ''
                        )
                    )
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
