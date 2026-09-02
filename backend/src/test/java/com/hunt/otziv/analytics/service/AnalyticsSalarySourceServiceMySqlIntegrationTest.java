package com.hunt.otziv.analytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AnalyticsSalarySourceServiceMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("canonical_salary_contract")
            .withUsername("root")
            .withPassword("root");

    private AnalyticsSalarySourceService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS analytics_salary_source");
        jdbc.execute("DROP TABLE IF EXISTS users_roles");
        jdbc.execute("DROP TABLE IF EXISTS roles");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("""
                CREATE TABLE users (
                    id BIGINT NOT NULL PRIMARY KEY,
                    fio VARCHAR(255),
                    active BIT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE roles (
                    id BIGINT NOT NULL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE users_roles (
                    user_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE analytics_salary_source (
                    metric_date DATE NOT NULL,
                    user_id BIGINT NOT NULL,
                    source_zp_id BIGINT NOT NULL,
                    salary_sum DECIMAL(20, 2) NOT NULL,
                    salary_entry_count BIGINT NOT NULL,
                    salary_review_count BIGINT NOT NULL
                ) ENGINE=InnoDB
                """);

        jdbc.update("INSERT INTO users (id, fio, active) VALUES (1, 'Первый', 1), (2, 'Второй', 1), (3, 'Неактивный', 0)");
        jdbc.update("INSERT INTO roles (id, name) VALUES (1, 'ROLE_MANAGER'), (2, 'ROLE_WORKER')");
        jdbc.update("INSERT INTO users_roles (user_id, role_id) VALUES (1, 1), (1, 2), (2, 2), (3, 2)");
        jdbc.update("""
                INSERT INTO analytics_salary_source
                    (metric_date, user_id, source_zp_id, salary_sum, salary_entry_count, salary_review_count)
                VALUES
                    ('2026-09-01', 1, 10, 100.00, 1, 2),
                    ('2026-09-02', 1, 11, 50.00, 1, 1),
                    ('2026-09-02', 3, 12, 900.00, 1, 9)
                """);

        service = new AnalyticsSalarySourceService(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void activeUserTotalsUseCanonicalRowsWithoutMultiplyingSalaryByRoleCount() {
        var rows = service.totalsForActiveUsers(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30)
        );

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().userId()).isEqualTo(1L);
        assertThat(rows.getFirst().salarySum()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(rows.getFirst().salaryEntryCount()).isEqualTo(2L);
        assertThat(rows.getFirst().salaryReviewCount()).isEqualTo(3L);
        assertThat(rows.get(1).userId()).isEqualTo(2L);
        assertThat(rows.get(1).salarySum()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
