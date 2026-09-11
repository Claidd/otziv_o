package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Exercises real Spring transaction advice; repository mapping is covered separately. */
class PaymentLinkCancellationWorkflowTransactionTest {
    @Test
    void callerTransactionIsSuspendedAroundProviderAndReservationCommitsBeforeNetwork() {
        Fixture f = new Fixture();
        when(f.bank.cancel(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.manager.commits).isEqualTo(1);
            assertThat(f.link.getBankCancelNonce()).isNotBlank();
            assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.NEEDS_RECONCILIATION);
            return f.refunded();
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isEqualTo(1);
            return null;
        }).when(f.outbox).enqueue(f.link);
        doAnswer(call -> {
            assertThat(f.manager.commits).isEqualTo(2);
            return null;
        }).when(f.shadow).reconcilePaymentLinkId(71L);

        new TransactionTemplate(f.manager).executeWithoutResult(status -> {
            f.workflow.cancel(71L);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isEqualTo(2);
        });

        assertThat(f.manager.commits).isEqualTo(3);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.REFUNDED);
        assertThat(f.link.getBankCancelNonce()).isNull();
        var order = inOrder(f.orders, f.links, f.bank, f.outbox, f.shadow);
        order.verify(f.orders).findByIdForCounterUpdate(7L);
        order.verify(f.links).findByIdForUpdate(71L);
        order.verify(f.links).save(f.link);
        order.verify(f.bank).cancel(any(), any());
        order.verify(f.orders).findByIdForCounterUpdate(7L);
        order.verify(f.links).findByIdForUpdate(71L);
        order.verify(f.links).save(f.link);
        order.verify(f.outbox).enqueue(f.link);
        order.verify(f.shadow).reconcilePaymentLinkId(71L);
    }

    @Test
    void unknownProviderResultCommitsQuarantineAndBlocksASecondCancelPost() {
        Fixture f = new Fixture();
        when(f.bank.cancel(any(), any())).thenThrow(new IllegalStateException("fixture timeout"));

        assertThatThrownBy(() -> f.workflow.cancel(71L)).isInstanceOf(IllegalStateException.class);
        assertThat(f.manager.commits).isEqualTo(2);
        assertThat(f.link.getBankCancelNonce()).isNotBlank();
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.NEEDS_RECONCILIATION);
        assertThat(f.link.getLastError()).startsWith("bank_cancel_ambiguous:");
        assertThatThrownBy(() -> f.workflow.cancel(71L)).isInstanceOf(ResponseStatusException.class);

        verify(f.bank, times(1)).cancel(any(), any());
        verifyNoInteractions(f.outbox, f.shadow);
    }

    @Test
    void sharedCancellationTransitionRequiresAndJoinsTheAlreadyLockedTransaction() {
        Fixture f = new Fixture();
        f.link.setBankCancelNonce("existing-claim");
        assertThatThrownBy(() -> f.workflow.clearBankCancelContext(f.link))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(f.link.getBankCancelNonce()).isEqualTo("existing-claim");

        new TransactionTemplate(f.manager).executeWithoutResult(status -> {
            f.workflow.clearBankCancelContext(f.link);
            assertThat(f.manager.commits).isZero();
        });
        assertThat(f.manager.commits).isEqualTo(1);
        assertThat(f.link.getBankCancelNonce()).isNull();
        verifyNoInteractions(f.bank);
    }

    @Test
    void forbiddenActorCannotReadOrReserveThePayment() {
        Fixture f = new Fixture();
        doThrow(new org.springframework.security.access.AccessDeniedException("fixture forbidden"))
                .when(f.access).requireCanManagePaymentLink(71L);
        assertThatThrownBy(() -> f.workflow.cancel(71L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(f.links, f.orders, f.bank, f.outbox);
        assertThat(f.manager.commits).isZero();
    }

    private static class Fixture {
        final TrackingTransactions manager = new TrackingTransactions();
        final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
        final OrderRepository orders = mock(OrderRepository.class);
        final TbankClient bank = mock(TbankClient.class);
        final PaymentLinkReturnOutboxService outbox = mock(PaymentLinkReturnOutboxService.class);
        final ContractorPaymentShadowService shadow = mock(ContractorPaymentShadowService.class);
        final ContractorPaymentTargetAccessPolicy access = mock(ContractorPaymentTargetAccessPolicy.class);
        final PaymentLink link = new PaymentLink();
        final PaymentLinkCancellationWorkflow workflow;

        Fixture() {
            var presenter = mock(PaymentLinkPresenter.class);
            var routes = mock(CommonInvoiceRouteSelector.class);
            var observations = mock(PaymentBankObservationService.class);
            var order = new Order(); order.setId(7L);
            var profile = new PaymentProfile(); profile.setId(3L);
            link.setId(71L); link.setOrder(order); link.setPaymentProfile(profile);
            link.setStatus(PaymentLinkStatus.CONFIRMED); link.setPaymentMethod(PaymentMethod.BANK_FORM);
            link.setAmountKopecks(100_00L); link.setTbankPaymentId("fixture-payment");
            link.setTbankOrderId("fixture-order"); link.setTbankTerminalKey("fixture-terminal");
            when(presenter.normalize(nullable(String.class))).thenAnswer(call -> {
                String value = call.getArgument(0); return value == null ? "" : value.trim();
            });
            when(routes.limit(nullable(String.class), anyInt())).thenAnswer(call -> call.getArgument(0));
            when(presenter.isRefundable(link)).thenReturn(true);
            when(observations.resolvePaymentProfile(link)).thenReturn(profile);
            when(observations.runtimeProfileForLink(profile, link)).thenReturn(new TbankPaymentProfile(
                    3L, "fixture", "Fixture", true, "fixture-terminal", "fixture-secret", false));
            when(links.findByIdWithOrder(71L)).thenReturn(Optional.of(link));
            when(orders.findByIdForCounterUpdate(7L)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return Optional.of(order);
            });
            when(links.findByIdForUpdate(71L)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return Optional.of(link);
            });
            workflow = proxied(new PaymentLinkCancellationWorkflow(presenter, routes, observations,
                    links, orders, bank, mock(TochkaPaymentProfileResolver.class), mock(TochkaClient.class),
                    shadow, outbox, access, proxied(new PaymentLinkTransactionExecutor(), manager)), manager);
        }

        TbankCancelResponse refunded() {
            return new TbankCancelResponse(true, "0", null, null, "fixture-terminal", "REFUNDED",
                    "fixture-payment", "fixture-order", 100_00L, 100_00L, 0L);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, TrackingTransactions manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource(false)));
        return (T) factory.getProxy();
    }

    /** Records commit/suspend semantics, without pretending to validate persistence mapping. */
    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        private static class Transaction { boolean active; }
        private final ThreadLocal<Transaction> current = new ThreadLocal<>();
        int commits;
        @Override protected Object doGetTransaction() {
            return current.get() == null ? new Transaction() : current.get();
        }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((Transaction) transaction).active = true; current.set((Transaction) transaction);
        }
        @Override protected Object doSuspend(Object transaction) { current.remove(); return transaction; }
        @Override protected void doResume(Object transaction, Object suspended) { current.set((Transaction) suspended); }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((Transaction) transaction).active = false; current.remove();
        }
    }
}
