package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.common_billing.dto.CommonInvoiceManualCardPaymentRequest;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.payments.api.StandalonePaymentOperations;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real production targets, Spring transaction advice, MySQL connections and InnoDB writes.
 * JDBC-backed repository adapters keep this focused on cross-service transaction semantics;
 * this fixture does not replace the full Hibernate/application startup integration tests.
 */
@Testcontainers
class CommonInvoiceTransactionProxyMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("common_invoice_transaction_proxy").withUsername("root").withPassword("root");

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 9, 7, 12, 0);
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final AtomicBoolean failFinalInvoiceWrite = new AtomicBoolean();
    private final AtomicLong settlementConnection = new AtomicLong();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate callerTransaction;
    private CommonInvoiceSettlementService settlement;
    private CommonInvoicePaymentOperations payments;
    private CommonInvoiceManualPaymentWorkflow manualPayments;

    @BeforeEach
    void setUp() throws Exception {
        dependencies.clear();
        failFinalInvoiceWrite.set(false);
        settlementConnection.set(0);
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        callerTransaction = new TransactionTemplate(transactionManager);
        dependencies.put(PlatformTransactionManager.class, transactionManager);
        createSchema();
        configureJdbcAdapters();

        CommonInvoiceSettlementService target = target(CommonInvoiceSettlementService.class);
        ReflectionTestUtils.setField(target, "orderTransactionService", dependency(OrderTransactionService.class));
        ReflectionTestUtils.setField(target, "nextOrderRequestService", dependency(NextOrderRequestService.class));
        settlement = transactionalProxy(target);
        payments = settlement;
        dependencies.put(CommonInvoiceSettlementService.class, settlement);
        manualPayments = transactionalProxy(target(CommonInvoiceManualPaymentWorkflow.class));
        assertThat(AopUtils.isAopProxy(settlement)).isTrue();
        assertThat(AopUtils.getTargetClass(settlement)).isEqualTo(CommonInvoiceSettlementService.class);
        assertThat(AopUtils.isAopProxy(manualPayments)).isTrue();
    }

    @Test
    void requiredSettlementJoinsCallerAndLateFailureRollsBackEveryFinancialWrite() {
        AtomicLong callerConnection = new AtomicLong();
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            callerConnection.set(connectionId());
            stageStandaloneConfirmation();
            assertThat(payments.applyConfirmedOrderPayment(101L, PAID_AT, "proxy integration")).isTrue();
            assertThat(settlementConnection.get()).isEqualTo(callerConnection.get());
            assertStagedConfirmation();
            throw new LateFailure();
        })).isInstanceOf(LateFailure.class);
        assertOriginalFinancialState();

        // Positive control: the same production flow writes and commits, so rollback is not a no-op assertion.
        callerTransaction.executeWithoutResult(status -> {
            stageStandaloneConfirmation();
            assertThat(payments.applyConfirmedOrderPayment(101L, PAID_AT, "successful retry")).isTrue();
        });
        assertStagedConfirmation();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_ledger", Integer.class)).isEqualTo(1);
    }

    @Test
    void caughtFailureInsideRequiredParticipantStillMarksCallerRollbackOnly() {
        failFinalInvoiceWrite.set(true);
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            stageStandaloneConfirmation();
            assertThatThrownBy(() -> payments.applyConfirmedOrderPayment(101L, PAID_AT, "late database failure"))
                    .isInstanceOf(LateFailure.class);
            // The caller intentionally catches the exception. The real proxy must still forbid commit.
            assertStagedConfirmation();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertOriginalFinancialState();
    }

    @Test
    void requiresNewReversalCommitsQuarantineDespiteCallerRollbackAndRestoresCallerConnection() {
        jdbc.update("UPDATE tx_invoices SET status='PAID',paid=200000");
        jdbc.update("UPDATE tx_items SET paid=TRUE,source_link=501 WHERE order_id=101");
        jdbc.update("UPDATE tx_links SET status='REFUNDED',terminal_status='REFUNDED',confirmed=100000 WHERE id=501");
        AtomicLong callerConnection = new AtomicLong();
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            callerConnection.set(connectionId());
            jdbc.update("INSERT INTO tx_probe(name) VALUES ('outer-reversal')");
            assertThat(payments.applyStandalonePaymentReversal(101L, 501L, PaymentLinkStatus.REFUNDED)).isTrue();
            assertThat(settlementConnection.get()).isNotEqualTo(callerConnection.get());
            assertThat(connectionId()).isEqualTo(callerConnection.get());
            throw new LateFailure();
        })).isInstanceOf(LateFailure.class);

        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10", String.class)).isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbc.queryForObject("SELECT paid FROM tx_items WHERE order_id=101", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_error FROM tx_invoices WHERE id=10", String.class))
                .startsWith("standalone_payment_reversed:");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class)).isZero();
    }

    @Test
    void notSupportedManualProviderStageSuspendsCallerAndCannotRollBackExternalObservation() {
        jdbc.update("UPDATE tx_invoices SET status='NEEDS_ATTENTION',last_error='standalone_payment_route_conflict: probe'");
        AtomicLong callerConnection = new AtomicLong();
        AtomicBoolean providerCalled = new AtomicBoolean();
        when(dependency(StandalonePaymentOperations.class).reconcileBankLink(eq(501L), any()))
                .thenAnswer(invocation -> {
                    providerCalled.set(true);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    // Spring may bind a connection for synchronization even with NOT_SUPPORTED;
                    // actual transaction state and JDBC auto-commit distinguish that from a database transaction.
                    assertThat(jdbc.queryForObject("SELECT @@autocommit", Integer.class)).isEqualTo(1);
                    assertThat(connectionId()).isNotEqualTo(callerConnection.get());
                    jdbc.update("INSERT INTO tx_probe(name) VALUES ('provider-observed')");
                    // Stop after the observable provider boundary, before starting the next locked finance stage.
                    throw new ProviderObservationComplete();
                });
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            callerConnection.set(connectionId());
            jdbc.update("INSERT INTO tx_probe(name) VALUES ('outer-provider')");
            assertThatThrownBy(() -> manualPayments.reportPaidByManualCardTransfer(10L,
                    new CommonInvoiceManualCardPaymentRequest("Проверка ранее отправленного банковского платежа"), () -> "operator"))
                    .isInstanceOf(ProviderObservationComplete.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(connectionId()).isEqualTo(callerConnection.get());
            throw new LateFailure();
        })).isInstanceOf(LateFailure.class);
        assertThat(providerCalled).isTrue();
        assertThat(jdbc.queryForList("SELECT name FROM tx_probe ORDER BY name", String.class))
                .containsExactly("provider-observed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_ledger", Integer.class)).isZero();
    }

    private void configureJdbcAdapters() {
        when(dependency(OrderAggregateMutationLockService.class).lock(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            jdbc.queryForObject("SELECT id FROM tx_orders WHERE id=? FOR UPDATE", Long.class, id);
            return loadOrder(id);
        });
        when(dependency(CommonBillingAccountRepository.class).findByIdWithRelationsForUpdate(1L)).thenAnswer(invocation -> {
            jdbc.queryForObject("SELECT id FROM tx_accounts WHERE id=1 FOR UPDATE", Long.class);
            return Optional.of(account());
        });
        CommonInvoiceRepository invoices = dependency(CommonInvoiceRepository.class);
        when(invoices.findByIdWithAccount(10L)).thenAnswer(invocation -> Optional.of(loadInvoice()));
        when(invoices.findByIdWithAccountForUpdate(10L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            settlementConnection.set(connectionId());
            jdbc.queryForObject("SELECT id FROM tx_invoices WHERE id=10 FOR UPDATE", Long.class);
            return Optional.of(loadInvoice());
        });
        when(invoices.save(any(CommonInvoice.class))).thenAnswer(invocation -> {
            CommonInvoice invoice = invocation.getArgument(0);
            jdbc.update("UPDATE tx_invoices SET status=?,amount=?,paid=?,last_error=? WHERE id=?",
                    invoice.getStatus().name(), invoice.getAmountKopecks(), invoice.getPaidKopecks(), invoice.getLastError(), invoice.getId());
            if (failFinalInvoiceWrite.get() && invoice.getStatus() == CommonInvoiceStatus.PARTIALLY_PAID) throw new LateFailure();
            return invoice;
        });
        CommonInvoiceOrderRepository items = dependency(CommonInvoiceOrderRepository.class);
        when(items.findByOrderIdWithInvoice(101L)).thenAnswer(invocation -> Optional.of(loadItems().getFirst()));
        when(items.findByInvoiceIdWithOrders(10L)).thenAnswer(invocation -> loadItems());
        when(items.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(items.saveAll(any())).thenAnswer(invocation -> {
            Iterable<CommonInvoiceOrder> changed = invocation.getArgument(0);
            for (CommonInvoiceOrder item : changed) jdbc.update(
                    "UPDATE tx_items SET paid=?,source_link=?,payment_method=?,paid_at=? WHERE order_id=?",
                    item.isPaid(), item.getSourcePaymentLinkId(), item.getPaymentMethod(), item.getPaidAt(), item.getOrder().getId());
            return changed;
        });
        PaymentLinkRepository links = dependency(PaymentLinkRepository.class);
        when(links.findByOrderIdForUpdate(101L)).thenAnswer(invocation -> {
            jdbc.queryForObject("SELECT id FROM tx_links WHERE order_id=101 FOR UPDATE", Long.class);
            return List.of(loadLink());
        });
        when(links.findByOrderIdInForRead(any())).thenAnswer(invocation -> List.of(loadLink()));
        when(dependency(BadReviewTaskService.class).getPayableSum(any())).thenReturn(BigDecimal.valueOf(1000));
    }

    private void stageStandaloneConfirmation() {
        jdbc.update("UPDATE tx_orders SET status='Оплачено' WHERE id=101");
        jdbc.update("UPDATE tx_links SET status='CONFIRMED',confirmed=100000,paid_at=? WHERE id=501", PAID_AT);
        jdbc.update("INSERT INTO tx_ledger(link_id,amount) VALUES (501,100000)");
    }

    private void assertOriginalFinancialState() {
        assertThat(jdbc.queryForObject("SELECT status FROM tx_orders WHERE id=101", String.class)).isEqualTo("Новый");
        assertThat(jdbc.queryForObject("SELECT status FROM tx_links WHERE id=501", String.class)).isEqualTo("INITIATED");
        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10", String.class)).isEqualTo("REMINDER");
        assertThat(jdbc.queryForObject("SELECT paid FROM tx_invoices WHERE id=10", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT paid FROM tx_items WHERE order_id=101", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_ledger", Integer.class)).isZero();
    }

    private void assertStagedConfirmation() {
        assertThat(jdbc.queryForObject("SELECT status FROM tx_orders WHERE id=101", String.class)).isEqualTo("Оплачено");
        assertThat(jdbc.queryForObject("SELECT status FROM tx_links WHERE id=501", String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10", String.class)).isEqualTo("PARTIALLY_PAID");
        assertThat(jdbc.queryForObject("SELECT paid FROM tx_invoices WHERE id=10", Long.class)).isEqualTo(100000L);
        assertThat(jdbc.queryForObject("SELECT source_link FROM tx_items WHERE order_id=101", Long.class)).isEqualTo(501L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_ledger", Integer.class)).isEqualTo(1);
    }

    private CommonBillingAccount account() {
        CommonBillingAccount account = new CommonBillingAccount(); account.setId(1L); return account;
    }

    private CommonInvoice loadInvoice() {
        return jdbc.queryForObject("SELECT * FROM tx_invoices WHERE id=10", (rs, row) -> {
            CommonInvoice invoice = new CommonInvoice(); invoice.setId(10L); invoice.setAccount(account());
            invoice.setStatus(CommonInvoiceStatus.valueOf(rs.getString("status")));
            invoice.setAmountKopecks(rs.getLong("amount")); invoice.setPaidKopecks(rs.getLong("paid"));
            invoice.setLastError(rs.getString("last_error")); return invoice;
        });
    }

    private Order loadOrder(Long id) {
        return jdbc.queryForObject("SELECT status FROM tx_orders WHERE id=?", (rs, row) -> {
            Order order = new Order(); order.setId(id);
            OrderStatus status = new OrderStatus(); status.setTitle(rs.getString("status")); order.setStatus(status); return order;
        }, id);
    }

    private List<CommonInvoiceOrder> loadItems() {
        CommonInvoice invoice = loadInvoice();
        return jdbc.query("SELECT * FROM tx_items ORDER BY order_id", (rs, row) -> {
            CommonInvoiceOrder item = new CommonInvoiceOrder(); item.setId(rs.getLong("order_id"));
            item.setInvoice(invoice); item.setOrder(loadOrder(rs.getLong("order_id")));
            item.setAmountKopecks(rs.getLong("amount")); item.setPaid(rs.getBoolean("paid"));
            item.setSourcePaymentLinkId(rs.getObject("source_link", Long.class)); item.setPaymentMethod(rs.getString("payment_method"));
            Timestamp paidAt = rs.getTimestamp("paid_at"); item.setPaidAt(paidAt == null ? null : paidAt.toLocalDateTime());
            return item;
        });
    }

    private PaymentLink loadLink() {
        return jdbc.queryForObject("SELECT * FROM tx_links WHERE id=501", (rs, row) -> {
            PaymentLink link = new PaymentLink(); link.setId(501L); link.setOrder(loadOrder(101L));
            link.setStatus(PaymentLinkStatus.valueOf(rs.getString("status"))); link.setPaymentMethod(PaymentMethod.BANK_FORM);
            link.setAmountKopecks(100000L); link.setConfirmedAmountKopecks(rs.getObject("confirmed", Long.class));
            Timestamp paidAt = rs.getTimestamp("paid_at"); link.setPaidAt(paidAt == null ? null : paidAt.toLocalDateTime());
            link.setTbankPaymentId("bank-501"); link.setTbankOrderId("order-101"); link.setTbankTerminalKey("terminal");
            link.setProviderTerminalStatus(rs.getString("terminal_status")); return link;
        });
    }

    private long connectionId() { return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); }

    private <T> T dependency(Class<T> type) { return type.cast(dependencies.computeIfAbsent(type, ignored -> mock(type))); }

    private <T> T target(Class<T> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes(); Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) arguments[i] = dependency(types[i]);
        return type.cast(constructor.newInstance(arguments));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private void createSchema() {
        for (String table : List.of("tx_probe", "tx_ledger", "tx_links", "tx_items", "tx_orders", "tx_invoices", "tx_accounts"))
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE tx_accounts(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_invoices(id BIGINT PRIMARY KEY,status VARCHAR(40),amount BIGINT,paid BIGINT,last_error VARCHAR(512)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_orders(id BIGINT PRIMARY KEY,status VARCHAR(80)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_items(order_id BIGINT PRIMARY KEY,amount BIGINT,paid BOOLEAN,source_link BIGINT NULL,payment_method VARCHAR(40),paid_at DATETIME NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_links(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(40),confirmed BIGINT NULL,paid_at DATETIME NULL,terminal_status VARCHAR(40)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,link_id BIGINT UNIQUE,amount BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_probe(name VARCHAR(60) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO tx_accounts VALUES (1)");
        jdbc.update("INSERT INTO tx_invoices VALUES (10,'REMINDER',200000,0,NULL)");
        jdbc.update("INSERT INTO tx_orders VALUES (101,'Новый'),(102,'Новый')");
        jdbc.update("INSERT INTO tx_items VALUES (101,100000,FALSE,NULL,NULL,NULL),(102,100000,FALSE,NULL,NULL,NULL)");
        jdbc.update("INSERT INTO tx_links VALUES (501,101,'INITIATED',NULL,NULL,NULL)");
    }

    private static final class LateFailure extends RuntimeException {}
    private static final class ProviderObservationComplete extends RuntimeException {}
}
