package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.p_products.model.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Actual MySQL audit SQL, row locks and Spring propagation; no production access or provider calls. */
@Testcontainers
class DeferredReminderRestartMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("future_reminder").withUsername("root").withPassword(UUID.randomUUID().toString());
    final LocalDateTime now = LocalDateTime.of(2026, 10, 29, 14, 0);
    final LocalDateTime cycle = now.minusDays(20);
    final String target = "order:77:" + cycle;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ScheduledClientMessageService service;
    ClientChatMessageSender sender;
    PeriodicReminderRestart.DeferredReminderCheck checked;

    @BeforeEach void prepare() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        var transactions = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("DROP TABLE IF EXISTS client_reminder_restart_audit");
        jdbc.execute("DROP TABLE IF EXISTS scheduled_client_message_state");
        jdbc.execute("DROP TABLE IF EXISTS fixture_order");
        jdbc.execute("CREATE TABLE fixture_order(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO fixture_order VALUES(77)");
        jdbc.execute("""
                CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,order_id BIGINT,
                  scenario VARCHAR(60),state_status VARCHAR(30),target_key VARCHAR(100),delivery_status VARCHAR(32),
                  delivery_envelope MEDIUMTEXT,delivery_token VARCHAR(64),delivery_channel VARCHAR(32),
                  delivery_prepared_at DATETIME(6),last_error_code VARCHAR(100),sent_count INT,
                  last_success_at DATETIME(6),dispatch_generation BIGINT,next_attempt_at DATETIME(6),locked_until DATETIME(6)) ENGINE=InnoDB
                """);
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1_10_341__authorized_reminder_restart.sql"));
        jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE")));
        var order = new Order(); order.setId(77L); order.setStatusChangedAt(cycle); order.setClientMessageGeneration(3);
        var manager = new Manager(); manager.setId(2L); manager.setClientId("fixture-client"); order.setManager(manager);
        var company = new Company(); company.setId(7L); company.setGroupId("fixture-chat"); order.setCompany(company);
        var status = new OrderStatus(); status.setTitle("На проверке"); order.setStatus(status); order.setStatusChangedAt(cycle);
        var source = new OrderDeliveryControlContext.Snapshot(77L, 2L, "На проверке", 3, cycle, false, null);
        var prepared = new ScheduledClientMessageService.PreparedScheduledDelivery(30L, ClientMessageScenario.REVIEW_CHECK_REMINDER,
                target, 77L, "token", "old-operation", null, "fixture-client", "fixture-chat", "text", null, 2,
                null, null, 2, "На проверке", null, List.of());
        String envelope = new ObjectMapper().writeValueAsString(prepared);
        jdbc.update("INSERT INTO scheduled_client_message_state VALUES(30,77,'REVIEW_CHECK_REMINDER','ACTIVE',?,'UNKNOWN',?,'token','MAX',?,'state_transaction_outcome_uncertain',3,?,0,NULL,NULL)",
                target, envelope, now.minusHours(1), now.minusDays(3));
        var states = mock(ScheduledClientMessageStateRepository.class);
        when(states.findById(anyLong())).thenAnswer(call -> call.<Long>getArgument(0) == 30L ? Optional.of(read(false)) : Optional.empty());
        when(states.findByIdForUpdate(30L)).thenAnswer(call -> Optional.of(read(true)));
        when(states.save(any())).thenAnswer(call -> {
            ScheduledClientMessageState saved = call.getArgument(0);
            jdbc.update("UPDATE scheduled_client_message_state SET delivery_status=?,delivery_envelope=?,delivery_token=?,delivery_channel=?,delivery_prepared_at=?,last_error_code=?,dispatch_generation=?,next_attempt_at=? WHERE state_id=30",
                    saved.getDeliveryStatus(), saved.getDeliveryEnvelope(), saved.getDeliveryToken(), saved.getDeliveryChannel(), saved.getDeliveryPreparedAt(),
                    saved.getLastErrorCode(), saved.getDispatchGeneration(), saved.getNextAttemptAt());
            return saved;
        });
        var orders = mock(OrderRepository.class); when(orders.findByIdForMutation(77L)).thenReturn(Optional.of(order));
        var context = mock(OrderDeliveryControlContext.class); when(context.read(77L)).thenReturn(Optional.of(source));
        when(context.lock(77L)).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM fixture_order WHERE id=77 FOR UPDATE", Long.class);
            return Optional.of(source);
        });
        sender = mock(ClientChatMessageSender.class);
        when(sender.recordedOutcome("old-operation")).thenReturn(ClientMessageSendResult.failed("operation_unknown", "unknown"));
        when(sender.hasSettledUncertainOperation("old-operation")).thenReturn(true);
        var actual = mock(ScheduledClientMessageService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(actual, "stateRepository", states);
        ReflectionTestUtils.setField(actual, "orderRepository", orders);
        ReflectionTestUtils.setField(actual, "orderDeliveryContext", context);
        ReflectionTestUtils.setField(actual, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(actual, "messageSender", sender);
        ReflectionTestUtils.setField(actual, "appSettingService", mock(AppSettingService.class));
        ReflectionTestUtils.setField(actual, "messageTime", new ClientMessageTime(Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())));
        ReflectionTestUtils.setField(actual, "slotPlanner", new ClientMessageSlotPlanner());
        var proxy = new ProxyFactory(actual); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (ScheduledClientMessageService) proxy.getProxy();
        checked = service.checkDeferredReminderRestart(30, 2L).orElseThrow();
    }

    ScheduledClientMessageState read(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM scheduled_client_message_state WHERE state_id=30" + (lock ? " FOR UPDATE" : ""), (r, n) ->
                ScheduledClientMessageState.builder().id(30L).orderId(77L).targetKey(target).scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                    .status(ScheduledMessageStateStatus.ACTIVE).deliveryStatus(r.getString("delivery_status"))
                    .deliveryEnvelope(r.getString("delivery_envelope")).deliveryToken(r.getString("delivery_token"))
                    .deliveryChannel(r.getString("delivery_channel")).deliveryPreparedAt(r.getTimestamp("delivery_prepared_at") == null ? null : r.getTimestamp("delivery_prepared_at").toLocalDateTime())
                    .lastErrorCode(r.getString("last_error_code")).sentCount(r.getInt("sent_count")).lastSuccessAt(r.getTimestamp("last_success_at").toLocalDateTime())
                    .dispatchGeneration(r.getLong("dispatch_generation")).nextAttemptAt(r.getTimestamp("next_attempt_at") == null ? null : r.getTimestamp("next_attempt_at").toLocalDateTime()).build());
    }

    @Test void auditAndQueueCommitTogetherAndRepeatKeepsTheSameDeadline() {
        var next = tx.execute(s -> service.restartDeferredReminder(checked, null, "owner"));
        assertThat(next).isAfterOrEqualTo(now.plusDays(2));
        assertThat(read(false).getSentCount()).isEqualTo(3);
        assertThat(read(false).getLastSuccessAt()).isEqualTo(now.minusDays(3));
        assertThat(service.pendingReminderRestart(30, 2L)).contains(new PeriodicReminderRestart.PendingReminderRestart(checked.source().order(), next, true));
        assertThatThrownBy(() -> tx.execute(s -> service.restartDeferredReminder(checked, null, "owner")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isEqualTo(1);
        assertThat(service.reminderRecoveryGeneration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.deliveryStatus')) FROM client_reminder_restart_audit", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(previous_snapshot,'$.currentOrderGeneration') FROM client_reminder_restart_audit", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.envelope')) FROM client_reminder_restart_audit", String.class)).isEqualTo(checked.source().envelope());
        assertThat(read(false).getNextAttemptAt()).isEqualTo(next);
        verify(sender, never()).deliverWithOperationId(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void failedOuterCommitRollsBackTheAuditAndTheFutureGeneration() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            service.restartDeferredReminder(checked, null, "owner");
            throw new IllegalStateException("fixture outer failure");
        })).hasMessageContaining("outer failure");
        assertThat(read(false).getDeliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(read(false).getDeliveryEnvelope()).isEqualTo(checked.source().envelope());
        assertThat(read(false).getDispatchGeneration()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isZero();
        assertThat(service.reminderRecoveryGeneration()).isZero();
    }

    @Test void concurrentRepairsCreateOnlyOneNewGenerationAndOneAudit() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        Callable<Boolean> work = () -> { start.await(); try {
            tx.execute(s -> service.restartDeferredReminder(checked, null, "owner")); return true;
        } catch (org.springframework.web.server.ResponseStatusException conflict) { return false; } };
        try {
            var a = pool.submit(work); var b = pool.submit(work); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(read(false).getDispatchGeneration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isEqualTo(1);
    }

    @Test void sourceWriteRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> service.restartDeferredReminder(checked, null, "owner"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(read(false).getDeliveryStatus()).isEqualTo("UNKNOWN");
    }

    @Test void restoredFutureQueueCannotPrematurelyArchiveSevenOldConfirmedReminders() {
        tx.execute(s -> service.restartDeferredReminder(checked, null, "owner"));
        jdbc.update("UPDATE scheduled_client_message_state SET sent_count=7 WHERE state_id=30");
        var state = read(false);
        assertThat(state.getLastSuccessAt().plusDays(2)).isBefore(now);
        Boolean archive = ReflectionTestUtils.invokeMethod(service, "silenceThresholdReached", state, now);
        assertThat(archive).isFalse();
        assertThat(state.getSentCount()).isEqualTo(7);
        assertThat(state.getNextAttemptAt()).isAfterOrEqualTo(now.plusDays(2));
    }
}
