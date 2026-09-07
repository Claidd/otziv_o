package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Real Spring advice around application ports; does not substitute for database lock tests. */
class ManualPaymentConfirmationWorkflowTransactionTest {
    @Test
    void forbiddenActorCannotReadOrConfirmTheSource() {
        Fixture f = new Fixture();
        doThrow(new AccessDeniedException("fixture forbidden")).when(f.access).requireCanManagePaymentLink(71L);

        assertThatThrownBy(() -> f.workflow.confirmManual(71L, "fixture actor"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(f.links, f.orders, f.settlement, f.notifications);
    }

    @Test
    void confirmationLocksOrderBeforeLinkAndNotifiesOnlyAfterTheIndependentCommit() throws Exception {
        Fixture f = new Fixture();
        when(f.settlement.handlePaymentStatusWithoutPrematureRepeat(f.order, 71L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isZero();
            return true;
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.manager.commits).isEqualTo(1);
            return null;
        }).when(f.notifications).notifyAfterCommit(71L);

        new TransactionTemplate(f.manager).executeWithoutResult(status -> {
            f.workflow.confirmManual(71L, "fixture actor");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isEqualTo(1);
        });

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.CONFIRMED);
        assertThat(f.link.getManualConfirmedBy()).isEqualTo("fixture actor");
        var sequence = inOrder(f.orders, f.links, f.settlement, f.notifications);
        sequence.verify(f.orders).findByIdForCounterUpdate(7L);
        sequence.verify(f.links).findByIdForUpdate(71L);
        sequence.verify(f.settlement).handlePaymentStatusWithoutPrematureRepeat(f.order, 71L);
        sequence.verify(f.notifications).notifyAfterCommit(71L);
        assertThat(f.manager.commits).isEqualTo(2);
    }

    @Test
    void changedOrderBindingIsRejectedBeforeCreditingOrNotifying() {
        Fixture f = new Fixture();
        var changed = new PaymentLink(); changed.setId(71L);
        var anotherOrder = new Order(); anotherOrder.setId(8L); changed.setOrder(anotherOrder);
        doReturn(Optional.of(changed)).when(f.links).findByIdForUpdate(71L);

        assertThatThrownBy(() -> f.workflow.confirmManual(71L, "fixture actor"))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(f.manager.rollbacks).isEqualTo(1);
        verifyNoInteractions(f.settlement, f.notifications, f.cancellation);
    }

