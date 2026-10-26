package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Exercise the production reservation SQL, including ambiguous/partial executions. */
@Testcontainers
class WorkloadTransferQuotaMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("workload_quota").withUsername("root").withPassword("local-test");

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 24, 0, 0);
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;

    @BeforeEach void schema() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS workload_transfer_executions");
        jdbc.execute("DROP TABLE IF EXISTS workload_transfer_workflows");
        jdbc.execute("DROP TABLE IF EXISTS workload_transfer_emergency_assignments");
        jdbc.execute("""
                CREATE TABLE workload_transfer_workflows (
                  workload_transfer_workflow_id BIGINT PRIMARY KEY, manager_id BIGINT,
                  source_worker_id BIGINT, company_id BIGINT, graph_fingerprint VARCHAR(64),
                  status VARCHAR(80), active BOOLEAN NOT NULL, created_at DATETIME(6))
                """);
        jdbc.execute("""
                CREATE TABLE workload_transfer_executions (
                  workflow_id BIGINT, status VARCHAR(40), transferred_order_count INT,
                  transferred_review_count INT)
                """);
        jdbc.execute("""
                CREATE TABLE workload_transfer_emergency_assignments (
                  source_manager_id BIGINT, source_worker_id BIGINT,
                  status VARCHAR(40), created_at DATETIME(6))
                """);
    }

    @Test void onlyTerminalPreExecutionBlocksReleaseAllThreeQuotaScopes() throws Exception {
        workflow(1, "BLOCKED_RECOMMENDATION_STALE", false, DAY);
        workflow(2, "BLOCKED_GRAPH_CHANGED", false, DAY);
        assertReservations(0);

        workflow(3, "OFFERING", true, DAY);
        workflow(4, "APPLIED", false, DAY);
        workflow(5, "APPLYING", true, DAY);
        workflow(6, "BLOCKED_RECOMMENDATION_STALE", true, DAY);
        workflow(7, "BLOCKED_EXECUTION", false, DAY);
        assertReservations(5);

        // Any execution record keeps its reservation, even when counters are zero
        // or status is FAILED: neither proves that no assignment took place.
        int expected = 5;
        long id = 8;
        for (String blocked : List.of("BLOCKED_RECOMMENDATION_STALE", "BLOCKED_GRAPH_CHANGED")) {
            for (String execution : List.of("STARTED", "FAILED", "APPLIED", "ROLLED_BACK")) {
                workflow(id, blocked, false, DAY);
                jdbc.update("INSERT INTO workload_transfer_executions VALUES (?,?,?,?)",
                        id, execution, execution.equals("APPLIED") ? 1 : 0,
                        execution.equals("STARTED") ? 2 : 0);
                assertReservations(++expected);
                id++;
            }
        }
        // Multiple execution records cannot multiply the workflow reservation.
        jdbc.update("INSERT INTO workload_transfer_executions VALUES (8,'APPLIED',1,1)");
        workflow(99, "APPLIED", false, DAY.minusNanos(1000));
        jdbc.update("INSERT INTO workload_transfer_emergency_assignments VALUES (2,14,'APPLIED',?)", DAY);
        jdbc.update("INSERT INTO workload_transfer_emergency_assignments VALUES (2,14,'BLOCKED',?)", DAY);
        assertReservations(expected + 1);
    }

    @Test void blockedGraphHistoryIsScopedToDayAndTerminalReason() throws Exception {
        workflow(1, "BLOCKED_RECOMMENDATION_STALE", false, DAY);
        workflow(2, "BLOCKED_GRAPH_CHANGED", false, DAY.plusHours(10));
        workflow(3, "BLOCKED_GRAPH_CHANGED", false, DAY.minusNanos(1000));
        workflow(4, "APPLIED", false, DAY);
        jdbc.update("UPDATE workload_transfer_workflows SET graph_fingerprint='same' WHERE workload_transfer_workflow_id IN (1,2)");
        var rows = named.queryForList(query("findBlockedGraphsSince"), Map.of("dayStart", DAY));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("sourceWorkerId", 14L)
                .containsEntry("companyId", 598L).containsEntry("graphFingerprint", "same");
    }

    private void workflow(long id, String status, boolean active, LocalDateTime at) {
        jdbc.update("INSERT INTO workload_transfer_workflows VALUES (?,2,14,598,?,?,?,?)",
                id, "graph-" + id, status, active, at);
    }

    private void assertReservations(long expected) throws Exception {
        for (String method : List.of("reservedByManagerSince", "reservedBySourceWorkerSince")) {
            var rows = named.queryForList(query(method), Map.of("dayStart", DAY));
            long total = rows.stream().mapToLong(row -> ((Number) row.get("reservedCount")).longValue()).sum();
            assertThat(total).as(method).isEqualTo(expected);
            if (expected > 0) assertThat(rows).hasSize(1);
        }
    }

    private String query(String method) throws Exception {
        return WorkloadTransferWorkflowRepository.class.getMethod(method, LocalDateTime.class)
                .getAnnotation(Query.class).value();
    }
}
