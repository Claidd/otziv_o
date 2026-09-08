package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.common_billing.dto.CommonInvoiceCloseRequest;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
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
 * Production workflows and transaction collaborators, real Spring advice and independent InnoDB connections.
 * Repository adapters use JDBC to isolate transaction behavior from Hibernate mapping/startup tests.
 * Order status transitions are a domain port; their writes participate in the workflow's real transaction.
 */
@Testcontainers
class CommonInvoiceAccountArchiveTransactionMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("common_invoice_account_archive_proxy").withUsername("root").withPassword("root");

    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final List<Long> reconciliationConnections = new ArrayList<>();
    private final List<Long> transitionedOrders = new ArrayList<>();
    private final AtomicBoolean failSecondOrder = new AtomicBoolean();
    private final AtomicBoolean failInvoiceSave = new AtomicBoolean();
    private final AtomicLong archiveConnection = new AtomicLong();
    private final AtomicLong accountCommandConnection = new AtomicLong();
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate callerTransaction;
    private CommonBillingAccountWorkflow accounts;
    private CommonInvoiceArchiveWorkflow archives;

    @BeforeEach
    void setUp() throws Exception {
        dependencies.clear(); reconciliationConnections.clear(); transitionedOrders.clear();
        failSecondOrder.set(false); failInvoiceSave.set(false); archiveConnection.set(0); accountCommandConnection.set(0);
        SecurityContextHolder.clearContext();
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        callerTransaction = new TransactionTemplate(transactionManager);
        dependencies.put(PlatformTransactionManager.class, transactionManager);
        createSchema();
        configureJdbcAdapters();

        // All financial workflow/transaction helpers below are actual production targets, never spies or mocks.
        register(CommonInvoiceSettlementService.class);
        register(CommonInvoicePresenter.class);
        register(CommonInvoiceCancellationService.class);
        register(CommonInvoiceInitializationService.class);
        register(CommonInvoiceDetailsAssembler.class);
        CommonInvoiceDeliveryService delivery = register(CommonInvoiceDeliveryService.class);
        ReflectionTestUtils.setField(delivery, "orderStatusTransitionService", dependency(OrderStatusTransitionService.class));
        register(CommonInvoiceManualPaymentWorkflow.class);
        register(CommonInvoiceMembershipWorkflow.class);
        register(CommonBillingCompanyReconciliationWorkflow.class);
        accounts = register(CommonBillingAccountWorkflow.class);
        register(CommonInvoiceBoardWorkflow.class);
        archives = register(CommonInvoiceArchiveWorkflow.class);
        ReflectionTestUtils.setField(archives, "orderStatusTransitionService", dependency(OrderStatusTransitionService.class));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void accountLinkReconciliationWaitsForCommitAndUsesIndependentTransactions() {
        AtomicLong callerConnection = new AtomicLong();
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            callerConnection.set(connectionId());
            accounts.addCompany(1L, 50L);
            assertThat(linkCount()).isEqualTo(1);
            assertThat(reconciliationConnections).isEmpty();
            assertThat(jdbc.queryForObject("SELECT reconcile_pending FROM tx_account_links WHERE id=71", Boolean.class)).isTrue();
            throw new LateFailure();
        })).isInstanceOf(LateFailure.class);
        assertThat(linkCount()).isZero();
        assertThat(reconciliationConnections).isEmpty();
        assertThat(jdbc.queryForObject("SELECT invoice_company FROM tx_accounts WHERE id=1", Long.class)).isNull();

        // Positive control traverses the same real addCompany -> afterCommit -> claim/reconcile path.
        callerTransaction.executeWithoutResult(status -> {
            callerConnection.set(connectionId());
            accounts.addCompany(1L, 50L);
            assertThat(reconciliationConnections).isEmpty();
            assertThat(connectionId()).isEqualTo(callerConnection.get());
        });
        assertThat(linkCount()).isEqualTo(1);
        assertThat(reconciliationConnections).hasSize(2).doesNotContain(callerConnection.get());
        assertThat(reconciliationConnections.get(0)).isNotEqualTo(reconciliationConnections.get(1));
        assertThat(jdbc.queryForObject("SELECT reconcile_pending FROM tx_account_links WHERE id=71", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT reconcile_attempts FROM tx_account_links WHERE id=71", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM tx_account_links WHERE id=71", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT invoice_company FROM tx_accounts WHERE id=1", Long.class)).isEqualTo(50L);
    }

    @Test
    void accountEntryPointOwnsTransactionWithoutAnOuterCallerTransaction() {
        accounts.addCompany(1L, 50L);
        assertThat(accountCommandConnection.get()).isPositive();
        assertThat(reconciliationConnections).hasSize(2).doesNotContain(accountCommandConnection.get());
        assertThat(jdbc.queryForObject("SELECT reconcile_pending FROM tx_account_links WHERE id=71", Boolean.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateOrderFailureRollsBackEarlierOrdersAndPositiveControlCommitsWholeGroup(boolean ban) {
        prepareArchiveCase(ban);
        failSecondOrder.set(true);
        assertThatThrownBy(() -> closeGroup(ban)).isInstanceOf(ResponseStatusException.class);
        assertThat(transitionedOrders).containsExactly(101L, 102L);
        assertOriginalGroup(ban);

        failSecondOrder.set(false); transitionedOrders.clear(); archiveConnection.set(0);
        closeGroup(ban);
        assertThat(transitionedOrders).containsExactly(101L, 102L);
        assertCommittedGroup(ban);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureAfterInvoiceUpdateRollsBackInvoiceItemsAndAllOrderWrites(boolean ban) {
        prepareArchiveCase(ban);
        failInvoiceSave.set(true);
        assertThatThrownBy(() -> closeGroup(ban)).isInstanceOf(LateFailure.class);
        assertThat(transitionedOrders).containsExactly(101L, 102L);
        assertOriginalGroup(ban);

        failInvoiceSave.set(false); transitionedOrders.clear(); archiveConnection.set(0);
        closeGroup(ban);
        assertCommittedGroup(ban);
    }

    private void configureJdbcAdapters() throws Exception {
        CommonBillingAccountRepository accountRepository = dependency(CommonBillingAccountRepository.class);
        when(accountRepository.findByIdWithRelations(anyLong())).thenAnswer(call -> Optional.of(loadAccount(call.getArgument(0))));
        when(accountRepository.findByIdWithRelationsForUpdate(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (id == 1L) accountCommandConnection.compareAndSet(0, connectionId());
            jdbc.queryForObject("SELECT id FROM tx_accounts WHERE id=? FOR UPDATE", Long.class, id);
            return Optional.of(loadAccount(id));
        });
        when(accountRepository.save(any(CommonBillingAccount.class))).thenAnswer(call -> {
            CommonBillingAccount account = call.getArgument(0);
            jdbc.update("UPDATE tx_accounts SET invoice_company=? WHERE id=?",
                    account.getInvoiceCompany() == null ? null : account.getInvoiceCompany().getId(), account.getId());
            return account;
        });
        when(dependency(CompanyRepository.class).findById(50L)).thenReturn(Optional.of(company(50L)));

        CommonBillingAccountCompanyRepository links = dependency(CommonBillingAccountCompanyRepository.class);
        when(links.findByAccount_IdAndCompany_Id(1L, 50L)).thenAnswer(call -> loadLink());
        when(links.findByAccount_IdOrderByCompany_TitleAsc(1L)).thenAnswer(call -> loadLink().stream().toList());
        when(links.findConfiguredEnabledLinksForCompany(50L)).thenAnswer(call -> loadLink().stream().toList());
        when(links.saveAndFlush(any(CommonBillingAccountCompany.class))).thenAnswer(call -> saveLink(call.getArgument(0)));
        when(links.save(any(CommonBillingAccountCompany.class))).thenAnswer(call -> saveLink(call.getArgument(0)));
        when(links.findByIdForUpdate(71L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            reconciliationConnections.add(connectionId());
            // The independent claim must see both writes made by the already committed account command.
            assertThat(jdbc.queryForObject("SELECT invoice_company FROM tx_accounts WHERE id=1", Long.class)).isEqualTo(50L);
            jdbc.queryForObject("SELECT id FROM tx_account_links WHERE id=71 FOR UPDATE", Long.class);
            return loadLink();
        });

        when(dependency(OrderAggregateMutationLockService.class).lock(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            jdbc.queryForObject("SELECT id FROM tx_orders WHERE id=? FOR UPDATE", Long.class, id);
            return loadOrder(id);
        });
        CommonInvoiceRepository invoices = dependency(CommonInvoiceRepository.class);
        when(invoices.findByIdWithAccount(10L)).thenAnswer(call -> Optional.of(loadInvoice()));
        when(invoices.findByIdWithAccountForUpdate(10L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (archiveConnection.get() == 0) archiveConnection.set(connectionId());
            assertThat(connectionId()).isEqualTo(archiveConnection.get());
            jdbc.queryForObject("SELECT id FROM tx_invoices WHERE id=10 FOR UPDATE", Long.class);
            return Optional.of(loadInvoice());
        });
        when(invoices.save(any(CommonInvoice.class))).thenAnswer(call -> {
            CommonInvoice invoice = call.getArgument(0);
            assertThat(connectionId()).isEqualTo(archiveConnection.get());
            jdbc.update("UPDATE tx_invoices SET status=?,previous_status=?,closed_by=?,closed_at=?,close_reason=? WHERE id=?",
                    invoice.getStatus().name(), invoice.getPreviousStatus(), invoice.getClosedBy(), invoice.getClosedAt(), invoice.getCloseReason(), invoice.getId());
            if (failInvoiceSave.get()) throw new LateFailure();
            return invoice;
        });
        CommonInvoiceOrderRepository items = dependency(CommonInvoiceOrderRepository.class);
        when(items.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L, 102L));
        when(items.findByInvoiceIdWithOrders(10L)).thenAnswer(call -> loadItems());
        when(items.findMembershipByInvoiceIdForRead(10L)).thenAnswer(call -> loadItems());
        when(items.saveAll(any())).thenAnswer(call -> {
            Iterable<CommonInvoiceOrder> changed = call.getArgument(0);
            for (CommonInvoiceOrder item : changed) jdbc.update("UPDATE tx_items SET archive_source=? WHERE order_id=?",
                    item.getArchiveSourceOrderStatusTitle(), item.getOrder().getId());
            return changed;
        });
        OrderStatusTransitionService transitions = dependency(OrderStatusTransitionService.class);
        doAnswer(call -> transitionOrder(call.getArgument(0), call.getArgument(1)))
                .when(transitions).changeStatusForCommonBillingOrder(anyLong(), anyString());
        doAnswer(call -> transitionOrder(call.getArgument(0), call.getArgument(1)))
                .when(transitions).changeStatusForPrivilegedCommonBillingOrder(anyLong(), anyString());
        when(dependency(BadReviewTaskService.class).getPayableSum(any())).thenReturn(BigDecimal.valueOf(1000));
        when(dependency(ManagerPermissionService.class).hasAnyRole(any(), eq("ADMIN"), eq("OWNER"))).thenReturn(true);
    }

    @Test
    void durablePublicationRecoveryUsesActualCanonicalTransactionAndCommittedCollectingTransitionIsIdempotent() {
        // These are the durable ready flags left when the publishing process
        // commits and stops before the old in-memory finalization callback.
        jdbc.execute("ALTER TABLE tx_items ADD ready BOOLEAN NOT NULL DEFAULT TRUE");
        jdbc.update("UPDATE tx_orders SET status='Ожидает общего счета'");
        CommonInvoiceOrderRepository items = dependency(CommonInvoiceOrderRepository.class);
        var binding = mock(CommonInvoiceOrderRepository.CurrentOrderInvoiceView.class);
        when(binding.getInvoiceId()).thenReturn(10L);
        when(items.findCurrentInvoiceBindingsByOrderIds(List.of(101L))).thenReturn(List.of(binding));
        when(items.findByInvoiceIdWithOrders(10L)).thenAnswer(call -> {
            List<CommonInvoiceOrder> loaded = loadItems();
            for (var item : loaded) {
                item.setActiveMembership(true);
                item.setReady(jdbc.queryForObject("SELECT ready FROM tx_items WHERE order_id=?",Boolean.class,item.getOrder().getId()));
            }
            return loaded;
        });
        when(dependency(com.hunt.otziv.p_products.service.OrderStatusService.class).getOrderStatusByTitle(anyString()))
                .thenAnswer(call -> {var status = new OrderStatus();status.setTitle(call.getArgument(0));return status;});
        when(dependency(com.hunt.otziv.p_products.repository.OrderRepository.class).save(any(Order.class)))
                .thenAnswer(call -> {Order order=call.getArgument(0);jdbc.update("UPDATE tx_orders SET status=? WHERE id=?",order.getStatus().getTitle(),order.getId());return order;});
        when(dependency(com.hunt.otziv.config.settings.service.AppSettingService.class).getBoolean(anyString(),anyBoolean())).thenReturn(true);
        var membership=dependency(CommonInvoiceMembershipWorkflow.class);
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> membership.finalizePublishedInvoiceForOrder(101L)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        failInvoiceSave.set(true);
        assertThatThrownBy(() -> membership.finalizePublishedInvoiceForOrder(101L)).isInstanceOf(LateFailure.class);
        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10",String.class)).isEqualTo("COLLECTING");
        assertThat(jdbc.queryForList("SELECT status FROM tx_orders ORDER BY id",String.class)).containsExactly("Ожидает общего счета","Ожидает общего счета");
        failInvoiceSave.set(false);archiveConnection.set(0);
        clearInvocations(dependency(OrderAggregateMutationLockService.class),dependency(CommonBillingAccountRepository.class),dependency(CommonInvoiceRepository.class));
        assertThat(membership.finalizePublishedInvoiceForOrder(101L)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10",String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForList("SELECT status FROM tx_orders ORDER BY id",String.class)).containsExactly("Опубликовано","Опубликовано");
        var locks=inOrder(dependency(OrderAggregateMutationLockService.class),dependency(CommonBillingAccountRepository.class),dependency(CommonInvoiceRepository.class));
        locks.verify(dependency(OrderAggregateMutationLockService.class)).lock(101L);
        locks.verify(dependency(OrderAggregateMutationLockService.class)).lock(102L);
        locks.verify(dependency(CommonBillingAccountRepository.class)).findByIdWithRelationsForUpdate(2L);
        locks.verify(dependency(CommonInvoiceRepository.class)).findByIdWithAccountForUpdate(10L);
        // A worker lost its own acknowledgement after the owner commit.
        // Re-read through a second actual transaction, without re-sending.
        archiveConnection.set(0);
        assertThat(membership.finalizePublishedInvoiceForOrder(101L)).isTrue();
        verify(dependency(CommonInvoiceAfterCommitSender.class),times(1)).send(10L,false);
    }

    private Object transitionOrder(Long id, String title) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(connectionId()).isEqualTo(archiveConnection.get());
        jdbc.update("UPDATE tx_orders SET status=? WHERE id=?", title, id);
        transitionedOrders.add(id);
        if (failSecondOrder.get() && id == 102L) throw new LateFailure();
        return true;
    }

    private void prepareArchiveCase(boolean ban) {
        jdbc.update("UPDATE tx_invoices SET status=? WHERE id=10", ban ? "UNPAID" : "COLLECTING");
        jdbc.update("UPDATE tx_orders SET status=?", ban ? "Не оплачено" : "В проверку");
    }

    private void closeGroup(boolean ban) {
        if (ban) archives.markBan(10L, () -> "operator");
        else archives.archiveInvoice(10L, new CommonInvoiceCloseRequest(true, "fixture"), () -> "operator");
    }

    private void assertOriginalGroup(boolean ban) {
        assertThat(jdbc.queryForList("SELECT status FROM tx_orders ORDER BY id", String.class))
                .containsExactly(ban ? "Не оплачено" : "В проверку", ban ? "Не оплачено" : "В проверку");
        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10", String.class)).isEqualTo(ban ? "UNPAID" : "COLLECTING");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM tx_invoices WHERE id=10", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_items WHERE archive_source IS NOT NULL", Integer.class)).isZero();
    }

    private void assertCommittedGroup(boolean ban) {
        assertThat(jdbc.queryForList("SELECT status FROM tx_orders ORDER BY id", String.class))
                .containsExactly(ban ? "Бан" : "Архив", ban ? "Бан" : "Архив");
        assertThat(jdbc.queryForObject("SELECT status FROM tx_invoices WHERE id=10", String.class)).isEqualTo(ban ? "BAN" : "ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT previous_status FROM tx_invoices WHERE id=10", String.class)).isEqualTo(ban ? "UNPAID" : "COLLECTING");
        assertThat(jdbc.queryForObject("SELECT closed_by FROM tx_invoices WHERE id=10", String.class)).isEqualTo("operator");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM tx_invoices WHERE id=10", Timestamp.class)).isNotNull();
        if (!ban) assertThat(jdbc.queryForList("SELECT archive_source FROM tx_items ORDER BY order_id", String.class)).containsExactly("В проверку", "В проверку");
    }

    private Company company(Long id) { Company company = new Company(); company.setId(id); company.setTitle("Company " + id); return company; }
    private CommonBillingAccount loadAccount(Long id) {
        return jdbc.queryForObject("SELECT * FROM tx_accounts WHERE id=?", (rs, row) -> {
            CommonBillingAccount account = new CommonBillingAccount(); account.setId(id); account.setName("Account " + id); account.setEnabled(true);
            Long companyId = rs.getObject("invoice_company", Long.class); account.setInvoiceCompany(companyId == null ? null : company(companyId)); return account;
        }, id);
    }
    private Optional<CommonBillingAccountCompany> loadLink() {
        return jdbc.query("SELECT * FROM tx_account_links WHERE id=71", (rs, row) -> {
            CommonBillingAccountCompany link = new CommonBillingAccountCompany(); link.setId(71L); link.setAccount(loadAccount(1L)); link.setCompany(company(50L));
            link.setEnabled(rs.getBoolean("enabled")); link.setReconcilePending(rs.getBoolean("reconcile_pending")); link.setReconcileAttempts(rs.getInt("reconcile_attempts"));
            link.setReconcileNextAttemptAt(dateTime(rs.getTimestamp("next_attempt"))); link.setReconcileLeaseUntil(dateTime(rs.getTimestamp("lease_until")));
            link.setReconcileLeaseToken(rs.getString("lease_token")); link.setReconcileLastError(rs.getString("last_error")); return link;
        }).stream().findFirst();
    }
    private CommonBillingAccountCompany saveLink(CommonBillingAccountCompany link) {
        link.setId(71L);
        jdbc.update("INSERT INTO tx_account_links VALUES (71,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),reconcile_pending=VALUES(reconcile_pending),reconcile_attempts=VALUES(reconcile_attempts),next_attempt=VALUES(next_attempt),lease_until=VALUES(lease_until),lease_token=VALUES(lease_token),last_error=VALUES(last_error)",
                link.isEnabled(), link.isReconcilePending(), link.getReconcileAttempts(), link.getReconcileNextAttemptAt(), link.getReconcileLeaseUntil(), link.getReconcileLeaseToken(), link.getReconcileLastError());
        return link;
    }
    private CommonInvoice loadInvoice() {
        return jdbc.queryForObject("SELECT * FROM tx_invoices WHERE id=10", (rs, row) -> {
            CommonInvoice invoice = new CommonInvoice(); invoice.setId(10L); invoice.setAccount(loadAccount(2L)); invoice.setAmountKopecks(200000); invoice.setPaidKopecks(0);
            invoice.setStatus(CommonInvoiceStatus.valueOf(rs.getString("status"))); invoice.setPreviousStatus(rs.getString("previous_status"));
            invoice.setClosedAt(dateTime(rs.getTimestamp("closed_at"))); invoice.setClosedBy(rs.getString("closed_by")); invoice.setCloseReason(rs.getString("close_reason")); return invoice;
        });
    }
    private Order loadOrder(Long id) {
        return jdbc.queryForObject("SELECT status FROM tx_orders WHERE id=?", (rs, row) -> {
            Order order = new Order(); order.setId(id); order.setCompany(company(51L));
            OrderStatus status = new OrderStatus(); status.setTitle(rs.getString("status")); order.setStatus(status); return order;
        }, id);
    }
    private List<CommonInvoiceOrder> loadItems() {
        CommonInvoice invoice = loadInvoice();
        return jdbc.query("SELECT * FROM tx_items ORDER BY order_id", (rs, row) -> {
            CommonInvoiceOrder item = new CommonInvoiceOrder(); item.setId(rs.getLong("order_id")); item.setInvoice(invoice); item.setOrder(loadOrder(rs.getLong("order_id")));
            item.setAmountKopecks(100000); item.setArchiveSourceOrderStatusTitle(rs.getString("archive_source")); return item;
        });
    }
    private LocalDateTime dateTime(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private int linkCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM tx_account_links", Integer.class); }
    private long connectionId() { return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); }
    private <T> T dependency(Class<T> type) { return type.cast(dependencies.computeIfAbsent(type, ignored -> mock(type))); }
    private <T> T register(Class<T> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0]; Class<?>[] types = constructor.getParameterTypes(); Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) args[i] = dependency(types[i]);
        ProxyFactory proxy = new ProxyFactory(type.cast(constructor.newInstance(args))); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        T bean = type.cast(proxy.getProxy()); dependencies.put(type, bean); return bean;
    }
    private void createSchema() {
        for (String table : List.of("tx_account_links", "tx_items", "tx_orders", "tx_invoices", "tx_accounts")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE tx_accounts(id BIGINT PRIMARY KEY,invoice_company BIGINT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_account_links(id BIGINT PRIMARY KEY,enabled BOOLEAN,reconcile_pending BOOLEAN,reconcile_attempts INT,next_attempt DATETIME(6),lease_until DATETIME(6),lease_token VARCHAR(80),last_error VARCHAR(512)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_invoices(id BIGINT PRIMARY KEY,status VARCHAR(40),previous_status VARCHAR(40),closed_by VARCHAR(80),closed_at DATETIME(6),close_reason VARCHAR(80)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_orders(id BIGINT PRIMARY KEY,status VARCHAR(80)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE tx_items(order_id BIGINT PRIMARY KEY,archive_source VARCHAR(80)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO tx_accounts VALUES (1,NULL),(2,NULL)");
        jdbc.update("INSERT INTO tx_invoices VALUES (10,'COLLECTING',NULL,NULL,NULL,NULL)");
        jdbc.update("INSERT INTO tx_orders VALUES (101,'В проверку'),(102,'В проверку')");
        jdbc.update("INSERT INTO tx_items VALUES (101,NULL),(102,NULL)");
    }
    private static final class LateFailure extends RuntimeException {}
}
