package com.hunt.otziv.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class IntegrationOutboxMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("outbox_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private IntegrationOutboxRepository repository;
    private IntegrationOutboxService service;
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
        repository = new IntegrationOutboxRepository(
                new NamedParameterJdbcTemplate(dataSource)
        );
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        IntegrationOutboxProperties properties = new IntegrationOutboxProperties();
        IntegrationOutboxMetrics metrics = new IntegrationOutboxMetrics(
                new SimpleMeterRegistry(),
                properties
        );
        service = new IntegrationOutboxService(
                repository,
                new IntegrationOutboxPayloadPolicy(new ObjectMapper(), properties),
                properties,
                metrics
        );
    }

    @Test
    void enqueueIsAtomicHashOnlyAndDeduplicated() {
        assertThatThrownBy(() -> service.enqueue(draft("dedup-1", "first")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.enqueue(draft("rolled-back", "not durable"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rowCount()).isZero();

        IntegrationOutboxService.EnqueueResult first = enqueue("dedup-1", "first");
        IntegrationOutboxService.EnqueueResult duplicate = enqueue("dedup-1", "first");

        assertThat(first.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.eventId()).isEqualTo(first.eventId());
        assertThat(rowCount()).isEqualTo(1);
        assertThatThrownBy(() -> enqueue("dedup-1", "changed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different payload");
        assertThat(jdbc.queryForObject(
                "SELECT OCTET_LENGTH(deduplication_key_hash) FROM integration_outbox",
                Integer.class
        )).isEqualTo(32);
        assertThat(jdbc.queryForObject(
                "SELECT payload FROM integration_outbox",
                String.class
        )).contains("first").doesNotContain("changed", "dedup-1");
    }

    @Test
    void duplicateDedupRejectsChangedAggregateVersion() {
        transaction.execute(status -> service.enqueue(draft("versioned", "same", 1L)));

        assertThatThrownBy(() -> transaction.execute(status ->
                service.enqueue(draft("versioned", "same", 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different event envelope");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void retrySuccessAndDeadTransitionsRequireCurrentProcessingToken() {
        enqueue("transition-1", "payload");
        IntegrationOutboxRepository.Claim first = claim(
                UUID.randomUUID().toString(),
                "node-a"
        );
        assertThat(first.attemptCount()).isEqualTo(1);

        assertThat(inTransaction(() -> repository.markRetry(
                first.outboxId(),
                UUID.randomUUID().toString(),
                100_000,
                "RETRYABLE"
        ))).isFalse();
        assertThat(status()).isEqualTo("PROCESSING");

        assertThat(inTransaction(() -> repository.markRetry(
                first.outboxId(),
                first.processingToken(),
                100_000,
                "RETRYABLE"
        ))).isTrue();
        assertThat(status()).isEqualTo("PENDING");
        jdbc.update("""
                UPDATE integration_outbox
                SET available_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                """);

        IntegrationOutboxRepository.Claim second = claim(
                UUID.randomUUID().toString(),
                "node-b"
        );
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.processingToken()).isNotEqualTo(first.processingToken());
        assertThat(inTransaction(() -> repository.markSucceeded(
                second.outboxId(),
                first.processingToken()
        ))).isFalse();
        assertThat(inTransaction(() -> repository.markSucceeded(
                second.outboxId(),
                second.processingToken()
        ))).isTrue();
        assertThat(status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredFinalAttemptIsBoundedlyRecoveredToDead() {
        IntegrationOutboxEventDraft draft = new IntegrationOutboxEventDraft(
                "order",
                "42",
                1L,
                "test.event",
                "final-attempt",
                Map.of("value", "payload"),
                1
        );
        transaction.execute(status -> service.enqueue(draft));
        claim(UUID.randomUUID().toString(), "node-a");
        jdbc.update("""
                UPDATE integration_outbox
                SET processing_started_at = TIMESTAMPADD(SECOND, -2, CURRENT_TIMESTAMP(6)),
                    processing_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                """);

        int changed = inTransaction(() -> repository.markExpiredExhaustedDead(
                10,
                Set.of("test.event"),
                "FINAL_ATTEMPT_PROCESSING_LEASE_EXPIRED"
        ));

        assertThat(changed).isEqualTo(1);
        assertThat(status()).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject(
                "SELECT processing_token IS NULL FROM integration_outbox",
                Boolean.class
        )).isTrue();
    }

    @Test
    void unknownEventTypeIsNeitherClaimedNorAttempted() {
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a",
                "future-type",
                "future.event",
                "payload",
                5
        )));

        Optional<IntegrationOutboxRepository.Claim> claim = transaction.execute(status ->
                repository.claimNext(
                        UUID.randomUUID().toString(),
                        "old-node",
                        30_000_000,
                        Set.of("test.event")
                ));

        assertThat(claim).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM integration_outbox",
                String.class
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM integration_outbox",
                Integer.class
        )).isZero();
    }

    @Test
    void unknownAggregateHeadBlocksKnownFollowerButNotOtherAggregates() {
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a", "future-head", "future.event", "future", 5
        )));
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a", "known-follower", "test.event", "blocked", 5
        )));
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-b", "known-independent", "test.event", "ready", 5
        )));

        IntegrationOutboxRepository.Claim claimed = claim(
                UUID.randomUUID().toString(),
                "old-node"
        );

        assertThat(claimed.aggregateId()).isEqualTo("order-b");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count
                FROM integration_outbox
                WHERE aggregate_id = 'order-a'
                  AND event_type = 'test.event'
                """, Integer.class)).isZero();
    }

    @Test
    void expiredFinalRecoveryDoesNotTouchUnknownEventType() {
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a",
                "future-final",
                "future.event",
                "payload",
                1
        )));
        IntegrationOutboxRepository.Claim claim = transaction.execute(status ->
                repository.claimNext(
                        UUID.randomUUID().toString(),
                        "new-node",
                        30_000_000,
                        Set.of("future.event")
                ).orElseThrow());
        jdbc.update("""
                UPDATE integration_outbox
                SET processing_started_at = TIMESTAMPADD(SECOND, -2, CURRENT_TIMESTAMP(6)),
                    processing_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE integration_outbox_id = ?
                """, claim.outboxId());

        int oldNodeChanged = inTransaction(() -> repository.markExpiredExhaustedDead(
                10,
                Set.of("test.event"),
                "FINAL_ATTEMPT_PROCESSING_LEASE_EXPIRED"
        ));

        assertThat(oldNodeChanged).isZero();
        assertThat(status()).isEqualTo("PROCESSING");
        assertThat(inTransaction(() -> repository.markExpiredExhaustedDead(
                10,
                Set.of("future.event"),
                "FINAL_ATTEMPT_PROCESSING_LEASE_EXPIRED"
        ))).isEqualTo(1);
        assertThat(status()).isEqualTo("DEAD");
    }

    @Test
    void aggregateHeadBlocksOvertakingWithoutStarvingOtherAggregates() {
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a", "a-1", "test.event", "first", 5
        )));
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a", "a-2", "test.event", "second", 5
        )));
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-b", "b-1", "test.event", "independent", 5
        )));

        IntegrationOutboxRepository.Claim firstA = claim(
                UUID.randomUUID().toString(),
                "node-a"
        );
        IntegrationOutboxRepository.Claim firstB = claim(
                UUID.randomUUID().toString(),
                "node-b"
        );
        assertThat(firstA.aggregateId()).isEqualTo("order-a");
        assertThat(firstB.aggregateId()).isEqualTo("order-b");
        assertThat(inTransaction(() -> repository.markSucceeded(
                firstB.outboxId(),
                firstB.processingToken()
        ))).isTrue();
        assertThat(inTransaction(() -> repository.markRetry(
                firstA.outboxId(),
                firstA.processingToken(),
                60_000_000,
                "RETRYABLE"
        ))).isTrue();

        Optional<IntegrationOutboxRepository.Claim> whileHeadIsDelayed = transaction.execute(status -> repository.claimNext(
                UUID.randomUUID().toString(),
                "node-c",
                30_000_000,
                Set.of("test.event")
        ));
        assertThat(whileHeadIsDelayed).isEmpty();

        jdbc.update("""
                UPDATE integration_outbox
                SET available_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE integration_outbox_id = ?
                """, firstA.outboxId());
        IntegrationOutboxRepository.Claim retriedA = claim(
                UUID.randomUUID().toString(),
                "node-a"
        );
        assertThat(retriedA.outboxId()).isEqualTo(firstA.outboxId());
        assertThat(inTransaction(() -> repository.markDead(
                retriedA.outboxId(),
                retriedA.processingToken(),
                "PERMANENT"
        ))).isTrue();

        Optional<IntegrationOutboxRepository.Claim> whileHeadIsDead = transaction.execute(status -> repository.claimNext(
                UUID.randomUUID().toString(),
                "node-c",
                30_000_000,
                Set.of("test.event")
        ));
        assertThat(whileHeadIsDead).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count
                FROM integration_outbox
                WHERE aggregate_id = 'order-a'
                  AND payload ->> '$.value' = 'second'
                """, Integer.class)).isZero();
    }

    @Test
    void skipLockedLetsTwoNodesClaimDifferentRows() throws Exception {
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a", "concurrent-1", "test.event", "one", 5
        )));
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-a", "concurrent-2", "test.event", "blocked-follower", 5
        )));
        transaction.execute(status -> service.enqueue(draftForAggregate(
                "order-b", "concurrent-3", "test.event", "independent", 5
        )));

        CountDownLatch firstRowLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<IntegrationOutboxRepository.Claim> first =
                    CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
                        IntegrationOutboxRepository.Claim claimed = repository.claimNext(
                                UUID.randomUUID().toString(),
                                "node-a",
                                30_000_000,
                                Set.of("test.event")
                        ).orElseThrow();
                        firstRowLocked.countDown();
                        await(releaseFirst);
                        return claimed;
                    }), executor);

            assertThat(firstRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<IntegrationOutboxRepository.Claim> second =
                    CompletableFuture.supplyAsync(() -> transaction.execute(status ->
                            repository.claimNext(
                                    UUID.randomUUID().toString(),
                                    "node-b",
                                    30_000_000,
                                    Set.of("test.event")
                            ).orElseThrow()
                    ), executor);

            IntegrationOutboxRepository.Claim secondClaim;
            try {
                secondClaim = second.get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
            IntegrationOutboxRepository.Claim firstClaim = first.get(5, TimeUnit.SECONDS);

            assertThat(firstClaim.aggregateId()).isEqualTo("order-a");
            assertThat(secondClaim.aggregateId()).isEqualTo("order-b");
            assertThat(secondClaim.outboxId()).isNotEqualTo(firstClaim.outboxId());
            assertThat(secondClaim.processingToken())
                    .isNotEqualTo(firstClaim.processingToken());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private IntegrationOutboxService.EnqueueResult enqueue(String dedupKey, String value) {
        return transaction.execute(status -> service.enqueue(draft(dedupKey, value)));
    }

    private IntegrationOutboxEventDraft draft(String dedupKey, String value) {
        return draft(dedupKey, value, 1L);
    }

    private IntegrationOutboxEventDraft draft(String dedupKey, String value, Long version) {
        return new IntegrationOutboxEventDraft(
                "order",
                "42",
                version,
                "test.event",
                dedupKey,
                Map.of("value", value),
                5
        );
    }

    private IntegrationOutboxEventDraft draftForAggregate(
            String aggregateId,
            String dedupKey,
            String eventType,
            String value,
            int maxAttempts
    ) {
        return new IntegrationOutboxEventDraft(
                "order",
                aggregateId,
                1L,
                eventType,
                dedupKey,
                Map.of("value", value),
                maxAttempts
        );
    }

    private IntegrationOutboxRepository.Claim claim(String token, String owner) {
        return transaction.execute(status -> repository.claimNext(
                token,
                owner,
                30_000_000,
                Set.of("test.event")
        ).orElseThrow());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        return transaction.execute(status -> operation.get());
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox", Integer.class);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM integration_outbox", String.class);
    }

    private void initializeSchema(DataSource dataSource) {
        JdbcTemplate setup = new JdbcTemplate(dataSource);
        setup.execute("DROP TABLE IF EXISTS integration_outbox");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_173__r2_integration_outbox.sql"
        )).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_195__integration_outbox_claim_indexes.sql"
        )).execute(dataSource);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent claim");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent claim");
        }
    }
}
