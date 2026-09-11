package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.maintenance.PerformerLegacyMaintenance;
import com.hunt.otziv.performers.maintenance.PerformerMaintenanceStartupGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class PerformerLegacyMaintenanceMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("performer_upgrade").withUsername("root").withPassword("root");
    @TempDir Path migrations;
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;
    private Connection connection;
    private PerformerLegacyMaintenance tool;

    @BeforeEach void setup() throws Exception {
        connectFixture();
        jdbc.execute("SET GLOBAL read_only=OFF"); jdbc.execute("SET GLOBAL event_scheduler=OFF");
        for(String table:List.of("performer_legacy_maintenance_rows","performer_legacy_maintenance_runs","performer_notification_resolutions","performer_notification_intents","review_performer_offers","review_performer_assignments","flyway_schema_history")) jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE review_performer_assignments(assignment_id BIGINT PRIMARY KEY,status VARCHAR(32),publish_available_at DATETIME(6))");
        jdbc.execute("CREATE TABLE review_performer_offers(offer_id BIGINT PRIMARY KEY,assignment_id BIGINT,status VARCHAR(32),offered_at DATETIME(6),telegram_message_id INT)");
        for(long id=1;id<=7;id++) {
            jdbc.update("INSERT INTO review_performer_assignments VALUES(?,'WAITING_PUBLICATION','2025-01-02 03:04:05')",id);
            jdbc.update("INSERT INTO review_performer_offers VALUES(?,?,'OFFERED','2025-01-02 03:04:05',?)",id,id,id%2==0?42:null);
        }
        for(String name:List.of("V1_10_288__performer_scoped_delivery.sql","V1_10_289__single_active_performer_offer.sql","V1_10_300__performer_readiness_intent_index.sql")) {
            Files.copy(new ClassPathResource("db/migration/"+name).getInputStream(),migrations.resolve(name));
        }
        // The minimal isolated fixture predates V288; production runner never baselines or edits history.
        flyway("1.10.287").baseline();
        jdbc.execute("CREATE USER IF NOT EXISTS 'maintenance_fixture_app'@'%' IDENTIFIED BY 'fixture-only' ACCOUNT LOCK");
        jdbc.execute("ALTER USER 'maintenance_fixture_app'@'%' ACCOUNT LOCK");
        String current=jdbc.queryForObject("SELECT CURRENT_USER()",String.class);
        for(var account:jdbc.queryForList("SELECT user,host FROM mysql.user WHERE Super_priv='Y' AND CONCAT(user,'@',host)<>CURRENT_USER()")) {
            jdbc.execute("ALTER USER '"+account.get("user")+"'@'"+account.get("host")+"' ACCOUNT LOCK");
        }
        jdbc.execute("SET GLOBAL read_only=ON");
    }

    @AfterEach void close() throws Exception {
        try {if(connection!=null && !connection.isClosed()) jdbc.execute("SET GLOBAL read_only=OFF");}
        finally {if(connection!=null) connection.close();}
    }

    @Test void startupCannotBypassOldHistoryMaintenanceButEmptySchemaCanMigrateNormally() {
        assertThatThrownBy(()->PerformerMaintenanceStartupGuard.assertReady(dataSource)).hasMessageContaining("Offline performer legacy maintenance required");
        jdbc.update("DELETE FROM review_performer_offers");jdbc.update("DELETE FROM review_performer_assignments");
        PerformerMaintenanceStartupGuard.assertReady(dataSource);
    }

    @Test void startupRefusesPendingV300ExactGenerationHistoricalMutation() {
        flyway("1.10.289").migrate();
        jdbc.update("UPDATE review_performer_assignments SET publication_generation=3 WHERE assignment_id=1");
        // No matching READY occurrence means original V300 has no historical data mutation.
        PerformerMaintenanceStartupGuard.assertReady(dataSource);
        jdbc.update("INSERT INTO performer_notification_intents(operation_key,assignment_id,notification_type,generation,status,due_at) VALUES('READY:1:3',1,'READY',3,'UNKNOWN',CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(()->PerformerMaintenanceStartupGuard.assertReady(dataSource)).hasMessageContaining("Offline performer legacy maintenance required");
    }

    @Test void conflictInventoryTraversesAllIdsAndStagingReportsOnlyCommittedMutations() {
        jdbc.update("INSERT INTO review_performer_offers VALUES(8,7,'OFFERED',CURRENT_TIMESTAMP(6),NULL)");
        var first=tool.inspectConflicts(0,null,3);
        assertThat(first.scanned()).isEqualTo(3);assertThat(first.conflicts()).isZero();assertThat(first.complete()).isFalse();
        var last=tool.inspectConflicts(first.nextAfterId(),first.throughId(),500);
        assertThat(last.scanned()).isEqualTo(4);assertThat(last.conflicts()).isEqualTo(1);
        assertThat(last.rows()).containsExactly(new PerformerLegacyMaintenance.Item(7,"ACTIVE_OFFER_CONFLICT:2"));
        jdbc.update("DELETE FROM review_performer_offers WHERE offer_id=8");
        String run=tool.begin("operator");var staged=tool.stage(run,2);
        assertThat(staged.stepScanned()).isEqualTo(2);assertThat(staged.stepChanged()).isEqualTo(2);
        assertThat(staged.rows()).extracting(PerformerLegacyMaintenance.MaintenanceItem::outcome).containsExactly("STAGED","STAGED");
        var restored=tool.restore(run,2,true);assertThat(restored.stepScanned()).isEqualTo(2);assertThat(restored.stepChanged()).isEqualTo(2);
    }

    @Test void dryRunIsCompleteAcrossCursorsWithoutCreatingTablesOrChangingOriginals() {
        long after=0; Long through=null; int count=0;
        while(true) {
            var batch=tool.previewStage("ASSIGNMENT",after,through,2);count+=batch.scanned();
            after=batch.nextAfterId();through=batch.throughId();if(batch.complete())break;
        }
        assertThat(count).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_assignments WHERE status='WAITING_PUBLICATION'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='performer_legacy_maintenance_runs'",Integer.class)).isZero();
    }

    @Test void fenceAndConflictPreflightRejectBeforeAnyMasking() {
        jdbc.execute("ALTER USER 'maintenance_fixture_app'@'%' ACCOUNT UNLOCK");
        assertThatThrownBy(()->tool.begin("operator")).hasMessageContaining("not locked");
        jdbc.execute("ALTER USER 'maintenance_fixture_app'@'%' ACCOUNT LOCK");
        jdbc.update("INSERT INTO review_performer_offers VALUES(8,1,'OFFERED',CURRENT_TIMESTAMP(6),NULL)");
        assertThat(tool.activeOfferConflicts(100)).hasSize(1);
        assertThatThrownBy(()->tool.begin("operator")).hasMessageContaining("conflicts");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_offers WHERE status='OFFERED'",Integer.class)).isEqualTo(8);
    }

    @Test void interruptedOldSchemaUpgradeResumesExactOriginalsAndOrdinaryFlywayHistory() throws Exception {
        String run=tool.begin("operator");tool.stage(run,2);
        assertThatThrownBy(()->PerformerMaintenanceStartupGuard.assertReady(dataSource)).hasMessageContaining("Incomplete offline");
        // New connection/tool instance represents a stopped process with only durable checkpoint retained.
        reconnect();
        while("STAGING".equals(tool.progress(run).phase())) assertThat(tool.stage(run,2).phase()).isIn("STAGING","MIGRATE_READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_assignments WHERE status='WAITING_PUBLICATION'",Integer.class)).isZero();
        flyway("1.10.300").migrate();flyway("1.10.300").validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents",Integer.class)).isZero();
        var originalHistory=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1.10.288','1.10.289','1.10.300') ORDER BY installed_rank");
        tool.migrationFinished(run);tool.restore(run,2,false);reconnect();
        while(!"COMPLETE".equals(tool.progress(run).phase()))tool.restore(run,2,false);
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1.10.288','1.10.289','1.10.300') ORDER BY installed_rank")).isEqualTo(originalHistory);
        assertThat(originalHistory).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_assignments WHERE status='WAITING_PUBLICATION' AND publication_generation=0 AND publish_available_at='2025-01-02 03:04:05'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_offers WHERE status='OFFERED' AND delivered_at IS NULL",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE status='LEGACY_UNKNOWN'",Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE status='SENT' AND telegram_message_id=42",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE status='PENDING'",Integer.class)).isZero();
        PerformerMaintenanceStartupGuard.assertReady(dataSource);
    }

    @Test void realAdditionalConnectionBlocksMaintenanceUntilServerConfirmsItClosed() throws Exception {
        long otherId;
        try (Connection other=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())) {
            var otherJdbc=new JdbcTemplate(new SingleConnectionDataSource(other,true));
            otherId=otherJdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
            assertThatThrownBy(()->tool.begin("operator")).hasMessageContaining("connections have not drained");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='performer_legacy_maintenance_runs'",Integer.class)).isZero();
        }
        awaitConnectionClosed(otherId);
        assertThat(tool.begin("operator")).isNotBlank();
    }

    @Test void interruptedStagingCanAbortWithoutMigrationOrLostOriginals() {
        String run=tool.begin("operator");tool.stage(run,3);reconnectUnchecked();
        tool.restore(run,1,true);reconnectUnchecked();
        while(!"ABORTED".equals(tool.progress(run).phase()))tool.restore(run,1,true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_assignments WHERE status='WAITING_PUBLICATION'",Integer.class)).isEqualTo(7);
        assertThat(tool.column("review_performer_assignments","publication_generation")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL'",Integer.class)).isZero();
    }

    @Test void partiallyUpgradedDatabasePreservesCurrentGenerationAndUnknownIntent() {
        flyway("1.10.289").migrate();
        jdbc.update("UPDATE review_performer_assignments SET publication_generation=3 WHERE assignment_id=1");
        jdbc.update("INSERT INTO performer_notification_intents(operation_key,assignment_id,notification_type,generation,status,due_at) VALUES('READY:1:3',1,'READY',3,'UNKNOWN',CURRENT_TIMESTAMP(6))");
        String run=tool.begin("operator");while("STAGING".equals(tool.progress(run).phase()))tool.stage(run,2);
        flyway("1.10.300").migrate();tool.migrationFinished(run);
        while(!"COMPLETE".equals(tool.progress(run).phase()))tool.restore(run,2,false);
        assertThat(jdbc.queryForObject("SELECT publication_generation FROM review_performer_assignments WHERE assignment_id=1",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT readiness_intent_generation FROM review_performer_assignments WHERE assignment_id=1",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM performer_notification_intents WHERE operation_key='READY:1:3'",String.class)).isEqualTo("UNKNOWN");
    }

    @Test void upgradedReconciliationIsBoundedResumableIdempotentAndNeverResendsHistory() {
        flyway("1.10.300").migrate();jdbc.update("DELETE FROM performer_notification_intents WHERE notification_type='READY'");
        var preview=tool.reconcile("READY_HISTORY",true,0,null,3);
        assertThat(preview.changed()).isZero();assertThat(preview.rows()).allMatch(row->row.outcome().equals("WOULD_CHANGE"));
        var first=tool.reconcile("READY_HISTORY",false,0,preview.throughId(),3);
        assertThat(first.changed()).isEqualTo(3);reconnectUnchecked();
        var rest=tool.reconcile("READY_HISTORY",false,first.nextAfterId(),first.throughId(),500);
        assertThat(rest.changed()).isEqualTo(4);assertThat(rest.complete()).isTrue();
        assertThat(tool.reconcile("READY_HISTORY",false,0,first.throughId(),500).changed()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE notification_type='READY' AND status='LEGACY_UNKNOWN'",Integer.class)).isEqualTo(7);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("filesystem:"+migrations.toAbsolutePath())
                .baselineVersion("1.10.287").target(target).cleanDisabled(true).load();
    }
    private void connectFixture() throws Exception {
        connection=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        // All fixture queries use the maintenance session. Opening and immediately
        // closing a connection per JdbcTemplate call races MySQL's COM_QUIT cleanup.
        dataSource=new SingleConnectionDataSource(connection,true);
        jdbc=new JdbcTemplate(dataSource);
        tool=new PerformerLegacyMaintenance(connection,"maintenance_fixture_app","%");
    }
    private void reconnect() throws Exception {
        long previousId=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
        connection.close();
        connectFixture();
        awaitConnectionClosed(previousId);
    }
    private void awaitConnectionClosed(long id) {
        // Wait for this known, explicitly closed fixture connection only. The
        // production write fence remains immediate and rejects every live peer.
        await().alias("closed fixture session removed from MySQL processlist")
                .pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(10))
                .until(()->jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist WHERE ID=?",Long.class,id)==0);
    }
    private void reconnectUnchecked() {try{reconnect();}catch(Exception error){throw new IllegalStateException(error);}}
}
