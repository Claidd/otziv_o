package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import com.hunt.otziv.z_zp.service.PaymentCheckSourceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Real Spring advice and application ports; database locking/mapping is tested separately. */
class PaymentLinkSettlementServiceTransactionTest {
    @Test
    void providerFinalizationCannotMutateOutsideTheCallersLockedTransaction() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.INITIATED);
        verifyNoInteractions(f.ledger, f.checks, f.common, f.outbox);
    }

    @Test
    void repeatedConfirmationCreditsOnceAndKeepsLedgerAndSourceBindingInTheExistingTransaction() throws Exception {
        Fixture f = new Fixture();
        when(f.ledger.handlePaymentStatus(f.order)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(PaymentCheckSourceContext.currentPaymentLinkId()).isEqualTo(71L);
            assertThat(f.manager.commits).isZero();
            return true;
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isZero();
            return null;
        }).when(f.checks).assertActiveCheckBoundToPaymentLink(7L, 71L);

        f.tx.executeWithoutResult(status -> {
            f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0");
            f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0");
            assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.CONFIRMED);
            assertThat(f.link.getConfirmedAmountKopecks()).isEqualTo(100_00L);
            assertThat(PaymentCheckSourceContext.currentPaymentLinkId()).isNull();
            assertThat(f.manager.commits).isZero();
            verifyNoInteractions(f.scheduler);
        });

        verify(f.ledger).handlePaymentStatus(f.order);
        verify(f.checks).assertActiveCheckBoundToPaymentLink(7L, 71L);
        verify(f.common).applyConfirmedOrderPayment(eq(7L), any(), anyString());
        verify(f.scheduler).cancelBadReviewAutoBanInNewTransaction(eq(7L), anyString());
        assertThat(f.manager.commits).isEqualTo(1);
    }

    @Test
    void anotherConfirmedLinkQuarantinesTheNewPaymentWithoutCreditingAgain() {
        Fixture f = new Fixture();
        var previous = new PaymentLink(); previous.setId(72L);
        when(f.links.findByOrder_IdAndStatusIn(eq(7L), anySet())).thenReturn(List.of(previous));

        f.tx.executeWithoutResult(status -> f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0"));

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.AMOUNT_MISMATCH);
        assertThat(f.link.getLastError()).startsWith("duplicate_confirmed_payment:");
        verifyNoInteractions(f.ledger, f.checks, f.common);
    }

    @Test
    void completedBadReviewWorkIsIncludedInTheAmountInvariant() {
        Fixture f = new Fixture();
        when(f.badReviews.getSummaryForOrder(7L)).thenReturn(new BadReviewTaskSummary(
                1, 0, 1, 0, new BigDecimal("15.00"), BigDecimal.ZERO));

        f.tx.executeWithoutResult(status -> f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0"));

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.AMOUNT_MISMATCH);
        assertThat(f.link.getLastError()).contains("115 руб.");
        verifyNoInteractions(f.ledger, f.checks, f.common);
    }

    @Test
    void testProviderConfirmationNeverCreditsTheOrder() {
        Fixture f = new Fixture();
        when(f.profiles.isTestTerminal("fixture-terminal")).thenReturn(true);

        f.tx.executeWithoutResult(status -> f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0"));

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.TEST_CONFIRMED);
        verifyNoInteractions(f.ledger, f.checks, f.common);
    }

    @Test
    void prepaymentWaitsForRecoveryThenLocksOrderBeforeLinkAndJoinsTheCallersTransaction() throws Exception {
        Fixture f = new Fixture();
        when(f.recovery.hasActiveRecoveryTasks(7L)).thenReturn(true);
        f.tx.executeWithoutResult(status -> f.settlement.applyBankStatus(f.link, "CONFIRMED", true, "0"));
        assertThat(f.link.getLastError()).isEqualTo(PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION);
        verifyNoInteractions(f.ledger, f.checks);
        when(f.recovery.hasActiveRecoveryTasks(7L)).thenReturn(false);
        when(f.ledger.handlePaymentStatus(f.order)).thenReturn(true);

        f.tx.executeWithoutResult(status -> {
            assertThat(f.settlement.applyConfirmedPrepaymentIfReady(f.order)).isTrue();
            assertThat(f.manager.commits).isEqualTo(1);
            assertThat(f.link.getLastError()).isNull();
            assertThat(f.settlement.applyConfirmedPrepaymentIfReady(f.order)).isFalse();
        });

        var sequence = inOrder(f.orders, f.links, f.ledger);
        sequence.verify(f.orders).findByIdForCounterUpdate(7L);
        sequence.verify(f.links).findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc(
                7L, PaymentLinkStatus.CONFIRMED, PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION);
        sequence.verify(f.ledger).handlePaymentStatus(f.order);
        verify(f.checks).assertActiveCheckBoundToPaymentLink(7L, 71L);
        assertThat(f.manager.commits).isEqualTo(2);
    }

    @Test
    void failedPrepaymentRollsBackApplicationBeforeCommittingItsRecoveryMarker() throws Exception {
        Fixture f = new Fixture();
        f.link.setStatus(PaymentLinkStatus.CONFIRMED);
        f.link.setLastError(PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION);
        when(f.ledger.handlePaymentStatus(f.order)).thenThrow(new IllegalStateException("fixture ledger failure"));

        assertThat(f.settlement.applyConfirmedPrepaymentIfReady(7L)).isFalse();

        assertThat(f.manager.rollbacks).isEqualTo(1);
        assertThat(f.manager.commits).isEqualTo(1);
        assertThat(f.link.getLastError()).startsWith(PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION)
                .contains(PaymentLinkSettlementService.PREPAID_APPLY_FAILED, "fixture ledger failure");
        verify(f.links).save(f.link);
        verifyNoInteractions(f.checks, f.notifications, f.scheduler, f.outbox);
    }

    @Test
    void sharedTransitionLeavesQuarantineCommitPolicyWithItsExistingApplicationOwner() throws Exception {
        Fixture f = new Fixture();
        when(f.ledger.handlePaymentStatus(f.order)).thenThrow(new IllegalStateException("fixture ledger failure"));
        var owner = proxied(new PaymentLinkTransactionExecutor(), f.manager);

        assertThatThrownBy(() -> owner.requiredNoRollback(() -> {
            try {
                f.settlement.handlePaymentStatusWithoutPrematureRepeat(f.order, 71L);
            } catch (Exception failure) {
                f.link.setLastError("manual_transition_requires_reconciliation");
                f.links.save(f.link);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "fixture reconciliation", failure);
            }
            return null;
        })).isInstanceOf(ResponseStatusException.class);

        assertThat(f.manager.commits).isEqualTo(1);
        assertThat(f.manager.rollbacks).isZero();
        verify(f.links).save(f.link);
        assertThat(PaymentCheckSourceContext.currentPaymentLinkId()).isNull();
    }

    @Test
    void refundOutboxJoinsTheProviderTransactionAndCommonInvoiceReconciliationWaitsForCommit() {
        Fixture f = new Fixture();
        f.link.setStatus(PaymentLinkStatus.CONFIRMED);
        when(f.cancellation.isRefundOrReversalBankStatus("REFUNDED")).thenReturn(true);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isZero();
            return null;
        }).when(f.outbox).enqueue(f.link);
        doAnswer(call -> {
            assertThat(f.manager.commits).isEqualTo(1);
            return null;
        }).when(f.common).applyStandalonePaymentReversal(7L, 71L, PaymentLinkStatus.REFUNDED);

        f.tx.executeWithoutResult(status -> {
            f.settlement.applyBankStatus(f.link, "REFUNDED", true, "0");
            verify(f.outbox).enqueue(f.link);
            verifyNoInteractions(f.common);
        });

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.REFUNDED);
        verify(f.common).applyStandalonePaymentReversal(7L, 71L, PaymentLinkStatus.REFUNDED);
        verifyNoInteractions(f.ledger, f.checks);
    }

    private static class Fixture {
        final TrackingTransactions manager = new TrackingTransactions();
        final TransactionTemplate tx = new TransactionTemplate(manager);
        final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
        final OrderRepository orders = mock(OrderRepository.class);
        final BadReviewTaskService badReviews = mock(BadReviewTaskService.class);
        final ReviewRecoveryGateService recovery = mock(ReviewRecoveryGateService.class);
        final OrderTransactionService ledger = mock(OrderTransactionService.class);
        final PaymentCheckService checks = mock(PaymentCheckService.class);
        final CommonInvoicePaymentOperations common = mock(CommonInvoicePaymentOperations.class);
        final PaymentProfileService profiles = mock(PaymentProfileService.class);
        final PaymentSuccessNotificationDeliveryService notifications = mock(PaymentSuccessNotificationDeliveryService.class);
        final PaymentInvoiceRetryScheduler scheduler = mock(PaymentInvoiceRetryScheduler.class);
        final PaymentLinkReturnOutboxService outbox = mock(PaymentLinkReturnOutboxService.class);
        final PaymentLinkCancellationWorkflow cancellation = mock(PaymentLinkCancellationWorkflow.class);
        final Order order = new Order();
        final PaymentLink link = new PaymentLink();
        final PaymentLinkSettlementService settlement;

        Fixture() {
            var presenter = mock(PaymentLinkPresenter.class);
            var routes = mock(CommonInvoiceRouteSelector.class);
            var runtime = mock(TbankRuntimeSettingsService.class);
            order.setId(7L); order.setSum(new BigDecimal("100.00")); order.setComplete(true);
            link.setId(71L); link.setOrder(order); link.setAmountKopecks(100_00L);
            link.setStatus(PaymentLinkStatus.INITIATED); link.setTbankTerminalKey("fixture-terminal");
            when(runtime.isApplyConfirmedPayments()).thenReturn(true);
            when(presenter.normalize(nullable(String.class))).thenAnswer(call -> {
                String value = call.getArgument(0); return value == null ? "" : value.trim();
            });
            when(presenter.amountRubles(anyLong())).thenAnswer(call -> BigDecimal.valueOf(call.getArgument(0, Long.class), 2));
            when(routes.limit(nullable(String.class), anyInt())).thenAnswer(call -> call.getArgument(0));
            when(badReviews.getSummaryForOrder(7L)).thenReturn(BadReviewTaskSummary.empty());
            when(orders.findByIdForCounterUpdate(7L)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return Optional.of(order);
            });
            when(links.findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc(
                    7L, PaymentLinkStatus.CONFIRMED, PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION))
                    .thenAnswer(call -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        return link.getLastError() != null && link.getLastError().startsWith(
                                PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION) ? Optional.of(link) : Optional.empty();
                    });
            settlement = proxied(new PaymentLinkSettlementService(new PaymentLinkAmountPolicy(badReviews),
                    cancellation, presenter, routes, links, orders, recovery, ledger, checks, runtime, profiles,
                    mock(TochkaPaymentProfileResolver.class), notifications, scheduler, common, outbox,
                    proxied(new PaymentLinkTransactionExecutor(), manager)), manager);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, TrackingTransactions manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource(false)));
        return (T) factory.getProxy();
    }

    /** Tracks actual advice commit/rollback/join behavior, without emulating database persistence. */
    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        private static class Transaction implements SmartTransactionObject {
            boolean active;
            boolean rollbackOnly;
            @Override public boolean isRollbackOnly() { return rollbackOnly; }
        }
        private final ThreadLocal<Transaction> current = new ThreadLocal<>();
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return current.get() == null ? new Transaction() : current.get(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((Transaction) transaction).active = true; current.set((Transaction) transaction);
        }
        @Override protected Object doSuspend(Object transaction) { current.remove(); return transaction; }
        @Override protected void doResume(Object transaction, Object suspended) { current.set((Transaction) suspended); }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((Transaction) status.getTransaction()).rollbackOnly = true;
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((Transaction) transaction).active = false; current.remove();
        }
    }
}
