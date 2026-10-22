package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.p_products.api.OrderDeliveryControlContext.Snapshot;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ManagerControlRepairContactCarryMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("repair_contact_carry").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private ManagerControlRepairContactCarry carry;
    private Snapshot order;
    private LocalDateTime cycle, touched, followUp;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS manager_daily_control_concrete_items,manager_daily_controls,scheduled_client_message_state,scheduled_client_message_attempts");
        jdbc.execute("CREATE TABLE manager_daily_controls(daily_control_id BIGINT PRIMARY KEY,manager_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE manager_daily_control_concrete_items(control_entity_id BIGINT PRIMARY KEY,control_id BIGINT,
                  entity_type VARCHAR(50),entity_id BIGINT,item_status VARCHAR(30),automatic_resolution BOOLEAN,
                  last_manual_touch_at DATETIME(6),follow_up_at DATETIME(6),comment VARCHAR(1000))
                """);
        jdbc.execute("""
                CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,order_id BIGINT,target_key VARCHAR(100),
                  state_status VARCHAR(30),sent_count INT,last_success_at DATETIME(6),last_error_code VARCHAR(100),delivery_status VARCHAR(30))
                """);
        jdbc.execute("CREATE TABLE scheduled_client_message_attempts(attempt_id BIGINT PRIMARY KEY,state_id BIGINT,attempt_status VARCHAR(30),attempted_at DATETIME(6))");
        carry = new ManagerControlRepairContactCarry(jdbc);
        cycle = LocalDateTime.now().minusDays(3).withNano(0);
        touched = LocalDateTime.now().minusHours(6).withNano(0);
        followUp = touched.plusDays(2);
        order = snapshot(cycle);
        jdbc.update("INSERT INTO manager_daily_controls VALUES(1,2)");
        jdbc.update("INSERT INTO manager_daily_control_concrete_items VALUES(10,1,'ORDER',77,'ACTION_TAKEN',FALSE,?,?,?)",
                touched, followUp, ManagerControlRepairContactCarry.CONFIRMED_REPAIR + "; новое сообщение не отправлялось");
        jdbc.update("INSERT INTO scheduled_client_message_state VALUES(40,77,?,'ACTIVE',1,?,NULL,NULL)",
                "order:77:" + cycle, touched.minusMinutes(1));
    }

    @Test
    void carriesConfirmedRepairToTheNextDayWithoutExtendingItsDeadline() {
        var contact = carry.latest(order).orElseThrow();
        assertThat(contact.touchedAt()).isEqualTo(touched);
        assertThat(contact.followUpAt()).isEqualTo(followUp);
        jdbc.update("INSERT INTO manager_daily_control_concrete_items VALUES(11,1,'ORDER',77,'OPEN',FALSE,NULL,NULL,NULL)");
        assertThat(carry.latest(order)).contains(contact);
    }

    @Test
    void contactReservationSeesAnotherCardRepairCommittedAfterAnEarlierRead() {
        jdbc.execute("DROP TABLE IF EXISTS carry_order_mutex");
        jdbc.execute("CREATE TABLE carry_order_mutex(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO carry_order_mutex VALUES(77)");
        jdbc.update("UPDATE manager_daily_control_concrete_items SET item_status='OPEN'");
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(new ManagerControlTransactionRunner());
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var runner = (ManagerControlTransactionRunner) factory.getProxy();
        runner.contactReservation(() -> {
            assertThat(carry.latest(order)).isEmpty();
            // Independent committed repair of another daily card, after our first consistent read.
            var concurrent = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
            concurrent.update("UPDATE manager_daily_control_concrete_items SET item_status='ACTION_TAKEN'");
            jdbc.queryForObject("SELECT id FROM carry_order_mutex WHERE id=77 FOR UPDATE", Long.class);
            assertThat(carry.latest(order).orElseThrow().followUpAt()).isEqualTo(followUp);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT follow_up_at FROM manager_daily_control_concrete_items WHERE control_entity_id=10", java.sql.Timestamp.class).toLocalDateTime())
                .isEqualTo(followUp);
    }

    @Test
    void laterAutomaticReminderDoesNotExtendTheRepairDeadlineOrCreateARepair() {
        jdbc.update("INSERT INTO scheduled_client_message_attempts VALUES(1,40,'SENT',?)", touched.minusMinutes(1));
        jdbc.update("UPDATE scheduled_client_message_state SET last_success_at=CURRENT_TIMESTAMP(6),sent_count=2");
        assertThat(carry.latest(order).orElseThrow().followUpAt()).isEqualTo(followUp);
        jdbc.update("UPDATE manager_daily_control_concrete_items SET comment='Обычное автоматическое напоминание'");
        assertThat(carry.latest(order)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE scheduled_client_message_state SET delivery_status='UNKNOWN'",
        "UPDATE scheduled_client_message_state SET delivery_status='PREPARED'",
        "UPDATE scheduled_client_message_state SET last_error_code='state_transaction_outcome_uncertain'",
        "UPDATE scheduled_client_message_state SET last_success_at=NULL",
        "UPDATE scheduled_client_message_state SET sent_count=0",
        "UPDATE scheduled_client_message_state SET state_status='DISABLED'",
        "UPDATE scheduled_client_message_state SET target_key='order:77:old-cycle'",
        "UPDATE manager_daily_controls SET manager_id=99",
        "UPDATE manager_daily_control_concrete_items SET item_status='OPEN'",
        "UPDATE manager_daily_control_concrete_items SET automatic_resolution=TRUE",
        "UPDATE manager_daily_control_concrete_items SET follow_up_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6))",
        "UPDATE manager_daily_control_concrete_items SET last_manual_touch_at=TIMESTAMPADD(DAY,-10,CURRENT_TIMESTAMP(6))",
        "UPDATE manager_daily_control_concrete_items SET comment=NULL"
    })
    void rejectsMissingProofChangedOwnershipAndExpiredOrUnrelatedActions(String change) {
        jdbc.update(change);
        assertThat(carry.latest(order)).isEmpty();
    }

    @Test
    void anotherUncertainOperationInTheCurrentCycleKeepsTheProblemVisible() {
        jdbc.update("INSERT INTO scheduled_client_message_state VALUES(41,77,?,'ACTIVE',0,NULL,'state_transaction_outcome_uncertain','UNKNOWN')",
                "order:77:" + cycle);
        assertThat(carry.latest(order)).isEmpty();
    }

    private Snapshot snapshot(LocalDateTime cycleAt) {
        return new Snapshot(77L, 2L, "На проверке", 0L, cycleAt, false, null);
    }

    @Test
    void doesNotCarryAcrossANewOrUnknownOrderCycle() {
        order = snapshot(touched.plusHours(1));
        assertThat(carry.latest(order)).isEmpty();
        order = snapshot(LocalDateTime.MIN);
        assertThat(carry.latest(order)).isEmpty();
    }
}
