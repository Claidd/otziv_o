package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
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

@Testcontainers
class PaymentSuccessNotificationRetryClaimMySqlIntegrationTest {

    private static final long LINK_ID = 42L;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("payment_notification_claim_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private PaymentSuccessNotificationRetryClaimRepository repository;
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
        repository = new PaymentSuccessNotificationRetryClaimRepository(
                new NamedParameterJdbcTemplate(dataSource)
        );
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("""
                INSERT INTO payment_links (
                    id,
                    status,
                    payment_success_notification_retry_eligible
                ) VALUES (?, 'CONFIRMED', 1)
                """, LINK_ID);
    }

    @Test
    void concurrentReplicasCannotOwnTheSameActiveLease() throws Exception {
        String firstToken = UUID.randomUUID().toString();
        String secondToken = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Boolean> first = claimAsync(
                    executor,
                    start,
                    firstToken,
                    "node-a"
            );
            CompletableFuture<Boolean> second = claimAsync(
                    executor,
                    start,
                    secondToken,
                    "node-b"
            );
            start.countDown();

            boolean firstOwned = first.get(10, TimeUnit.SECONDS);
            boolean secondOwned = second.get(10, TimeUnit.SECONDS);

            assertThat(firstOwned ^ secondOwned).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT processing_token
                    FROM payment_success_notification_retry_claims
                    WHERE payment_link_id = ?
                    """, String.class, LINK_ID))
                    .isEqualTo(firstOwned ? firstToken : secondToken);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredCrashLeaseIsReclaimedAndOldOwnerIsFenced() {
        String crashedToken = UUID.randomUUID().toString();
        String recoveryToken = UUID.randomUUID().toString();
        assertThat(claim(crashedToken, "crashed-node")).isTrue();
        jdbc.update("""
                UPDATE payment_success_notification_retry_claims
                SET processing_started_at = TIMESTAMPADD(SECOND, -2, CURRENT_TIMESTAMP(6)),
                    processing_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE payment_link_id = ?
                """, LINK_ID);

        assertThat(claim(recoveryToken, "recovery-node")).isTrue();
        assertThat(finalizeSuccess(crashedToken)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT payment_success_notified_at IS NULL
                FROM payment_links
                WHERE id = ?
                """, Boolean.class, LINK_ID)).isTrue();

        assertThat(finalizeSuccess(recoveryToken)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT payment_success_notified_at IS NOT NULL
                FROM payment_links
                WHERE id = ?
                """, Boolean.class, LINK_ID)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT row_version FROM payment_links WHERE id = ?",
                Long.class,
                LINK_ID
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_success_notification_retry_claims",
                Integer.class
        )).isZero();
    }

    @Test
    void expiredOwnerCannotFinalizeBeforeAnotherReplicaReclaims() {
        String expiredToken = UUID.randomUUID().toString();
        assertThat(claim(expiredToken, "expired-node")).isTrue();
        jdbc.update("""
                UPDATE payment_success_notification_retry_claims
                SET processing_started_at = TIMESTAMPADD(SECOND, -2, CURRENT_TIMESTAMP(6)),
                    processing_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE payment_link_id = ?
                """, LINK_ID);

        assertThat(finalizeSuccess(expiredToken)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT payment_success_notified_at IS NULL
                FROM payment_links
                WHERE id = ?
                """, Boolean.class, LINK_ID)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_success_notification_retry_claims",
                Integer.class
        )).isEqualTo(1);
    }

    private CompletableFuture<Boolean> claimAsync(
            ExecutorService executor,
            CountDownLatch start,
            String token,
            String owner
    ) {
        return CompletableFuture.supplyAsync(() -> {
            await(start);
            return claim(token, owner);
        }, executor);
    }

    private boolean claim(String token, String owner) {
        return inTransaction(() -> repository.lockRetryEligiblePaymentLink(LINK_ID)
                && repository.tryAcquire(
                        LINK_ID,
                        token,
                        owner,
                        Duration.ofMinutes(2)
                ));
    }

    private boolean finalizeSuccess(String token) {
        return inTransaction(() -> {
            if (!repository.lockPaymentLinkForFinalization(LINK_ID)
                    || !repository.lockOwnedClaim(LINK_ID, token)) {
                return false;
            }
            boolean updated = repository.markSucceeded(LINK_ID);
            repository.release(LINK_ID, token);
            return updated;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent claim start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent claim interrupted", exception);
        }
    }

    private void initializeSchema(DataSource dataSource) {
        JdbcTemplate setup = new JdbcTemplate(dataSource);
        setup.execute("DROP TABLE IF EXISTS payment_success_notification_retry_claims");
        setup.execute("DROP TABLE IF EXISTS payment_links");
        setup.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    payment_success_notified_at DATETIME(6) NULL,
                    payment_success_notification_error VARCHAR(512) NULL,
                    payment_success_notification_retry_eligible TINYINT(1) NOT NULL DEFAULT 0,
                    row_version BIGINT NOT NULL DEFAULT 0,
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_198__payment_success_notification_retry_claims.sql"
        )).execute(dataSource);
    }
}
