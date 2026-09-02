package com.hunt.otziv.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PaymentReturnRecoveryCleanupMigrationMySqlIntegrationTest {

    private static final String MIGRATION =
            "V1_10_284__exclude_test_returns_and_close_resolved_recovery.sql";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("payment_return_cleanup_contract")
            .withUsername("root")
            .withPassword("root");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @TempDir
    Path migrationDirectory;

    @BeforeEach
    void setUp() throws IOException {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[] {
                "flyway_schema_history",
                "personal_reminders", "business_audit_events",
                "order_payment_reconciliations", "payment_check",
                "archive_payment_links", "payment_links", "payment_profiles",
                "orders", "order_statuses"
        }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        createSchema();
        seedRows();
        try (var input = new ClassPathResource("db/migration/" + MIGRATION).getInputStream()) {
            Files.copy(input, migrationDirectory.resolve(MIGRATION),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void testReturnsAndExactCompletedRefundBecomeAuditedTerminalNoops() {
        Timestamp originalProcessedAt = jdbc.queryForObject("""
                SELECT return_recovery_processed_at
                FROM payment_links
                WHERE id = 3
                """, Timestamp.class);

        runMigration();

        for (long linkId : new long[] {3L, 8L, 9L, 3918L}) {
            assertThat(outcome(linkId)).isEqualTo("ACCEPTED_NOOP");
            assertThat(jdbc.queryForObject("""
                    SELECT return_recovery_resolved_at IS NOT NULL
                       AND return_recovery_resolved_by = 'system:migration:v284'
                       AND NULLIF(TRIM(return_recovery_resolution_reason), '') IS NOT NULL
                    FROM payment_links
                    WHERE id = ?
                    """, Boolean.class, linkId)).isTrue();
        }
        assertThat(outcome(10L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(outcome(12L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(archiveOutcome(7L)).isEqualTo("ACCEPTED_NOOP");
        assertThat(archiveOutcome(18L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(archiveOutcome(19L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(jdbc.queryForObject("""
                SELECT return_recovery_processed_at
                FROM payment_links
                WHERE id = 3
                """, Timestamp.class)).isEqualTo(originalProcessedAt);
        assertThat(jdbc.queryForObject("""
                SELECT return_recovery_payment_check_id
                FROM payment_links
                WHERE id = 3
                """, Long.class)).isEqualTo(81L);
        assertThat(jdbc.queryForObject("""
                SELECT return_recovery_payment_check_id
                FROM payment_links
                WHERE id = 3918
                """, Long.class)).isEqualTo(20240L);

        assertThat(openReminderCount(3L, 42L)).isZero();
        assertThat(openReminderCount(8L, 42L)).isZero();
        assertThat(openReminderCount(9L, 42L)).isZero();
        assertThat(openReminderCount(3918L, 24378L)).isZero();
        assertThat(openReminderCount(10L, 42L)).isOne();
        assertThat(openReminderCount(12L, 42L)).isOne();
        assertThat(openReminderCount(3L, 999L)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM personal_reminders
                WHERE source_type = 'PAYMENT_RETURN_RECONCILIATION'
                  AND source_id = 3
                  AND source_order_id = 42
                  AND completed_at IS NOT NULL
                """, Integer.class)).isOne();

        assertThat(auditCount("PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 3L)).isOne();
        assertThat(auditCount("PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 8L)).isOne();
        assertThat(auditCount("PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 9L)).isOne();
        assertThat(auditCount(
                "PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED", 3918L)).isOne();
        assertThat(auditCount(
                "ARCHIVED_PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 7L)).isOne();
        assertThat(auditCount(
                "ARCHIVED_PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 18L)).isZero();
        assertThat(auditCount(
                "ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED", 7L)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT details LIKE '%openRemindersCompleted=1%'
                FROM business_audit_events
                WHERE action = 'PAYMENT_RETURN_TEST_RECOVERY_IGNORED'
                  AND entity_id = '3'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT details LIKE '%priorAuditEventId=%'
                  AND details LIKE '%no order, payment_check, company or reward-ledger mutation was performed%'
                FROM business_audit_events
                WHERE action = 'PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED'
                  AND entity_id = '3918'
                """, Boolean.class)).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT order_sum FROM orders WHERE order_id = 24378",
                java.math.BigDecimal.class)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject(
                "SELECT check_sum FROM payment_check WHERE check_id = 20240",
                java.math.BigDecimal.class)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject(
                "SELECT check_active FROM payment_check WHERE check_id = 20240",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payment_links WHERE id = 3918",
                String.class)).isEqualTo("REFUNDED");

        long resolvedVersion = rowVersion(3918L);
        runMigration();
        assertThat(rowVersion(3918L)).isEqualTo(resolvedVersion);
        assertThat(archiveOutcome(7L)).isEqualTo("ACCEPTED_NOOP");
        assertThat(auditCount(
                "PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED", 3918L)).isOne();
        assertThat(auditCount(
                "ARCHIVED_PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 7L)).isOne();
    }

    @Test
    void completedRefundDriftFailsBeforeAnyResolutionMutation() {
        jdbc.update("UPDATE payment_check SET check_sum = 999.00 WHERE check_id = 20240");

        assertThatThrownBy(this::runMigration).isInstanceOf(Exception.class);

        assertThat(outcome(3L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(outcome(3918L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(openReminderCount(3L, 42L)).isOne();
        assertThat(auditCount("PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 3L)).isZero();
        assertThat(auditCount(
                "PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED", 3918L)).isZero();
    }

    @Test
    void markerWriteFailureRollsBackAuditAndReminderCompletion() {
        jdbc.execute("""
                CREATE TRIGGER reject_v284_marker
                BEFORE UPDATE ON payment_links
                FOR EACH ROW
                BEGIN
                    IF OLD.id = 8 AND NEW.return_recovery_outcome = 'ACCEPTED_NOOP' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic marker failure';
                    END IF;
                END
                """);

        assertThatThrownBy(this::runMigration).isInstanceOf(Exception.class);

        assertThat(outcome(3L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(outcome(8L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(outcome(3918L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(archiveOutcome(7L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(openReminderCount(3L, 42L)).isOne();
        assertThat(openReminderCount(8L, 42L)).isOne();
        assertThat(openReminderCount(3918L, 24378L)).isOne();
        assertThat(auditCount("PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 3L)).isZero();
        assertThat(auditCount(
                "PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED", 3918L)).isZero();
    }

    @Test
    void flywayHistoryWriteFailureRollsBackAllBusinessWrites() {
        configuredFlyway().baseline();
        jdbc.execute("""
                CREATE TRIGGER reject_v284_history
                BEFORE INSERT ON flyway_schema_history
                FOR EACH ROW
                BEGIN
                    IF NEW.version = '1.10.284' AND NEW.success = 1 THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = 'synthetic Flyway history failure';
                    END IF;
                END
                """);

        assertThatThrownBy(() -> configuredFlyway().migrate()).isInstanceOf(Exception.class);

        assertThat(outcome(3L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(outcome(3918L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(archiveOutcome(7L)).isEqualTo("MANUAL_RECONCILIATION");
        assertThat(openReminderCount(3L, 42L)).isOne();
        assertThat(openReminderCount(3918L, 24378L)).isOne();
        assertThat(auditCount("PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 3L)).isZero();
        assertThat(auditCount(
                "PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED", 3918L)).isZero();
        assertThat(auditCount(
                "ARCHIVED_PAYMENT_RETURN_TEST_RECOVERY_IGNORED", 7L)).isZero();
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE order_statuses (
                    order_status_id BIGINT NOT NULL PRIMARY KEY,
                    order_status_title VARCHAR(80) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    order_id BIGINT NOT NULL PRIMARY KEY,
                    order_status BIGINT NOT NULL,
                    order_sum DECIMAL(12, 2) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_profiles (
                    id BIGINT NOT NULL PRIMARY KEY,
                    terminal_key VARCHAR(64) NOT NULL,
                    test_mode BOOLEAN NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    payment_profile_id BIGINT NULL,
                    row_version BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL,
                    payment_method VARCHAR(32) NOT NULL DEFAULT 'BANK_FORM',
                    tbank_terminal_key VARCHAR(64) NULL,
                    bank_cancel_origin_status VARCHAR(32) NULL,
                    confirmed_amount_kopecks BIGINT NULL,
                    return_recovery_processed_at DATETIME(6) NULL,
                    return_recovery_payment_check_id BIGINT NULL,
                    return_recovery_outcome VARCHAR(32) NULL,
                    return_recovery_resolved_at DATETIME(6) NULL,
                    return_recovery_resolved_by VARCHAR(150) NULL,
                    return_recovery_resolution_reason VARCHAR(512) NULL,
                    last_error VARCHAR(512) NULL,
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    CONSTRAINT ck_payment_links_return_recovery_tuple CHECK (
                        (
                            return_recovery_processed_at IS NULL
                            AND return_recovery_payment_check_id IS NULL
                            AND return_recovery_outcome IS NULL
                            AND return_recovery_resolved_at IS NULL
                            AND return_recovery_resolved_by IS NULL
                            AND return_recovery_resolution_reason IS NULL
                        )
                        OR (
                            return_recovery_processed_at IS NOT NULL
                            AND (
                                return_recovery_payment_check_id IS NULL
                                OR return_recovery_payment_check_id > 0
                            )
                            AND (
                                (
                                    return_recovery_outcome = 'MANUAL_RECONCILIATION'
                                    AND return_recovery_resolved_at IS NULL
                                    AND return_recovery_resolved_by IS NULL
                                    AND return_recovery_resolution_reason IS NULL
                                )
                                OR (
                                    return_recovery_outcome = 'ACCEPTED_NOOP'
                                    AND return_recovery_resolved_at IS NOT NULL
                                    AND NULLIF(TRIM(return_recovery_resolved_by), '') IS NOT NULL
                                    AND NULLIF(TRIM(return_recovery_resolution_reason), '') IS NOT NULL
                                )
                            )
                        )
                    )
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_check (
                    check_id BIGINT NOT NULL PRIMARY KEY,
                    check_order BIGINT NOT NULL,
                    check_sum DECIMAL(12, 2) NOT NULL,
                    check_payment_link BIGINT NULL,
                    check_active BOOLEAN NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_payment_links (
                    id BIGINT NOT NULL PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    payment_profile_id BIGINT NULL,
                    status VARCHAR(32) NOT NULL,
                    payment_method VARCHAR(32) NOT NULL DEFAULT 'BANK_FORM',
                    tbank_terminal_key VARCHAR(64) NULL,
                    return_recovery_processed_at DATETIME(6) NULL,
                    return_recovery_payment_check_id BIGINT NULL,
                    return_recovery_outcome VARCHAR(32) NULL,
                    return_recovery_resolved_at DATETIME(6) NULL,
                    return_recovery_resolved_by VARCHAR(150) NULL,
                    return_recovery_resolution_reason VARCHAR(512) NULL,
                    last_error VARCHAR(512) NULL,
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    CONSTRAINT ck_archive_payment_links_return_recovery_tuple CHECK (
                        (
                            return_recovery_processed_at IS NULL
                            AND return_recovery_payment_check_id IS NULL
                            AND return_recovery_outcome IS NULL
                            AND return_recovery_resolved_at IS NULL
                            AND return_recovery_resolved_by IS NULL
                            AND return_recovery_resolution_reason IS NULL
                        )
                        OR (
                            return_recovery_processed_at IS NOT NULL
                            AND (
                                return_recovery_payment_check_id IS NULL
                                OR return_recovery_payment_check_id > 0
                            )
                            AND (
                                (
                                    return_recovery_outcome = 'MANUAL_RECONCILIATION'
                                    AND return_recovery_resolved_at IS NULL
                                    AND return_recovery_resolved_by IS NULL
                                    AND return_recovery_resolution_reason IS NULL
                                )
                                OR (
                                    return_recovery_outcome = 'ACCEPTED_NOOP'
                                    AND return_recovery_resolved_at IS NOT NULL
                                    AND NULLIF(TRIM(return_recovery_resolved_by), '') IS NOT NULL
                                    AND NULLIF(TRIM(return_recovery_resolution_reason), '') IS NOT NULL
                                )
                            )
                        )
                    )
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE order_payment_reconciliations (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    reconciliation_key VARCHAR(160) NOT NULL,
                    order_id BIGINT NOT NULL,
                    adjustment_kopecks BIGINT NOT NULL,
                    active BOOLEAN NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE personal_reminders (
                    personal_reminder_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    source_type VARCHAR(60) NULL,
                    source_id BIGINT NULL,
                    source_order_id BIGINT NULL,
                    completed_at DATETIME(6) NULL,
                    updated_at DATETIME(6) NOT NULL
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
                    PRIMARY KEY (event_id)
                ) ENGINE=InnoDB
                  DEFAULT CHARACTER SET utf8mb4
                  COLLATE utf8mb4_unicode_ci
                """);
    }

    private void seedRows() {
        jdbc.update("""
                INSERT INTO order_statuses (order_status_id, order_status_title)
                VALUES (10, 'Оплачено'), (2, 'Напоминание')
                """);
        jdbc.update("""
                INSERT INTO orders (order_id, order_status, order_sum)
                VALUES (42, 2, 100.00), (24378, 10, 1000.00)
                """);
        jdbc.update("""
                INSERT INTO payment_profiles (id, terminal_key, test_mode)
                VALUES (1, 'PROFILE-TEST', TRUE), (2, 'PROFILE-LIVE', FALSE)
                """);
        insertManualLink(3L, 42L, 1L, "historical-link-demo", "REFUNDED", null, 81L, 1L);
        insertManualLink(8L, 42L, 2L, "legacy-terminal-demo", "CANCELED", null, null, 2L);
        insertManualLink(9L, 42L, 2L, "PROFILE-LIVE", "CANCELED", "TEST_CONFIRMED", null, 3L);
        insertManualLink(10L, 42L, 1L, "PROFILE-TEST", "REFUNDED", null, null, 4L);
        insertManualLink(12L, 42L, 1L, "manual-method-demo", "REFUNDED", null, null, 5L);
        jdbc.update("UPDATE payment_links SET payment_method = 'MANUAL_MOBILE_BANK' WHERE id = 12");
        jdbc.update("""
                INSERT INTO payment_links (
                    id, order_id, payment_profile_id, row_version, status,
                    tbank_terminal_key, confirmed_amount_kopecks, updated_at
                ) VALUES (3815, 24378, 2, 5, 'CONFIRMED', 'PROFILE-LIVE',
                          100000, CURRENT_TIMESTAMP(6))
                """);
        insertManualLink(
                3918L, 24378L, 2L, "PROFILE-LIVE", "REFUNDED", null, 20240L, 6L);
        jdbc.update("UPDATE payment_links SET confirmed_amount_kopecks = 100000 WHERE id = 3918");
        insertArchivedManualLink(7L, 22382L, 1L, "legacy-archive-demo");
        insertArchivedManualLink(18L, 22386L, 1L, "PROFILE-TEST");
        insertArchivedManualLink(19L, 22387L, 2L, "PROFILE-LIVE");
        jdbc.update("""
                INSERT INTO payment_check (
                    check_id, check_order, check_sum, check_payment_link, check_active
                ) VALUES (20240, 24378, 1000.00, NULL, TRUE)
                """);
        jdbc.update("""
                INSERT INTO order_payment_reconciliations (
                    reconciliation_key, order_id, adjustment_kopecks, active
                ) VALUES ('V275:ORDER:24378:CLIENT-OVERPAYMENT', 24378, -100000, FALSE)
                """);
        jdbc.update("""
                INSERT INTO business_audit_events (
                    created_at, actor, source, action, entity_type, entity_id,
                    order_id, old_value, new_value, details
                ) VALUES (
                    '2026-08-29 10:52:48.613881',
                    'owner:hunt',
                    'owner_confirmed_tbank_refund',
                    'ORDER_DUPLICATE_PAYMENT_REFUNDED',
                    'PAYMENT_LINK',
                    '3918',
                    24378,
                    'cash=200000;check=200000;adjustment=-100000;link=AMOUNT_MISMATCH',
                    'cash=100000;check=100000;adjustment=inactive;link=REFUNDED',
                    'Exact owner-confirmed refund evidence'
                )
                """);
        for (long[] archived : new long[][] {
                {7L, 22382L}, {18L, 22386L}, {19L, 22387L}
        }) {
            jdbc.update("""
                    INSERT INTO business_audit_events (
                        created_at, actor, source, action, entity_type, entity_id,
                        order_id, old_value, new_value, details
                    ) VALUES (
                        CURRENT_TIMESTAMP(6),
                        'system:migration:v283',
                        'FLYWAY',
                        'ARCHIVED_PAYMENT_RETURN_RECONCILIATION_REQUIRED',
                        'PAYMENT_LINK',
                        ?,
                        ?,
                        'UNATTRIBUTED_ARCHIVED_FULL_RETURN',
                        'MANUAL_RECONCILIATION',
                        'status=REFUNDED; no financial mutation was attempted'
                    )
                    """, String.valueOf(archived[0]), archived[1]);
        }
        for (long linkId : new long[] {3L, 8L, 9L, 10L, 12L, 3918L}) {
            long orderId = linkId == 3918L ? 24378L : 42L;
            jdbc.update("""
                    INSERT INTO personal_reminders (
                        source_type, source_id, source_order_id, completed_at, updated_at
                    ) VALUES ('PAYMENT_RETURN_RECONCILIATION', ?, ?, NULL, CURRENT_TIMESTAMP(6))
                    """, linkId, orderId);
        }
        jdbc.update("""
                INSERT INTO personal_reminders (
                    source_type, source_id, source_order_id, completed_at, updated_at
                ) VALUES ('PAYMENT_RETURN_RECONCILIATION', 3, 999, NULL, CURRENT_TIMESTAMP(6))
                """);
    }

    private void insertManualLink(
            long id,
            long orderId,
            Long profileId,
            String terminalKey,
            String status,
            String cancelOrigin,
            Long paymentCheckId,
            long rowVersion
    ) {
        jdbc.update("""
                INSERT INTO payment_links (
                    id, order_id, payment_profile_id, row_version, status,
                    tbank_terminal_key, bank_cancel_origin_status,
                    return_recovery_processed_at, return_recovery_payment_check_id,
                    return_recovery_outcome, last_error, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '2026-09-02 17:54:56.000000', ?,
                          'MANUAL_RECONCILIATION',
                          'payment_return_manual_reconciliation: test fixture',
                          CURRENT_TIMESTAMP(6))
                """, id, orderId, profileId, rowVersion, status, terminalKey,
                cancelOrigin, paymentCheckId);
    }

    private void insertArchivedManualLink(
            long id,
            long orderId,
            long profileId,
            String terminalKey
    ) {
        jdbc.update("""
                INSERT INTO archive_payment_links (
                    id, order_id, payment_profile_id, status, payment_method,
                    tbank_terminal_key, return_recovery_processed_at,
                    return_recovery_outcome, last_error, updated_at
                ) VALUES (?, ?, ?, 'REFUNDED', 'BANK_FORM', ?,
                          '2026-09-02 17:53:36.507158',
                          'MANUAL_RECONCILIATION',
                          'archived_payment_return_manual_reconciliation:v283_missing_financial_cycle_attribution',
                          CURRENT_TIMESTAMP(6))
                """, id, orderId, profileId, terminalKey);
    }

    private void runMigration() {
        configuredFlyway().migrate();
    }

    private Flyway configuredFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrationDirectory.toAbsolutePath()
                        .toString().replace('\\', '/'))
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1.10.283"))
                .load();
    }

    private String outcome(long linkId) {
        return jdbc.queryForObject(
                "SELECT return_recovery_outcome FROM payment_links WHERE id = ?",
                String.class,
                linkId
        );
    }

    private long rowVersion(long linkId) {
        return jdbc.queryForObject(
                "SELECT row_version FROM payment_links WHERE id = ?",
                Long.class,
                linkId
        );
    }

    private String archiveOutcome(long linkId) {
        return jdbc.queryForObject(
                "SELECT return_recovery_outcome FROM archive_payment_links WHERE id = ?",
                String.class,
                linkId
        );
    }

    private int openReminderCount(long linkId, long orderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM personal_reminders
                WHERE source_type = 'PAYMENT_RETURN_RECONCILIATION'
                  AND source_id = ?
                  AND source_order_id = ?
                  AND completed_at IS NULL
                """, Integer.class, linkId, orderId);
    }

    private int auditCount(String action, long linkId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM business_audit_events
                WHERE action = ?
                  AND entity_type = 'PAYMENT_LINK'
                  AND entity_id = ?
                """, Integer.class, action, String.valueOf(linkId));
    }
}