    @Test
    void partialSourceThenFullSourceConfirmationCreditsOnlyTheExactSourceOnce() throws Exception {
        Fixture f = new Fixture();
        f.link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        when(f.routes.isFrozenContractorRoute(f.link)).thenReturn(true);
        when(f.settlement.handlePaymentStatusWithoutPrematureRepeat(f.order, 71L)).thenReturn(true);

        f.workflow.confirmContractorPaymentSource(71L, 40_00L, null, "fixture statement", "fixture actor");
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.AMOUNT_MISMATCH);
        verify(f.settlement, never()).handlePaymentStatusWithoutPrematureRepeat(any(), any());
        f.workflow.confirmContractorPaymentSource(71L, 100_00L, null, "fixture statement", "fixture actor");
        f.workflow.confirmContractorPaymentSource(71L, 100_00L, null, "fixture replay", "fixture actor");

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.CONFIRMED);
        assertThat(f.link.getConfirmedAmountKopecks()).isEqualTo(100_00L);
        verify(f.settlement).handlePaymentStatusWithoutPrematureRepeat(f.order, 71L);
        verify(f.notifications).notifyAfterCommit(71L);
        verify(f.cancellation, times(3)).reconcileContractorPaymentRouteAfterCommit(71L);
        assertThatThrownBy(() -> f.workflow.confirmContractorPaymentSource(
                71L, 30_00L, null, "fixture reduction", "fixture actor"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(f.link.getConfirmedAmountKopecks()).isEqualTo(100_00L);
    }

    @Test
    void competingBankReservationPreventsSourceConfirmationAndDoesNotReleaseTheAttempt() {
        Fixture f = new Fixture();
        f.link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        when(f.routes.isFrozenContractorRoute(f.link)).thenReturn(true);
        var competing = new PaymentLink(); competing.setId(72L); competing.setOrder(f.order);
        competing.setStatus(PaymentLinkStatus.CREATED); competing.setBankInitNonce("fixture pending reservation");
        when(f.links.findByOrderIdForUpdate(7L)).thenReturn(List.of(f.link, competing));

        assertThatThrownBy(() -> f.workflow.confirmContractorPaymentSource(
                71L, 100_00L, null, "fixture statement", "fixture actor"))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(competing.getBankInitNonce()).isEqualTo("fixture pending reservation");
        assertThat(f.link.getConfirmedAmountKopecks()).isNull();
        verify(f.links, never()).save(any());
        verifyNoInteractions(f.settlement, f.liveRouting, f.notifications);
    }

    @Test
    void verifiedAbsentTransferReleasesOnlyTheSelectedInstructionWithoutCreditingTheOrder() {
        Fixture f = new Fixture();
        var actor = new UsernamePasswordAuthenticationToken("fixture actor", "unused");

        f.workflow.closeManualAsUnpaid(71L, true, true, "fixture statement checked", "fixture actor", actor);

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.CANCELED);
        assertThat(f.link.getLastError()).startsWith(ManualPaymentConfirmationWorkflow.MANUAL_UNPAID_CLOSED_AUDIT_PREFIX);
        verify(f.receipts).release(f.link, "Перевод не поступил");
        verify(f.managerAccess).requireOrderAccess(7L, actor);
        verifyNoInteractions(f.settlement, f.notifications);
    }

    @Test
    void expiredPublicValidationPreservesTheOwnersNoRollbackPolicyAndReleasesTheReservation() {
        Fixture f = new Fixture();
        f.link.setExpiresAt(LocalDateTime.now().minusHours(1));
        assertThatThrownBy(() -> f.lifecycle.validatePayable(f.link, true))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);

        assertThatThrownBy(() -> f.executor.requiredNoRollback(() -> {
            f.lifecycle.validatePayable(f.link, true);
            return null;
        })).isInstanceOf(ResponseStatusException.class);

        assertThat(f.manager.commits).isEqualTo(1);
        assertThat(f.manager.rollbacks).isZero();
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.EXPIRED);
        verify(f.receipts).release(f.link, "Срок действия платёжной ссылки истек");
    }

    @Test
    void amountChangeCannotRetireAReservedBankAttempt() {
        Fixture f = new Fixture();
        f.link.setStatus(PaymentLinkStatus.CREATED); f.link.setBankInitNonce("fixture pending reservation");
        f.order.setSum(new BigDecimal("120.00"));

        f.executor.required(() -> {
            assertThat(f.lifecycle.isAmountChanged(f.link)).isTrue();
            assertThat(f.lifecycle.expireIfAmountChanged(f.link)).isFalse();
            return null;
        });

        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.CREATED);
        assertThat(f.link.getBankInitNonce()).isNotBlank();
        verifyNoInteractions(f.receipts);
        verify(f.links, never()).save(any());
    }

    private static class Fixture {
        final TrackingTransactions manager = new TrackingTransactions();
        final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
        final OrderRepository orders = mock(OrderRepository.class);
        final PaymentLinkSettlementService settlement = mock(PaymentLinkSettlementService.class);
        final PaymentLinkCancellationWorkflow cancellation = mock(PaymentLinkCancellationWorkflow.class);
        final CommonInvoiceRouteSelector routes = mock(CommonInvoiceRouteSelector.class);
        final ManualPaymentRecipientTelegramNotificationService notifications = mock(ManualPaymentRecipientTelegramNotificationService.class);
        final ManualPaymentTaskReceiptIntegrationService receipts = mock(ManualPaymentTaskReceiptIntegrationService.class);
        final ContractorPaymentLiveRoutingService liveRouting = mock(ContractorPaymentLiveRoutingService.class);
        final ContractorPaymentTargetAccessPolicy access = mock(ContractorPaymentTargetAccessPolicy.class);
        final ManagerAccessService managerAccess = mock(ManagerAccessService.class);
        final Order order = new Order();
        final PaymentLink link = new PaymentLink();
        final PaymentLinkTransactionExecutor executor = proxied(new PaymentLinkTransactionExecutor(), manager);
        final PaymentLinkLifecycleService lifecycle;
        final ManualPaymentConfirmationWorkflow workflow;

        Fixture() {
            var presenter = mock(PaymentLinkPresenter.class);
            order.setId(7L); order.setSum(new BigDecimal("100.00")); order.setComplete(true);
            link.setId(71L); link.setOrder(order); link.setAmountKopecks(100_00L);
            link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
            link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
            link.setManualSource(ManualPaymentSource.PROFILE_MONTHLY_LIMIT);
            link.setExpiresAt(LocalDateTime.now().plusDays(1));
            when(presenter.normalize(nullable(String.class))).thenAnswer(call -> {
                String value = call.getArgument(0); return value == null ? "" : value.trim();
            });
            when(presenter.amountRubles(anyLong())).thenAnswer(call -> BigDecimal.valueOf(call.getArgument(0, Long.class), 2));
            when(presenter.isManualPayment(link)).thenReturn(true);
            when(presenter.toAdminResponse(link)).thenReturn(mock(com.hunt.otziv.payments.dto.AdminPaymentLinkResponse.class));
            when(routes.limit(nullable(String.class), anyInt())).thenAnswer(call -> call.getArgument(0));
            when(settlement.canApplyOrderPaymentNow(order)).thenReturn(true);
            when(links.findByIdWithOrder(71L)).thenReturn(Optional.of(link));
            when(links.findByIdForUpdate(71L)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return Optional.of(link);
            });
            when(orders.findByIdForCounterUpdate(7L)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return Optional.of(order);
            });
            when(links.findByOrderIdForUpdate(7L)).thenReturn(List.of(link));
            var amounts = new PaymentLinkAmountPolicy(mock(BadReviewTaskService.class));
            lifecycle = proxied(new PaymentLinkLifecycleService(amounts, presenter, links, receipts,
                    mock(OrderPaymentIntegrityService.class)), manager);
            workflow = proxied(new ManualPaymentConfirmationWorkflow(amounts, settlement, lifecycle,
                    cancellation, presenter, routes, links, orders, notifications, mock(ManualPaymentTaskService.class),
                    receipts, managerAccess, liveRouting, mock(ContractorActualPaymentAttributionService.class),
                    access, executor), manager);
        }

    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, TrackingTransactions manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource(false)));
        return (T) factory.getProxy();
    }

    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        private static class Transaction { boolean active; }
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
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((Transaction) transaction).active = false; current.remove();
        }
    }
}
