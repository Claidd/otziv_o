package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.scheduler.SchedulerLeaseService;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ReconciliationArchiveIndexMigrationMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("index_contract")
            .withUsername("root")
            .withPassword("root");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[] {
                "s3_object_cleanup_queue", "scheduler_leases", "archive_reviews", "archive_orders",
                "payment_links", "companies"
        }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE companies (
                    company_id BIGINT NOT NULL PRIMARY KEY,
                    company_status VARCHAR(255),
                    company_active BIT NOT NULL,
                    company_status_changed_at DATETIME(6)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_orders (
                    order_id BIGINT NOT NULL PRIMARY KEY,
                    order_company BIGINT,
                    restored_at DATETIME(6),
                    archived_at DATETIME(6),
                    dummy_column BIGINT,
                    INDEX idx_orders_worker_changed (dummy_column),
                    INDEX idx_orders_waiting_for_client (dummy_column),
                    INDEX idx_archive_orders_batch (archived_at)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_reviews (
                    review_id BIGINT NOT NULL PRIMARY KEY,
                    review_order_details BINARY(16),
                    dummy_column BIGINT,
                    INDEX idx_reviews_publish_date (dummy_column),
                    INDEX idx_reviews_worker_metrics (dummy_column),
                    INDEX idx_archive_reviews_order_details (review_order_details, review_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL PRIMARY KEY,
                    bank_init_nonce VARCHAR(36),
                    bank_init_lease_until DATETIME(6),
                    bank_cancel_origin_status VARCHAR(32),
                    bank_reconciliation_attempted_at DATETIME(6),
                    updated_at DATETIME(6) NOT NULL
                ) ENGINE=InnoDB
                """);
        runMigration("V1_10_175__r2_scheduler_leases.sql");
    }

    @Test
    void schedulerLeaseProvidesCrossInstanceExclusionAndRelease() {
        SchedulerLeaseService first = new SchedulerLeaseService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource)
        );
        SchedulerLeaseService second = new SchedulerLeaseService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource)
        );

        SchedulerLeaseService.Lease lease = first.tryAcquire("integration-lease", Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(second.tryAcquire("integration-lease", Duration.ofMinutes(1))).isEmpty();

        first.release(lease);

        assertThat(second.tryAcquire("integration-lease", Duration.ofMinutes(1))).isPresent();
    }

    @Test
    void candidateIndexesAreCreatedAndUnusedLiveIndexesAreRemovedIdempotently() {
        runMigration("V1_10_207__reconciliation_candidate_indexes.sql");
        runMigration("V1_10_208__remove_live_workflow_indexes_from_archives.sql");
        runMigration("V1_10_209__durable_s3_object_cleanup_queue.sql");
        runMigration("V1_10_207__reconciliation_candidate_indexes.sql");
        runMigration("V1_10_208__remove_live_workflow_indexes_from_archives.sql");

        assertThat(indexExists("companies", "idx_companies_archive_message_candidates")).isTrue();
        assertThat(indexExists("archive_orders", "idx_archive_orders_company_restore_latest")).isTrue();
        assertThat(indexExists("payment_links", "idx_payment_links_bank_init_reserved")).isTrue();
        assertThat(indexExists("payment_links", "idx_payment_links_cancel_reconciliation")).isTrue();

        assertThat(indexExists("archive_orders", "idx_orders_worker_changed")).isFalse();
        assertThat(indexExists("archive_orders", "idx_orders_waiting_for_client")).isFalse();
        assertThat(indexExists("archive_reviews", "idx_reviews_publish_date")).isFalse();
        assertThat(indexExists("archive_reviews", "idx_reviews_worker_metrics")).isFalse();

        assertThat(indexExists("archive_orders", "idx_archive_orders_batch")).isTrue();
        assertThat(indexExists("archive_reviews", "idx_archive_reviews_order_details")).isTrue();
        assertThat(indexExists("s3_object_cleanup_queue", "uk_s3_cleanup_object_identity")).isTrue();
        assertThat(indexExists("s3_object_cleanup_queue", "idx_s3_cleanup_due")).isTrue();
    }

    private boolean indexExists(String table, String index) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, table, index);
        return count != null && count > 0;
    }

    private void runMigration(String name) {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + name))
                .execute(dataSource);
    }
}
