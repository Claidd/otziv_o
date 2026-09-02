package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

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
class PaymentReturnFinancialCycleMigrationMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("payment_return_cycle_contract")
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
                    provider_terminal_status VARCHAR(32) NULL
                ) ENGINE=InnoDB
                """);
        // Historical archive schemas do not necessarily contain the later
        // provider_terminal_status column; V282 must still be applicable.
        jdbc.execute("""
                CREATE TABLE archive_payment_links (
                    id BIGINT NOT NULL PRIMARY KEY
                ) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO payment_check (check_id, check_sum) VALUES (1, 100.00)");
        jdbc.update("INSERT INTO payment_links (id, provider_terminal_status) VALUES (7, 'REFUNDED')");
        // Simulate a MySQL auto-commit failure after only part of V282 applied.
        jdbc.execute("ALTER TABLE payment_check ADD COLUMN check_paid_amount INT NULL AFTER check_sum");
        jdbc.execute("""
                ALTER TABLE payment_links
                ADD COLUMN return_recovery_processed_at DATETIME(6) NULL AFTER provider_terminal_status
                """);
    }

    @Test
    void migrationAddsLiveAndArchiveColumnsWithoutGuessingLegacySnapshots() {
        runMigration();
        runMigration();

        assertThat(jdbc.queryForObject(
                "SELECT check_paid_amount FROM payment_check WHERE check_id = 1",
                Integer.class
        )).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT check_payment_link FROM payment_check WHERE check_id = 1",
                Long.class
        )).isNull();

        jdbc.update("""
                UPDATE payment_links
                SET return_recovery_processed_at = CURRENT_TIMESTAMP(6),
                    return_recovery_payment_check_id = 1,
                    return_recovery_outcome = 'APPLIED'
                WHERE id = 7
                """);
        assertThat(jdbc.queryForObject(
                "SELECT return_recovery_outcome FROM payment_links WHERE id = 7",
                String.class
        )).isEqualTo("APPLIED");

        assertThat(columnCount("archive_payment_check", "check_paid_amount")).isOne();
        assertThat(columnCount("archive_payment_check", "check_payment_link")).isOne();
        assertThat(columnCount("archive_payment_links", "return_recovery_processed_at")).isOne();
        assertThat(columnCount("archive_payment_links", "return_recovery_payment_check_id")).isOne();
        assertThat(columnCount("archive_payment_links", "return_recovery_outcome")).isOne();
        assertThat(columnCount("payment_check", "check_paid_amount")).isOne();
        assertThat(columnCount("payment_links", "return_recovery_processed_at")).isOne();
    }

    private void runMigration() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_282__bind_payment_returns_to_financial_cycle.sql"
        )).execute(dataSource);
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
}
