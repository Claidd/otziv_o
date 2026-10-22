package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.*;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.payments.service.BadReviewPaymentInstructionOrchestrator;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Production workflow/collaborators, Spring transaction advice, independent MySQL connections and
 * InnoDB writes. JDBC-backed repository adapters isolate the state machine; full Hibernate mapping
 * and application startup are covered by the application's existing integration suite.
 */
@Testcontainers
class ManagerControlClientSendMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_client_send").withUsername("root").withPassword(UUID.randomUUID().toString());

    private static final Principal PRINCIPAL = () -> "fixture-manager";
    private static final Authentication AUTH = new UsernamePasswordAuthenticationToken("fixture-manager", "n/a", List.of());
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<String> operation = new AtomicReference<>();
    private final AtomicReference<ClientMessageSendResult> result = new AtomicReference<>();
    private final AtomicReference<Runnable> deliveryHook = new AtomicReference<>();
    private final AtomicBoolean failAudit = new AtomicBoolean();
    private final AtomicBoolean failRelease = new AtomicBoolean();
    private final AtomicReference<java.math.BigDecimal> orderSum = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicReference<LocalDateTime> cycle = new AtomicReference<>();
    private final AtomicReference<String> currentOrderStatus = new AtomicReference<>();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private DataSourceTransactionManager transactions;
    private ManagerControlClientSendWorkflow workflow;
    private ManagerClientMessageQueue queue;
    private ManagerClientMessageWorker worker;
    private ClientChatMessageSender sender;
    private ManagerControlOrderDeliveryState deliveryState;
    private ManagerControlRepairContactCarry repairCarry;
    private com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty uncertainty;
    private com.hunt.otziv.p_products.api.OrderDeliveryControlContext contexts;

    private BadReviewPaymentInstructionOrchestrator payments;
    private OrderService orderService;
    private ManagerDailyControlConcreteItemRepository cards;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        sends.set(0); operation.set(null); result.set(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        deliveryHook.set(() -> { }); failAudit.set(false); failRelease.set(false);
        orderSum.set(new java.math.BigDecimal("1200.00"));
        generation.set(0);
        cycle.set(LocalDateTime.of(2026, 7, 14, 0, 0));
        currentOrderStatus.set("Не оплачено");
        jdbc.execute("DROP VIEW IF EXISTS manager_daily_control_concrete_items");
        jdbc.execute("DROP TABLE IF EXISTS mc_send_card, mc_send_parent, mc_send_control, mc_send_source, mc_send_events, mc_send_probe");
        jdbc.execute("CREATE TABLE mc_send_card(id BIGINT PRIMARY KEY,status VARCHAR(30),action_type VARCHAR(30),comment TEXT,touched DATETIME(6),resolved DATETIME(6),follow_up DATETIME(6),automatic BOOLEAN,episodes BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_parent(id BIGINT PRIMARY KEY,status VARCHAR(30),action_type VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_control(id BIGINT PRIMARY KEY,status VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_source(id BIGINT PRIMARY KEY,released BOOLEAN) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,comment TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_probe(name VARCHAR(40)) ENGINE=InnoDB");
        jdbc.execute("CREATE VIEW manager_daily_control_concrete_items AS SELECT id control_entity_id,77 entity_id,'ORDER' entity_type FROM mc_send_card");
        jdbc.update("INSERT INTO mc_send_card VALUES(1,'OPEN',NULL,'original',NULL,NULL,NULL,FALSE,0)");
        jdbc.update("INSERT INTO mc_send_parent VALUES(2,'OPEN',NULL)");
        jdbc.update("INSERT INTO mc_send_control VALUES(3,'RED')");
        jdbc.update("INSERT INTO mc_send_source VALUES(77,FALSE)");

        cards = mock(ManagerDailyControlConcreteItemRepository.class);
        var items = mock(ManagerDailyControlItemRepository.class);
        var controls = mock(ManagerDailyControlRepository.class);
        var events = mock(ManagerDailyControlEventRepository.class);
        var orders = mock(OrderRepository.class);
        payments = mock(BadReviewPaymentInstructionOrchestrator.class);
        var permissions = mock(ManagerPermissionService.class);
        var users = mock(UserService.class);
        sender = mock(ClientChatMessageSender.class);
        var lookup = mock(ManagerControlWorkerTaskLookup.class);
        when(cards.findByIdForUpdate(1L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(readCard(true));
        });
        when(cards.save(any())).thenAnswer(call -> {
            ManagerDailyControlConcreteItem card = call.getArgument(0);
            jdbc.update("UPDATE mc_send_card SET status=?,action_type=?,comment=?,touched=?,resolved=?,follow_up=?,automatic=?,episodes=? WHERE id=1",
                    card.getStatus().name(), card.getActionType() == null ? null : card.getActionType().name(),
                    card.getComment(), card.getLastManualTouchAt(), card.getResolvedAt(), card.getFollowUpAt(),
                    card.isAutomaticResolution(), card.getResolvedEpisodeCount());
            return card;
        });
        when(cards.findByParentItem(any())).thenAnswer(call -> List.of(readCard(false)));
        when(items.findByControl(any())).thenAnswer(call -> List.of(readParent()));
        when(items.save(any())).thenAnswer(call -> {
            ManagerDailyControlItem item = call.getArgument(0);
            jdbc.update("UPDATE mc_send_parent SET status=?,action_type=? WHERE id=2", item.getStatus().name(),
                    item.getActionType() == null ? null : item.getActionType().name());
            return item;
        });
        when(controls.save(any())).thenAnswer(call -> {
            ManagerDailyControl control = call.getArgument(0);
            jdbc.update("UPDATE mc_send_control SET status=? WHERE id=3", control.getStatus().name());
            return control;
        });
        when(events.save(any())).thenAnswer(call -> {
            ManagerDailyControlEvent event = call.getArgument(0);
            jdbc.update("INSERT INTO mc_send_events(comment) VALUES(?)", event.getComment());
            if (failAudit.get()) throw new IllegalStateException("fixture late audit write failure");
            return event;
        });
        when(orders.findByIdForCounterUpdate(77L)).thenAnswer(call -> Optional.of(order()));
        when(orders.findById(77L)).thenAnswer(call -> Optional.of(order()));
        when(permissions.hasRole(AUTH, "ADMIN")).thenReturn(true);
        User actor = new User(); actor.setId(5L);
        when(users.findByUserName(PRINCIPAL.getName())).thenReturn(Optional.of(actor));
        when(lookup.userDisplayName(any())).thenReturn("");
        when(payments.prepareAuthorized(77L, AUTH)).thenReturn(
                new BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction("frozen fixture message", "payment-source", 77L, true));
        when(payments.releaseKnownUnsent(any(), eq(AUTH))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("UPDATE mc_send_source SET released=TRUE WHERE id=77");
            if (failRelease.get()) throw new IllegalStateException("fixture source release failure");
            return true;
        });
        when(sender.sendWithOperationId(any(), any(), any(), any(), any(), anyString())).thenAnswer(call -> {
            sends.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String token = call.getArgument(5);
            operation.set(token);
            assertThat(token).matches("[0-9a-f-]{36}");
            // Independent autocommit connection sees the durable fence before any outbound effect.
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT comment FROM mc_send_card WHERE id=1", String.class))
                    .isEqualTo("client_message_delivery_prepared:" + token);
            deliveryHook.get().run();
            return result.get();
        });
        var access = new ManagerControlAccessPolicy(mock(ManagerRepository.class), users,
                mock(ManagerAccessService.class), permissions);
        var lifecycle = new ManagerControlCardLifecycle(cards, items, events, mock(ManagerPerformanceService.class), mock(ManagerControlReadSnapshots.class), mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class));
        var texts = new ManagerControlClientMessageText(mock(ScheduledClientMessageService.class), orders);
        jdbc.execute("DROP TABLE IF EXISTS manager_client_message_queue");
        try { jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V1_10_313__manager_client_message_queue.sql"))); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        var encryption = new com.hunt.otziv.security.credentials.CredentialEncryptionProperties();
        encryption.setActiveKeyId("test"); encryption.setActiveKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        queue = proxy(new ManagerClientMessageQueue(jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                new com.hunt.otziv.security.credentials.CredentialCipher(encryption)));
        repairCarry = mock(ManagerControlRepairContactCarry.class);
        uncertainty = mock(com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty.class);
        contexts = mock(com.hunt.otziv.p_products.api.OrderDeliveryControlContext.class);
        when(contexts.read(77L)).thenAnswer(call -> Optional.of(context()));
        when(contexts.lock(77L)).thenAnswer(call -> Optional.of(context()));
        deliveryState = new ManagerControlOrderDeliveryState(queue, contexts, uncertainty, repairCarry);
        var presenter = new ManagerControlConcretePresenter(mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class), mock(com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCommitmentService.class), mock(WorkerRiskIncidentRepository.class), lookup,
                mock(ManagerControlInvoiceDiagnostics.class), mock(ScheduledClientMessageStateRepository.class),
                mock(CompanyRepository.class), new ManagerControlSlaPolicy(mock(AppSettingService.class)), deliveryState);
        var actors = mock(com.hunt.otziv.u_users.api.DeferredUserAuthority.class);
        var deferredActor = new com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor(1L, "fixture-manager", 0, java.util.Set.of("ROLE_MANAGER"));
        when(actors.capture(AUTH)).thenReturn(deferredActor); when(actors.revalidate(deferredActor)).thenReturn(AUTH);
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), anyString())).thenAnswer(call -> {
            var target = (com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target) call.getArgument(0);
            var company = new Company(); company.setId(target.companyId()); company.setTitle(target.title());
            return sender.sendWithOperationId(company, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
        });
        orderService = mock(OrderService.class);
        workflow = proxy(new ManagerControlClientSendWorkflow(proxy(new ManagerControlTransactionRunner()), cards,
                controls, orders, orderService, sender, queue, actors, payments, access, lifecycle, texts, presenter, deliveryState));
        worker = proxy(new ManagerClientMessageWorker(queue, workflow, mock(ManagerControlClientReplyWorkflow.class), sender,
                actors, () -> true, proxy(new ManagerControlTransactionRunner())));
        assertThat(AopUtils.isAopProxy(workflow)).isTrue();
    }

    private com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse enqueue() {
        int before = sends.get();
        var response = workflow.sendClientMessage(1L, PRINCIPAL, AUTH);
        assertThat(response.delivery().status()).isEqualTo("QUEUED");
        assertThat(response.itemStatus()).isEqualTo("ACTION_TAKEN");
        assertThat(sends.get()).isEqualTo(before);
        operation.set(response.delivery().operationId());
        return response;
    }

    private void due() { jdbc.update("UPDATE manager_client_message_queue SET next_attempt_at=CURRENT_TIMESTAMP(6)"); }
    private com.hunt.otziv.client_messages.api.DeliveryOperation deliveryState() { return queue.status(1L, operation.get()); }

    private ManagerControlScheduledContactGuard realReservationState() {
        jdbc.execute("DROP TABLE IF EXISTS scheduled_client_message_state,orders,order_statuses");
        jdbc.execute("CREATE TABLE order_statuses(order_status_id BIGINT PRIMARY KEY,order_status_title VARCHAR(80)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_manager BIGINT,order_status BIGINT,client_message_generation BIGINT,order_status_changed_at DATETIME(6),order_changed DATE,order_created DATE,order_waiting_for_client BOOLEAN,order_waiting_for_client_changed_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,order_id BIGINT,target_key VARCHAR(100),state_status VARCHAR(30),delivery_status VARCHAR(30),last_error_code VARCHAR(100),delivery_envelope TEXT,sent_count INT DEFAULT 0,last_success_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO order_statuses VALUES(1,?)", currentOrderStatus.get());
        jdbc.update("INSERT INTO orders VALUES(77,4,1,?,?,NULL,NULL,FALSE,NULL)", generation.get(), cycle.get());
        var currentOrders = proxy(new com.hunt.otziv.p_products.service.OrderDeliveryControlContextService(mock(OrderRepository.class), jdbc));
        when(contexts.lock(77L)).thenAnswer(call -> currentOrders.lock(77L));
        var states = mock(ScheduledClientMessageStateRepository.class);
        when(states.findByOrderIdIn(any())).thenAnswer(call -> jdbc.query("SELECT * FROM scheduled_client_message_state WHERE order_id=77", (rs,n) ->
                com.hunt.otziv.client_messages.model.ScheduledClientMessageState.builder().id(rs.getLong("state_id"))
                    .orderId(77L).targetKey(rs.getString("target_key"))
                    .status(com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus.valueOf(rs.getString("state_status")))
                    .deliveryStatus(rs.getString("delivery_status")).lastErrorCode(rs.getString("last_error_code")).build()));
        var currentScheduled = proxy(new com.hunt.otziv.client_messages.service.CurrentOrderDeliveryUncertaintyService(states, jdbc));
        when(uncertainty.hasUncertainCurrentDeliveryForUpdate(anyLong(), anyString(), nullable(String.class), anyBoolean()))
                .thenAnswer(call -> currentScheduled.hasUncertainCurrentDeliveryForUpdate(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        when(uncertainty.hasUncertainCurrentDelivery(anyLong(), anyString(), nullable(String.class)))
                .thenAnswer(call -> currentScheduled.hasUncertainCurrentDelivery(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(uncertainty.hasRecentCurrentDeliveryForUpdate(anyLong(), anyString(), nullable(String.class), any()))
                .thenAnswer(call -> currentScheduled.hasRecentCurrentDeliveryForUpdate(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        when(uncertainty.hasRecentCurrentDelivery(anyLong(), anyString(), nullable(String.class), any()))
                .thenAnswer(call -> currentScheduled.hasRecentCurrentDelivery(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        return proxy(new ManagerControlScheduledContactGuard(currentOrders, queue, repairCarry));
    }

    private void scheduledReservation(String deliveryStatus, String code) {
        jdbc.update("INSERT INTO scheduled_client_message_state(state_id,order_id,target_key,state_status,delivery_status,last_error_code,delivery_envelope) VALUES(40,77,?,'ACTIVE',?,?,NULL)",
                "order:77:" + cycle.get().withNano(0), deliveryStatus, code);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"UNKNOWN,state_transaction_outcome_uncertain", "UNKNOWN,manual_delivery_handoff_pending", "PREPARED,delivery_prepared", "CLAIMED,state_transaction_in_progress"})
    void currentScheduledReservationBlocksNewManualSendAndExposesTheReason(String deliveryStatus, String code) {
        realReservationState();
        scheduledReservation(deliveryStatus, code);
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_queue", Integer.class)).isZero();
        assertThat(deliveryState.sendBlockedReason(readCard(false))).contains("Не отправляйте повторное сообщение");
        assertThat(state()).isEqualTo("OPEN");
        assertThat(comment()).isEqualTo("original");
        assertThat(sends.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"PREPARED", "UNKNOWN"})
    void scheduledPreparationAppearingAfterEnqueueBlocksProviderDispatch(String deliveryStatus) {
        realReservationState();
        enqueue();
        scheduledReservation(deliveryStatus, "state_transaction_outcome_uncertain");
        worker.drain();
        assertThat(sends.get()).isZero();
        assertThat(deliveryState().errorCode()).isEqualTo("context_changed");
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM scheduled_client_message_state", String.class)).isEqualTo(deliveryStatus);
    }

    @Test void bareSchedulerClaimYieldsToTheAlreadyDurableManualReservation() {
        var scheduledGuard = realReservationState();
        enqueue();
        scheduledReservation("CLAIMED", "state_transaction_in_progress");
        jdbc.update("UPDATE scheduled_client_message_state SET delivery_envelope='frozen previously known-unsent retry'");
        assertThat(Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> scheduledGuard.blocksScheduledContact(77L)))).isTrue();
        worker.drain();
        assertThat(sends.get()).isEqualTo(1);
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isNull();
        // A promptly completed manual receipt still postpones the competing scheduled contact.
        assertThat(Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> scheduledGuard.blocksScheduledContact(77L)))).isTrue();
    }

    @Test void scheduledReservationFromThePreviousCycleDoesNotBlockCurrentContact() {
        realReservationState();
        scheduledReservation("UNKNOWN", "manual_delivery_handoff_pending");
        jdbc.update("UPDATE scheduled_client_message_state SET target_key='order:77:old-cycle'");
        enqueue();
        worker.drain();
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void fastAutomaticSuccessBlocksAnotherManualContactWithoutChangingBusinessPending() {
        realReservationState();
        scheduledReservation(null, null);
        jdbc.update("UPDATE scheduled_client_message_state SET sent_count=1,last_success_at=CURRENT_TIMESTAMP(6)");
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        assertThat(deliveryState.sendBlockedReason(readCard(false))).contains("Автоматическая отправка клиенту уже подтверждена");
        assertThat(deliveryState.pending(readCard(false))).isFalse();
        new TransactionTemplate(transactions).execute(status -> { deliveryState.requireMutationAllowed(readCard(false)); return null; });
        assertThat(state()).isEqualTo("OPEN");
        assertThat(readCard(false).getFollowUpAt()).isNull();
        assertThat(sends.get()).isZero();
        jdbc.update("UPDATE scheduled_client_message_state SET delivery_status='UNKNOWN',last_error_code='manual_delivery_handoff_pending'");
        assertThat(deliveryState.sendBlockedReason(readCard(false))).contains("Исход автоматической отправки");
    }

    @Test void automaticReceiptAppearingAfterManualEnqueueBlocksDispatchWithoutRenewingIt() {
        realReservationState();
        enqueue();
        scheduledReservation(null, null);
        jdbc.update("UPDATE scheduled_client_message_state SET sent_count=1,last_success_at=CURRENT_TIMESTAMP(6)");
        var receiptBefore = jdbc.queryForMap("SELECT sent_count,last_success_at FROM scheduled_client_message_state");
        worker.drain();
        assertThat(sends.get()).isZero();
        assertThat(deliveryState().errorCode()).isEqualTo("context_changed");
        assertThat(jdbc.queryForMap("SELECT sent_count,last_success_at FROM scheduled_client_message_state")).isEqualTo(receiptBefore);
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "old_cycle", "no_sent_count"})
    void onlyProvenRecentAutomaticContactFromThisCycleBlocksManualSend(String exception) {
        realReservationState();
        scheduledReservation(null, null);
        jdbc.update("UPDATE scheduled_client_message_state SET sent_count=1,last_success_at=CURRENT_TIMESTAMP(6)");
        if (exception.equals("expired")) jdbc.update("UPDATE scheduled_client_message_state SET last_success_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY)");
        if (exception.equals("old_cycle")) jdbc.update("UPDATE scheduled_client_message_state SET target_key='order:77:older-cycle'");
        if (exception.equals("no_sent_count")) jdbc.update("UPDATE scheduled_client_message_state SET sent_count=0");
        enqueue(); worker.drain();
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void scheduledCurrentReadSeesACommitAfterAnEarlierRepeatableReadSnapshot() {
        realReservationState();
        new TransactionTemplate(transactions).execute(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_client_message_state", Integer.class)).isZero();
            new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()))
                    .update("INSERT INTO scheduled_client_message_state(state_id,order_id,target_key,state_status,delivery_status,last_error_code,delivery_envelope) VALUES(40,77,?,'ACTIVE','UNKNOWN','manual_delivery_handoff_pending',NULL)", "order:77:" + cycle.get());
            assertThat(uncertainty.hasUncertainCurrentDeliveryForUpdate(77L, "order:77:" + cycle.get(), null, true)).isTrue();
            return null;
        });
    }

    @Test void concurrentScheduledPreparationOwnsTheOrderUntilItsDurableEnvelopeCommits() throws Exception {
        var scheduledGuard = realReservationState();
        var reserved = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var scheduled = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(scheduledGuard.blocksScheduledContact(77L)).isFalse();
                reserved.countDown();
                try { assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                scheduledReservation("PREPARED", "delivery_prepared");
                return null;
            }));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
            var manual = pool.submit(() -> assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH))
                    .isInstanceOf(ResponseStatusException.class));
            try {
                assertThatThrownBy(() -> manual.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { commit.countDown(); }
            scheduled.get(5, TimeUnit.SECONDS);
            manual.get(5, TimeUnit.SECONDS);
        }
        assertThat(sends.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_queue", Integer.class)).isZero();
    }

    @Test void receiptRepairCannotOverwriteAnExistingManagerDeliveryFence() {
        enqueue();
        String prepared = comment();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                repairOutcome().recordConfirmedContact(readCard(false), control(), "confirmed", PRINCIPAL, "fixture", "order:77:2026-07-14T00:00")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(comment()).isEqualTo(prepared);
        assertThat(deliveryState().status()).isEqualTo("QUEUED");
        assertThat(sends.get()).isZero();
    }

    @Test void confirmedReceiptRepairDoesNotRereadStaleScheduledUnknown() {
        when(uncertainty.hasUncertainCurrentDelivery(anyLong(), anyString(), any())).thenReturn(true);
        new TransactionTemplate(transactions).execute(status ->
                repairOutcome().recordConfirmedContact(readCard(false), control(), "confirmed", PRINCIPAL, "fixture", "order:77:2026-07-14T00:00"));
        assertThat(state()).isEqualTo("ACTION_TAKEN");
        verifyNoInteractions(uncertainty);
        assertThat(sends.get()).isZero();
    }

    @Test void recoveredContactPreventsAnotherSendUntilItsOriginalFollowUp() {
        when(repairCarry.latest(any())).thenReturn(Optional.of(new ManagerControlRepairContactCarry.Contact(
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1))));
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_queue", Integer.class)).isZero();
        assertThat(sends.get()).isZero();
    }

    @Test void orderLockReadsTheCurrentCycleAndOwnerDespiteAnOlderReadSnapshot() {
        jdbc.execute("DROP TABLE IF EXISTS orders,order_statuses");
        jdbc.execute("CREATE TABLE order_statuses(order_status_id BIGINT PRIMARY KEY,order_status_title VARCHAR(80)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_manager BIGINT,order_status BIGINT,client_message_generation BIGINT,order_status_changed_at DATETIME(6),order_changed DATE,order_created DATE,order_waiting_for_client BOOLEAN,order_waiting_for_client_changed_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO order_statuses VALUES(1,'На проверке')");
        jdbc.update("INSERT INTO orders VALUES(77,4,1,0,?,NULL,NULL,FALSE,NULL)", cycle.get());
        var repository = mock(OrderRepository.class);
        when(repository.findById(77L)).thenReturn(Optional.of(order()));
        var api = proxy(new com.hunt.otziv.p_products.service.OrderDeliveryControlContextService(repository, jdbc));
        LocalDateTime nextCycle = cycle.get().plusDays(1);
        new TransactionTemplate(transactions).execute(status -> {
            assertThat(api.read(77L).orElseThrow().cycleAt()).isEqualTo(cycle.get());
            jdbc.queryForObject("SELECT order_manager FROM orders WHERE order_id=77", Long.class);
            new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()))
                    .update("UPDATE orders SET order_manager=9,client_message_generation=1,order_status_changed_at=? WHERE order_id=77", nextCycle);
            var current = api.lock(77L).orElseThrow();
            assertThat(current.managerId()).isEqualTo(9L);
            assertThat(current.generation()).isEqualTo(1);
            assertThat(current.cycleAt()).isEqualTo(nextCycle);
            return null;
        });
    }

    private ManagerControlRepairOutcome repairOutcome() {
        var examples = mock(ManagerControlProblemExamples.class);
        when(examples.limit(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        return new ManagerControlRepairOutcome(examples, mock(ManagerControlAccessPolicy.class),
                mock(ManagerControlCardLifecycle.class), mock(ManagerControlConcretePresenter.class),
                mock(ManagerDailyControlRepository.class), cards, deliveryState);
    }

    @Test void deliveredReminderKeepsClientFollowUpInsteadOfClaimingTheOrderResolved() {
        enqueue();
        workflow.validateQueued(queue.snapshot(operation.get()), AUTH);
        worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(state()).isEqualTo("ACTION_TAKEN");
        assertThat(readCard(false).getResolvedAt()).isNull();
        assertThat(readCard(false).getFollowUpAt()).isAfter(LocalDateTime.now().plusDays(1));
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(sends.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Опубликовано", "Выставлен счет"})
    void invoiceStageTransitionRetainsCompletionAndRejectsImmediateRepeat(String sourceStatus) throws Exception {
        currentOrderStatus.set(sourceStatus);
        when(orderService.changeStatusForOrder(eq(77L), anyString())).thenAnswer(call -> {
            currentOrderStatus.set(call.getArgument(1));
            cycle.set(LocalDateTime.now());
            generation.incrementAndGet();
            return true;
        });
        enqueue();
        worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(state()).isEqualTo("RESOLVED");
        assertThat(currentOrderStatus.get()).isEqualTo("Опубликовано".equals(sourceStatus) ? "Выставлен счет" : "Напоминание");
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void dailyCopyRestoresConfirmedContactAndDoesNotReopenAfterSnapshotRefresh() {
        enqueue(); worker.drain();
        var copy = readCard(false); copy.setId(2L); copy.setStatus(ManagerDailyControlItemStatus.OPEN);
        copy.setComment(null); copy.setFollowUpAt(null); copy.setLastManualTouchAt(null);
        assertThat(deliveryState.restoreConfirmedContact(copy)).isTrue();
        assertThat(copy.getStatus()).isEqualTo(ManagerDailyControlItemStatus.ACTION_TAKEN);
        var snapshot = new ManagerControlConcreteSnapshotWorkflow(mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class),
                mock(ManagerControlProblemExamples.class), mock(ManagerControlCardLifecycle.class),
                mock(ManagerControlSlaPolicy.class), mock(ManagerControlConcretePresenter.class), cards, deliveryState);
        assertThat(snapshot.reopenResolvedConcreteItemStillActive(readParent(), copy)).isFalse();
        assertThat(snapshot.isConcreteSnoozed(copy)).isTrue();
        assertThatThrownBy(() -> deliveryState.requireSendAllowed(copy, context())).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE manager_client_message_queue SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY)");
        assertThat(deliveryState.delivery(copy)).isNull();
        assertThatCode(() -> deliveryState.requireSendAllowed(copy, context())).doesNotThrowAnyException();
    }

    @Test void unknownReceiptSurvivesLostCommentAndDailyCopyButDoesNotFenceANewCycle() {
        result.set(ClientMessageSendResult.failed("operation_unknown", "fixture"));
        enqueue(); worker.drain();
        jdbc.update("UPDATE mc_send_card SET status='OPEN',comment=NULL WHERE id=1");
        var copy = readCard(false); copy.setId(2L);
        assertThatThrownBy(() -> deliveryState.requireSendAllowed(copy, context())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> deliveryState.requireMutationAllowed(copy)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        assertThat(deliveryState.delivery(copy).status()).isEqualTo("UNKNOWN");
        generation.incrementAndGet();
        assertThat(deliveryState.delivery(copy)).isNull();
        assertThatCode(() -> deliveryState.requireSendAllowed(copy, context())).doesNotThrowAnyException();
        assertThat(queue.status(1, operation.get()).status()).isEqualTo("UNKNOWN");
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void provenSentWithLostLegacyFenceFinalizesByReceiptWithoutAnotherSend() {
        failAudit.set(true); enqueue(); worker.drain();
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        jdbc.update("UPDATE mc_send_card SET status='OPEN',comment=NULL WHERE id=1");
        failAudit.set(false);
        when(sender.recordedOutcome(operation.get())).thenReturn(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        due(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isNull();
        assertThat(state()).isEqualTo("ACTION_TAKEN");
        assertThat(readCard(false).getFollowUpAt()).isAfter(LocalDateTime.now().plusDays(1));
        assertThat(sends.get()).isEqualTo(1);
    }

    private String historicalSuccessor(String state, String code, LocalDateTime createdAt, LocalDateTime updatedAt) throws Exception {
        var original = queue.snapshot(operation.get());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.preparedJson());
        String id = UUID.randomUUID().toString();
        frozen.put("deliveryToken", id);
        var copy = new ManagerClientMessageQueue.Command(id, original.cardId(), "CONTROL", frozen.toString(), original.actor());
        new TransactionTemplate(transactions).execute(status -> { queue.enqueue(copy); return null; });
        jdbc.update("UPDATE manager_client_message_queue SET state=?,error_code=?,created_at=?,updated_at=?,next_attempt_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)) WHERE operation_id=?",
                state, code, createdAt, updatedAt, id);
        return id;
    }

    private String supersededContact(int successorAgeDays) throws Exception {
        failAudit.set(true); enqueue(); worker.drain(); failAudit.set(false);
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toLocalDateTime();
        jdbc.update("UPDATE manager_client_message_queue SET created_at=?,updated_at=?,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?",
                now.minusDays(10), now.minusDays(9), operation.get());
        LocalDateTime contact = now.minusDays(successorAgeDays);
        String successor = historicalSuccessor("SENT", null, contact.minusMinutes(1), contact);
        jdbc.update("UPDATE mc_send_card SET status='ACTION_TAKEN',comment='later confirmed contact',touched=?,follow_up=? WHERE id=1",
                contact, contact.plusDays(2));
        when(sender.recordedOutcome(operation.get())).thenReturn(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        return successor;
    }

    @ParameterizedTest @ValueSource(ints = {0, 4})
    void supersededConfirmedOperationRetiresOnlyItsLeaseWithoutRenewingContact(int successorAgeDays) throws Exception {
        String successor = supersededContact(successorAgeDays);
        var cardBefore = jdbc.queryForMap("SELECT * FROM mc_send_card WHERE id=1");
        var parentBefore = jdbc.queryForMap("SELECT * FROM mc_send_parent WHERE id=2");
        var sourceBefore = jdbc.queryForMap("SELECT * FROM mc_send_source WHERE id=77");
        var oldClock = jdbc.queryForMap("SELECT created_at,updated_at,next_attempt_at,attempts FROM manager_client_message_queue WHERE operation_id=?", operation.get());
        var newerBefore = jdbc.queryForMap("SELECT * FROM manager_client_message_queue WHERE operation_id=?", successor);
        int eventsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM mc_send_events", Integer.class);

        worker.drain();

        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("superseded_by_confirmed_contact");
        assertThat(jdbc.queryForMap("SELECT created_at,updated_at,next_attempt_at,attempts FROM manager_client_message_queue WHERE operation_id=?", operation.get())).isEqualTo(oldClock);
        assertThat(jdbc.queryForMap("SELECT * FROM manager_client_message_queue WHERE operation_id=?", successor)).isEqualTo(newerBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM mc_send_card WHERE id=1")).isEqualTo(cardBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM mc_send_parent WHERE id=2")).isEqualTo(parentBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM mc_send_source WHERE id=77")).isEqualTo(sourceBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_send_events", Integer.class)).isEqualTo(eventsBefore);
        assertThat(queue.claim()).isEmpty();
        assertThat(sends.get()).isEqualTo(1);
        if (successorAgeDays > 2) {
            var copy = readCard(false); copy.setStatus(ManagerDailyControlItemStatus.OPEN); copy.setFollowUpAt(null);
            assertThat(deliveryState.restoreConfirmedContact(copy)).isFalse();
            assertThat(deliveryState.delivery(copy)).isNull();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"UNKNOWN,receipt_unavailable", "SENDING,", "RETRYABLE,gateway_not_ready", "FAILED,context_changed", "SENT,finalization_required"})
    void aNewerUncertainOperationPreventsRetiringAnOldConfirmedFence(String state, String code) throws Exception {
        supersededContact(4);
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toLocalDateTime();
        historicalSuccessor(state, code, now.minusDays(1), now.minusDays(1));
        var cardBefore = jdbc.queryForMap("SELECT * FROM mc_send_card WHERE id=1");
        worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(jdbc.queryForMap("SELECT * FROM mc_send_card WHERE id=1")).isEqualTo(cardBefore);
        assertThat(sends.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"source", "cycle", "receipt", "claim"})
    void supersededSettlementRequiresUnchangedSourceProofAndClaim(String change) throws Exception {
        supersededContact(4);
        var cardBefore = jdbc.queryForMap("SELECT * FROM mc_send_card WHERE id=1");
        if (change.equals("source")) orderSum.set(new java.math.BigDecimal("1500.00"));
        if (change.equals("cycle")) generation.incrementAndGet();
        if (change.equals("receipt")) when(sender.recordedOutcome(operation.get()))
                .thenReturn(ClientMessageSendResult.failed("operation_envelope_mismatch", "fixture rejected receipt"));
        if (change.equals("claim")) {
            var claim = queue.claim().orElseThrow();
            jdbc.update("UPDATE manager_client_message_queue SET claim_token='replacement-worker' WHERE operation_id=?", operation.get());
            workflow.completeQueued(claim, queue.snapshot(operation.get()), ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"), AUTH, "SENT", null, 0);
        } else worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(jdbc.queryForMap("SELECT * FROM mc_send_card WHERE id=1")).isEqualTo(cardBefore);
        assertThat(sends.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "known_unsent", "unavailable"})
    void provenSentCanNeverDowngradeOrResendWhenLaterReceiptIsMissingOrContradictory(String receipt) {
        failAudit.set(true); enqueue(); worker.drain();
        if (receipt.equals("known_unsent")) when(sender.recordedOutcome(operation.get()))
                .thenReturn(ClientMessageSendResult.failed("gateway_not_ready", "fixture"));
        else if (receipt.equals("unavailable")) when(sender.recordedOutcome(operation.get()))
                .thenThrow(new IllegalStateException("fixture receipt unavailable"));
        due(); worker.drain(); due(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void legacyProvenSentCannotFinalizeANewerCycleWithTheSameStatus() throws Exception {
        failAudit.set(true); enqueue(); worker.drain();
        var command = queue.snapshot(operation.get());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(command.preparedJson());
        source.remove(java.util.List.of("sourceGeneration", "sourceStatusChangedAt"));
        var legacy = new ManagerClientMessageQueue.Command(command.operationId(), command.cardId(), command.kind(), source.toString(), command.actor());
        var properties = new com.hunt.otziv.security.credentials.CredentialEncryptionProperties();
        properties.setActiveKeyId("test"); properties.setActiveKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new com.hunt.otziv.security.credentials.CredentialCipher(properties);
        jdbc.update("UPDATE manager_client_message_queue SET envelope_ciphertext=? WHERE operation_id=?", cipher.encrypt(queue.encode(legacy)), operation.get());
        // Compare with the persisted operation clock; the test JVM and MySQL may use different zones.
        cycle.set(jdbc.queryForObject("SELECT created_at FROM manager_client_message_queue WHERE operation_id=?",
                Timestamp.class, operation.get()).toLocalDateTime().plusHours(1));
        failAudit.set(false);
        when(sender.recordedOutcome(operation.get())).thenReturn(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        due(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(readCard(false).getFollowUpAt()).isNull();
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void staleRecoveryLeaseCannotRestoreTheCardFence() {
        failAudit.set(true); enqueue(); worker.drain();
        jdbc.update("UPDATE mc_send_card SET status='OPEN',comment=NULL WHERE id=1");
        due();
        var claim = queue.claim().orElseThrow();
        jdbc.update("UPDATE manager_client_message_queue SET claim_token='new-worker' WHERE operation_id=?", operation.get());
        workflow.completeQueued(claim, queue.snapshot(operation.get()),
                ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"), AUTH, "SENT", null, 0);
        assertThat(comment()).isNull();
        assertThat(state()).isEqualTo("OPEN");
        assertThat(readCard(false).getFollowUpAt()).isNull();
    }

    @Test void crashedFinalizerRetainsSentProofAcrossLeaseRecovery() {
        failAudit.set(true); enqueue(); worker.drain(); due();
        var abandoned = queue.claim().orElseThrow();
        assertThat(abandoned.previousState()).isEqualTo("SENT");
        assertThat(deliveryState().status()).isEqualTo("SENT");
        jdbc.update("UPDATE manager_client_message_queue SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE)");
        var recovered = queue.claim().orElseThrow();
        assertThat(recovered.previousState()).isEqualTo("SENT");
        assertThat(recovered.mayDispatch()).isFalse();
        assertThat(deliveryState().status()).isEqualTo("SENT");
    }

    @Test void changedAmountBeforeDispatchCannotSendAnObsoletePaymentMessage() {
        enqueue(); orderSum.set(new java.math.BigDecimal("1500.00")); worker.drain();
        assertThat(sends.get()).isZero();
        assertThat(deliveryState().status()).isEqualTo("FAILED");
        assertThat(deliveryState().errorCode()).isEqualTo("context_changed");
        assertThat(state()).isEqualTo("ACTION_TAKEN");
    }

    @Test void changedAmountDuringIoKeepsProofWithoutClosingTheChangedOrder() {
        enqueue(); deliveryHook.set(() -> orderSum.set(new java.math.BigDecimal("1500.00"))); worker.drain();
        assertThat(sends.get()).isEqualTo(1);
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(state()).isEqualTo("ACTION_TAKEN");
    }

    @Test void knownUnsentReceiptAfterUnknownRetriesTheOriginalOperation() {
        result.set(ClientMessageSendResult.failed("operation_unknown", "fixture"));
        enqueue(); worker.drain();
        String original = operation.get();
        when(sender.recordedOutcome(original)).thenReturn(ClientMessageSendResult.failed("gateway_not_ready", "not admitted"));
        due(); worker.drain();
        assertThat(sends.get()).isEqualTo(1);
        result.set(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        due(); worker.drain();
        assertThat(operation.get()).isEqualTo(original);
        assertThat(sends.get()).isEqualTo(2);
        assertThat(deliveryState().status()).isEqualTo("SENT");
    }

    @Test
    void requestCommitsOnlyTheQueueAndWorkerFinalizationSurvivesCallerRollback() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO mc_send_probe VALUES('outer')");
            enqueue();
            assertThat(sends.get()).isZero();
            worker.drain();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("fixture caller rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("ACTION_TAKEN");
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_send_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_send_probe", Integer.class)).isZero();
        assertThat(sends.get()).isEqualTo(1);
        assertThat(sourceReleased()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation_unknown", "operation_pending", "operation_running", "operation_result_not_durable"})
    void returnedUnknownRetainsSourceAndBlocksAnotherRequest(String code) {
        result.set(ClientMessageSendResult.failed(code, "fixture unconfirmed response"));
        enqueue(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("UNKNOWN");
        assertThat(comment()).isEqualTo("client_message_delivery_unknown:" + operation.get());
        assertThat(sourceReleased()).isFalse();
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        due(); worker.drain();
        assertThat(sends.get()).isEqualTo(1);
        verify(payments, never()).releaseKnownUnsent(any(), any());
    }

    @Test
    void positiveReceiptAfterLostResponseFinalizesWithoutAnotherSend() {
        result.set(ClientMessageSendResult.failed("operation_unknown", "fixture"));
        enqueue(); worker.drain();
        when(sender.recordedOutcome(operation.get())).thenReturn(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        due(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(state()).isEqualTo("ACTION_TAKEN");
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test
    void admissionRejectionsAreBoundedAndReleaseSourceOnlyAtTerminalFailure() {
        result.set(ClientMessageSendResult.failed("gateway_not_ready", "fixture admission rejection"));
        enqueue(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("RETRYABLE");
        assertThat(sourceReleased()).isFalse();
        for (int i = 0; i < 4; i++) { due(); worker.drain(); }
        assertThat(deliveryState().status()).isEqualTo("FAILED");
        assertThat(state()).isEqualTo("OPEN");
        assertThat(comment()).isEqualTo("original");
        assertThat(sourceReleased()).isTrue();
        assertThat(sends.get()).isEqualTo(5);
    }

    @Test
    void failedSourceReleaseRollsBackAndCannotReopenTheCard() {
        result.set(ClientMessageSendResult.failed("gateway_not_ready", "fixture admission rejection"));
        failRelease.set(true); enqueue();
        for (int i = 0; i < 5; i++) { due(); worker.drain(); }
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(comment()).isEqualTo("client_message_delivery_unknown:" + operation.get());
        assertThat(sourceReleased()).isFalse();
    }

    @Test
    void databaseFailureAfterDeliveryRetainsProofButDoesNotPartiallyCloseTheCard() {
        failAudit.set(true); enqueue(); worker.drain();
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(deliveryState().errorCode()).isEqualTo("finalization_required");
        assertThat(state()).isEqualTo("ACTION_TAKEN");
        assertThat(jdbc.queryForObject("SELECT episodes FROM mc_send_card", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_send_events", Integer.class)).isZero();
        assertThat(sourceReleased()).isFalse();
        due(); worker.drain(); assertThat(sends.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replacedCardFenceCannotBeOverwrittenByTheOldDelivery(boolean sent) {
        result.set(sent ? ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message")
                : ClientMessageSendResult.failed("gateway_not_ready", "fixture admission rejection"));
        String replacement = "client_message_delivery_prepared:" + UUID.randomUUID();
        deliveryHook.set(() -> jdbc.update("UPDATE mc_send_card SET comment=? WHERE id=1", replacement));
        enqueue(); worker.drain();
        assertThat(comment()).isEqualTo(replacement);
        assertThat(sourceReleased()).isFalse();
        verify(payments, never()).releaseKnownUnsent(any(), any());
    }

    @Test
    void simultaneousRequestDuringWorkerIoCannotSendTwice() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerRelease = new CountDownLatch(1);
        deliveryHook.set(() -> {
            providerEntered.countDown();
            try { assertThat(providerRelease.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        });
        enqueue();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> worker.drain());
            try {
                assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH))
                        .isInstanceOf(ResponseStatusException.class));
                second.get(10, TimeUnit.SECONDS);
                assertThat(sends.get()).isEqualTo(1);
            } finally { providerRelease.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(deliveryState().status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_send_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void oldPreparationWithoutAQueueRecordIsNeverBackfilledOrResent() {
        String token = UUID.randomUUID().toString();
        jdbc.update("UPDATE mc_send_card SET comment=?,touched=? WHERE id=1", "client_message_delivery_prepared:" + token,
                LocalDateTime.now().minusMinutes(16));
        assertThatThrownBy(() -> workflow.sendClientMessage(1L, PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        assertThat(comment()).startsWith("client_message_delivery_unknown:" + token + ";");
        assertThat(sends.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_queue", Integer.class)).isZero();
    }

    private ManagerDailyControlConcreteItem readCard(boolean locked) {
        return jdbc.queryForObject("SELECT * FROM mc_send_card WHERE id=1" + (locked ? " FOR UPDATE" : ""), (rs, row) -> {
            var card = new ManagerDailyControlConcreteItem();
            card.setId(1L); card.setEntityId(77L); card.setEntityType("ORDER"); card.setTitle("fixture order");
            card.setControl(control()); card.setParentItem(readParent());
            card.setStatus(ManagerDailyControlItemStatus.valueOf(rs.getString("status")));
            String action = rs.getString("action_type");
            card.setActionType(action == null ? null : ManagerDailyControlActionType.valueOf(action));
            card.setComment(rs.getString("comment")); card.setLastManualTouchAt(time(rs.getTimestamp("touched")));
            card.setResolvedAt(time(rs.getTimestamp("resolved"))); card.setFollowUpAt(time(rs.getTimestamp("follow_up")));
            card.setAutomaticResolution(rs.getBoolean("automatic")); card.setResolvedEpisodeCount(rs.getLong("episodes"));
            return card;
        });
    }
    private ManagerDailyControlItem readParent() {
        return jdbc.queryForObject("SELECT * FROM mc_send_parent WHERE id=2", (rs, row) -> {
            var parent = new ManagerDailyControlItem(); parent.setId(2L); parent.setControl(control());
            parent.setCount(1); parent.setGroup(ManagerDailyControlGroup.ACTION); parent.setSeverity(ManagerDailyControlSeverity.CRITICAL);
            parent.setStatus(ManagerDailyControlItemStatus.valueOf(rs.getString("status")));
            String action = rs.getString("action_type");
            parent.setActionType(action == null ? null : ManagerDailyControlActionType.valueOf(action));
            return parent;
        });
    }
    private ManagerDailyControl control() {
        var control = new ManagerDailyControl(); control.setId(3L); control.setManager(manager()); return control;
    }
    private Manager manager() { var manager = new Manager(); manager.setId(4L); manager.setClientId("fixture-client"); return manager; }
    private Order order() {
        var order = new Order(); order.setId(77L); order.setManager(manager());
        order.setClientMessageGeneration(generation.get());
        order.setStatus(OrderStatus.builder().title(currentOrderStatus.get()).build());
        order.setStatusChangedAt(cycle.get());
        var company = new Company(); company.setId(8L); company.setTitle("fixture company"); company.setGroupId("fixture-group");
        order.setCompany(company); order.setSum(orderSum.get()); return order;
    }
    private String state() { return jdbc.queryForObject("SELECT status FROM mc_send_card WHERE id=1", String.class); }
    private com.hunt.otziv.p_products.api.OrderDeliveryControlContext.Snapshot context() {
        return new com.hunt.otziv.p_products.api.OrderDeliveryControlContext.Snapshot(77L, 4L,
                currentOrderStatus.get(), generation.get(), cycle.get(), false, null);
    }
    private String comment() { return jdbc.queryForObject("SELECT comment FROM mc_send_card WHERE id=1", String.class); }
    private boolean sourceReleased() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT released FROM mc_send_source WHERE id=77", Boolean.class)); }
    private static LocalDateTime time(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
