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
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private DataSourceTransactionManager transactions;
    private ManagerControlClientSendWorkflow workflow;
    private ManagerClientMessageQueue queue;
    private ManagerClientMessageWorker worker;
    private ClientChatMessageSender sender;

    private BadReviewPaymentInstructionOrchestrator payments;
    private ManagerDailyControlConcreteItemRepository cards;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        sends.set(0); operation.set(null); result.set(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        deliveryHook.set(() -> { }); failAudit.set(false); failRelease.set(false);
        orderSum.set(new java.math.BigDecimal("1200.00"));
        jdbc.execute("DROP TABLE IF EXISTS mc_send_card, mc_send_parent, mc_send_control, mc_send_source, mc_send_events, mc_send_probe");
        jdbc.execute("CREATE TABLE mc_send_card(id BIGINT PRIMARY KEY,status VARCHAR(30),action_type VARCHAR(30),comment TEXT,touched DATETIME(6),resolved DATETIME(6),follow_up DATETIME(6),automatic BOOLEAN,episodes BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_parent(id BIGINT PRIMARY KEY,status VARCHAR(30),action_type VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_control(id BIGINT PRIMARY KEY,status VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_source(id BIGINT PRIMARY KEY,released BOOLEAN) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,comment TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_send_probe(name VARCHAR(40)) ENGINE=InnoDB");
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
        var lifecycle = new ManagerControlCardLifecycle(cards, items, events, mock(ManagerPerformanceService.class));
        var texts = new ManagerControlClientMessageText(mock(ScheduledClientMessageService.class), orders);
        var presenter = new ManagerControlConcretePresenter(mock(WorkerRiskIncidentRepository.class), lookup,
                mock(ManagerControlInvoiceDiagnostics.class), mock(ScheduledClientMessageStateRepository.class),
                mock(CompanyRepository.class), new ManagerControlSlaPolicy(mock(AppSettingService.class)));
        jdbc.execute("DROP TABLE IF EXISTS manager_client_message_queue");
        try { jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V1_10_313__manager_client_message_queue.sql"))); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        var encryption = new com.hunt.otziv.security.credentials.CredentialEncryptionProperties();
        encryption.setActiveKeyId("test"); encryption.setActiveKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        queue = proxy(new ManagerClientMessageQueue(jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                new com.hunt.otziv.security.credentials.CredentialCipher(encryption)));
        var actors = mock(com.hunt.otziv.u_users.api.DeferredUserAuthority.class);
        var deferredActor = new com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor(1L, "fixture-manager", 0, java.util.Set.of("ROLE_MANAGER"));
        when(actors.capture(AUTH)).thenReturn(deferredActor); when(actors.revalidate(deferredActor)).thenReturn(AUTH);
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), anyString())).thenAnswer(call -> {
            var target = (com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target) call.getArgument(0);
            var company = new Company(); company.setId(target.companyId()); company.setTitle(target.title());
            return sender.sendWithOperationId(company, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
        });
        workflow = proxy(new ManagerControlClientSendWorkflow(proxy(new ManagerControlTransactionRunner()), cards,
                controls, orders, mock(OrderService.class), sender, queue, actors, payments, access, lifecycle, texts, presenter));
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
        assertThat(state()).isEqualTo("RESOLVED");
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
        assertThat(state()).isEqualTo("RESOLVED");
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
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());
        var company = new Company(); company.setId(8L); company.setTitle("fixture company"); company.setGroupId("fixture-group");
        order.setCompany(company); order.setSum(orderSum.get()); return order;
    }
    private String state() { return jdbc.queryForObject("SELECT status FROM mc_send_card WHERE id=1", String.class); }
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
