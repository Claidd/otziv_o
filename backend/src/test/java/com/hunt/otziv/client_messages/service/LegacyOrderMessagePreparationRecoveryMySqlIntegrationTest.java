package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class LegacyOrderMessagePreparationRecoveryMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("legacy_preparation").withUsername("root").withPassword(UUID.randomUUID().toString());
    private static final LocalDateTime NOW = LocalDateTime.of(2026,9,11,10,0);
    JdbcTemplate jdbc;
    OrderRepository orders;
    ScheduledClientMessageStateRepository states;
    ClientMessageTransactionRunner transactions;
    LegacyOrderMessagePreparationRecovery recovery;

    @BeforeEach void setup() throws Exception {
        var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc = new JdbcTemplate(data);
        for (String name : List.of("orders","flyway_schema_history","scheduled_client_message_state",
                "scheduled_client_message_attempts","scheduled_client_message_dispatch_guard",
                "order_client_message_occurrences","payment_route_change_notification_outbox")) jdbc.execute("DROP TABLE IF EXISTS " + name);
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,status VARCHAR(80),changed_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE flyway_schema_history(version VARCHAR(50),installed_on DATETIME(6),success BOOLEAN)");
        jdbc.execute("CREATE TABLE payment_route_change_notification_outbox(id BIGINT PRIMARY KEY)");
        String migration = new ClassPathResource("db/migration/V1_9_2__scheduled_client_messages.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        // Real state/attempt DDL; the earlier unrelated companies/settings migration needs the full application schema.
        String tables = migration.substring(migration.indexOf("CREATE TABLE IF NOT EXISTS scheduled_client_message_state"),
                migration.indexOf("INSERT INTO app_settings"));
        new ResourceDatabasePopulator(new ByteArrayResource(tables.getBytes(StandardCharsets.UTF_8))).execute(data);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_241__bad_review_delivery_tokens.sql")).execute(data);
        for (String name : List.of("305__order_notification_occurrences","309__scheduled_delivery_snapshots"))
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_" + name + ".sql")).execute(data);
        jdbc.update("INSERT INTO flyway_schema_history VALUES('1.10.305','2026-09-09 19:39:42',TRUE)");
        jdbc.update("INSERT INTO orders VALUES(7,'В проверку','2026-09-10 10:00:00',0)");
        jdbc.update("""
                INSERT INTO scheduled_client_message_state(state_id,scenario,target_type,target_key,order_id,state_status,
                    last_error_code,last_error_message,consecutive_failures,delivery_status,created_at,last_attempt_at)
                VALUES(11,'REVIEW_CHECK_DELIVERY_RETRY','ORDER','order:7:2026-09-10T10:00',7,'ACTIVE',
                    'state_transaction_outcome_uncertain','Транзакция обработки откатилась. Причина: legacy_operation_unverified',
                    1,'CLAIMED','2026-09-10 10:00:00.001','2026-09-10 11:00:00')
                """);
        jdbc.update("""
                INSERT INTO scheduled_client_message_attempts(state_id,scenario,target_type,target_key,order_id,attempt_status,
                    error_code,error_message,attempted_at)
                SELECT state_id,scenario,target_type,target_key,order_id,'FAILED',last_error_code,last_error_message,last_attempt_at
                FROM scheduled_client_message_state
                """);
        transactions = new ClientMessageTransactionRunner(new DataSourceTransactionManager(data));
        orders = mock(OrderRepository.class);
        when(orders.findByIdForMutation(anyLong())).thenAnswer(call -> jdbc.query(
                "SELECT * FROM orders WHERE order_id=? FOR UPDATE", (rs,row) -> {
                    var order=new Order();order.setId(rs.getLong("order_id"));var status=new OrderStatus();status.setTitle(rs.getString("status"));
                    order.setStatus(status);order.setStatusChangedAt(rs.getTimestamp("changed_at").toLocalDateTime());
                    order.setClientMessageGeneration(rs.getLong("client_message_generation"));return order;
                },call.getArgument(0,Long.class)).stream().findFirst());
        when(orders.save(any(Order.class))).thenAnswer(call -> {Order order=call.getArgument(0);
            jdbc.update("UPDATE orders SET client_message_generation=? WHERE order_id=?",order.getClientMessageGeneration(),order.getId());return order;});
        states=mock(ScheduledClientMessageStateRepository.class);
        when(states.findById(anyLong())).thenAnswer(call -> jdbc.query("SELECT * FROM scheduled_client_message_state WHERE state_id=?",
                (rs,row) -> state(rs),call.getArgument(0,Long.class)).stream().findFirst());
        when(states.findByIdForUpdate(anyLong())).thenAnswer(call -> jdbc.query("SELECT * FROM scheduled_client_message_state WHERE state_id=? FOR UPDATE",
                (rs,row) -> state(rs),call.getArgument(0,Long.class)).stream().findFirst());
        when(states.save(any(ScheduledClientMessageState.class))).thenAnswer(call -> {ScheduledClientMessageState state=call.getArgument(0);
            jdbc.update("""
                    UPDATE scheduled_client_message_state SET delivery_status=?,last_error_code=?,last_error_message=?,
                        locked_until=?,next_attempt_at=?,delivery_recovery_checked_at=? WHERE state_id=?
                    """,state.getDeliveryStatus(),state.getLastErrorCode(),state.getLastErrorMessage(),state.getLockedUntil(),
                    state.getNextAttemptAt(),state.getDeliveryRecoveryCheckedAt(),state.getId());return state;});
        var attempts=mock(ScheduledClientMessageAttemptRepository.class);
        when(attempts.save(any(ScheduledClientMessageAttempt.class))).thenAnswer(call -> {ScheduledClientMessageAttempt a=call.getArgument(0);
            jdbc.update("""
                    INSERT INTO scheduled_client_message_attempts(state_id,scenario,target_type,target_key,order_id,
                        attempt_status,channel,error_code,error_message,attempted_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,a.getStateId(),a.getScenario().name(),a.getTargetType().name(),a.getTargetKey(),a.getOrderId(),
                    a.getStatus().name(),a.getChannel(),a.getErrorCode(),a.getErrorMessage(),a.getAttemptedAt());return a;});
        recovery=new LegacyOrderMessagePreparationRecovery(jdbc,orders,states,attempts,transactions);
    }

    @ParameterizedTest @ValueSource(strings={"REVIEW_CHECK_DELIVERY_RETRY","PAYMENT_INVOICE_RETRY"})
    void postCutoverUnpreparedActionReturnsToQueueWithAuditAndWithoutAllocatingOperation(String scenario) {
        if (scenario.equals("PAYMENT_INVOICE_RETRY")) {
            jdbc.update("UPDATE orders SET status='Опубликовано'");
            jdbc.update("UPDATE scheduled_client_message_state SET scenario=?",scenario);
            jdbc.update("UPDATE scheduled_client_message_attempts SET scenario=?",scenario);
        }
        assertThat(recovery.recover(11,NOW)).isTrue();
        assertThat(number("SELECT client_message_generation FROM orders")).isEqualTo(1);
        var state=states.findById(11L).orElseThrow();
        assertThat(state.getNextAttemptAt()).isEqualTo(NOW);assertThat(state.getLastErrorCode()).isNull();
        assertThat(state.getDeliveryEnvelope()).isNull();assertThat(state.getDeliveryStatus()).isNull();
        assertThat(number("SELECT COUNT(*) FROM order_client_message_occurrences")).isZero();
        assertThat(number("SELECT COUNT(*) FROM scheduled_client_message_attempts WHERE error_code='legacy_preparation_recovered'")).isEqualTo(1);
        assertThat(recovery.recover(11,NOW)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"unknown","envelope","token","provider","sent","old-cycle","before-cutover",
            "occurrence","attempt-sent","attempt-ambiguous","history-missing","cutover-missing","active-claim"})
    void incompleteOrContradictoryEvidenceNeverRearms(String condition) {
        switch(condition) {
            case "unknown" -> jdbc.update("UPDATE scheduled_client_message_state SET delivery_status='UNKNOWN'");
            case "envelope" -> jdbc.update("UPDATE scheduled_client_message_state SET delivery_envelope='{}'");
            case "token" -> jdbc.update("UPDATE scheduled_client_message_state SET delivery_token='existing-token'");
            case "provider" -> jdbc.update("UPDATE scheduled_client_message_state SET delivery_channel='MAX'");
            case "sent" -> jdbc.update("UPDATE scheduled_client_message_state SET sent_count=1");
            case "old-cycle" -> jdbc.update("UPDATE scheduled_client_message_state SET target_key='order:7:old-cycle'");
            case "before-cutover" -> jdbc.update("UPDATE flyway_schema_history SET installed_on='2026-09-11 00:00:00'");
            case "occurrence" -> jdbc.update("INSERT INTO order_client_message_occurrences(order_id,logical_kind,operation_id,generation,business_generation) VALUES(7,'action:old',?,1,1)",UUID.randomUUID().toString());
            case "attempt-sent" -> jdbc.update("UPDATE scheduled_client_message_attempts SET attempt_status='SENT',channel='WhatsApp'");
            case "attempt-ambiguous" -> jdbc.update("UPDATE scheduled_client_message_attempts SET error_code=NULL,error_message=NULL");
            case "history-missing" -> jdbc.update("DELETE FROM scheduled_client_message_attempts");
            case "cutover-missing" -> jdbc.update("DELETE FROM flyway_schema_history");
            case "active-claim" -> jdbc.update("UPDATE scheduled_client_message_state SET locked_until=?",NOW.plusMinutes(1));
        }
        assertThat(recovery.recover(11,NOW)).isFalse();
        assertThat(number("SELECT client_message_generation FROM orders")).isZero();
        assertThat(states.findById(11L).orElseThrow().getNextAttemptAt()).isNull();
        assertThat(number("SELECT COUNT(*) FROM scheduled_client_message_attempts WHERE error_code='legacy_preparation_recovered'")).isZero();
    }

    @Test void recoveryAuditFailureRollsBackGenerationAndStateTogether() {
        jdbc.execute("CREATE TRIGGER refuse_recovery BEFORE INSERT ON scheduled_client_message_attempts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rejects audit'");
        assertThatThrownBy(() -> recovery.recover(11,NOW)).isInstanceOf(RuntimeException.class);
        assertThat(number("SELECT client_message_generation FROM orders")).isZero();
        assertThat(states.findById(11L).orElseThrow().getNextAttemptAt()).isNull();
    }

    @Test void concurrentRepairAndRecoveryScanRearmExactlyOnce() throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<Boolean> repair=() -> {ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return recovery.recover(11,NOW);};
            var first=pool.submit(repair);var second=pool.submit(repair);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(number("SELECT COUNT(*) FROM scheduled_client_message_attempts WHERE error_code='legacy_preparation_recovered'")).isEqualTo(1);
    }

    @Test void boundedScanFindsHistoricalMisclassificationAndTypedPreparationFailure() {
        jdbc.update("UPDATE scheduled_client_message_state SET last_error_code='legacy_operation_unverified'");
        recovery.recoverDue(NOW);
        assertThat(states.findById(11L).orElseThrow().getNextAttemptAt()).isEqualTo(NOW);
    }

    private int number(String sql) {return jdbc.queryForObject(sql,Integer.class);}
    private ScheduledClientMessageState state(ResultSet rs) throws SQLException {
        var s=new ScheduledClientMessageState();s.setId(rs.getLong("state_id"));s.setOrderId(rs.getLong("order_id"));
        s.setScenario(ClientMessageScenario.valueOf(rs.getString("scenario")));s.setTargetType(ClientMessageTargetType.valueOf(rs.getString("target_type")));
        s.setTargetKey(rs.getString("target_key"));s.setStatus(ScheduledMessageStateStatus.valueOf(rs.getString("state_status")));
        s.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());s.setLastErrorCode(rs.getString("last_error_code"));s.setLastErrorMessage(rs.getString("last_error_message"));
        s.setDeliveryStatus(rs.getString("delivery_status"));s.setDeliveryEnvelope(rs.getString("delivery_envelope"));s.setDeliveryMessage(rs.getString("delivery_message"));
        s.setDeliveryToken(rs.getString("delivery_token"));s.setDeliveryChannel(rs.getString("delivery_channel"));s.setDeliveryTaskId(rs.getObject("delivery_task_id",Long.class));
        s.setSentCount(rs.getInt("sent_count"));s.setConsecutiveFailures(rs.getInt("consecutive_failures"));
        s.setDeliveryPreparedAt(time(rs,"delivery_prepared_at"));s.setLastSuccessAt(time(rs,"last_success_at"));
        s.setLockedUntil(time(rs,"locked_until"));s.setNextAttemptAt(time(rs,"next_attempt_at"));return s;
    }
    private LocalDateTime time(ResultSet rs,String column) throws SQLException {var value=rs.getTimestamp(column);return value==null?null:value.toLocalDateTime();}
}
