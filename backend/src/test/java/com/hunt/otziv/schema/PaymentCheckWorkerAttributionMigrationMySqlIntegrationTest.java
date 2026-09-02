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
class PaymentCheckWorkerAttributionMigrationMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("payment_check_worker_contract")
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
                "business_audit_events", "app_settings", "archive_payment_check",
                "payment_check", "archive_orders", "orders", "workers"
        }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }

        jdbc.execute("""
                CREATE TABLE workers (
                    worker_id BIGINT NOT NULL PRIMARY KEY,
                    user_id BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    order_id BIGINT NOT NULL PRIMARY KEY,
                    order_worker BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_orders (
                    order_id BIGINT NOT NULL PRIMARY KEY,
                    order_worker BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_check (
                    check_id BIGINT NOT NULL PRIMARY KEY,
                    check_order BIGINT NULL,
                    check_manager BIGINT NULL,
                    check_worker BIGINT NULL,
                    check_date DATE NULL,
                    check_active BIT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_payment_check LIKE payment_check
                """);
        jdbc.execute("""
                CREATE TABLE app_settings (
                    setting_key VARCHAR(190) NOT NULL PRIMARY KEY,
                    setting_value TEXT NULL,
                    updated_at DATETIME(6) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE business_audit_events (
                    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    created_at DATETIME(6) NOT NULL,
                    actor VARCHAR(150) NOT NULL,
                    source VARCHAR(80) NOT NULL,
                    action VARCHAR(80) NOT NULL,
                    entity_type VARCHAR(40) NOT NULL,
                    entity_id VARCHAR(80) NULL,
                    order_id BIGINT NULL,
                    old_value TEXT NULL,
                    new_value TEXT NULL,
                    details TEXT NULL
                ) ENGINE=InnoDB
                  DEFAULT CHARACTER SET utf8mb4
                  COLLATE utf8mb4_unicode_ci
                """);

        jdbc.update("INSERT INTO workers (worker_id, user_id) VALUES (10, 100), (11, 110), (12, NULL)");
        jdbc.update("INSERT INTO orders (order_id, order_worker) VALUES (1, 10), (2, 11), (3, 12), (6, 11), (7, 10)");
        jdbc.update("INSERT INTO archive_orders (order_id, order_worker) VALUES (4, 11), (5, 10)");
        jdbc.update("""
                INSERT INTO payment_check
                    (check_id, check_order, check_manager, check_worker, check_date, check_active)
                VALUES
                    (101, 1, 7, 7, '2026-07-01', 1),
                    (102, 2, 7, 110, '2026-07-02', 1),
                    (103, 1, 7, 7, '2026-07-03', 0),
                    (104, 3, 7, 7, '2026-07-04', 1),
                    (105, 6, 7, 100, '2026-07-05', 1),
                    (106, 7, 7, NULL, '2026-07-06', 1)
                """);
        jdbc.update("""
                INSERT INTO archive_payment_check
                    (check_id, check_order, check_manager, check_worker, check_date, check_active)
                VALUES
                    (201, 4, 7, 7, '2025-11-01', 1),
                    (202, 5, 7, 7, '2025-11-02', 0)
                """);
    }

    @Test
    void migrationRepairsLiveAndArchiveOnceWithoutGuessingOrTouchingInactiveHistory() {
        runMigration();
        runMigration();

        assertThat(worker("payment_check", 101)).isEqualTo(100L);
        assertThat(worker("payment_check", 102)).isEqualTo(110L);
        assertThat(worker("payment_check", 103)).isEqualTo(7L);
        assertThat(worker("payment_check", 104)).isEqualTo(7L);
        assertThat(worker("payment_check", 105)).isEqualTo(100L);
        assertThat(worker("payment_check", 106)).isNull();
        assertThat(worker("archive_payment_check", 201)).isEqualTo(110L);
        assertThat(worker("archive_payment_check", 202)).isEqualTo(7L);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_audit_events "
                        + "WHERE actor = 'system:flyway-v281' "
                        + "AND action = 'PAYMENT_CHECK_WORKER_REATTRIBUTED'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_audit_events "
                        + "WHERE actor = 'system:flyway-v281' "
                        + "AND action = 'PAYMENT_CHECK_WORKER_REATTRIBUTION_REQUIRED'",
                Integer.class
        )).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT setting_value FROM app_settings "
                        + "WHERE setting_key = 'financial-integrity.v268-analytics-rebuild-pending'",
                String.class
        )).isEqualTo("true");
    }

    private Long worker(String table, long checkId) {
        return jdbc.queryForObject(
                "SELECT check_worker FROM " + table + " WHERE check_id = ?",
                Long.class,
                checkId
        );
    }

    private void runMigration() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_281__repair_payment_check_worker_attribution.sql"
        )).execute(dataSource);
    }
}
