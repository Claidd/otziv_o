package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ContractorRoutingCanonicalLockOrderMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    )
            .withDatabaseName("contractor_routing_lock_order")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        jdbc.execute("DROP TABLE IF EXISTS routing_decisions");
        jdbc.execute("DROP TABLE IF EXISTS contractor_profiles");
        jdbc.execute("DROP TABLE IF EXISTS users_roles");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, active BOOLEAN NOT NULL) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE users_roles (
                    user_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    PRIMARY KEY (user_id, role_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE contractor_profiles (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    role_name VARCHAR(24) NOT NULL,
                    INDEX idx_profile_user (user_id, role_name)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("CREATE TABLE routing_decisions (id BIGINT AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO users (id, active) VALUES (1, TRUE), (2, TRUE)");
        jdbc.update("INSERT INTO users_roles (user_id, role_id) VALUES (1, 10), (2, 20)");
        // Profile ids intentionally run opposite to user ids.
        jdbc.update("""
                INSERT INTO contractor_profiles (id, user_id, role_name)
                VALUES (20, 1, 'SPECIALIST'), (10, 2, 'MANAGER')
                """);
    }

    @Test
    void oppositeSpecialistManagerPrioritiesCompleteWithoutDeadlock() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> first = route(executor, start, List.of(1L, 2L), List.of(20L, 10L));
            CompletableFuture<Void> second = route(executor, start, List.of(2L, 1L), List.of(10L, 20L));

            CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routing_decisions", Integer.class))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletableFuture<Void> route(
            ExecutorService executor,
            CyclicBarrier start,
            List<Long> priorityUserIds,
            List<Long> priorityProfileIds
    ) {
        return CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            await(start);
            priorityUserIds.stream().distinct().sorted().forEach(userId -> jdbc.queryForObject(
                    "SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId
            ));
            priorityUserIds.stream().distinct().sorted().forEach(userId -> jdbc.queryForList(
                    "SELECT role_id FROM users_roles WHERE user_id = ? ORDER BY role_id FOR UPDATE",
                    Long.class,
                    userId
            ));
            priorityProfileIds.stream().distinct().sorted().forEach(profileId -> jdbc.queryForObject(
                    "SELECT id FROM contractor_profiles WHERE id = ? FOR UPDATE", Long.class, profileId
            ));
            jdbc.update("INSERT INTO routing_decisions VALUES ()");
        }), executor);
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Routing concurrency checkpoint failed", exception);
        }
    }
}
