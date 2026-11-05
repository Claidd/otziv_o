package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** V1_10_381 closes every non-test audit of the previous rules without touching finished or test audits. */
@Testcontainers
class ManagerAuditResetMigrationMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("audit_reset").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void schema() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("manager_report_review_events", "manager_report_review_disputes",
                "manager_report_review_issues", "manager_report_review_sessions")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE manager_report_review_sessions (
                    review_id BIGINT PRIMARY KEY, test_mode TINYINT(1) NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL, issue_count INT NOT NULL DEFAULT 0,
                    current_question_index INT NOT NULL DEFAULT 0, reply_prompt_message_id INT NULL,
                    answer_quality VARCHAR(32) NULL, answer_quality_reason VARCHAR(1000) NULL,
                    audit_required TINYINT(1) NOT NULL DEFAULT 0, completed_at DATETIME(6) NULL,
                    restricted_at DATETIME(6) NULL, restriction_released_at DATETIME(6) NULL,
                    ai_unavailable_started_at DATETIME(6) NULL, ai_unavailable_seconds BIGINT NOT NULL DEFAULT 0,
                    recovery_attempts INT NOT NULL DEFAULT 0, recovery_next_attempt_at DATETIME(6) NULL,
                    recovery_awaiting_context TINYINT(1) NOT NULL DEFAULT 0
                ) ENGINE=InnoDB""");
        jdbc.execute("""
                CREATE TABLE manager_report_review_issues (
                    issue_id BIGINT PRIMARY KEY, review_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
                    FOREIGN KEY (review_id) REFERENCES manager_report_review_sessions(review_id)
                ) ENGINE=InnoDB""");
        jdbc.execute("""
                CREATE TABLE manager_report_review_disputes (
                    dispute_id BIGINT PRIMARY KEY, issue_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
                    owner_comment VARCHAR(2000) NULL, resolved_at DATETIME(6) NULL, resolved_by_user_id BIGINT NULL,
                    FOREIGN KEY (issue_id) REFERENCES manager_report_review_issues(issue_id) ON DELETE CASCADE
                ) ENGINE=InnoDB""");
        jdbc.execute("""
                CREATE TABLE manager_report_review_events (
                    event_id BIGINT AUTO_INCREMENT PRIMARY KEY, review_id BIGINT NOT NULL,
                    event_type VARCHAR(48) NOT NULL, actor_user_id BIGINT NULL, actor_role VARCHAR(24) NOT NULL,
                    source VARCHAR(32) NOT NULL, payload_text LONGTEXT NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ) ENGINE=InnoDB""");
        // 187: open, restricted, a pending question and a dispute draft.
        jdbc.update("""
                INSERT INTO manager_report_review_sessions (review_id, status, issue_count, current_question_index,
                    reply_prompt_message_id, answer_quality, audit_required, restricted_at)
                VALUES (187, 'QUESTION_PENDING', 2, 0, 91, 'NEEDS_CLARIFICATION', 0, NOW(6) - INTERVAL 2 HOUR)""");
        jdbc.update("INSERT INTO manager_report_review_issues VALUES (296, 187, 'PENDING'), (297, 187, 'DISPUTE_PENDING'), (298, 187, 'ANSWERED')");
        jdbc.update("INSERT INTO manager_report_review_disputes (dispute_id, issue_id, status) VALUES (37, 297, 'DRAFT')");
        // 185: waits for the owner, the AI check paused 10 minutes ago.
        jdbc.update("""
                INSERT INTO manager_report_review_sessions (review_id, status, issue_count, audit_required,
                    ai_unavailable_started_at, ai_unavailable_seconds, recovery_attempts, recovery_awaiting_context,
                    recovery_next_attempt_at, answer_quality)
                VALUES (185, 'DISPUTED', 2, 1, NOW(6) - INTERVAL 600 SECOND, 30, 3, 1, NOW(6), 'OWNER_REVIEW_REQUIRED')""");
        jdbc.update("INSERT INTO manager_report_review_issues VALUES (292, 185, 'DISPUTED'), (293, 185, 'NEEDS_CONTEXT')");
        jdbc.update("INSERT INTO manager_report_review_disputes (dispute_id, issue_id, status) VALUES (34, 292, 'OPEN'), (35, 293, 'NEEDS_CONTEXT')");
        // 150: finished long ago; its decided dispute and answered question stay as they are.
        jdbc.update("""
                INSERT INTO manager_report_review_sessions (review_id, status, issue_count, current_question_index,
                    completed_at, answer_quality) VALUES (150, 'COMPLETED', 1, 1, '2026-10-18 23:00:00', 'ACCEPTED')""");
        jdbc.update("INSERT INTO manager_report_review_issues VALUES (165, 150, 'WITHDRAWN')");
        jdbc.update("INSERT INTO manager_report_review_disputes (dispute_id, issue_id, status, owner_comment) VALUES (23, 165, 'ACCEPTED', 'Решение владельца')");
        // 151: finished, but its dispute was never decided.
        jdbc.update("""
                INSERT INTO manager_report_review_sessions (review_id, status, issue_count, current_question_index,
                    completed_at, answer_quality) VALUES (151, 'COMPLETED', 1, 1, '2026-10-19 23:00:00', 'ACCEPTED')""");
        jdbc.update("INSERT INTO manager_report_review_issues VALUES (166, 151, 'DISPUTED')");
        jdbc.update("INSERT INTO manager_report_review_disputes (dispute_id, issue_id, status) VALUES (24, 166, 'OPEN')");
        // 900: an owner's test audit is never touched.
        jdbc.update("INSERT INTO manager_report_review_sessions (review_id, test_mode, status, issue_count) VALUES (900, 1, 'QUESTION_PENDING', 1)");
        jdbc.update("INSERT INTO manager_report_review_issues VALUES (901, 900, 'PENDING')");
    }

    @Test
    void closesOpenAuditsOfThePreviousRulesWithoutTouchingFinishedOrTestAudits() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_381__close_pre_reset_manager_audits.sql")).execute(dataSource);

        Map<String, Object> restricted = session(187);
        assertThat(restricted.get("status")).isEqualTo("COMPLETED");
        assertThat(restricted.get("completed_at")).isNotNull();
        assertThat(restricted.get("restriction_released_at")).isNotNull();
        assertThat(restricted.get("reply_prompt_message_id")).isNull();
        assertThat(restricted.get("current_question_index")).isEqualTo(2);
        assertThat(restricted.get("answer_quality")).isEqualTo("AUDIT_RESET");
        assertThat((String) restricted.get("answer_quality_reason")).contains("разбор начат заново");

        Map<String, Object> paused = session(185);
        assertThat(paused.get("status")).isEqualTo("COMPLETED");
        assertThat(paused.get("audit_required")).isEqualTo(false);
        assertThat(paused.get("ai_unavailable_started_at")).isNull();
        assertThat(((Number) paused.get("ai_unavailable_seconds")).longValue()).isBetween(625L, 700L);
        assertThat(paused.get("recovery_awaiting_context")).isEqualTo(false);
        assertThat(paused.get("recovery_next_attempt_at")).isNull();
        assertThat(paused.get("recovery_attempts")).isEqualTo(0);
        assertThat(paused.get("restriction_released_at")).as("never restricted").isNull();

        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i') FROM manager_report_review_sessions WHERE review_id = 151",
                String.class)).as("an existing completion time is kept").isEqualTo("2026-10-19 23:00");
        assertThat(session(151).get("answer_quality")).isEqualTo("AUDIT_RESET");
        assertThat(session(150).get("answer_quality")).isEqualTo("ACCEPTED");
        assertThat(session(900).get("status")).isEqualTo("QUESTION_PENDING");

        assertThat(issueStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                165L, "WITHDRAWN", 166L, "WITHDRAWN", 292L, "WITHDRAWN", 293L, "WITHDRAWN",
                296L, "WITHDRAWN", 297L, "WITHDRAWN", 298L, "ANSWERED", 901L, "PENDING"));
        assertThat(jdbc.queryForList("""
                SELECT dispute_id FROM manager_report_review_disputes
                WHERE status = 'ACCEPTED' AND resolved_at IS NOT NULL AND resolved_by_user_id IS NULL
                  AND owner_comment LIKE 'Спор закрыт без решения владельца%'""", Long.class))
                .containsExactlyInAnyOrder(24L, 34L, 35L, 37L);
        assertThat(jdbc.queryForObject("SELECT owner_comment FROM manager_report_review_disputes WHERE dispute_id = 23",
                String.class)).isEqualTo("Решение владельца");
        assertThat(jdbc.queryForList("""
                SELECT review_id FROM manager_report_review_events
                WHERE event_type = 'AUDIT_RESET' AND actor_role = 'SYSTEM' AND source = 'migration' AND actor_user_id IS NULL""",
                Long.class)).containsExactlyInAnyOrder(151L, 185L, 187L);
    }

    private Map<String, Object> session(long id) {
        return jdbc.queryForMap("SELECT * FROM manager_report_review_sessions WHERE review_id = ?", id);
    }

    private Map<Long, String> issueStatuses() {
        Map<Long, String> result = new java.util.HashMap<>();
        jdbc.query("SELECT issue_id, status FROM manager_report_review_issues",
                row -> { result.put(row.getLong("issue_id"), row.getString("status")); });
        return result;
    }
}
