package com.hunt.otziv.client_messages.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateBatchRepository.StateSeed;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ScheduledClientMessageStateBatchRepositoryMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("scheduled_message_batch_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private ScheduledClientMessageStateBatchRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        repository = new ScheduledClientMessageStateBatchRepository(
                new NamedParameterJdbcTemplate(dataSource)
        );
        jdbc.execute("DROP TABLE IF EXISTS scheduled_client_message_state");
        jdbc.execute("""
                CREATE TABLE scheduled_client_message_state (
                    state_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    scenario VARCHAR(64) NOT NULL,
                    target_type VARCHAR(32) NOT NULL,
                    target_key VARCHAR(255) NOT NULL,
                    company_id BIGINT NULL,
                    order_id BIGINT NULL,
                    archive_order_id BIGINT NULL,
                    state_status VARCHAR(32) NOT NULL,
                    next_attempt_at DATETIME(6) NULL,
                    consecutive_failures INT NOT NULL,
                    sent_count INT NOT NULL,
                    last_error_code VARCHAR(128) NULL,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    UNIQUE KEY uq_scheduled_message_target (scenario, target_key)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void aliasUpsertDistinguishesCurrentAndIncomingColumnsOnMySql9() {
        jdbc.update("""
                INSERT INTO scheduled_client_message_state (
                    scenario, target_type, target_key, company_id, order_id,
                    archive_order_id, state_status, next_attempt_at,
                    consecutive_failures, sent_count, last_error_code,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', NULL, 0, 0, NULL, NOW(6), NOW(6))
                """,
                ClientMessageScenario.PAYMENT_REMINDER.name(),
                ClientMessageTargetType.ORDER.name(),
                "order:1",
                10L,
                1L
        );
        LocalDateTime dueAt = LocalDateTime.of(2026, 8, 4, 12, 0);

        repository.upsertAll(List.of(
                seed("order:1", 1L, 77L, dueAt),
                seed("order:2", 2L, null, dueAt.plusHours(1))
        ));

        assertThat(jdbc.queryForObject("""
                SELECT archive_order_id
                FROM scheduled_client_message_state
                WHERE scenario = 'PAYMENT_REMINDER' AND target_key = 'order:1'
                """, Long.class)).isEqualTo(77L);
        assertThat(jdbc.queryForObject("""
                SELECT next_attempt_at
                FROM scheduled_client_message_state
                WHERE scenario = 'PAYMENT_REMINDER' AND target_key = 'order:1'
                """, LocalDateTime.class)).isEqualTo(dueAt);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_client_message_state",
                Integer.class
        )).isEqualTo(2);

        repository.upsertAll(List.of(seed("order:1", 1L, 77L, dueAt)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_client_message_state",
                Integer.class
        )).isEqualTo(2);
    }

    private static StateSeed seed(
            String targetKey,
            Long orderId,
            Long archiveOrderId,
            LocalDateTime dueAt
    ) {
        return new StateSeed(
                ClientMessageScenario.PAYMENT_REMINDER,
                ClientMessageTargetType.ORDER,
                targetKey,
                10L,
                orderId,
                archiveOrderId,
                dueAt
        );
    }
}
