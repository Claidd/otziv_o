package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Production approval/route policy with real Spring advice and independent InnoDB connections.
 * JDBC repository adapters isolate lock/commit behavior; JPA projection startup is covered by
 * OtzivOApplicationTests. The downstream manual-payment owner is tested in PaymentLinkServiceTest.
 */
@Testcontainers
class OwnerManualCardApprovalWorkflowMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383");
    private Fixture f;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS approval_orders(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS approval_links(id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS approval_requests(
                  id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, link_id BIGINT NOT NULL UNIQUE,
                  token_hash VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL,
                  attempts INT NOT NULL, reason VARCHAR(500), last_error VARCHAR(512)
                ) ENGINE=InnoDB
                """);
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM approval_links");
        jdbc.update("DELETE FROM approval_orders");
        jdbc.update("INSERT INTO approval_orders VALUES (7), (8)");
        jdbc.update("INSERT INTO approval_links VALUES (71, 7)");
        jdbc.update("INSERT INTO approval_requests VALUES (91, 7, 71, ?, 'PENDING', 0, 'Fixture transfer', NULL)", hash("fixture-token"));
        f = new Fixture(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void preparationCommitsBeforePaymentHandoffAndReplayDoesNotCreditAgain() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT attempts FROM approval_requests WHERE id=91", Integer.class)).isEqualTo(1);
            assertThat(f.status()).isEqualTo("PENDING");
            assertThat(call.getArgument(10, Boolean.class)).isTrue();
            return null;
        }).when(f.payments).confirmPaidByManualCardTransferInternal(eq(71L), eq(100_00L), anyString(),
                nullable(String.class), anyString(), eq(f.auth), any(), eq(ContractorRecipientType.OWNER),
                isNull(), eq("OWNER"), eq(true));

        var first = f.approve();
        var replay = f.approve();

        assertThat(first.alreadyCompleted()).isFalse();
        assertThat(replay.alreadyCompleted()).isTrue();
        assertThat(f.status()).isEqualTo("CONFIRMED");
        var calls = inOrder(f.orders, f.links, f.approvals, f.payments);
        calls.verify(f.approvals).findBindingById(91L);
        calls.verify(f.orders).findByIdForCounterUpdate(7L);
        calls.verify(f.links).findByIdForUpdate(71L);
        calls.verify(f.approvals).findByIdForUpdate(91L);
        verify(f.payments, times(1)).confirmPaidByManualCardTransferInternal(anyLong(), anyLong(), anyString(),
                nullable(String.class), anyString(), any(), any(), any(), nullable(Long.class), anyString(), eq(true));
    }

    @Test
    void concurrentReplacementRequestFinishesAndOldCallbackFailsWithoutLockInversion() throws Exception {
        CountDownLatch requestHasOrder = new CountDownLatch(1);
        CountDownLatch allowRequest = new CountDownLatch(1);
        CountDownLatch callbackReadBinding = new CountDownLatch(1);
        f.orderLocked = () -> {
            if (Thread.currentThread().getName().equals("approval-request")) {
                requestHasOrder.countDown();
                await(allowRequest);
            }
        };
        f.bindingRead = callbackReadBinding::countDown;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var request = pool.submit(() -> {
                Thread.currentThread().setName("approval-request");
                return f.workflow.submitManagerManualCardPaymentForOrder(7L, "Replacement request", null,
                        ContractorRecipientType.OWNER, null, "OWNER", "fixture-manager", f.auth);
            });
            assertThat(requestHasOrder.await(5, TimeUnit.SECONDS)).isTrue();
            var callback = pool.submit(() -> {
                Thread.currentThread().setName("approval-callback");
                return catchThrowable(f::approve);
            });
            try {
                assertThat(callbackReadBinding.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                allowRequest.countDown();
            }
            assertThat(request.get(8, TimeUnit.SECONDS)).isNotNull();
            assertThat(callback.get(8, TimeUnit.SECONDS)).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(f.status()).isEqualTo("PENDING");
        assertThat(f.jdbc.queryForObject("SELECT token_hash FROM approval_requests WHERE id=91", String.class))
                .isNotEqualTo(hash("fixture-token"));
        verify(f.payments, never()).confirmPaidByManualCardTransferInternal(anyLong(), anyLong(), anyString(),
                nullable(String.class), anyString(), any(), any(), any(), nullable(Long.class), anyString(), eq(true));
    }

    @Test
    void downstreamUnknownKeepsPendingApprovalAndPersistsFailureInsteadOfClaimingConfirmation() {
        doThrow(new IllegalStateException("fixture provider outcome unknown"))
                .when(f.payments).confirmPaidByManualCardTransferInternal(anyLong(), anyLong(), anyString(),
                        nullable(String.class), anyString(), any(), any(), any(), nullable(Long.class), anyString(), eq(true));
        assertThatThrownBy(f::approve).isInstanceOf(IllegalStateException.class);
        assertThat(f.status()).isEqualTo("PENDING");
        assertThat(f.jdbc.queryForObject("SELECT attempts FROM approval_requests WHERE id=91", Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM approval_requests WHERE id=91", String.class)).contains("unknown");
    }

    @Test
    void wrongActorAndWrongGroupNeverDispatchTheManualPayment() {
        var otherAuth = new TestingAuthenticationToken("someone-else", "unused", "ROLE_OWNER");
        assertThatThrownBy(() -> f.workflow.approveOwnerManualCardPayment(91L, "fixture-token", -700L, f.owner, otherAuth))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(f.approvals);
        assertThatThrownBy(() -> f.workflow.approveOwnerManualCardPayment(91L, "fixture-token", -701L, f.owner, f.auth))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(f.payments);
        assertThat(f.status()).isEqualTo("PENDING");
        verify(f.approvals, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void invalidCallbackSecretCannotAcquireOrderOrLinkLocks() {
        assertThatThrownBy(() -> f.workflow.approveOwnerManualCardPayment(91L, "wrong-secret", -700L, f.owner, f.auth))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(f.orders, f.links, f.payments);
        verify(f.approvals, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void bindingChangedAfterReadIsRejectedBeforePaymentHandoff() {
        f.bindingRead = () -> {
            // Different connection: the projection must be revalidated after taking locks.
            JdbcTemplate outside = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
            outside.update("UPDATE approval_requests SET order_id=8 WHERE id=91");
        };
        assertThatThrownBy(f::approve).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Запрос изменился");
        verifyNoInteractions(f.payments);
        assertThat(f.status()).isEqualTo("PENDING");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static class Fixture {
        final JdbcTemplate jdbc;
        final OrderRepository orders = mock(OrderRepository.class);
        final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
        final OwnerManualCardPaymentApprovalRepository approvals = mock(OwnerManualCardPaymentApprovalRepository.class);
        final ManualCardPaymentWorkflow payments = mock(ManualCardPaymentWorkflow.class);
        final User owner = new User();
        final TestingAuthenticationToken auth = new TestingAuthenticationToken("fixture-owner", "unused", "ROLE_OWNER");
        final OwnerManualCardApprovalWorkflow workflow;
        volatile Runnable orderLocked = () -> {};
        volatile Runnable bindingRead = () -> {};

        Fixture(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
            this.jdbc = jdbc;
            owner.setId(1L); owner.setUsername("fixture-owner"); owner.setActive(true);
            var role = new Role(); role.setName("ROLE_OWNER"); owner.setRoles(List.of(role));
            var presenter = mock(PaymentLinkPresenter.class);
            var routes = mock(CommonInvoiceRouteSelector.class);
            when(presenter.normalize(nullable(String.class))).thenAnswer(call -> {
                String value = call.getArgument(0); return value == null ? "" : value.trim();
            });
            when(routes.limit(nullable(String.class), anyInt())).thenAnswer(call -> call.getArgument(0));
            var lifecycle = new PaymentLinkLifecycleService(mock(PaymentLinkAmountPolicy.class), presenter,
                    links, mock(ManualPaymentTaskReceiptIntegrationService.class), mock(OrderPaymentIntegrityService.class));
            var policy = new ManualCardRoutePolicy(lifecycle, mock(ManualPaymentConfirmationWorkflow.class),
                    presenter, mock(ContractorPaymentRuntimeSwitch.class), mock(ContractorActualPaymentAttributionService.class));
            when(orders.findByIdForCounterUpdate(7L)).thenAnswer(call -> {
                requireTransaction(); jdbc.queryForObject("SELECT id FROM approval_orders WHERE id=7 FOR UPDATE", Long.class);
                orderLocked.run(); return Optional.of(order());
            });
            when(links.findByIdWithOrder(71L)).thenAnswer(call -> Optional.of(link()));
            when(links.findByIdForUpdate(71L)).thenAnswer(call -> {
                requireTransaction(); jdbc.queryForObject("SELECT id FROM approval_links WHERE id=71 FOR UPDATE", Long.class);
                return Optional.of(link());
            });
            when(links.save(any(PaymentLink.class))).thenAnswer(call -> { requireTransaction(); return call.getArgument(0); });
            when(approvals.findBindingById(91L)).thenAnswer(call -> {
                var binding = jdbc.queryForObject("SELECT order_id, link_id, token_hash FROM approval_requests WHERE id=91",
                        (row, index) -> new Binding(row.getLong(1), row.getLong(2), row.getString(3)));
                bindingRead.run(); return Optional.of(binding);
            });
            when(approvals.findByIdForUpdate(91L)).thenAnswer(call -> { requireTransaction(); return loadApproval("id=91"); });
            when(approvals.findByPaymentLinkIdForUpdate(71L)).thenAnswer(call -> { requireTransaction(); return loadApproval("link_id=71"); });
            when(approvals.save(any())).thenAnswer(call -> save(call.getArgument(0)));
            when(approvals.saveAndFlush(any())).thenAnswer(call -> save(call.getArgument(0)));
            when(payments.validatedReceiptUrl(nullable(String.class))).thenReturn("");
            when(payments.selectManualCardPaymentRouteForOrder(7L, auth)).thenReturn(new ManualCardPaymentWorkflow.OrderManualCardRoute(71L, 100_00L));
            workflow = proxied(new OwnerManualCardApprovalWorkflow(lifecycle,
                    mock(PaymentLinkPreparationWorkflow.class), policy, payments, presenter, routes,
                    links, orders, mock(ManagerAccessService.class), mock(ManualCardPaymentReviewNotificationService.class),
                    approvals, proxied(new PaymentLinkTransactionExecutor(), transactions)), transactions);
            clearInvocations(payments);
        }

        private Order order() {
            var manager = new Manager(); manager.setId(17L); manager.setAuditTelegramGroupChatId(-700L);
            var company = new Company(); company.setId(27L); company.setManager(manager);
            var order = new Order(); order.setId(7L); order.setCompany(company); return order;
        }

        private PaymentLink link() {
            var link = new PaymentLink(); link.setId(71L); link.setOrder(order()); link.setAmountKopecks(100_00L);
            link.setStatus(PaymentLinkStatus.CREATED); link.setPaymentMethod(PaymentMethod.BANK_FORM);
            link.setManualActualRecipientType(ContractorRecipientType.OWNER);
            link.setManualActualCashDestinationKind(ContractorCashDestinationKind.OWNER);
            link.setManualActualRecipientFrozenAt(LocalDateTime.now()); return link;
        }

        private Optional<OwnerManualCardPaymentApproval> loadApproval(String fixturePredicate) {
            return jdbc.query("SELECT * FROM approval_requests WHERE " + fixturePredicate + " FOR UPDATE", (row, index) -> {
                var approval = new OwnerManualCardPaymentApproval(); approval.setId(row.getLong("id"));
                approval.setOrderId(row.getLong("order_id")); approval.setPaymentLinkId(row.getLong("link_id"));
                approval.setCallbackTokenHash(row.getString("token_hash"));
                approval.setStatus(OwnerManualCardPaymentApprovalStatus.valueOf(row.getString("status")));
                approval.setAttemptCount(row.getInt("attempts")); approval.setReason(row.getString("reason"));
                approval.setLastError(row.getString("last_error")); approval.setAmountKopecks(100_00L);
                approval.setRecipientType(ContractorRecipientType.OWNER); approval.setRecipientKey("OWNER");
                approval.setRequestedBy("fixture-manager"); approval.setReceiptUrl(""); return approval;
            }).stream().findFirst();
        }

        private OwnerManualCardPaymentApproval save(OwnerManualCardPaymentApproval approval) {
            requireTransaction();
            jdbc.update("UPDATE approval_requests SET order_id=?, link_id=?, token_hash=?, status=?, attempts=?, reason=?, last_error=? WHERE id=?",
                    approval.getOrderId(), approval.getPaymentLinkId(), approval.getCallbackTokenHash(), approval.getStatus().name(),
                    approval.getAttemptCount(), approval.getReason(), approval.getLastError(), approval.getId());
            return approval;
        }

        private String status() { return jdbc.queryForObject("SELECT status FROM approval_requests WHERE id=91", String.class); }
        private void requireTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
        OwnerManualCardApprovalWorkflow.OwnerManualCardPaymentApprovalOutcome approve() {
            return workflow.approveOwnerManualCardPayment(91L, "fixture-token", -700L, owner, auth);
        }
    }

    private record Binding(Long getOrderId, Long getPaymentLinkId, String getCallbackTokenHash)
            implements OwnerManualCardPaymentApprovalRepository.ApprovalBinding {}

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, PlatformTransactionManager transactions) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource(false)));
        return (T) factory.getProxy();
    }
}
