package com.hunt.otziv.common_billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CommonInvoiceSuccessorMembershipMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("common_successor_membership")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_orders");
        jdbc.execute("""
                CREATE TABLE common_invoice_orders (
                    invoice_order_id BIGINT NOT NULL AUTO_INCREMENT,
                    invoice_id BIGINT NOT NULL,
                    active_membership BOOLEAN NOT NULL DEFAULT TRUE,
                    order_id BIGINT NOT NULL,
                    active_order_id BIGINT
                      GENERATED ALWAYS AS (
                        CASE WHEN active_membership THEN order_id ELSE NULL END
                      ) STORED,
                    PRIMARY KEY (invoice_order_id),
                    UNIQUE KEY uk_common_invoice_active_order (active_order_id)
                ) ENGINE=InnoDB
                """);
        jdbc.update("""
                INSERT INTO common_invoice_orders (invoice_id, active_membership, order_id)
                VALUES (10, TRUE, 101)
                """);
    }

    @Test
    void flushedInactivePredecessorAllowsIdentityInsertOfActiveSuccessor() {
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.update("""
                    UPDATE common_invoice_orders
                    SET active_membership = FALSE
                    WHERE invoice_id = 10
                    """)).isEqualTo(1);
            assertThat(jdbc.update("""
                    INSERT INTO common_invoice_orders (invoice_id, active_membership, order_id)
                    VALUES (11, TRUE, 101)
                    """)).isEqualTo(1);
        });

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM common_invoice_orders
                WHERE order_id = 101
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM common_invoice_orders
                WHERE active_order_id = 101
                """, Integer.class)).isEqualTo(1);
    }
}
