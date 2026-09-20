package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ContractorLegacyRewardRuntimeDiagnosticsMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("legacy_reward_runtime").withUsername("root").withPassword("root");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        for (String table : List.of("contractor_legacy_reward_reconciliation_items",
                "contractor_legacy_reward_reconciliation_runs", "orders", "order_statuses", "order_details",
                "reviews", "bad_review_tasks", "zp", "contractor_completion_reward_markers",
                "contractor_reward_ledger", "contractor_payment_profiles", "users", "workers", "managers")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        String migration = new String(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                "db/migration/V1_10_249__contractor_legacy_reward_reconciliation.sql")).readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : migration.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        jdbc.execute("CREATE TABLE orders (order_id BIGINT PRIMARY KEY, order_amount INT, order_status INT, order_manager BIGINT, order_pay_day DATE)");
        jdbc.execute("CREATE TABLE order_statuses (order_status_id INT PRIMARY KEY, order_status_title VARCHAR(64))");
        jdbc.execute("CREATE TABLE order_details (order_detail_id BIGINT PRIMARY KEY, order_detail_order BIGINT)");
        jdbc.execute("CREATE TABLE reviews (review_order_details BIGINT, review_publish BOOLEAN, review_publish_date DATE)");
        jdbc.execute("CREATE TABLE bad_review_tasks (bad_review_task_id BIGINT PRIMARY KEY, bad_review_task_order BIGINT, bad_review_task_worker BIGINT, bad_review_task_status VARCHAR(32), bad_review_task_completed_date DATE, bad_review_task_price DECIMAL(19,2))");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, coefficient DECIMAL(10,6))");
        jdbc.execute("CREATE TABLE managers (manager_id BIGINT PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE workers (worker_id BIGINT PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE zp (zp_id BIGINT PRIMARY KEY, zp_order BIGINT, zp_user BIGINT, zp_profession BIGINT,
                    zp_sum DECIMAL(19,2), zp_amount INT, zp_date DATE, zp_active BOOLEAN, zp_source VARCHAR(64),
                    zp_contractor_role VARCHAR(24), zp_attribution_final BOOLEAN, zp_reward_basis DECIMAL(19,2),
                    zp_attribution_snapshot TEXT)
                """);
        jdbc.execute("CREATE TABLE contractor_completion_reward_markers (id BIGINT PRIMARY KEY, order_id BIGINT, logical_source VARCHAR(64), occurred_on DATE)");
        jdbc.execute("CREATE TABLE contractor_payment_profiles (id BIGINT PRIMARY KEY, user_id BIGINT, contractor_role VARCHAR(24))");
        jdbc.execute("CREATE TABLE contractor_reward_ledger (id BIGINT PRIMARY KEY, source_zp_id BIGINT, profile_id BIGINT, order_id BIGINT, active BOOLEAN, amount_kopecks BIGINT, work_units INT, occurred_on DATE, source_code VARCHAR(64))");
        jdbc.update("INSERT INTO order_statuses VALUES (1, 'Оплачено'), (2, 'Не оплачено')");
        jdbc.update("INSERT INTO orders VALUES (91, 1, 1, 11, '2026-08-25')");
        jdbc.update("INSERT INTO order_details VALUES (501, 91)");
        jdbc.update("INSERT INTO reviews VALUES (501, TRUE, '2026-07-31')");
        jdbc.update("INSERT INTO users VALUES (101, 0.08), (102, 0.30)");
        jdbc.update("INSERT INTO managers VALUES (11, 101)");
        jdbc.update("INSERT INTO workers VALUES (12, 102)");
        jdbc.update("INSERT INTO bad_review_tasks VALUES (701, 91, 12, 'DONE', '2026-08-24', 200)");
        jdbc.update("""
                INSERT INTO zp VALUES
                (1, 91, 101, 11, 80, 1, '2026-08-25', TRUE, 'ORDER_MANAGER_REWARD', 'MANAGER', TRUE, 1000, NULL),
                (2, 91, 102, 12, 300, 1, '2026-08-25', TRUE, 'ORDER_SPECIALIST_REWARD', 'SPECIALIST', TRUE, 1000, NULL),
                (3, 91, 101, 11, 16, 1, '2026-08-25', TRUE, 'BAD_REVIEW_DONE_MANAGER:701', 'MANAGER', TRUE, 200, NULL),
                (4, 91, 102, 12, 60, 1, '2026-08-25', TRUE, 'BAD_REVIEW_DONE_SPECIALIST:701', 'SPECIALIST', TRUE, 200, NULL)
                """);
        jdbc.update("""
                INSERT INTO contractor_completion_reward_markers VALUES
                (1, 91, 'ORDER_COMPLETION_MANAGER', '2026-07-31'),
                (2, 91, 'ORDER_COMPLETION_SPECIALIST', '2026-07-31'),
                (3, 91, 'PERFORMER_PRODUCT_COMPLETION', '2026-07-31'),
                (4, 91, 'BAD_REVIEW_DONE:701', '2026-08-25')
                """);
        jdbc.update("INSERT INTO contractor_payment_profiles VALUES (1, 101, 'MANAGER'), (2, 102, 'SPECIALIST')");
        jdbc.update("""
                INSERT INTO contractor_reward_ledger VALUES
                (1, 3, 1, 91, TRUE, 1600, 1, '2026-08-25', 'BAD_REVIEW_DONE_MANAGER:701'),
                (2, 4, 2, 91, TRUE, 6000, 1, '2026-08-25', 'BAD_REVIEW_DONE_SPECIALIST:701')
                """);
    }

    @Test
    void fullyAccountedLaterWorkResolvesRuntimeWarningWithoutRelaxingActivation() throws Exception {
        assertThat(conflicts(false)).isEqualTo(1);
        assertThat(conflicts(true)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(zp_sum) FROM zp", java.math.BigDecimal.class))
                .isEqualByComparingTo("456.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM zp WHERE zp_id = 3",
            "DELETE FROM zp WHERE zp_id = 4",
            "DELETE FROM zp WHERE zp_id IN (3, 4)",
            "DELETE FROM contractor_completion_reward_markers WHERE id = 4",
            "DELETE FROM contractor_reward_ledger WHERE source_zp_id = 4",
            "UPDATE contractor_reward_ledger SET amount_kopecks = 1 WHERE source_zp_id = 4",
            "UPDATE contractor_reward_ledger SET profile_id = 1 WHERE source_zp_id = 4",
            "UPDATE contractor_reward_ledger SET source_code = 'UNKNOWN' WHERE source_zp_id = 4",
            "UPDATE contractor_reward_ledger SET active = FALSE WHERE source_zp_id = 4",
            "UPDATE zp SET zp_reward_basis = 400 WHERE zp_id = 4",
            "UPDATE zp SET zp_sum = 0 WHERE zp_id = 4",
            "UPDATE zp SET zp_attribution_final = FALSE WHERE zp_id = 4",
            "UPDATE zp SET zp_profession = 99 WHERE zp_id = 4",
            "UPDATE zp SET zp_user = 101 WHERE zp_id = 4",
            "UPDATE zp SET zp_date = '2026-08-24' WHERE zp_id = 4",
            "UPDATE bad_review_tasks SET bad_review_task_worker = 99",
            "UPDATE bad_review_tasks SET bad_review_task_completed_date = NULL",
            "UPDATE bad_review_tasks SET bad_review_task_price = 0",
            "UPDATE orders SET order_status = 2",
            "UPDATE reviews SET review_publish_date = '2026-08-21'",
            "UPDATE reviews SET review_publish_date = NULL",
            "DELETE FROM contractor_completion_reward_markers WHERE id IN (1, 2)",
            "UPDATE zp SET zp_source = NULL WHERE zp_id = 1",
            "UPDATE zp SET zp_source = 'order_manager_reward' WHERE zp_id = 1"
    })
    void missingOrChangedEvidenceStillRaisesWarning(String mutation) throws Exception {
        jdbc.update(mutation);
        assertThat(conflicts(true)).isEqualTo(1);
    }

    @Test
    void duplicateTaskSourceOrLedgerCannotBeUsedAsEvidence() throws Exception {
        jdbc.update("INSERT INTO zp SELECT 5, zp_order, zp_user, 99, zp_sum, zp_amount, zp_date, zp_active, zp_source, zp_contractor_role, zp_attribution_final, zp_reward_basis, zp_attribution_snapshot FROM zp WHERE zp_id = 4");
        assertThat(conflicts(true)).isEqualTo(1);
        jdbc.update("DELETE FROM zp WHERE zp_id = 5");
        jdbc.update("INSERT INTO contractor_reward_ledger SELECT 3, source_zp_id, profile_id, order_id, active, amount_kopecks, work_units, occurred_on, source_code FROM contractor_reward_ledger WHERE id = 2");
        assertThat(conflicts(true)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000001"})
    void noPositiveRoundedRewardDoesNotRequireAnInventedSalaryRow(String coefficient) throws Exception {
        jdbc.update("UPDATE users SET coefficient = ? WHERE id = 101", new java.math.BigDecimal(coefficient));
        jdbc.update("DELETE FROM zp WHERE zp_id = 3");
        jdbc.update("DELETE FROM contractor_reward_ledger WHERE source_zp_id = 3");
        assertThat(conflicts(true)).isZero();
        assertThat(conflicts(false)).isEqualTo(1);
    }

    @Test
    void taskCompletedAfterPaymentUsesItsOwnLaterDate() throws Exception {
        jdbc.update("UPDATE bad_review_tasks SET bad_review_task_completed_date = '2026-08-27'");
        jdbc.update("UPDATE zp SET zp_date = '2026-08-27' WHERE zp_id IN (3, 4)");
        jdbc.update("UPDATE contractor_reward_ledger SET occurred_on = '2026-08-27'");
        jdbc.update("UPDATE contractor_completion_reward_markers SET occurred_on = '2026-08-27' WHERE id = 4");
        assertThat(conflicts(true)).isZero();
    }

    @Test
    void changedCurrentRatesDoNotInvalidateExistingSalaryAndLedger() throws Exception {
        jdbc.update("UPDATE users SET coefficient = 0 WHERE id = 101");
        jdbc.update("UPDATE users SET coefficient = 0.50 WHERE id = 102");
        assertThat(conflicts(true)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM zp WHERE zp_id = 3",
            "UPDATE contractor_reward_ledger SET amount_kopecks = 1 WHERE source_zp_id = 3",
            "INSERT INTO zp SELECT 5, zp_order, zp_user, 99, zp_sum, zp_amount, zp_date, zp_active, zp_source, zp_contractor_role, zp_attribution_final, zp_reward_basis, zp_attribution_snapshot FROM zp WHERE zp_id = 3"
    })
    void zeroCurrentRateCannotHideCorruptedExistingAccounting(String mutation) throws Exception {
        jdbc.update("UPDATE users SET coefficient = 0 WHERE id = 101");
        jdbc.update(mutation);
        assertThat(conflicts(true)).isEqualTo(1);
    }

    @Test
    void everyLaterTaskNeedsIndependentEvidence() throws Exception {
        jdbc.update("INSERT INTO bad_review_tasks VALUES (702, 91, 12, 'DONE', '2026-08-24', 200)");
        assertThat(conflicts(true)).isEqualTo(1);
    }

    @Test
    void fullyReconciledPreCutoverWorkStillPassesBothPolicies() throws Exception {
        jdbc.update("UPDATE bad_review_tasks SET bad_review_task_completed_date = '2026-07-31'");
        assertThat(conflicts(true)).isZero();
        assertThat(conflicts(false)).isZero();
    }

    private long conflicts(boolean runtime) throws Exception {
        String method = runtime ? "countActiveLegacyRewardRuntimeConflicts" : "countActiveLegacyRewardCutoverConflicts";
        String sql = ContractorCompletionCutoverPreflightRepository.class.getMethod(method, LocalDate.class)
                .getAnnotation(Query.class).value();
        return Objects.requireNonNull(new NamedParameterJdbcTemplate(jdbc).queryForObject(
                sql, Map.of("startDate", LocalDate.of(2026, 8, 20)), Long.class));
    }
}
