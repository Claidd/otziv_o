package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.repository.AppSettingRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real mappings, committed snapshots and independent MySQL connections; no real provider calls. */
@Testcontainers
class ScheduledDeliveryTransactionMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("scheduled_delivery_protocol").withUsername("root").withPassword("root");
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManager entityManager;
    private static ClientMessageTransactionRunner transactions;
    private static ScheduledClientMessageStateRepository states;
    private static OrderRepository orders;
    private static AppSettingRepository settingsRepository;
    private static JpaTransactionManager transactionManager;
    private static JdbcTemplate independent;
    private ScheduledClientMessageService service;
    private ClientChatMessageSender sender;
    private Long stateId;
    private Long orderId;
    private final LocalDateTime preparedAt = LocalDateTime.of(2026, 9, 8, 10, 0);

    static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @BeforeAll static void startPersistence() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("com.hunt.otziv");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        transactionManager = new JpaTransactionManager(emf);
        transactions = new ClientMessageTransactionRunner(transactionManager);
        var repositories = new JpaRepositoryFactory(entityManager);
        states = repositories.getRepository(ScheduledClientMessageStateRepository.class);
        orders = repositories.getRepository(OrderRepository.class);
        settingsRepository = repositories.getRepository(AppSettingRepository.class);
        independent = new JdbcTemplate(dataSource());
        independent.setQueryTimeout(5);
        independent.execute("CREATE TABLE scheduled_client_message_dispatch_guard (guard_id INT PRIMARY KEY) ENGINE=InnoDB");
        independent.update("INSERT INTO scheduled_client_message_dispatch_guard VALUES (1)");
    }

    @AfterAll static void stopPersistence() { if (factory != null) factory.destroy(); }

    @BeforeEach void setup() throws Exception {
        independent.update("DELETE FROM scheduled_client_message_state");
        independent.update("DELETE FROM orders");
        independent.update("DELETE FROM app_settings");
        service = newService();
        sender = ScheduledDeliveryProtocolTest.dependency(service, "messageSender", ClientChatMessageSender.class);
        transactions.runInNewTransaction(() -> {
            var order = Order.builder().build();
            entityManager.persist(order);
            orderId = order.getId();
            var state = newState("cycle:1");
            state.setOrderId(orderId);
            entityManager.persist(state);
            stateId = state.getId();
        });
    }

    private ScheduledClientMessageService newService() throws Exception {
        var fresh = ScheduledDeliveryProtocolTest.serviceWithMockDependencies();
        ReflectionTestUtils.setField(fresh, "stateRepository", states);
        ReflectionTestUtils.setField(fresh, "orderRepository", orders);
        ReflectionTestUtils.setField(fresh, "transactionRunner", transactions);
        when(ScheduledDeliveryProtocolTest.dependency(fresh, "slotPlanner", ClientMessageSlotPlanner.class)
                .nextAllowedAt(any(), nullable(String.class))).thenAnswer(inv -> inv.getArgument(0));
        return fresh;
    }

    private AppSettingService settingsInstance() {
        var factory = new org.springframework.aop.framework.ProxyFactory(new AppSettingService(settingsRepository));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactionManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (AppSettingService) factory.getProxy();
    }

    private ScheduledClientMessageState newState(String key) {
        return ScheduledClientMessageState.builder().scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .targetType(ClientMessageTargetType.ORDER).targetKey(key).status(ScheduledMessageStateStatus.ACTIVE).build();
    }

    private ScheduledClientMessageService.PreparedScheduledDelivery prepare() {
        return transactions.callInPreparationTransaction(() -> {
            Order order = orders.findByIdForMutation(orderId).orElseThrow();
            var state = states.findByIdForUpdate(stateId).orElseThrow();
            states.lockDispatchBudget();
            var company = Company.builder().id(1L).title("Frozen company").telegramGroupChatId(-1001L)
                    .urlChat("https://t.me/company").build();
            return ReflectionTestUtils.invokeMethod(service, "persistScheduledDelivery", state, company, null,
                    "К оплате: 1200 руб.", "+79991112233", 3, null, null, order, null, preparedAt);
        });
    }

    @Test void stalledProviderSeesCommittedSnapshotAndDoesNotHoldStateOrOrderLocks() throws Exception {
        var prepared = prepare();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(sender.deliverWithOperationId(any(), nullable(String.class), nullable(String.class), any(), any(), any()))
                .thenAnswer(inv -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(independent.queryForObject("SELECT delivery_status FROM scheduled_client_message_state WHERE state_id = ?",
                            String.class, stateId)).isEqualTo("PREPARED");
                    assertThat(independent.queryForObject("SELECT delivery_envelope FROM scheduled_client_message_state WHERE state_id = ?",
                            String.class, stateId)).contains("1200", "+79991112233");
                    entered.countDown();
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                    return ClientMessageSendResult.sent("Telegram", "42");
                });
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> sending = pool.submit(() -> service.dispatchScheduledDelivery(prepared, preparedAt));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                independent.execute((ConnectionCallback<Void>) connection -> {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET innodb_lock_wait_timeout = 2");
                        statement.executeQuery("SELECT order_id FROM orders WHERE order_id = " + orderId + " FOR UPDATE").close();
                        statement.executeQuery("SELECT state_id FROM scheduled_client_message_state WHERE state_id = " + stateId + " FOR UPDATE").close();
                    } finally { connection.rollback(); }
                    return null;
                });
            } finally { release.countDown(); }
            sending.get(15, TimeUnit.SECONDS);
        }
        assertThat(states.findById(stateId).orElseThrow().getSentCount()).isEqualTo(1);
    }

    @Test void restartReconcilesReceiptWithoutDispatchAndNeverInfersUnsentFromAbsence() throws Exception {
        var prepared = prepare();
        var restarted = newService();
        var receiptSender = ScheduledDeliveryProtocolTest.dependency(restarted, "messageSender", ClientChatMessageSender.class);
        when(receiptSender.recordedOutcome(prepared.operationId())).thenReturn(ClientMessageOperationFence.unknown(),
                ClientMessageSendResult.sent("Telegram", "42"));
        ReflectionTestUtils.invokeMethod(restarted, "recoverOrdinaryDeliveries", preparedAt.plusMinutes(6));
        var unknown = states.findById(stateId).orElseThrow();
        assertThat(unknown.getDeliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(unknown.getNextAttemptAt()).isNull();
        assertThat(unknown.getDeliveryEnvelope()).contains("1200");
        ReflectionTestUtils.invokeMethod(restarted, "recoverOrdinaryDeliveries", preparedAt.plusMinutes(12));
        ReflectionTestUtils.invokeMethod(restarted, "recoverOrdinaryDeliveries", preparedAt.plusMinutes(18));
        assertThat(states.findById(stateId).orElseThrow().getSentCount()).isEqualTo(1);
        verify(receiptSender, times(2)).recordedOutcome(prepared.operationId());
        verify(receiptSender, never()).deliverWithOperationId(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(sender);
    }

    @Test void anotherInstanceCanDisableLiveAfterCommitDespiteWarmOnCacheAndResumeTheSameEnvelope() {
        var settingsA = settingsInstance();
        var settingsB = settingsInstance();
        settingsB.setBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        assertThat(settingsA.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).isTrue();
        ReflectionTestUtils.setField(service, "appSettingService", settingsA);
        var prepared = prepare();
        String saved = states.findById(stateId).orElseThrow().getDeliveryEnvelope();
        settingsB.setBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, false);
        assertThat(settingsA.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).isTrue();

        service.dispatchScheduledDelivery(prepared, preparedAt);

        var paused = states.findById(stateId).orElseThrow();
        assertThat(paused.getDeliveryStatus()).isEqualTo("RETRYABLE");
        assertThat(paused.getDeliveryEnvelope()).isEqualTo(saved);
        assertThat(paused.getDeliveryToken()).isEqualTo(prepared.token());
        assertThat(paused.getSentCount()).isZero();
        verifyNoInteractions(sender);
        settingsB.setBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        assertThat(settingsA.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).isFalse();
        var resumed = prepare();
        assertThat(resumed).isEqualTo(prepared);
        when(sender.deliverWithOperationId(any(), nullable(String.class), nullable(String.class), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        service.dispatchScheduledDelivery(resumed, preparedAt);
        verify(sender).deliverWithOperationId(eq(prepared.target()), nullable(String.class), nullable(String.class),
                eq(prepared.message()), any(), eq(prepared.operationId()));
        assertThat(states.findById(stateId).orElseThrow().getSentCount()).isEqualTo(1);
    }

    @Test void committedSentRetainsChannelGapAcrossInstancesWithWarmOldSettingsCache() throws Exception {
        var settingsA = settingsInstance();
        var settingsB = settingsInstance();
        LocalDateTime oldSentAt = preparedAt.minusDays(1);
        settingsA.setString("client.messages.last-sent-at.Telegram", oldSentAt.toString());
        settingsA.setString("client.messages.last-sent-at.ANY", oldSentAt.toString());
        assertThat(settingsB.getString("client.messages.last-sent-at.Telegram", null)).isEqualTo(oldSentAt.toString());
        assertThat(settingsB.getString("client.messages.last-sent-at.ANY", null)).isEqualTo(oldSentAt.toString());
        ReflectionTestUtils.setField(service, "appSettingService", settingsA);
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-08T04:00:00Z"), java.time.ZoneId.systemDefault());
        ReflectionTestUtils.setField(service, "clock", clock);
        var prepared = prepare();
        when(sender.deliverWithOperationId(any(), nullable(String.class), nullable(String.class), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        service.dispatchScheduledDelivery(prepared, preparedAt);
        assertThat(states.latestReservedDeliveryAt("Telegram")).isEmpty();
        assertThat(states.findById(stateId).orElseThrow().getDeliveryEnvelope()).isNull();
        assertThat(settingsB.getString("client.messages.last-sent-at.Telegram", null)).isEqualTo(oldSentAt.toString());
        var otherInstance = newService();
        ReflectionTestUtils.setField(otherInstance, "appSettingService", settingsB);
        ReflectionTestUtils.setField(otherInstance, "slotPlanner", new ClientMessageSlotPlanner());
        ReflectionTestUtils.setField(otherInstance, "clock", clock);

        boolean admitted = transactions.callInPreparationTransaction(() -> {
            var next = newState("next-gap-candidate");
            entityManager.persist(next);
            return ReflectionTestUtils.invokeMethod(otherInstance, "reserveBackgroundDeliverySlot", next,
                    Company.builder().urlChat("https://t.me/company").build(), LocalDateTime.now(clock));
        });

        assertThat(admitted).isFalse();
        assertThat(states.findByScenarioAndTargetKey(ClientMessageScenario.REVIEW_CHECK_REMINDER, "next-gap-candidate")
                .orElseThrow().getLastErrorCode()).isEqualTo("rate_limited");
        assertThat(settingsB.getString("client.messages.last-sent-at.Telegram", null)).isEqualTo("2026-09-08T12:00");
    }

    @Test void contendedBudgetReaderSeesTheReservationCommittedByThePreviousInstance() throws Exception {
        var locked = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> transactions.callInPreparationTransaction(() -> {
                states.lockDispatchBudget();
                assertThat(states.countReservedDeliveriesSince(preparedAt.toLocalDate().atStartOfDay())).isZero();
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                var state = states.findByIdForUpdate(stateId).orElseThrow();
                state.setDeliveryStatus("PREPARED");
                state.setDeliveryPreparedAt(preparedAt);
                state.setDeliveryChannel("TELEGRAM");
                states.save(state);
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Long> second = pool.submit(() -> transactions.callInPreparationTransaction(() -> {
                // Establish a read before the first commit, reproducing the RR stale-snapshot risk.
                assertThat(states.countReservedDeliveriesSince(preparedAt.toLocalDate().atStartOfDay())).isZero();
                secondStarted.countDown();
                states.lockDispatchBudget();
                return states.countReservedDeliveriesSince(preparedAt.toLocalDate().atStartOfDay());
            }));
            try { assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        }
    }

    @Test void onlyNewPreDispatchClaimsAreRearmedAndRecoveryBackoffDoesNotStarveLaterRows() {
        var prepared = prepare();
        transactions.runInNewTransaction(() -> {
            var held = states.findByIdForUpdate(stateId).orElseThrow();
            held.setDeliveryStatus("UNKNOWN");
            held.setDeliveryRecoveryCheckedAt(preparedAt.plusMinutes(6));
            var claimed = newState("new-claim");
            claimed.setDeliveryStatus("CLAIMED");
            claimed.setLockedUntil(preparedAt.minusMinutes(1));
            entityManager.persist(claimed);
            var legacy = newState("legacy-uncertain");
            legacy.setLastErrorCode("state_transaction_in_progress");
            legacy.setLockedUntil(preparedAt.minusMinutes(1));
            entityManager.persist(legacy);
            var later = newState("later-recovery");
            later.setDeliveryStatus("PREPARED");
            later.setDeliveryPreparedAt(preparedAt.plusSeconds(1));
            later.setDeliveryEnvelope(held.getDeliveryEnvelope());
            entityManager.persist(later);
        });
        int released = transactions.callInNewTransaction(() -> states.releaseExpiredOrdinaryPreparationClaims(preparedAt));
        assertThat(released).isEqualTo(1);
        var legacy = states.findByScenarioAndTargetKey(ClientMessageScenario.REVIEW_CHECK_REMINDER, "legacy-uncertain").orElseThrow();
        assertThat(legacy.getLastErrorCode()).isEqualTo("state_transaction_in_progress");
        assertThat(legacy.getLockedUntil()).isNotNull();
        var recoverable = states.findRecoverablePreparedIds(preparedAt.plusMinutes(1), PageRequest.of(0, 20));
        assertThat(recoverable).hasSize(1).doesNotContain(prepared.stateId());
    }
}
