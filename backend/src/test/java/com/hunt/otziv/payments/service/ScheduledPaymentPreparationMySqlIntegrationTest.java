package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.service.*;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.api.OrderManualContactReservation;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.*;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderDeliveryControlContextService;
import com.hunt.otziv.p_products.status.service.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.*;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real payment workflow, Spring advice, Hibernate entities and InnoDB order locks; no external I/O. */
@Testcontainers
class ScheduledPaymentPreparationMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("scheduled_payment_preparation").withUsername("root").withPassword("fixture-password");
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManager em;
    private static JpaTransactionManager transactions;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static OrderRepository orders;
    private static PaymentLinkRepository links;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicBoolean manualReserved = new AtomicBoolean();
    private final AtomicBoolean failRendering = new AtomicBoolean();
    private final AtomicBoolean failAfterCommit = new AtomicBoolean();
    private Runnable afterRollback = () -> { };
    private ScheduledClientMessageService service;
    private PaymentLinkService payments;
    private ClientMessageTransactionRunner runner;
    private Long orderId;
    private Long stateId;
    private LocalDateTime now;
    private LocalDateTime lease;

    @BeforeAll
    static void start() {
        String url = MYSQL.getJdbcUrl();
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "sessionVariables=innodb_lock_wait_timeout=2",
                MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new JpaTransactionManager(factory.getObject()); transactions.setDataSource(ds);
        tx = new TransactionTemplate(transactions);
        var repositories = new JpaRepositoryFactory(em);
        orders = repositories.getRepository(OrderRepository.class);
        links = repositories.getRepository(PaymentLinkRepository.class);
    }

    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        now = LocalDateTime.now().withNano(0); lease = now.plusMinutes(5);
        dependencies.put(OrderRepository.class, orders); dependencies.put(PaymentLinkRepository.class, links);
        dependencies.put(JdbcTemplate.class, jdbc);
        dependencies.put(PaymentLinkTransactionExecutor.class, advised(new PaymentLinkTransactionExecutor()));
        dependencies.put(TbankPaymentProperties.class, new TbankPaymentProperties());
        // Keep the production pure helpers: Mockito's default null is not valid normalized text.
        var observations = dependency(PaymentBankObservationService.class);
        doCallRealMethod().when(observations).normalize(nullable(String.class));
        doCallRealMethod().when(observations).limit(nullable(String.class), anyInt());
        doCallRealMethod().when(observations).orderManager(any());
        when(dependency(PaymentLinkPresenter.class).normalize(nullable(String.class)))
                .thenAnswer(call -> observations.normalize(call.getArgument(0)));
        doCallRealMethod().when(dependency(PaymentLinkPresenter.class)).amountRubles(anyLong());
        when(dependency(CommonInvoiceRouteSelector.class).normalize(nullable(String.class)))
                .thenAnswer(call -> observations.normalize(call.getArgument(0)));
        when(dependency(CommonInvoiceRouteSelector.class).limit(nullable(String.class), anyInt()))
                .thenAnswer(call -> observations.limit(call.getArgument(0), call.getArgument(1)));
        when(dependency(TbankRuntimeSettingsService.class).isPaymentLinksEnabled()).thenReturn(true);
        when(dependency(PaymentLinkAmountPolicy.class).payableSum(any())).thenReturn(new BigDecimal("1200"));
        when(dependency(PaymentLinkAmountPolicy.class).amountKopecks(any())).thenReturn(120000L);
        PaymentProfile profile = tx.execute(status -> {
            var value = new PaymentProfile(); value.setCode(UUID.randomUUID().toString());
            value.setName("Synthetic profile"); value.setTerminalKey(UUID.randomUUID().toString()); em.persist(value); return value;
        });
        var profiles = dependency(PaymentProfileService.class);
        when(profiles.selectForManager(any())).thenReturn(profile);
        when(profiles.lockForRouting(any())).thenAnswer(call -> call.getArgument(0));
        when(profiles.lockManagerForRouting(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> { PaymentLink link = call.getArgument(0); link.setPaymentProfile(profile);
            link.setPaymentProfileCode(profile.getCode()); return null;
        }).when(dependency(CommonInvoiceRouteSelector.class)).applyPaymentProfile(any(), any());
        doAnswer(call -> { PaymentLink link = call.getArgument(0); link.setPaymentMethod(PaymentMethod.BANK_FORM);
            link.setStatus(PaymentLinkStatus.CREATED); return null;
        }).when(dependency(CommonInvoiceRouteSelector.class)).applyBankPaymentRoute(any());
        when(dependency(PaymentLinkPresenter.class).isPaperInvoice(any())).thenAnswer(call ->
                ((PaymentLink) call.getArgument(0)).getPaymentMethod() == PaymentMethod.OWNER_PAPER_INVOICE);
        when(dependency(PaymentLinkPresenter.class).toManagerResponse(any())).thenAnswer(call -> {
            PaymentLink link = call.getArgument(0);
            em.flush(); // A real inserted payment source must roll back if rendering subsequently fails.
            if (failRendering.get()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) afterRollback.run(); }
                });
                throw new IllegalStateException("synthetic rendering failure");
            }
            if (failAfterCommit.get()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { throw new IllegalStateException("synthetic after-commit failure"); }
            });
            return new ManagerPaymentLinkResponse(link.getToken(), "https://fixture.invalid/pay/" + link.getToken(),
                    link.getOrder().getId(), new BigDecimal("1200"), 120000L, link.getStatus().name(),
                    link.getPaymentMethod().name(), link.getExpiresAt(), "Synthetic instruction", "Synthetic payment message");
        });
        dependencies.put(PaymentLinkPreparationWorkflow.class, advised(construct(PaymentLinkPreparationWorkflow.class)));
        payments = advised(construct(PaymentLinkService.class)); dependencies.put(PaymentLinkService.class, payments);
        var settings = dependency(AppSettingService.class);
        when(settings.getString(anyString(), nullable(String.class))).thenAnswer(call ->
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE.equals(call.getArgument(0)) ? "BANK_LINK" : call.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        when(settings.getBooleanFreshFailClosed(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        ObjectProvider<PaymentLinkService> paymentProvider = mock(ObjectProvider.class);
        when(paymentProvider.getObject()).thenReturn(payments);
        dependencies.put(OrderPaymentMessageBuilder.class, new OrderPaymentMessageBuilder(settings, paymentProvider,
                dependency(com.hunt.otziv.bad_reviews.service.BadReviewTaskService.class)));
        var context = advised(new OrderDeliveryControlContextService(orders, jdbc));
        dependencies.put(OrderDeliveryControlContext.class, context);
        when(dependency(OrderManualContactReservation.class).blocksScheduledContact(anyLong())).thenAnswer(call -> {
            context.lock(call.getArgument(0)); return manualReserved.get();
        });
        runner = new ClientMessageTransactionRunner(transactions); dependencies.put(ClientMessageTransactionRunner.class, runner);
        dependencies.put(ClientMessageTime.class, new ClientMessageTime());
        var states = dependency(ScheduledClientMessageStateRepository.class);
        when(states.findOrderIdByStateId(anyLong())).thenAnswer(call -> Optional.ofNullable(jdbc.queryForObject(
                "SELECT order_id FROM scheduled_client_message_state WHERE state_id=?", Long.class, call.getArgument(0, Long.class))));
        when(states.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(
                em.find(ScheduledClientMessageState.class, call.getArgument(0), LockModeType.PESSIMISTIC_WRITE)));
        when(states.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(em.find(ScheduledClientMessageState.class, call.getArgument(0))));
        when(states.save(any())).thenAnswer(call -> em.merge(call.getArgument(0)));
        when(dependency(ScheduledClientMessageAttemptRepository.class).save(any())).thenAnswer(call -> {
            ScheduledClientMessageAttempt attempt = call.getArgument(0); em.persist(attempt); return attempt;
        });
        when(dependency(CompanyRepository.class).findByIdForCompanyDto(anyLong())).thenAnswer(call -> Optional.ofNullable(em.find(Company.class, call.getArgument(0))));
        when(dependency(ClientMessageSlotPlanner.class).afterGap(any(), any(), anyInt(), any())).thenAnswer(call -> call.getArgument(0));
        when(dependency(ClientMessageSlotPlanner.class).nextAllowedAt(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(dependency(ClientChatMessageSender.class).deliverWithOperationId(any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(call -> delivered());
        var notifications = dependency(OrderStatusNotificationService.class);
        when(notifications.prepareAction(anyString(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Order order = call.getArgument(1);
            return new OrderStatusNotificationService.PreparedAction(order.getId(), call.getArgument(0), call.getArgument(5),
                    order.getClientMessageGeneration(), "fixture-token", "fixture-operation-" + order.getId(), null,
                    call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(6), null, null, List.of());
        });
        when(notifications.dispatchPreparedAction(any())).thenAnswer(call -> delivered());
        service = construct(ScheduledClientMessageService.class);
    }

    @ParameterizedTest
    @EnumSource(value = ClientMessageScenario.class, names = {"PAYMENT_REMINDER", "PAYMENT_INVOICE_RETRY"})
    void bothScheduledPaymentPathsCommitLinkAndEnvelopeBeforeTransportWithoutSelfLock(ClientMessageScenario scenario) {
        fixture(scenario);
        assertTimeout(Duration.ofSeconds(12), this::process);
        assertThat(sends).hasValue(1);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(state().getDeliveryStatus()).isEqualTo("UNKNOWN"); // An uncertain transport remains uncertain.
        assertThat(state().getLastErrorCode()).isEqualTo(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
    }

    @Test
    void independentPaymentTransactionReallyCannotReacquireTheOuterOrderLock() {
        fixture(ClientMessageScenario.PAYMENT_REMINDER);
        assertTimeout(Duration.ofSeconds(12), () -> assertThatThrownBy(() -> runner.callInPreparationTransaction(() -> {
            dependency(OrderDeliveryControlContext.class).lock(orderId);
            return payments.createForOrderInNewTransaction(orderId);
        })).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, sql -> {
            assertThat(sql.getErrorCode()).isEqualTo(1205);
            assertThat(sql.getSQLState()).isIn("HY000", "40001");
        }));
        assertThat(paymentCount()).isZero(); assertThat(sends).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = ClientMessageScenario.class, names = {"PAYMENT_REMINDER", "PAYMENT_INVOICE_RETRY"})
    void paymentFailureRollsBackItsInsertedSourceAndRecordsKnownUnsentAfterRollback(ClientMessageScenario scenario) {
        fixture(scenario); failRendering.set(true);
        process();
        assertThat(paymentCount()).isZero(); assertThat(sends).hasValue(0);
        var state = state();
        assertThat(state.getLastErrorCode()).isEqualTo("payment_instruction_failed");
        assertThat(state.getDeliveryStatus()).isNull(); assertThat(state.getDeliveryEnvelope()).isNull();
        assertThat(state.getNextAttemptAt()).isNotNull(); assertThat(state.getLockedUntil()).isNull();
    }

    @Test
    void manualReservationStillStopsPreparationBeforePaymentSourceOrTransport() {
        fixture(ClientMessageScenario.PAYMENT_REMINDER); manualReserved.set(true);
        process();
        assertThat(paymentCount()).isZero(); assertThat(sends).hasValue(0);
        assertThat(state().getLastErrorCode()).isEqualTo("manual_contact_reserved");
    }

    @Test
    void successfullyPreparedPaperInvoiceRemainsAvailableForTheOwnersIssuedConfirmation() {
        fixture(ClientMessageScenario.PAYMENT_REMINDER);
        tx.executeWithoutResult(status -> em.find(Order.class, orderId).setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE));
        process();
        assertThat(paymentCount()).isEqualTo(1); assertThat(sends).hasValue(0);
        assertThat(state().getDeliveryEnvelope()).isNull(); assertThat(state().getDeliveryStatus()).isNull();
        assertThat(state().getLastErrorCode()).isEqualTo("payment_instruction_failed");
        assertThat(state().getNextAttemptAt()).isNotNull();

        var owner = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("fixture-owner", "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER")));
        var confirmed = payments.markPaperInvoiceIssuedAuthorized(orderId, owner);
        assertThat(confirmed.status()).isEqualTo("WAITING_MANUAL_PAYMENT");
        assertThat(paymentCount()).isEqualTo(1); assertThat(sends).hasValue(0);
    }

    @Test
    void paymentFailurePreservesPreviouslyFrozenKnownUnsentOperation() {
        fixture(ClientMessageScenario.PAYMENT_REMINDER);
        tx.executeWithoutResult(status -> {
            var state = em.find(ScheduledClientMessageState.class, stateId);
            state.setDeliveryEnvelope("previous-known-unsent-envelope"); state.setDeliveryToken("previous-token");
        });
        failRendering.set(true); process();
        assertThat(state().getDeliveryStatus()).isEqualTo("RETRYABLE");
        assertThat(state().getDeliveryEnvelope()).isEqualTo("previous-known-unsent-envelope");
        assertThat(state().getDeliveryToken()).isEqualTo("previous-token");
        assertThat(paymentCount()).isZero(); assertThat(sends).hasValue(0);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UNKNOWN", "CLAIMED", "EXPIRED"})
    void paymentFailureCannotOverwriteDeliveryOrLeaseChangedAfterRollback(String successor) {
        fixture(ClientMessageScenario.PAYMENT_REMINDER); failRendering.set(true);
        afterRollback = () -> runner.runInNewTransaction(() -> {
            var state = em.find(ScheduledClientMessageState.class, stateId, LockModeType.PESSIMISTIC_WRITE);
            if ("EXPIRED".equals(successor)) state.setLockedUntil(now.minusSeconds(1));
            else if ("CLAIMED".equals(successor)) state.setLockedUntil(lease.plusMinutes(5));
            else { state.setDeliveryStatus("UNKNOWN"); state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN); }
        });
        process();
        assertThat(state().getLastErrorCode()).isNotEqualTo("payment_instruction_failed");
        assertThat(state().getConsecutiveFailures()).isZero();
        assertThat(paymentCount()).isZero(); assertThat(sends).hasValue(0);
    }

    @Test
    void afterCommitFailureNeverRearmsTheCommittedEnvelopeOrCallsTransport() {
        fixture(ClientMessageScenario.PAYMENT_REMINDER); failAfterCommit.set(true);
        assertThatThrownBy(this::process).isInstanceOf(IllegalStateException.class).hasMessage("synthetic after-commit failure");
        assertThat(paymentCount()).isEqualTo(1); assertThat(sends).hasValue(0);
        assertThat(state().getDeliveryStatus()).isEqualTo("PREPARED");
        assertThat(state().getDeliveryEnvelope()).isNotBlank();
        assertThat(state().getLastErrorCode()).isEqualTo("delivery_prepared");
    }

    @Test
    void joinedPaymentEntryRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> payments.createForOrderInCurrentTransaction(1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private void fixture(ClientMessageScenario scenario) {
        tx.executeWithoutResult(status -> {
            Manager manager = new Manager(); manager.setClientId("fixture"); em.persist(manager);
            Company company = new Company(); company.setTitle("Synthetic company"); company.setManager(manager);
            company.setCity("Synthetic city"); company.setTelephone(String.format("79%09d", manager.getId()));
            company.setGroupId("fixture-group"); em.persist(company);
            OrderStatus orderStatus = new OrderStatus(); orderStatus.setTitle(scenario == ClientMessageScenario.PAYMENT_REMINDER ? "Выставлен счет" : "Опубликовано"); em.persist(orderStatus);
            Order order = new Order(); order.setCompany(company); order.setManager(manager); order.setSum(new BigDecimal("1200"));
            order.setStatus(orderStatus); order.setStatusChangedAt(now.minusDays(3)); order.setClientMessageGeneration(1); em.persist(order); orderId = order.getId();
            var state = ScheduledClientMessageState.builder().scenario(scenario).targetType(ClientMessageTargetType.ORDER)
                    .targetKey("order:" + orderId + ":" + order.getStatusChangedAt()).companyId(company.getId()).orderId(orderId)
                    .status(ScheduledMessageStateStatus.ACTIVE).deliveryStatus("CLAIMED")
                    .lastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS).lockedUntil(lease).build();
            em.persist(state); stateId = state.getId();
        });
    }

    private void process() { ReflectionTestUtils.invokeMethod(service, "processClaimedStateOutsideTransaction", stateId, now, lease, false); }
    private ScheduledClientMessageState state() { return tx.execute(status -> em.find(ScheduledClientMessageState.class, stateId)); }
    private long paymentCount() { return tx.execute(status -> em.createQuery("select count(p) from PaymentLink p where p.order.id=:order", Long.class).setParameter("order", orderId).getSingleResult()); }
    private ClientMessageSendResult delivered() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(state().getDeliveryStatus()).isEqualTo("PREPARED");
        assertThat(state().getDeliveryEnvelope()).isNotBlank(); sends.incrementAndGet();
        return ClientMessageSendResult.failed("operation_unknown", "Synthetic uncertain outcome");
    }
    @SuppressWarnings("unchecked") private <T> T dependency(Class<T> type) {
        return (T) dependencies.computeIfAbsent(type, ignored -> mock(type));
    }
    private <T> T construct(Class<T> type) {
        try {
            var constructor = Arrays.stream(type.getConstructors()).max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
            return type.cast(constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(this::dependency).toArray()));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    @SuppressWarnings("unchecked") private static <T> T advised(T target) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
