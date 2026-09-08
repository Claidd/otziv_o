package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class LeadCommandMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("lead_commands").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private LeadCommandRepository repository;
    @BeforeEach void prepare() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS lead_command_replay_audit");
        jdbc.execute("DROP TABLE IF EXISTS lead_command_source");
        jdbc.execute("DROP TABLE IF EXISTS lead_command_stream");
        jdbc.execute("DROP TABLE IF EXISTS lead_command_manual_requests");
        jdbc.execute("DROP TABLE IF EXISTS lead_inbound_receipts");
        jdbc.execute("DROP TABLE IF EXISTS lead_inbound_entities");
        jdbc.execute("DROP TABLE IF EXISTS lead_inbound_targets");
        jdbc.execute("DROP TABLE IF EXISTS lead_sync_queue");
        jdbc.execute("DROP TABLE IF EXISTS lead_command_queue");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_2_9__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_2_91__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_2_92__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_10_293__lead_command_delivery.sql"),
                new ClassPathResource("db/migration/V1_10_298__lead_command_consumer_compatibility_fence.sql"),
                new ClassPathResource("db/migration/V1_10_299__lead_blocking_scope_index.sql"),
                new ClassPathResource("db/migration/V1_10_306__lead_versioned_delivery_protocol.sql")).execute(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repository = new LeadCommandRepository(jdbc);
    }
    @Test void exhaustedOldRowsDoNotStarveFreshWork() {
        tx.executeWithoutResult(status -> {
            for (long id=1; id<=100; id++) repository.enqueue(id,"79990000000","SYNC","{}");
        });
        jdbc.update("UPDATE lead_command_queue SET retry_count=20");
        tx.executeWithoutResult(status -> repository.enqueue(101,"79990000001","SYNC","{}"));
        var claim = tx.execute(status -> repository.claim(20));
        assertThat(claim).isPresent();
        assertThat(claim.orElseThrow().id()).isEqualTo(101);
    }
    @Test void twoConsumersClaimDifferentCommandsAndPreservePerLeadOrder() throws Exception {
        tx.executeWithoutResult(status -> {
            repository.enqueue(1,"79990000000","SYNC","{}");
            repository.enqueue(1,"79990000000","SYNC","{}");
            repository.enqueue(2,"79990000001","SYNC","{}");
        });
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> {start.await(); return tx.execute(status -> repository.claim(20)).orElseThrow();});
            var b=pool.submit(() -> {start.await(); return tx.execute(status -> repository.claim(20)).orElseThrow();});
            start.countDown();
            var ids=List.of(a.get(15,TimeUnit.SECONDS).id(),b.get(15,TimeUnit.SECONDS).id());
            assertThat(ids).containsExactlyInAnyOrder(1L,3L);
        }
        var next=tx.execute(status -> repository.claim(20));
        assertThat(next).isEmpty();
    }
    @Test void expiredLeaseCannotBeCompletedAndRequiresAuditedResolution() {
        tx.executeWithoutResult(status -> repository.enqueue(1,"79990000000","SYNC","{}"));
        var claim=tx.execute(status -> repository.claim(20)).orElseThrow();
        jdbc.update("UPDATE lead_command_queue SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))");
        boolean late=tx.execute(status -> repository.complete(claim,"SUCCEEDED",null,0));
        assertThat(late).isFalse();
        tx.executeWithoutResult(status -> repository.recoverExpired(20));
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM lead_command_queue",String.class)).isEqualTo("UNKNOWN");
        boolean resolved=tx.execute(status -> repository.resolve(claim.id(),"CONFIRMED_NOT_DELIVERED","operator","Remote evidence checked"));
        assertThat(resolved).isTrue();
        var retry=tx.execute(status -> repository.claim(20)).orElseThrow();
        assertThat(retry.commandId()).isEqualTo(claim.commandId());
        assertThat(retry.token()).isNotEqualTo(claim.token());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_replay_audit",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest
    @ValueSource(strings = {"READY", "PROCESSING", "UNKNOWN", "LEGACY", "QUARANTINED", "DEAD"})
    void unresolvedCommandsBlockUntilTheirSuccessfulResolutionCommits(String state) {
        tx.executeWithoutResult(status -> {
            repository.enqueue(7,"79990000000","SYNC","{}");
            repository.enqueue(7,"79990000000","SYNC","{}");
        });
        jdbc.update("UPDATE lead_command_queue SET delivery_state=?, next_attempt_at=TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)) WHERE id=1", state);
        assertThat(jdbc.queryForObject("SELECT blocking_lead_id FROM lead_command_queue WHERE id=1", Long.class)).isEqualTo(7L);
        var blocked = tx.execute(status -> repository.claim(20));
        assertThat(blocked).isEmpty();

        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE lead_command_queue SET delivery_state='SUCCEEDED' WHERE id=1");
            assertThat(jdbc.queryForObject("SELECT blocking_lead_id FROM lead_command_queue WHERE id=1", Long.class)).isNull();
            status.setRollbackOnly();
        });
        var stillBlocked = tx.execute(status -> repository.claim(20));
        assertThat(stillBlocked).isEmpty();
        tx.executeWithoutResult(status -> jdbc.update("UPDATE lead_command_queue SET delivery_state='SUCCEEDED' WHERE id=1"));
        var next = tx.execute(status -> repository.claim(20)).orElseThrow();
        assertThat(next.id()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT blocking_lead_id FROM lead_command_queue WHERE id=1", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT blocking_lead_id FROM lead_command_queue WHERE id=2", Long.class)).isEqualTo(7L);
    }
    @Test void intentRollsBackWithBusinessTransactionAndLegacyBackfillIsRestartable() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            repository.enqueue(1,"79990000000","SYNC","{}");
            throw new IllegalStateException("Business rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_queue",Integer.class)).isZero();
        jdbc.update("INSERT INTO lead_command_queue(lead_id,telephone_lead,payload_json) VALUES(1,'79990000000','{}')");
        var legacy=repository.legacyBatch(1).getFirst();
        boolean first=tx.execute(status -> repository.classifyLegacy(legacy.id(),"QUARANTINED","LEAD_PAYLOAD_INVALID"));
        boolean second=tx.execute(status -> repository.classifyLegacy(legacy.id(),"READY",null));
        assertThat(first).isTrue(); assertThat(second).isFalse();
        assertThat(repository.legacyBatch(1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT payload_json FROM lead_command_queue",String.class)).isEqualTo("{}");
    }
}
