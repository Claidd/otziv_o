package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.contractor_payments.service.*;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Real Spring advice and real creation/init/state/resolution owners; no provider I/O. */
class PaymentLinkInitializationWorkflowTransactionTest {
    @Test
    void callerIsSuspendedAndReservationCommitsBeforeBankThenOrderIsLockedBeforeLinkAgain() {
        Fixture f = new Fixture();
        when(f.bank.init(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.manager.commits).isEqualTo(1);
            assertThat(f.link.getBankInitNonce()).isNotBlank();
            assertThat(f.link.getBankInitLeaseUntil()).isAfter(LocalDateTime.now());
            return f.created();
        });

        new TransactionTemplate(f.manager).executeWithoutResult(status -> {
            PublicPaymentInitResponse result = f.init();
            assertThat(result.paymentUrl()).isEqualTo(Fixture.PAYMENT_URL);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.manager.commits).isEqualTo(2);
        });

        assertThat(f.manager.commits).isEqualTo(3);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.INITIATED);
        assertThat(f.link.getBankInitNonce()).isNull();
        var calls = inOrder(f.orders, f.links, f.bank);
        calls.verify(f.orders).findByIdForCounterUpdate(7L);
        calls.verify(f.links).findByTokenForUpdate(Fixture.TOKEN);
        calls.verify(f.links).save(f.link);
        calls.verify(f.bank).init(any(), any());
        calls.verify(f.orders).findByIdForCounterUpdate(7L);
        calls.verify(f.links).findByIdForUpdate(71L);
        calls.verify(f.links).save(f.link);
    }

    @Test
    void lostCreateResponseCommitsUnknownAndASecondExplicitInitCannotRepeatPost() {
        Fixture f = new Fixture();
        when(f.bank.init(any(), any())).thenThrow(new IllegalStateException("fixture timeout"));

        assertThatThrownBy(f::init).isInstanceOf(IllegalStateException.class);
        assertThat(f.manager.commits).isEqualTo(2);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.NEEDS_RECONCILIATION);
        assertThat(f.link.getLastError()).startsWith("bank_init_ambiguous:");
        String immutableProviderOrder = f.link.getTbankOrderId();
        assertThat(immutableProviderOrder).isNotBlank();
        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);

        assertThat(f.link.getTbankOrderId()).isEqualTo(immutableProviderOrder);
        assertThat(f.link.getPaymentUrl()).isNull();
        verify(f.bank, times(1)).init(any(), any());
        assertThat(f.manager.rollbacks).isZero();
    }

    @Test
    void activeReservationRejectsConcurrentInitBeforeNetwork() {
        Fixture f = new Fixture();
        f.link.setBankInitNonce("another-in-flight-create");
        f.link.setBankInitLeaseUntil(LocalDateTime.now().plusMinutes(2));
        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);
        assertThat(f.link.getBankInitNonce()).isEqualTo("another-in-flight-create");
        verifyNoInteractions(f.bank);
    }

    @Test
    void expiredReservationWithoutProviderReceiptCommitsQuarantineAndNeverCreatesAgain() {
        Fixture f = new Fixture();
        f.link.setBankInitNonce("expired-unknown-create");
        f.link.setBankInitLeaseUntil(LocalDateTime.now().minusMinutes(2));
        f.link.setTbankOrderId("stable-provider-order");

        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);
        assertThat(f.manager.commits).isEqualTo(1);
        assertThat(f.manager.rollbacks).isZero();
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.NEEDS_RECONCILIATION);
        assertThat(f.link.getTbankOrderId()).isEqualTo("stable-provider-order");
        assertThat(f.link.getLastError()).contains("reservation_expired_without_provider_result");
        verifyNoInteractions(f.bank);
    }

    @Test
    void amountChangedDuringNetworkQuarantinesReceiptInsteadOfExposingPaymentUrl() {
        Fixture f = new Fixture();
        when(f.bank.init(any(), any())).thenAnswer(call -> {
            f.order.setSum(new BigDecimal("200.00"));
            return f.created();
        });
        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.NEEDS_RECONCILIATION);
        assertThat(f.link.getTbankPaymentId()).isEqualTo("fixture-payment");
        assertThat(f.link.getPaymentUrl()).isNull();
        assertThat(f.link.getLastError()).contains("order_amount_changed_during_init_response");
        assertThat(f.manager.commits).isEqualTo(2);
    }

    @Test
    void expiredLinkDuringNetworkCannotPublishTheLateProviderUrl() {
        Fixture f = new Fixture();
        when(f.bank.init(any(), any())).thenAnswer(call -> {
            f.link.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            return f.created();
        });
        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);
        assertThat(f.link.getStatus()).isEqualTo(PaymentLinkStatus.NEEDS_RECONCILIATION);
        assertThat(f.link.getTbankPaymentId()).isEqualTo("fixture-payment");
        assertThat(f.link.getPaymentUrl()).isNull();
        assertThat(f.link.getLastError()).contains("link_expired_during_init_response");
        assertThat(f.manager.commits).isEqualTo(2);
    }

    @Test
    void changedTokenCannotAcceptTheOldReservationReceipt() {
        Fixture f = new Fixture();
        when(f.bank.init(any(), any())).thenAnswer(call -> {
            f.link.setToken("a-different-public-capability");
            return f.created();
        });
        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);
        assertThat(f.link.getTbankPaymentId()).isNull();
        assertThat(f.link.getPaymentUrl()).isNull();
        assertThat(f.link.getBankInitNonce()).isNotBlank();
        verify(f.bank, times(1)).init(any(), any());
    }

    @Test
    void invalidCapabilityAndMissingConsentNeverOpenBankOrPaymentTransaction() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.workflow.init("unknown", "fixture@example.test", true, true, true, null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> f.workflow.init(Fixture.TOKEN, "fixture@example.test", false, true, true, null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(f.bank, f.orders);
        assertThat(f.manager.commits).isZero();
    }

    @Test
    void commonInvoiceAttachmentBlocksStandaloneInitUnderTheOrderLock() {
        Fixture f = new Fixture();
        when(f.common.isOrderInActiveCommonInvoice(7L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return true;
        });
        assertThatThrownBy(f::init).isInstanceOf(ResponseStatusException.class);
        var calls = inOrder(f.orders, f.common);
        calls.verify(f.orders).findByIdForCounterUpdate(7L);
        calls.verify(f.common).isOrderInActiveCommonInvoice(7L);
        verify(f.links, never()).save(any());
        verifyNoInteractions(f.bank);
    }

    @Test
    void failedReplacementThroughActualPreparationDoesNotMarkCallersTransactionRollbackOnly() {
        Fixture f = new Fixture();
        when(f.common.isOrderInActiveCommonInvoice(7L)).thenReturn(true);
        new TransactionTemplate(f.manager).executeWithoutResult(status -> {
            assertThat(f.resolution.createReplacementPublicLink(7L, LocalDateTime.now())).isEmpty();
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(f.manager.commits).isZero();
        });
        assertThat(f.manager.commits).isEqualTo(1);
        assertThat(f.manager.rollbacks).isZero();
        verify(f.orders).findByIdForCounterUpdate(7L);
        verify(f.common).isOrderInActiveCommonInvoice(7L);
        verifyNoInteractions(f.bank);
    }

    @Test
    void joinOnlyReplacementAndStateOperationsCannotStartTheirOwnTransaction() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.preparation.createReplacementInCurrentTransaction(7L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> f.state.clearBankInitReservation(f.link))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(f.orders, f.bank);
    }

    @Test
    void preparationChecksExplicitActorUnderLockBeforeAnyRouteReservation() {
        Fixture f = new Fixture();
        Authentication foreignActor = mock(Authentication.class);
        doThrow(new AccessDeniedException("fixture foreign order"))
                .when(f.access).requireOrderAccess(7L, foreignActor);
        assertThatThrownBy(() -> f.preparation.prepareForOrderAuthorized(7L, foreignActor))
                .isInstanceOf(AccessDeniedException.class);
        var calls = inOrder(f.orders, f.access);
        calls.verify(f.orders).findByIdForCounterUpdate(7L);
        calls.verify(f.access).requireOrderAccess(7L, foreignActor);
        verify(f.links, never()).save(any());
        verifyNoInteractions(f.common, f.bank);
        assertThat(f.manager.rollbacks).isEqualTo(1);
    }

    private static class Fixture {
        static final String TOKEN = "fixture-public-capability";
        static final String PAYMENT_URL = "https://securepay.tinkoff.ru/fixture-payment";
        final TrackingTransactions manager = new TrackingTransactions();
        final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
        final OrderRepository orders = mock(OrderRepository.class);
        final TbankClient bank = mock(TbankClient.class);
        final CommonInvoicePaymentOperations common = mock(CommonInvoicePaymentOperations.class);
        final ManagerAccessService access = mock(ManagerAccessService.class);
        final Order order = new Order();
        final PaymentLink link = new PaymentLink();
        final BankInitializationStateService state;
        final PaymentLinkPreparationWorkflow preparation;
        final PublicPaymentLinkResolutionService resolution;
        final PaymentLinkInitializationWorkflow workflow;

        Fixture() {
            order.setId(7L); order.setSum(new BigDecimal("100.00"));
            var profile = new PaymentProfile(); profile.setId(3L);
            link.setId(71L); link.setOrder(order); link.setToken(TOKEN); link.setPaymentProfile(profile);
            link.setAmountKopecks(100_00L); link.setStatus(PaymentLinkStatus.CREATED);
            link.setPaymentMethod(PaymentMethod.BANK_FORM); link.setExpiresAt(LocalDateTime.now().plusDays(1));
            var properties = new TbankPaymentProperties(); properties.setPublicBaseUrl("https://fixture.example.test");
            var presenter = mock(PaymentLinkPresenter.class);
            var routes = mock(CommonInvoiceRouteSelector.class);
            var observations = mock(PaymentBankObservationService.class);
            var profiles = mock(PaymentProfileService.class);
            var settings = mock(TbankRuntimeSettingsService.class);
            var receipts = mock(ManualPaymentTaskReceiptIntegrationService.class);
            var integrity = mock(OrderPaymentIntegrityService.class);
            var amounts = new PaymentLinkAmountPolicy(mock(BadReviewTaskService.class));
            var transactions = proxied(new PaymentLinkTransactionExecutor(), manager);
            when(presenter.normalize(nullable(String.class))).thenAnswer(call -> {
                String value = call.getArgument(0); return value == null ? "" : value.trim();
            });
            when(presenter.amountRubles(anyLong())).thenAnswer(call -> BigDecimal.valueOf(call.getArgument(0, Long.class), 2));
            when(routes.limit(nullable(String.class), anyInt())).thenAnswer(call -> call.getArgument(0));
            when(observations.resolvePaymentProfile(link)).thenReturn(profile);
            when(profiles.toRuntime(profile)).thenReturn(new TbankPaymentProfile(
                    3L, "fixture", "Fixture", true, "fixture-terminal", "fixture-secret", false));
            when(settings.isPaymentLinksEnabled()).thenReturn(true);
            when(links.findByTokenWithOrder(TOKEN)).thenReturn(Optional.of(link));
            when(links.findByTokenForUpdate(TOKEN)).thenAnswer(call -> { assertTransaction(); return Optional.of(link); });
            when(links.findByIdForUpdate(71L)).thenAnswer(call -> { assertTransaction(); return Optional.of(link); });
            when(orders.findByIdForCounterUpdate(7L)).thenAnswer(call -> { assertTransaction(); return Optional.of(order); });
            when(links.findByOrderIdForUpdate(7L)).thenAnswer(call -> { assertTransaction(); return List.of(link); });
            when(links.save(any(PaymentLink.class))).thenAnswer(call -> { assertTransaction(); return call.getArgument(0); });
            var lifecycle = proxied(new PaymentLinkLifecycleService(amounts, presenter, links, receipts, integrity), manager);
            state = proxied(new BankInitializationStateService(lifecycle, presenter, routes, links), manager);
            preparation = proxied(new PaymentLinkPreparationWorkflow(amounts, lifecycle, state, presenter,
                    routes, observations, links, orders, properties, settings, profiles, receipts,
                    mock(ManualPaymentTaskRepository.class), common, integrity, access,
                    mock(ContractorPaymentLiveRoutingService.class), mock(ContractorPaymentShadowService.class),
                    mock(ContractorPaymentRuntimeSwitch.class), mock(ContractorActualPaymentAttributionService.class),
                    mock(PaymentIssueReminderService.class), transactions), manager);
            resolution = proxied(new PublicPaymentLinkResolutionService(lifecycle, preparation, presenter, links, settings), manager);
            workflow = proxied(new PaymentLinkInitializationWorkflow(mock(PaymentLinkSettlementService.class),
                    lifecycle, state, preparation, resolution, mock(PaymentLinkCancellationWorkflow.class), presenter,
                    routes, observations, links, orders, properties, profiles, bank,
                    mock(TochkaPaymentProfileResolver.class), mock(TochkaClient.class), new TochkaPaymentOperationMapper(),
                    integrity, mock(PaymentLinkReturnOutboxService.class), mock(ContractorPaymentTargetAccessPolicy.class),
                    transactions), manager);
        }

        PublicPaymentInitResponse init() {
            return workflow.init(TOKEN, "fixture@example.test", true, true, true, "127.0.0.1", "fixture");
        }

        TbankInitResponse created() {
            return new TbankInitResponse(true, "0", null, null, "fixture-terminal", "NEW",
                    "fixture-payment", link.getTbankOrderId(), 100_00L, PAYMENT_URL);
        }

        private void assertTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, TrackingTransactions manager) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource(false)));
        return (T) factory.getProxy();
    }

    /** Tests transaction advice and rollback-only propagation; database mapping is tested separately. */
    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        private static class Transaction implements SmartTransactionObject {
            boolean active; boolean rollbackOnly;
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
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { ((Transaction) status.getTransaction()).rollbackOnly = true; }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((Transaction) transaction).active = false; current.remove();
        }
    }
}
