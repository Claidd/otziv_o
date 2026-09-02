package com.hunt.otziv.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PaymentReturnManualResolutionMigrationMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("payment_return_manual_contract")
            .withUsername("root")
            .withPassword("root");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[] {
                "payment_link_return_reconciliation_outbox",
                "business_audit_events",
                "archive_payment_links", "payment_links",
                "archive_payment_check", "payment_check"
        }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE payment_check (
                    check_id BIGINT NOT NULL PRIMARY KEY,
                    check_sum DECIMAL(12, 2) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("CREATE TABLE archive_payment_check LIKE payment_check");
        jdbc.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL PRIMARY KEY,
                    row_version BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL,
                    provider_terminal_status VARCHAR(32) NULL,
                    confirmed_amount_kopecks BIGINT NULL,
                    paid_at DATETIME(6) NULL,
                    manual_confirmed_at DATETIME(6) NULL,
                    bank_cancel_origin_status VARCHAR(32) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_payment_links (
                    id BIGINT NOT NULL PRIMARY KEY,
                    order_id BIGINT NULL,
                    status VARCHAR(32) NULL,
                    confirmed_amount_kopecks BIGINT NULL,
                    paid_at DATETIME(6) NULL,
                    manual_confirmed_at DATETIME(6) NULL,
                    last_error VARCHAR(1000) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE business_audit_events (
                    event_id BIGINT NOT NULL AUTO_INCREMENT,
                    created_at DATETIME(6) NOT NULL,
                    actor VARCHAR(150) NOT NULL,
                    source VARCHAR(80) NOT NULL,
                    action VARCHAR(80) NOT NULL,
                    entity_type VARCHAR(40) NOT NULL,
                    entity_id VARCHAR(80) NULL,
                    order_id BIGINT NULL,
                    review_id BIGINT NULL,
                    old_value TEXT NULL,
                    new_value TEXT NULL,
                    details TEXT NULL,
                    PRIMARY KEY (event_id),
                    INDEX idx_business_audit_action_created (action, created_at)
                ) ENGINE=InnoDB
                  DEFAULT CHARACTER SET utf8mb4
                  COLLATE utf8mb4_unicode_ci
                """);
        jdbc.update("INSERT INTO payment_check (check_id, check_sum) VALUES (1, 100.00)");
        jdbc.update("INSERT INTO archive_payment_check (check_id, check_sum) VALUES (2, 200.00)");
        jdbc.update("""
                INSERT INTO payment_links (id, row_version, status, provider_terminal_status)
                VALUES (7, 4, 'REFUNDED', 'REFUNDED')
                """);
        jdbc.update("""
                INSERT INTO payment_links (
                    id, row_version, status, provider_terminal_status, confirmed_amount_kopecks
                ) VALUES (9, 5, 'CANCELED', 'CANCELED', 10000)
                """);
        jdbc.update("""
                INSERT INTO payment_links (id, row_version, status, provider_terminal_status)
                VALUES (10, 6, 'CANCELED', 'CANCELED')
                """);
        jdbc.update("INSERT INTO archive_payment_links (id, order_id, status) VALUES (8, 42, 'CONFIRMED')");
        jdbc.update("INSERT INTO archive_payment_links (id, order_id, status) VALUES (11, 43, 'REFUNDED')");
        jdbc.update("INSERT INTO archive_payment_links (id, order_id, status) VALUES (12, 44, 'CANCELED')");
        runMigration("V1_10_282__bind_payment_returns_to_financial_cycle.sql");
        jdbc.execute("""
                CREATE TABLE payment_link_return_reconciliation_outbox (
                    outbox_id BIGINT NOT NULL AUTO_INCREMENT,
                    payment_link_id BIGINT NOT NULL,
                    source_version BIGINT NOT NULL,
                    observed_status VARCHAR(32) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    claim_token CHAR(36) NULL,
                    lease_until DATETIME(6) NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    last_error VARCHAR(1000) NULL,
                    processed_at DATETIME(6) NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (outbox_id),
                    UNIQUE KEY uk_payment_link_return_outbox_source
                        (payment_link_id, source_version, observed_status),
                    INDEX idx_payment_link_return_outbox_due
                        (status, next_attempt_at, lease_until, outbox_id),
                    INDEX idx_payment_link_return_outbox_link
                        (payment_link_id, status),
                    CONSTRAINT ck_payment_link_return_outbox_status
                        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED')),
                    CONSTRAINT ck_payment_link_return_outbox_attempts
                        CHECK (attempt_count >= 0)
                ) ENGINE=InnoDB
                """);
        jdbc.update("""
                INSERT INTO payment_link_return_reconciliation_outbox (
                    payment_link_id, source_version, observed_status,
                    status, processed_at
                ) VALUES (7, 4, 'REFUNDED', 'SUCCEEDED', CURRENT_TIMESTAMP(6))
                """);
    }

    @Test
    void migrationIsRerunnableAndEnforcesLiveArchiveTupleParity() {
        runMigration("V1_10_283__payment_return_manual_resolution.sql");
        runMigration("V1_10_283__payment_return_manual_resolution.sql");

        assertThat(columnCount("payment_links", "return_recovery_resolved_by")).isOne();
        assertThat(columnCount("archive_payment_links", "return_recovery_resolved_by")).isOne();
        assertThat(columnCount("archive_payment_links", "bank_cancel_origin_status")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT collation_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'business_audit_events'
                  AND column_name = 'entity_id'
                """, String.class)).isEqualTo("utf8mb4_unicode_ci");
        assertThat(constraintCount("payment_check", "ck_payment_check_return_snapshot")).isOne();
        assertThat(constraintCount("archive_payment_check", "ck_archive_payment_check_return_snapshot")).isOne();
        assertThat(constraintCount("payment_links", "ck_payment_links_return_recovery_tuple")).isOne();
        assertThat(constraintCount(
                "archive_payment_links", "ck_archive_payment_links_return_recovery_tuple")).isOne();
        assertThat(outboxStatus(7L, 4L, "REFUNDED")).isEqualTo("PENDING");
        assertThat(outboxStatus(9L, 5L, "CANCELED")).isEqualTo("PENDING");
        assertThat(outboxCount(10L)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT return_recovery_outcome
                FROM archive_payment_links
                WHERE id = 11
                """, String.class)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM business_audit_events
                WHERE action = 'ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED'
                  AND entity_id = '11'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM archive_payment_links
                WHERE id = 12
                  AND return_recovery_processed_at IS NULL
                  AND return_recovery_outcome IS NULL
                """, Integer.class)).isOne();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment_check SET check_paid_amount = -1 WHERE check_id = 1"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment_check SET check_payment_link = 7 WHERE check_id = 1"))
                .isInstanceOf(Exception.class);
        jdbc.update("""
                UPDATE payment_check
                SET check_paid_amount = 0, check_payment_link = 7
                WHERE check_id = 1
                """);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE payment_links
                SET return_recovery_processed_at = CURRENT_TIMESTAMP(6),
                    return_recovery_outcome = 'UNKNOWN'
                WHERE id = 7
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE payment_links
                SET return_recovery_processed_at = CURRENT_TIMESTAMP(6),
                    return_recovery_outcome = 'APPLIED'
                WHERE id = 7
                """)).isInstanceOf(Exception.class);
        jdbc.update("""
                UPDATE payment_links
                SET return_recovery_processed_at = CURRENT_TIMESTAMP(6),
                    return_recovery_payment_check_id = 1,
                    return_recovery_outcome = 'APPLIED'
                WHERE id = 7
                """);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE archive_payment_links
                SET return_recovery_processed_at = CURRENT_TIMESTAMP(6),
                    return_recovery_outcome = 'ACCEPTED_NOOP'
                WHERE id = 8
                """)).isInstanceOf(Exception.class);
        jdbc.update("""
                UPDATE archive_payment_links
                SET return_recovery_processed_at = CURRENT_TIMESTAMP(6),
                    return_recovery_outcome = 'ACCEPTED_NOOP',
                    return_recovery_resolved_at = CURRENT_TIMESTAMP(6),
                    return_recovery_resolved_by = 'owner@test',
                    return_recovery_resolution_reason = 'Сверено по выписке'
                WHERE id = 8
                """);
        assertThat(jdbc.queryForObject(
                "SELECT return_recovery_outcome FROM archive_payment_links WHERE id = 8",
                String.class
        )).isEqualTo("ACCEPTED_NOOP");
    }

    @Test
    void manualFollowUpRequeueNeverStealsAnActiveWorkerClaim() {
        PaymentLinkReturnOutboxRepository repository = new PaymentLinkReturnOutboxRepository(
                new NamedParameterJdbcTemplate(dataSource));
        jdbc.update("""
                UPDATE payment_link_return_reconciliation_outbox
                SET status = 'PROCESSING',
                    claim_token = '00000000-0000-0000-0000-000000000007',
                    lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6))
                WHERE payment_link_id = 7
                """);

        repository.requeue(7L, 4L, "REFUNDED");

        assertThat(outboxStatus(7L, 4L, "REFUNDED")).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("""
                SELECT claim_token
                FROM payment_link_return_reconciliation_outbox
                WHERE payment_link_id = 7
                  AND source_version = 4
                  AND observed_status = 'REFUNDED'
                """, String.class)).isEqualTo("00000000-0000-0000-0000-000000000007");

        jdbc.update("""
                UPDATE payment_link_return_reconciliation_outbox
                SET status = 'SUCCEEDED',
                    claim_token = NULL,
                    lease_until = NULL,
                    processed_at = '2026-01-02 03:04:05.000000',
                    last_error = 'historic-success'
                WHERE payment_link_id = 7
                """);
        repository.requeue(7L, 4L, "REFUNDED");
        assertThat(outboxStatus(7L, 4L, "REFUNDED")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT processed_at
                FROM payment_link_return_reconciliation_outbox
                WHERE payment_link_id = 7
                  AND source_version = 4
                  AND observed_status = 'REFUNDED'
                """, java.sql.Timestamp.class))
                .isEqualTo(java.sql.Timestamp.valueOf("2026-01-02 03:04:05"));
        assertThat(jdbc.queryForObject("""
                SELECT last_error
                FROM payment_link_return_reconciliation_outbox
                WHERE payment_link_id = 7
                  AND source_version = 4
                  AND observed_status = 'REFUNDED'
                """, String.class)).isEqualTo("historic-success");
    }

    private void runMigration(String name) {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + name)).execute(dataSource);
    }

    private Integer columnCount(String table, String column) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, table, column);
    }

    private Integer constraintCount(String table, String constraint) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                """, Integer.class, table, constraint);
    }

    private String outboxStatus(long linkId, long sourceVersion, String observedStatus) {
        return jdbc.queryForObject("""
                SELECT status
                FROM payment_link_return_reconciliation_outbox
                WHERE payment_link_id = ?
                  AND source_version = ?
                  AND observed_status = ?
                """, String.class, linkId, sourceVersion, observedStatus);
    }

    private Integer outboxCount(long linkId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM payment_link_return_reconciliation_outbox
                WHERE payment_link_id = ?
                """, Integer.class, linkId);
    }
}
