package com.hunt.otziv.p_products.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PaidOrderSalaryGapMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("salary_gap").withUsername("root").withPassword("root");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        for (String table : List.of("orders", "order_statuses", "contractor_payment_rollout_state", "managers",
                "users", "workers", "reviews", "order_details", "zp", "archive_zp", "contractor_reward_ledger",
                "review_recovery_tasks", "review_recovery_batches", "contractor_completion_reward_repair_state",
                "contractor_completion_reward_markers", "bad_review_tasks")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE orders (order_id BIGINT PRIMARY KEY, order_status INT, order_manager BIGINT, order_worker BIGINT, order_pay_day DATE, order_amount INT, order_sum DECIMAL(19,2))");
        jdbc.execute("CREATE TABLE order_statuses (order_status_id INT PRIMARY KEY, order_status_title VARCHAR(64))");
        jdbc.execute("CREATE TABLE contractor_payment_rollout_state (id INT, accounting_authority VARCHAR(32), attribution_start_date DATE)");
        jdbc.execute("CREATE TABLE managers (manager_id BIGINT, user_id BIGINT)");
        jdbc.execute("CREATE TABLE workers (worker_id BIGINT, user_id BIGINT)");
        jdbc.execute("CREATE TABLE users (id BIGINT, coefficient DECIMAL(5,2))");
        jdbc.execute("CREATE TABLE order_details (order_detail_id BIGINT, order_detail_order BIGINT)");
        jdbc.execute("CREATE TABLE reviews (review_order_details BIGINT, review_publish BOOLEAN, review_worker BIGINT)");
        jdbc.execute("CREATE TABLE bad_review_tasks (bad_review_task_order BIGINT, bad_review_task_worker BIGINT, bad_review_task_status VARCHAR(32), bad_review_task_price DECIMAL(19,2))");
        jdbc.execute("CREATE TABLE zp (zp_order BIGINT, zp_active BOOLEAN)");
        jdbc.execute("CREATE TABLE archive_zp (zp_order BIGINT, zp_active BOOLEAN)");
        jdbc.execute("CREATE TABLE contractor_reward_ledger (order_id BIGINT, active BOOLEAN)");
        jdbc.execute("CREATE TABLE review_recovery_tasks (review_recovery_task_order BIGINT, review_recovery_task_batch BIGINT, review_recovery_task_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE review_recovery_batches (review_recovery_batch_id BIGINT, review_recovery_batch_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE contractor_completion_reward_repair_state (order_id BIGINT, next_attempt_at DATETIME)");
        jdbc.execute("CREATE TABLE contractor_completion_reward_markers (order_id BIGINT, logical_source VARCHAR(64))");
        jdbc.update("INSERT INTO order_statuses VALUES (1, 'Оплачено'), (2, 'Не оплачено')");
        jdbc.update("INSERT INTO contractor_payment_rollout_state VALUES (1, 'PAYMENT', '2026-08-20')");
        jdbc.update("INSERT INTO users VALUES (1, 0.08), (2, 0.30)");
        jdbc.update("INSERT INTO managers VALUES (1, 1)");
        jdbc.update("INSERT INTO workers VALUES (1, 2)");
    }

    @Test
    void findsPaidOrderDespiteCompleteOldMarkersAndStopsAfterSalaryIsRestored() throws Exception {
        paidOrder(24195);
        jdbc.update("INSERT INTO contractor_completion_reward_markers VALUES (24195, 'ORDER_COMPLETION_MANAGER'), (24195, 'ORDER_COMPLETION_SPECIALIST'), (24195, 'PERFORMER_PRODUCT_COMPLETION')");
        assertThat(gaps()).containsExactly(24195L);
        jdbc.update("INSERT INTO zp VALUES (24195, TRUE)");
        assertThat(gaps()).isEmpty();
    }

    @Test
    void protectsHistoricalArchivedAndAlreadyAccountedRewards() throws Exception {
        for (int id = 1; id <= 5; id++) paidOrder(id);
        jdbc.update("UPDATE orders SET order_pay_day='2026-08-19' WHERE order_id=1");
        jdbc.update("INSERT INTO archive_zp VALUES (2, TRUE)");
        jdbc.update("INSERT INTO contractor_reward_ledger VALUES (3, TRUE)");
        jdbc.update("UPDATE orders SET order_status=2 WHERE order_id=4");
        jdbc.update("INSERT INTO zp VALUES (5, FALSE)");
        assertThat(gaps()).containsExactly(5L);
        jdbc.update("UPDATE contractor_payment_rollout_state SET accounting_authority='LEGACY'");
        assertThat(gaps()).isEmpty();
        jdbc.update("UPDATE contractor_payment_rollout_state SET accounting_authority='COMPLETION'");
        assertThat(gaps()).containsExactly(5L);
    }

    @Test
    void respectsUnfinishedWorkRecoveryBackoffAndZeroRewardRates() throws Exception {
        for (int id = 1; id <= 4; id++) paidOrder(id);
        jdbc.update("UPDATE reviews SET review_publish=FALSE WHERE review_order_details=1");
        jdbc.update("INSERT INTO review_recovery_batches VALUES (1, 'OPEN')");
        jdbc.update("INSERT INTO review_recovery_tasks VALUES (2, 1, 'PLANNED')");
        jdbc.update("INSERT INTO contractor_completion_reward_repair_state VALUES (3, '2026-09-13 00:00:00')");
        assertThat(gaps()).containsExactly(4L);
        jdbc.update("UPDATE users SET coefficient=0");
        assertThat(gaps()).isEmpty();
    }

    @Test
    void usesActualPublishedWorkerWhenCurrentAssigneeHasNoReward() throws Exception {
        paidOrder(1L);
        jdbc.update("UPDATE users SET coefficient=0 WHERE id=1");
        jdbc.update("UPDATE orders SET order_worker=NULL WHERE order_id=1");
        assertThat(gaps()).containsExactly(1L);
        jdbc.update("UPDATE reviews SET review_worker=NULL");
        assertThat(gaps()).isEmpty();
    }

    @Test
    void findsMissingSalaryForCompletedAdditionalWorkWhenBaseRatesAreZero() throws Exception {
        paidOrder(1L);
        jdbc.update("UPDATE users SET coefficient=0");
        jdbc.update("INSERT INTO users VALUES (3, 0.30)");
        jdbc.update("INSERT INTO workers VALUES (3, 3)");
        jdbc.update("INSERT INTO bad_review_tasks VALUES (1, 3, 'DONE', 200)");
        assertThat(gaps()).containsExactly(1L);
        jdbc.update("UPDATE bad_review_tasks SET bad_review_task_status='CANCELED'");
        assertThat(gaps()).isEmpty();
    }

    @Test
    void requiresPaymentDateAndRechecksEvidenceBetweenBatches() throws Exception {
        paidOrder(1L);
        jdbc.update("UPDATE orders SET order_pay_day=NULL");
        assertThat(gaps()).isEmpty();
        jdbc.update("UPDATE orders SET order_pay_day='2026-09-01'");
        assertThat(gaps()).containsExactly(1L);
        jdbc.update("INSERT INTO archive_zp VALUES (1, TRUE)");
        assertThat(gaps()).isEmpty();
    }

    private void paidOrder(long id) {
        jdbc.update("INSERT INTO orders VALUES (?, 1, 1, 1, '2026-08-21', 1, 1000)", id);
        jdbc.update("INSERT INTO order_details VALUES (?, ?)", id, id);
        jdbc.update("INSERT INTO reviews VALUES (?, TRUE, 1)", id);
    }

    private List<Long> gaps() throws Exception {
        String sql = OrderRepository.class.getMethod("findPaidOrdersWithoutSalary", LocalDateTime.class, Pageable.class)
                .getAnnotation(Query.class).value();
        return new NamedParameterJdbcTemplate(jdbc).queryForList(sql,
                new MapSqlParameterSource("dueAt", LocalDateTime.of(2026, 9, 12, 12, 0)), Long.class);
    }
}
