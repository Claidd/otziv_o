package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ContractorOrderWorkerIdentityMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    )
            .withDatabaseName("contractor_order_worker_identity")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("DROP TABLE IF EXISTS workers");
        jdbc.execute("""
                CREATE TABLE workers (
                    worker_id BIGINT NOT NULL,
                    PRIMARY KEY (worker_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    order_id BIGINT NOT NULL,
                    order_worker BIGINT NULL,
                    PRIMARY KEY (order_id),
                    CONSTRAINT order_worker
                        FOREIGN KEY (order_worker) REFERENCES workers (worker_id)
                        ON DELETE CASCADE ON UPDATE NO ACTION
                ) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO workers (worker_id) VALUES (7)");
        jdbc.update("INSERT INTO orders (order_id, order_worker) VALUES (11, 7)");
        jdbc.execute("""
                ALTER TABLE orders
                    DROP FOREIGN KEY order_worker
                """);
        jdbc.execute("""
                ALTER TABLE orders
                    ADD CONSTRAINT order_worker
                        FOREIGN KEY (order_worker) REFERENCES workers (worker_id)
                        ON DELETE RESTRICT ON UPDATE NO ACTION
                """);
    }

    @Test
    void deletingCurrentSpecialistCannotCascadeDeleteDurableOrder() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM workers WHERE worker_id = 7"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_id = 11 AND order_worker = 7",
                Integer.class
        )).isEqualTo(1);
    }
}
