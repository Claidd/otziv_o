package com.hunt.otziv.performers.service;

import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class PerformerNotificationMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("performer_delivery").withUsername("root").withPassword("root");
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    PerformerNotificationRepository repository;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repository = new PerformerNotificationRepository(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS performer_notification_resolutions");
        jdbc.execute("DROP TABLE IF EXISTS performer_notification_intents");
        jdbc.execute("DROP TABLE IF EXISTS review_performer_offers");
        jdbc.execute("DROP TABLE IF EXISTS review_performer_assignments");
        jdbc.execute("""
                CREATE TABLE review_performer_assignments (assignment_id BIGINT PRIMARY KEY,
                    status VARCHAR(32), publish_available_at DATETIME(6))
                """);
        jdbc.execute("""
                CREATE TABLE review_performer_offers (offer_id BIGINT PRIMARY KEY, assignment_id BIGINT,
                    status VARCHAR(32), offered_at DATETIME(6), telegram_message_id INT)
                """);
        jdbc.update("INSERT INTO review_performer_assignments VALUES (1, 'WAITING_PUBLICATION', CURRENT_TIMESTAMP(6))");
        jdbc.update("INSERT INTO review_performer_offers VALUES (1, 1, 'OFFERED', CURRENT_TIMESTAMP(6), NULL)");
        jdbc.update("INSERT INTO review_performer_offers VALUES (2, 1, 'OFFERED', CURRENT_TIMESTAMP(6), 42)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_288__performer_scoped_delivery.sql")).execute(ds);
        migrateReadinessMarker();
    }

    @Test
    void migrationPreservesUnknownHistoryWithoutResendingOrInventingTimestamps() {
        assertThat(repository.unresolved(10)).hasSize(2);
        assertThat(repository.operationalSnapshot().legacyUnknown()).isEqualTo(2);
        assertThat(transaction.<java.util.Optional<PerformerNotificationRepository.Intent>>execute(status -> repository.claim(120))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM review_performer_offers WHERE offer_id=1", String.class))
                .isEqualTo("LEGACY_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM review_performer_offers WHERE offer_id=2", String.class))
                .isEqualTo("LEGACY_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(delivered_at) FROM review_performer_offers", Integer.class)).isZero();
    }

    @Test
    void activeOfferConstraintRequiresExplicitLegacyConflictResolution() {
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_289__single_active_performer_offer.sql"));
        assertThatThrownBy(() -> migration.execute(jdbc.getDataSource()))
                .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        // Test fixture represents a reviewed decision. Production migration never makes this choice.
        jdbc.update("UPDATE review_performer_offers SET status='SKIPPED' WHERE offer_id=2");
        migration.execute(jdbc.getDataSource());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO review_performer_offers(offer_id,assignment_id,status,offered_at)
                VALUES (3,1,'OFFERED',CURRENT_TIMESTAMP(6))
                """)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void rollbackRemovesIntentAndDuplicateGenerationIsOneRow() {
        seedAssignment(2, 1);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            repository.enqueue(2L, null, "READY", 1, repository.now());
            assertThat(marker(2)).isEqualTo(1);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(marker(2)).isZero();
        assertThat(repository.readyAssignmentIds(20)).containsExactly(2L);
        assertThat(transaction.<java.util.Optional<PerformerNotificationRepository.Intent>>execute(status -> repository.claim(120))).isEmpty();
        for (int i=0;i<2;i++) transaction.executeWithoutResult(status -> repository.enqueue(2L, null, "READY", 1, repository.now()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE operation_key='READY:2:1'", Integer.class)).isEqualTo(1);
        assertThat(marker(2)).isEqualTo(1);
        assertThat(repository.readyAssignmentIds(20)).isEmpty();
    }

    @Test
    void markerMigrationUsesOnlyExactCurrentIntentRegardlessOfDeliveryStatus() {
        jdbc.execute("ALTER TABLE review_performer_assignments DROP INDEX idx_performer_ready_notification, DROP COLUMN ready_notification_pending, DROP COLUMN readiness_intent_generation");
        seedAssignment(2, 2);
        seedAssignment(3, 3);
        seedAssignment(4, 4);
        seedAssignment(5, 5);
        jdbc.update("""
                INSERT INTO performer_notification_intents
                  (operation_key,assignment_id,notification_type,generation,status,due_at)
                VALUES ('READY:2:1',2,'READY',1,'SENT',CURRENT_TIMESTAMP(6)),
                  ('READY:3:3',3,'READY',3,'UNKNOWN',CURRENT_TIMESTAMP(6)),
                  ('READY:4:4',4,'READY',4,'CANCELLED',CURRENT_TIMESTAMP(6)),
                  ('ACCEPTED:5:0',5,'ACCEPTED',0,'SENT',CURRENT_TIMESTAMP(6))
                """);

        migrateReadinessMarker();

        assertThat(marker(1)).isZero(); // legacy generation zero must not start sending
        assertThat(marker(2)).isZero(); // only an older intent exists
        assertThat(marker(3)).isEqualTo(3);
        assertThat(marker(4)).isEqualTo(4);
        assertThat(marker(5)).isZero(); // a different notification kind is not evidence
        assertThat(repository.readyAssignmentIds(20)).containsExactly(2L, 5L);
        assertThat(jdbc.queryForObject("SELECT status FROM performer_notification_intents WHERE operation_key='READY:3:3'", String.class))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void duplicateRepairsMarkerWithoutRequeueAndOldGenerationCannotSuppressNewCycle() {
        seedAssignment(2, 1);
        transaction.executeWithoutResult(tx -> repository.enqueue(2L, null, "READY", 1, repository.now()));
        jdbc.update("UPDATE performer_notification_intents SET status='UNKNOWN' WHERE operation_key='READY:2:1'");
        jdbc.update("UPDATE review_performer_assignments SET readiness_intent_generation=0 WHERE assignment_id=2");
        transaction.executeWithoutResult(tx -> repository.enqueue(2L, null, "READY", 1, repository.now()));
        assertThat(marker(2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM performer_notification_intents WHERE operation_key='READY:2:1'", String.class))
                .isEqualTo("UNKNOWN");

        jdbc.update("UPDATE review_performer_assignments SET publication_generation=2 WHERE assignment_id=2");
        transaction.executeWithoutResult(tx -> repository.enqueue(2L, null, "READY", 1, repository.now()));
        assertThat(marker(2)).isEqualTo(1);
        assertThat(repository.readyAssignmentIds(20)).containsExactly(2L);
        transaction.executeWithoutResult(tx -> repository.enqueue(2L, null, "READY", 2, repository.now()));
        assertThat(marker(2)).isEqualTo(2);
        assertThat(repository.readyAssignmentIds(20)).isEmpty();
    }

    @Test
    void indexedPendingSelectionProgressesBeyondExistingIntentBacklog() {
        for (int i = 2; i < 52; i++) {
            seedAssignment(i, 1);
            long id = i;
            transaction.executeWithoutResult(tx -> repository.enqueue(id, null, "READY", 1, repository.now()));
        }
        seedAssignment(52, 1);
        seedAssignment(53, 1);
        assertThat(repository.readyAssignmentIds(1)).containsExactly(52L);
        transaction.executeWithoutResult(tx -> repository.enqueue(52L, null, "READY", 1, repository.now()));
        assertThat(repository.readyAssignmentIds(1)).containsExactly(53L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_assignments WHERE ready_notification_pending=1", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentCanonicalEnqueueCommitsOneIntentAndOneCurrentMarker() throws Exception {
        seedAssignment(2, 1);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> enqueue = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                transaction.executeWithoutResult(tx -> {
                    jdbc.queryForObject("SELECT assignment_id FROM review_performer_assignments WHERE assignment_id=2 FOR UPDATE", Long.class);
                    repository.enqueue(2L, null, "READY", 1, repository.now());
                });
                return null;
            };
            var first = executor.submit(enqueue);
            var second = executor.submit(enqueue);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally { start.countDown(); }
        assertThat(marker(2)).isEqualTo(1);
        assertThat(repository.readyAssignmentIds(20)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE operation_key='READY:2:1'", Integer.class)).isEqualTo(1);
    }

    private void migrateReadinessMarker() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_300__performer_readiness_intent_index.sql"))
                .execute(jdbc.getDataSource());
    }

    private void seedAssignment(long id, long generation) {
        jdbc.update("""
                INSERT INTO review_performer_assignments(assignment_id,status,publish_available_at,publication_generation)
                VALUES (?,'WAITING_PUBLICATION',TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),?)
                """, id, generation);
    }

    private long marker(long id) {
        return jdbc.queryForObject("SELECT readiness_intent_generation FROM review_performer_assignments WHERE assignment_id=?", Long.class, id);
    }

    @Test
    void expiredClaimBecomesUnknownAndLateAcknowledgementCannotOverwriteIt() {
        transaction.executeWithoutResult(status -> repository.enqueue(2L, null, "READY", 1, repository.now()));
        var first = transaction.execute(status -> repository.claim(120)).orElseThrow();
        jdbc.update("UPDATE performer_notification_intents SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE notification_id=?", first.id());
        transaction.executeWithoutResult(status -> repository.expireClaims(10));
        assertThat(repository.get(first.id()).orElseThrow().status()).isEqualTo("UNKNOWN");
        Boolean finished = transaction.execute(status -> repository.finish(first, "SENT", 42, "late"));
        assertThat(finished).isFalse();
        assertThat(transaction.<java.util.Optional<PerformerNotificationRepository.Intent>>execute(status -> repository.claim(120))).isEmpty();
    }

    @Test
    void claimSkipsLockedIntentAndProgressesPastFirstPage() throws Exception {
        for (long id=2;id<=23;id++) {
            long key=id;
            transaction.executeWithoutResult(status -> repository.enqueue(key, null, "READY", 1, repository.now()));
        }
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> transaction.execute(status -> {
                var claim = repository.claim(120).orElseThrow();
                held.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return claim;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var second = transaction.execute(status -> repository.claim(120)).orElseThrow();
            release.countDown();
            var firstClaim=first.get(5,TimeUnit.SECONDS);
            assertThat(second.id()).isNotEqualTo(firstClaim.id());
            transaction.executeWithoutResult(status -> repository.finish(firstClaim,"SENT",41,"ok"));
            transaction.executeWithoutResult(status -> repository.finish(second,"SENT",42,"ok"));
        } finally { release.countDown(); }
        int processed=2;
        while (true) {
            var next=transaction.execute(status -> repository.claim(120));
            if(next.isEmpty())break;
            transaction.executeWithoutResult(status -> repository.finish(next.get(),"SENT",43,"ok"));
            processed++;
        }
        assertThat(processed).isEqualTo(22);
    }

    @Test
    void explicitRequeueKeepsIdentityAndRecordsWhoConfirmedNoSend() {
        var legacy=repository.unresolved(10).getFirst();
        Boolean resolved = transaction.execute(status -> repository.resolve(legacy,"PENDING",null,"admin","Verified no message","CONFIRM_NOT_SENT_REQUEUE"));
        assertThat(resolved).isTrue();
        var claim=transaction.execute(status -> repository.claim(120)).orElseThrow();
        assertThat(claim.id()).isEqualTo(legacy.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_resolutions",Integer.class)).isEqualTo(1);
    }
}
