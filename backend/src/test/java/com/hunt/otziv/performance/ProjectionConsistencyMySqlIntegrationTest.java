package com.hunt.otziv.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse;
import com.hunt.otziv.manager_control.service.ManagerControlReadSnapshots;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Actual InnoDB locking/rollback; no production account or provider is involved. */
@Testcontainers
class ProjectionConsistencyMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(FinancialScenarioBenchmark.MYSQL_IMAGE)
            .withDatabaseName("projection_test").withUsername("root").withPassword("projection-local-only");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static SchedulerLeaseService leases;
    static ManagerControlReadSnapshots snapshots;
    static final LocalDate DATE=LocalDate.of(2026,9,11);
    static final String SCOPE="a".repeat(64);

    @BeforeAll static void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_175__r2_scheduler_leases.sql"),
                new ClassPathResource("db/migration/V1_10_314__manager_control_read_snapshots.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var named=new NamedParameterJdbcTemplate(ds);
        leases=new SchedulerLeaseService(named);
        snapshots=new ManagerControlReadSnapshots(named,new ObjectMapper().findAndRegisterModules());
    }
    static ManagerControlManagerResponse response(long id) throws Exception {
        return new ObjectMapper().findAndRegisterModules().readValue("{\"managerId\":"+id+"}",ManagerControlManagerResponse.class);
    }
    @Test void invalidationPreventsOldComputationFromRestoringSnapshotAndRespectsScope() throws Exception {
        var response=response(41);
        tx.executeWithoutResult(s -> snapshots.save(DATE,SCOPE,0,response));
        assertThat(snapshots.fresh(List.of(41L),DATE,SCOPE)).containsKey(41L);
        assertThat(snapshots.fresh(List.of(42L),DATE,SCOPE)).isEmpty();
        assertThat(snapshots.fresh(List.of(41L),DATE,"b".repeat(64))).isEmpty();
        // The background calculation has already read generation 0 when a command commits.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE manager_control_read_snapshots SET generation=generation+1,payload=NULL,generated_at=NULL,access_scope=NULL WHERE manager_id=41"));
        tx.executeWithoutResult(s -> snapshots.save(DATE,SCOPE,0,response));
        assertThat(snapshots.fresh(List.of(41L),DATE,SCOPE)).isEmpty();
        tx.executeWithoutResult(s -> snapshots.save(DATE,SCOPE,1,response));
        assertThat(snapshots.fresh(List.of(41L),DATE,SCOPE)).containsKey(41L);
        jdbc.update("UPDATE manager_control_read_snapshots SET generated_at=CURRENT_TIMESTAMP - INTERVAL 61 SECOND WHERE manager_id=41");
        assertThat(snapshots.fresh(List.of(41L),DATE,SCOPE)).isEmpty();
    }
    @Test void rolledBackInvalidationLeavesCommittedSnapshotReadable() throws Exception {
        var today=jdbc.queryForObject("SELECT CURRENT_DATE",LocalDate.class);
        var response=response(42);
        tx.executeWithoutResult(s -> snapshots.save(today,SCOPE,0,response));
        tx.executeWithoutResult(s -> { snapshots.invalidate(List.of(42L)); s.setRollbackOnly(); });
        assertThat(snapshots.fresh(List.of(42L),today,SCOPE)).containsKey(42L);
        tx.executeWithoutResult(s -> snapshots.invalidate(List.of(42L)));
        assertThat(snapshots.fresh(List.of(42L),today,SCOPE)).isEmpty();
    }
    @Test void leaseAndDurableCheckpointCommitTogetherAndExpiredOwnerCannotPublish() {
        var lease=leases.tryAcquire("test-cursor",Duration.ofMinutes(2)).orElseThrow();
        tx.executeWithoutResult(s -> { leases.holdForTransaction(lease,Duration.ofMinutes(2)); leases.advanceProjectionCursor(lease,DATE,25); s.setRollbackOnly(); });
        assertThat(tx.<Long>execute(s -> leases.projectionCursor(lease,DATE))).isZero();
        tx.executeWithoutResult(s -> leases.advanceProjectionCursor(lease,DATE,25));
        assertThat(tx.<Long>execute(s -> leases.projectionCursor(lease,DATE))).isEqualTo(25);
        assertThat(tx.<Long>execute(s -> leases.projectionCursor(lease,DATE.plusDays(1)))).isZero();
        leases.release(lease);
        var next=leases.tryAcquire("test-cursor",Duration.ofMinutes(2)).orElseThrow();
        assertThat(next.fencingToken()).isGreaterThan(lease.fencingToken());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.advanceProjectionCursor(lease,DATE,50)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tx.<Long>execute(s -> leases.projectionCursor(next,DATE))).isEqualTo(25);
        leases.release(next);
    }
    @Test void leaseRowStaysLockedUntilSnapshotTransactionCommits() throws Exception {
        var lease=leases.tryAcquire("test-lock",Duration.ofMinutes(2)).orElseThrow();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var finish=new java.util.concurrent.CountDownLatch(1);
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writer=executor.submit(() -> tx.executeWithoutResult(s -> {
                leases.holdForTransaction(lease,Duration.ofMinutes(2)); entered.countDown();
                try { if(!finish.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var contender=executor.submit(() -> leases.tryAcquire("test-lock",Duration.ofMinutes(2)));
            try { assertThatThrownBy(() -> contender.get(200,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class); }
            finally { finish.countDown(); }
            writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(contender.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        } finally { finish.countDown(); leases.release(lease); }
    }
}
