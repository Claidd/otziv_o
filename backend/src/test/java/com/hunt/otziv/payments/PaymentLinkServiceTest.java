package com.hunt.otziv.payments;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetQrBankListCommand;
import com.hunt.otziv.payments.dto.TbankGetQrBankListResponse;
import com.hunt.otziv.payments.dto.TbankGetQrCommand;
import com.hunt.otziv.payments.dto.TbankGetQrResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.ManualPaymentRecipientTelegramNotificationService;
import com.hunt.otziv.payments.service.ManualCardPaymentReviewNotificationService;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.service.PaymentLinkArchiveService;
import com.hunt.otziv.payments.service.PaymentLinkReturnOutboxService;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentLinkTransactionExecutor;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.PaymentSuccessNotificationDeliveryService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Role;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentLinkServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private OrderTransactionService orderTransactionService;

    @Mock
    private TbankClient tbankClient;

    @Mock
    private PaymentProfileService paymentProfileService;

    @Mock
    private TbankRuntimeSettingsService runtimeSettingsService;

    @Mock
    private PaymentSuccessNotificationDeliveryService paymentSuccessNotificationDeliveryService;

    @Mock
    private ManualPaymentRecipientTelegramNotificationService manualPaymentRecipientTelegramNotificationService;

    @Mock
    private ManualPaymentTaskService manualPaymentTaskService;

    @Mock
    private ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    @Mock
    private ManualPaymentTaskRepository manualPaymentTaskRepository;

    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    @Mock
    private PaymentLinkArchiveService paymentLinkArchiveService;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private OrderPaymentIntegrityService orderPaymentIntegrityService;

    @Mock
    private ManagerAccessService managerAccessService;

    @Mock
    private ManualCardPaymentReviewNotificationService manualCardPaymentReviewNotificationService;

    @Mock
    private OwnerManualCardPaymentApprovalRepository ownerManualCardPaymentApprovalRepository;

    @Mock
    private ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    @Mock
    private ContractorPaymentShadowService contractorPaymentShadowService;

    @Mock
    private ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;

    @Mock
    private PaymentLinkReturnOutboxService paymentLinkReturnOutboxService;

    @Mock
    private ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    @Mock
    private PaymentIssueReminderService paymentIssueReminderService;

    @Mock
    private ContractorActualPaymentAttributionService actualPaymentAttributionService;

    @Mock
    private Authentication authentication;

    @Test
    void payableChangeExpiresPristineOrdinaryLinkAndReleasesItsAllocation() {
        PaymentLinkService service = service(properties());
        Order order = order(8_902L, "ООО Исправленный счёт", BigDecimal.valueOf(2_500));
        PaymentLink link = payableLink(order, "stale-ordinary-link", 240_000L);
        link.setId(89_021L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setContractorAllocationId(451L);

        when(orderRepository.findByIdForCounterUpdate(8_902L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(8_902L)).thenReturn(List.of(link));

        assertTrue(service.refreshLinkedOrderAmount(8_902L));

        assertEquals(PaymentLinkStatus.EXPIRED, link.getStatus());
        assertTrue(link.getLastError().contains("2400 руб., стало 2500 руб."));
        verify(taskReceiptIntegrationService)
                .release(link, "Сумма заказа изменилась; старый резерв освобожден");
        verify(contractorPaymentLiveRoutingService).releaseClosedPaymentLink(link);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void payableChangeQuarantinesOrdinaryLinkWithPaymentEvidence() {
        PaymentLinkService service = service(properties());
        Order order = order(8_903L, "ООО Счёт со следами оплаты", BigDecimal.valueOf(2_500));
        PaymentLink link = payableLink(order, "started-ordinary-link", 240_000L);
        link.setId(89_031L);
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setTbankPaymentId("payment-89031");

        when(orderRepository.findByIdForCounterUpdate(8_903L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(8_903L)).thenReturn(List.of(link));

        assertTrue(service.refreshLinkedOrderAmount(8_903L));

        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertTrue(link.getLastError().contains("есть признаки платежного действия"));
        verify(paymentLinkRepository).save(link);
        verify(taskReceiptIntegrationService, never()).release(any(PaymentLink.class), anyString());
        verify(contractorPaymentLiveRoutingService, never()).releaseClosedPaymentLink(any());
    }

    @Test
    void payableChangeNeverMutatesPaidOrdinaryLink() {
        PaymentLinkService service = service(properties());
        Order order = order(8_904L, "ООО Оплаченный счёт", BigDecimal.valueOf(2_500));
        PaymentLink link = payableLink(order, "paid-ordinary-link", 240_000L);
        link.setId(89_041L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(LocalDateTime.now().minusHours(1));

        when(orderRepository.findByIdForCounterUpdate(8_904L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(8_904L)).thenReturn(List.of(link));

        assertFalse(service.refreshLinkedOrderAmount(8_904L));

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        verify(paymentLinkRepository, never()).save(link);
        verifyNoInteractions(taskReceiptIntegrationService);
        verify(contractorPaymentLiveRoutingService, never()).releaseClosedPaymentLink(any());
    }

    @Test
    void ownerPaperInvoiceDoesNotCreateBankPaymentOrContractorReserve() {
        PaymentLinkService service = service(properties());
        Order order = order(8_901L, "ООО Бумажный счёт", BigDecimal.valueOf(2_000));
        order.setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE);
        when(orderRepository.findByIdForCounterUpdate(8_901L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(8_901L)).thenReturn(List.of());
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            saved.setId(89_011L);
            return saved;
        });

        ManagerPaymentLinkResponse response = service.createForOrder(8_901L);

        ArgumentCaptor<PaymentLink> linkCaptor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(linkCaptor.capture());
        PaymentLink link = linkCaptor.getValue();
        assertEquals(PaymentMethod.OWNER_PAPER_INVOICE, link.getPaymentMethod());
        assertEquals(ManualPaymentSource.OWNER_PAPER_INVOICE, link.getManualSource());
        assertEquals(PaymentLinkStatus.CREATED, link.getStatus());
        assertNull(link.getContractorAllocationId());
        assertNull(link.getTbankPaymentId());
        assertNull(link.getPaymentUrl());
        assertFalse(response.copyText().contains("/pay/"));
        assertTrue(response.instructionText().contains("выставленному счёту"));
        verifyNoInteractions(tbankClient);
        verify(contractorPaymentLiveRoutingService, never()).reserveForPaymentLink(any(PaymentLink.class));
        verify(contractorPaymentShadowService, never()).reserveForPaymentLinkId(anyLong(), any());
        verify(paymentProfileService, never()).selectForManager(any());
    }

    @Test
    void explicitOwnerTbankLinkSurvivesImmediateBackgroundPreparation() {
        PaymentLinkService service = service(properties());
        Order order = order(24_808L, "Вита-мед", BigDecimal.valueOf(2_000));
        order.setInvoicePaymentMode(InvoicePaymentMode.OWNER_TBANK);
        PaymentLink tbank = payableLink(order, "explicit-owner-tbank", 200_000L);
        tbank.setId(7_258L);
        tbank.setReservedAmountKopecks(200_000L);
        tbank.setPaymentMethod(PaymentMethod.BANK_FORM);
        tbank.setContractorAllocationId(987L);

        when(orderRepository.findByIdForCounterUpdate(24_808L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(24_808L)).thenReturn(List.of(tbank));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(24_808L), anyCollection(), any(LocalDateTime.class)
        )).thenReturn(Optional.of(tbank));
        when(contractorPaymentLiveRoutingService.frozenPaymentLinkAction(7_258L, 987L))
                .thenReturn(ContractorPaymentLiveRoutingService.FrozenPaymentLinkAction.KEEP);

        ManagerPaymentLinkResponse response = service.createForOrder(24_808L);

        assertEquals("explicit-owner-tbank", response.token());
        assertEquals(PaymentMethod.BANK_FORM.name(), response.paymentMethod());
        assertEquals(PaymentLinkStatus.CREATED, tbank.getStatus());
        verify(paymentProfileService, never()).selectForManager(any());
        verify(contractorPaymentLiveRoutingService, never()).reserveForPaymentLink(any());
        verify(contractorPaymentLiveRoutingService, never()).reserveContractorForPaymentLink(any());
        verify(contractorPaymentLiveRoutingService, never()).reserveOwnerForPaymentLink(any());
        verify(paymentLinkRepository, never()).save(tbank);
    }

    @Test
    void explicitEmployeeTaskRequisitesSurviveImmediateBackgroundPreparation() {
        PaymentLinkService service = service(properties());
        Order order = order(24_810L, "Вита-мед", BigDecimal.valueOf(2_000));
        order.setInvoicePaymentMode(InvoicePaymentMode.EMPLOYEE_REQUISITES);
        PaymentLink manual = payableLink(order, "explicit-employee-requisites", 200_000L);
        manual.setId(7_260L);
        manual.setReservedAmountKopecks(200_000L);
        manual.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        manual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        manual.setManualSource(ManualPaymentSource.MANUAL_TASK);
        manual.setManualPaymentType(ManualPaymentType.MOBILE_BANK);

        when(orderRepository.findByIdForCounterUpdate(24_810L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(24_810L)).thenReturn(List.of(manual));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(24_810L), anyCollection(), any(LocalDateTime.class)
        )).thenReturn(Optional.of(manual));

        ManagerPaymentLinkResponse response = service.createForOrder(24_810L);

        assertEquals("explicit-employee-requisites", response.token());
        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK.name(), response.paymentMethod());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, manual.getStatus());
        verify(paymentProfileService, never()).selectForManager(any());
        verify(contractorPaymentLiveRoutingService, never()).reserveForPaymentLink(any());
        verify(contractorPaymentLiveRoutingService, never()).reserveOwnerForPaymentLink(any());
        verify(paymentLinkRepository, never()).save(manual);
    }

    @Test
    void explicitOwnerTbankModeCreatesOnlyOwnerAcquiringRoute() {
        PaymentLinkService service = service(properties());
        Order order = order(24_809L, "Вита-мед", BigDecimal.valueOf(2_000));
        order.setInvoicePaymentMode(InvoicePaymentMode.OWNER_TBANK);
        ContractorPaymentAllocation owner = new ContractorPaymentAllocation();
        owner.setId(988L);
        owner.setRecipientType(ContractorRecipientType.OWNER);

        when(orderRepository.findByIdForCounterUpdate(24_809L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(24_809L)).thenReturn(List.of());
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(24_809L), anyCollection(), any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(7_259L);
            }
            return saved;
        });
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(true);
        when(contractorPaymentLiveRoutingService.reserveOwnerForPaymentLink(any(PaymentLink.class)))
                .thenReturn(owner);

        ManagerPaymentLinkResponse response = service.createForOrder(24_809L);

        assertEquals(PaymentMethod.BANK_FORM.name(), response.paymentMethod());
        ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(saved.capture());
        PaymentLink link = saved.getAllValues().getLast();
        assertEquals(988L, link.getContractorAllocationId());
        assertEquals(PaymentMethod.BANK_FORM, link.getPaymentMethod());
        assertNull(link.getManualSource());
        verify(contractorPaymentLiveRoutingService).reserveOwnerForPaymentLink(link);
        verify(contractorPaymentLiveRoutingService, never()).reserveForPaymentLink(any());
        verify(contractorPaymentLiveRoutingService, never()).reserveContractorForPaymentLink(any());
    }

    @Test
    void paymentRouteTargetsMapToDurableOrderModes() {
        PaymentLinkService service = service(properties());

        assertEquals(
                InvoicePaymentMode.EMPLOYEE_REQUISITES,
                ReflectionTestUtils.invokeMethod(
                        service,
                        "paymentModeForTarget",
                        PaymentRouteChangeTarget.EMPLOYEE_REQUISITES
                )
        );
        assertEquals(
                InvoicePaymentMode.OWNER_TBANK,
                ReflectionTestUtils.invokeMethod(
                        service,
                        "paymentModeForTarget",
                        PaymentRouteChangeTarget.OWNER_TBANK
                )
        );
    }

    @Test
    void shadowReservationFailureDoesNotEscapeAfterCommitCallback() {
        PaymentLinkService service = service(properties());
        PaymentLink link = payableLink(null, "shadow-callback", 10_000L);
        link.setId(8_901L);
        link.setShadowRouteGeneration("generation");
        doThrow(new IllegalStateException("test shadow failure"))
                .when(contractorPaymentShadowService)
                .reserveForPaymentLinkId(8_901L, "generation");

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            ManagerPaymentLinkResponse response = ReflectionTestUtils.invokeMethod(
                    service,
                    "toManagerResponseWithShadowRoute",
                    link
            );
            assertNotNull(response);
            verify(contractorPaymentShadowService, never())
                    .reserveForPaymentLinkId(anyLong(), any());
            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            assertDoesNotThrow(synchronizations.getFirst()::afterCommit);

            verify(contractorPaymentShadowService)
                    .reserveForPaymentLinkId(8_901L, "generation");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void liveContractorRouteDoesNotCreateExtraShadowReservation() {
        PaymentLinkService service = service(properties());
        PaymentLink link = payableLink(null, "live-route", 10_000L);
        link.setId(8_902L);
        link.setShadowRouteGeneration("shadow-generation");
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setContractorAllocationId(8_903L);

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            ManagerPaymentLinkResponse response = ReflectionTestUtils.invokeMethod(
                    service,
                    "toManagerResponseWithShadowRoute",
                    link
            );

            assertNotNull(response);
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations()
                    .isEmpty());
            verify(contractorPaymentShadowService, never())
                    .reserveForPaymentLinkId(anyLong(), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void paidOrderSchedulerCleanupRunsOnlyAfterPaymentTransactionCompletes() {
        PaymentLinkService service = service(properties());
        Order order = order(409L, "ООО Без дедлока", BigDecimal.valueOf(1000));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    service,
                    "cancelBadReviewAutoBanAfterCommit",
                    order,
                    "Оплата подтверждена"
            );

            verify(paymentInvoiceRetryScheduler, never()).cancelBadReviewAutoBan(any(), anyString());
            verify(paymentInvoiceRetryScheduler, never()).cancelBadReviewAutoBanInNewTransaction(any(), anyString());
            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED
            );

            verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBanInNewTransaction(409L, "Оплата подтверждена");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void createForOrderRollsBackBusinessConflictToPreventOrphanRouteWrites() throws Exception {
        Transactional transactional = PaymentLinkService.class
                .getMethod("createForOrder", Long.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(0, transactional.noRollbackFor().length);
        assertEquals(0, transactional.noRollbackForClassName().length);
    }

    @Test
    void authorizedCreationLocksCurrentOrderBeforeAuthorizationAndSideEffects() throws Exception {
        Transactional transactional = PaymentLinkService.class
                .getMethod("createForOrderAuthorized", Long.class, Authentication.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertEquals(0, transactional.noRollbackFor().length);
        assertEquals(0, transactional.noRollbackForClassName().length);

        PaymentLinkService service = service(properties());
        Order orderBeforeBulkClear = order(410L, "ООО Точный порядок", BigDecimal.valueOf(1000));
        Order reloadedOrder = order(410L, "ООО Точный порядок", BigDecimal.valueOf(1000));
        when(orderRepository.findByIdForCounterUpdate(410L))
                .thenReturn(Optional.of(orderBeforeBulkClear), Optional.of(reloadedOrder));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(410L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(paymentLinkRepository.save(any(PaymentLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createForOrderAuthorized(410L, authentication);

        InOrder ordered = inOrder(
                orderRepository,
                managerAccessService,
                paymentLinkRepository,
                orderPaymentIntegrityService
        );
        ordered.verify(orderRepository).findByIdForCounterUpdate(410L);
        ordered.verify(managerAccessService).requireOrderAccess(410L, authentication);
        ordered.verify(paymentLinkRepository).findExpiredManualLinksForUpdate(
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class)
        );
        ordered.verify(orderRepository).findByIdForCounterUpdate(410L);
        ordered.verify(orderPaymentIntegrityService).assertPaymentCycleAllowed(reloadedOrder);
        ordered.verify(paymentLinkRepository, times(2)).save(any(PaymentLink.class));
        verify(orderRepository, never()).findByIdForMutation(410L);
    }

    @Test
    void authorizedCreationDenialHappensAfterLockAndBeforeEverySideEffect() {
        PaymentLinkService service = service(properties());
        Order order = order(411L, "ООО Чужой заказ", BigDecimal.valueOf(1000));
        ResponseStatusException denied = new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Заказ не найден"
        );
        when(orderRepository.findByIdForCounterUpdate(411L)).thenReturn(Optional.of(order));
        doThrow(denied).when(managerAccessService).requireOrderAccess(411L, authentication);

        ResponseStatusException actual = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrderAuthorized(411L, authentication)
        );

        assertSame(denied, actual);
        InOrder ordered = inOrder(orderRepository, managerAccessService);
        ordered.verify(orderRepository).findByIdForCounterUpdate(411L);
        ordered.verify(managerAccessService).requireOrderAccess(411L, authentication);
        verify(orderRepository, never()).findByIdForMutation(411L);
        verify(runtimeSettingsService, never()).isPaymentLinksEnabled();
        verify(paymentLinkRepository, never()).expireManualLinks(
                anyCollection(),
                anyCollection(),
                any(PaymentLinkStatus.class),
                anyString(),
                any(LocalDateTime.class)
        );
        verify(orderPaymentIntegrityService, never()).assertPaymentCycleAllowed(any(Order.class));
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void scheduledReconciliationRecordsAttemptEvenWhenBankStatusIsUnchanged() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        PaymentLink link = new PaymentLink();
        link.setId(444L);
        Order order = order(444L, "ООО Ротация", BigDecimal.valueOf(100));
        link.setOrder(order);
        link.setAmountKopecks(10000L);
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-444");
        link.setTbankOrderId("order-444");
        link.setTbankTerminalKey("terminal");
        when(paymentLinkRepository.findByIdWithOrder(444L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(444L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(444L)).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-444")))
                .thenReturn(new TbankGetStateResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "NEW",
                        "payment-444",
                        "order-444",
                        10000L
                ));

        boolean changed = service.reconcileBankLink(444L);

        assertFalse(changed);
        assertNotNull(link.getBankReconciliationAttemptedAt());
        InOrder ordered = inOrder(tbankClient, orderRepository, paymentLinkRepository);
        ordered.verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-444"));
        ordered.verify(orderRepository).findByIdForCounterUpdate(444L);
        ordered.verify(paymentLinkRepository).findByIdForUpdate(444L);
    }

    @Test
    void cancelReconciliationRestoresConfirmedStateWithoutReplayingPaymentSideEffects() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(447L, "ООО Восстановление возврата", BigDecimal.valueOf(100));
        PaymentLink link = new PaymentLink();
        link.setId(447L);
        link.setOrder(order);
        link.setToken("reconcile-cancel-447");
        link.setAmountKopecks(10000L);
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setBankCancelNonce("expired-cancel-reservation");
        link.setBankCancelLeaseUntil(LocalDateTime.now().minusMinutes(1));
        link.setBankCancelOriginStatus(PaymentLinkStatus.CONFIRMED);
        link.setBankCancelOriginError("prepaid_waiting_order_completion");
        link.setLastError("bank_cancel_ambiguous: provider timeout");
        link.setTbankPaymentId("payment-447");
        link.setTbankOrderId("order-447");
        link.setTbankTerminalKey("terminal");

        when(paymentLinkRepository.findByIdWithOrder(447L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(447L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(447L)).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-447")))
                .thenReturn(new TbankGetStateResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "CONFIRMED",
                        "payment-447",
                        "order-447",
                        10000L
                ));

        assertTrue(service.reconcileBankLink(447L));

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertNull(link.getBankCancelNonce());
        assertNotNull(link.getBankCancelLeaseUntil());
        assertTrue(link.getBankCancelLeaseUntil().isAfter(LocalDateTime.now().plusHours(23)));
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getBankCancelOriginStatus());
        assertEquals("prepaid_waiting_order_completion", link.getBankCancelOriginError());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(paymentSuccessNotificationDeliveryService, never()).deliverAfterCommit(any());
    }

    @Test
    void confirmedObservationDuringCancelWatchDoesNotExtendDeadline() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(448L, "ООО Окно возврата", BigDecimal.valueOf(100));
        PaymentLink link = cancelWatchLink(448L, order);
        LocalDateTime watchDeadline = LocalDateTime.now().plusHours(12);
        link.setBankCancelLeaseUntil(watchDeadline);

        when(paymentLinkRepository.findByIdWithOrder(448L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(448L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(448L)).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-448")))
                .thenReturn(tbankState("CONFIRMED", "payment-448", "order-448", 10000L));

        assertFalse(service.reconcileBankLink(448L));

        assertEquals(watchDeadline, link.getBankCancelLeaseUntil());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getBankCancelOriginStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
    }

    @Test
    void confirmedObservationAfterCancelWatchClearsContextButPreservesPrepaymentMarker() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(449L, "ООО Завершение окна возврата", BigDecimal.valueOf(100));
        PaymentLink link = cancelWatchLink(449L, order);
        link.setBankCancelLeaseUntil(LocalDateTime.now().minusSeconds(1));

        when(paymentLinkRepository.findByIdWithOrder(449L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(449L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(449L)).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-449")))
                .thenReturn(tbankState("CONFIRMED", "payment-449", "order-449", 10000L));

        assertFalse(service.reconcileBankLink(449L));

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
        assertNull(link.getBankCancelNonce());
        assertNull(link.getBankCancelLeaseUntil());
        assertNull(link.getBankCancelOriginStatus());
        assertNull(link.getBankCancelOriginError());
    }

    @Test
    void scheduledReconciliationSkipsARecentlyAttemptedLockedLink() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(445L);
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setTbankPaymentId("payment-445");
        link.setBankReconciliationAttemptedAt(LocalDateTime.now());
        when(paymentLinkRepository.findByIdWithOrder(445L)).thenReturn(Optional.of(link));

        assertFalse(service.reconcileBankLink(445L));

        verify(tbankClient, never()).getState(any(), anyString());
    }

    @Test
    void scheduledReconciliationDoesNotApplyLateRejectedObservationAfterConfirmation() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(446L, "ООО Поздняя сверка", BigDecimal.valueOf(100));

        PaymentLink snapshot = new PaymentLink();
        snapshot.setId(446L);
        snapshot.setOrder(order);
        snapshot.setToken("reconcile-446");
        snapshot.setAmountKopecks(10000L);
        snapshot.setStatus(PaymentLinkStatus.INITIATED);
        snapshot.setTbankPaymentId("payment-446");
        snapshot.setTbankOrderId("order-446");
        snapshot.setTbankTerminalKey("terminal");

        PaymentLink confirmed = new PaymentLink();
        confirmed.setId(446L);
        confirmed.setOrder(order);
        confirmed.setToken("reconcile-446");
        confirmed.setAmountKopecks(10000L);
        confirmed.setStatus(PaymentLinkStatus.CONFIRMED);
        confirmed.setTbankPaymentId("payment-446");
        confirmed.setTbankOrderId("order-446");
        confirmed.setTbankTerminalKey("terminal");

        when(paymentLinkRepository.findByIdWithOrder(446L)).thenReturn(Optional.of(snapshot));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-446")))
                .thenReturn(new TbankGetStateResponse(
                        false,
                        "1051",
                        "rejected",
                        null,
                        "terminal",
                        "REJECTED",
                        "payment-446",
                        "order-446",
                        10000L
                ));
        when(orderRepository.findByIdForCounterUpdate(446L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(446L)).thenReturn(Optional.of(confirmed));

        assertFalse(service.reconcileBankLink(446L));

        assertEquals(PaymentLinkStatus.CONFIRMED, confirmed.getStatus());
        assertNull(confirmed.getBankReconciliationAttemptedAt());
        InOrder ordered = inOrder(tbankClient, orderRepository, paymentLinkRepository);
        ordered.verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-446"));
        ordered.verify(orderRepository).findByIdForCounterUpdate(446L);
        ordered.verify(paymentLinkRepository).findByIdForUpdate(446L);
        verify(paymentLinkRepository, never()).save(confirmed);
    }

    @Test
    void reconcileActiveLinkExpiresUnstartedLinkWhenOrderAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(70L, "ООО Новая сумма", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(701L);
        link.setOrder(order);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setAmountKopecks(90000L);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(70L),
                        anyCollection(),
                        any(LocalDateTime.class)
                ))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(70L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(701L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findById(701L)).thenReturn(Optional.of(link));

        PaymentLinkService.PaymentLinkReconcileResult result =
                service.reconcileActiveLinkForOrder(70L);

        assertTrue(result.changed());
        assertEquals(PaymentLinkStatus.CREATED, result.statusBefore());
        assertEquals(PaymentLinkStatus.EXPIRED, result.statusAfter());
        assertEquals(PaymentLinkStatus.EXPIRED, link.getStatus());
        assertTrue(link.getLastError().contains("Сумма заказа изменилась"));
        verify(paymentLinkRepository).save(link);
        verify(tbankClient, never()).getState(any(), anyString());
    }

    @Test
    void createForOrderBuildsHiddenTokenizedLinkWithPayableAmount() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(10L, "ООО Ромашка", BigDecimal.valueOf(1000));
        order.getCompany().setLastPayerEmail(" LAST@EXAMPLE.RU ");

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(10L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(10L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(10L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250.50), BigDecimal.ZERO));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(10L);

        assertNotNull(response.token());
        assertFalse(response.token().isBlank());
        assertEquals("https://example.ru/pay/" + response.token(), response.url());
        assertEquals(BigDecimal.valueOf(125050, 2), response.amount());
        assertEquals(125050L, response.amountKopecks());
        assertTrue(response.copyText().contains("Ссылка на оплату: https://example.ru/pay/"));
        assertNull(response.telegramCopyTransferNumber());

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        assertEquals("last@example.ru", captor.getValue().getPayerEmail());
        assertEquals(TbankPaymentProfile.PRIMARY_CODE, captor.getValue().getPaymentProfileCode());
        assertEquals("Основной магазин", captor.getValue().getPaymentProfileName());
    }

    @Test
    void disabledLiveMasterReusesPublishedContractorLinkWithoutChangingSnapshots() {
        PaymentLinkService service = service(properties());
        Order order = order(710L, "ООО Зафиксированный маршрут", BigDecimal.valueOf(1000));
        Filial filial = new Filial();
        filial.setTitle("Сибирская, 10");
        order.setFilial(filial);
        PaymentLink existing = new PaymentLink();
        existing.setId(711L);
        existing.setToken("contractor-frozen-token");
        existing.setOrder(order);
        existing.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        existing.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        existing.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        existing.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        existing.setManualBankName("Зафиксированный банк");
        existing.setManualComment("Зафиксированный комментарий");
        existing.setAmountKopecks(100_000L);
        existing.setReservedAmountKopecks(100_000L);
        existing.setContractorAllocationId(712L);
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(orderRepository.findByIdForCounterUpdate(710L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(710L)).thenReturn(List.of(existing));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(710L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(contractorPaymentLiveRoutingService.frozenPaymentLinkAction(711L, 712L))
                .thenReturn(ContractorPaymentLiveRoutingService.FrozenPaymentLinkAction.KEEP);
        when(contractorPaymentLiveRoutingService.activePaymentLinkRequisites(existing))
                .thenReturn(Optional.of(requisites(
                        712L,
                        "Зафиксированный получатель",
                        "+79990001122",
                        "Зафиксированный банк",
                        "Зафиксированный комментарий"
                )));

        ManagerPaymentLinkResponse response = service.createForOrder(710L);

        assertEquals("contractor-frozen-token", response.token());
        assertTrue(response.instructionText().contains("+79990001122"));
        assertTrue(response.instructionText().contains("Зафиксированный получатель"));
        assertTrue(response.instructionText().contains("Банк: Зафиксированный банк"));
        assertTrue(response.instructionText().contains("Зафиксированный комментарий"));
        assertEquals(
                "ООО Зафиксированный маршрут - Сибирская, 10\n\n"
                        + "Здравствуйте, ваш заказ выполнен. К оплате: 1000 руб.\n\n"
                        + "Оплата по мобильному банку: +79990001122\n"
                        + "Получатель: Зафиксированный получатель\n"
                        + "Банк: Зафиксированный банк\n\n"
                        + "После оплаты отправьте чек в этот чат.",
                response.copyText()
        );
        assertFalse(response.copyText().contains("Зафиксированный комментарий"));
        assertEquals("+79990001122", response.telegramCopyTransferNumber());
        assertEquals(712L, existing.getContractorAllocationId());
        assertNull(existing.getManualPhone());
        assertNull(existing.getManualRecipientName());
        assertEquals("Зафиксированный банк", existing.getManualBankName());
        verify(contractorPaymentLiveRoutingService, never()).enabledForNewRoutes();
        verify(contractorPaymentLiveRoutingService, never()).reserveForPaymentLink(any());
        verify(paymentProfileService, never()).selectForManager(any());
        verify(paymentLinkRepository, never()).save(existing);
    }

    @Test
    void replacementLinkReleasesExpiredLivePredecessorBeforeNewReservation() {
        PaymentLinkService service = service(properties());
        Order order = order(720L, "ООО Повторный счет", BigDecimal.valueOf(1000));
        PaymentLink expired = new PaymentLink();
        expired.setId(721L);
        expired.setOrder(order);
        expired.setStatus(PaymentLinkStatus.EXPIRED);
        expired.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        expired.setContractorAllocationId(722L);
        expired.setAmountKopecks(100_000L);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        ContractorPaymentAllocation ownerFallback = new ContractorPaymentAllocation();
        ownerFallback.setId(723L);
        ownerFallback.setRecipientType(ContractorRecipientType.OWNER);
        when(orderRepository.findByIdForCounterUpdate(720L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(720L)).thenReturn(List.of(expired));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(720L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(true);
        when(contractorPaymentLiveRoutingService.reserveForPaymentLink(any(PaymentLink.class)))
                .thenReturn(ownerFallback);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(724L);
            }
            return saved;
        });

        service.createForOrder(720L);

        InOrder routingOrder = inOrder(contractorPaymentLiveRoutingService);
        routingOrder.verify(contractorPaymentLiveRoutingService).releaseClosedPaymentLink(expired);
        routingOrder.verify(contractorPaymentLiveRoutingService).enabledForNewRoutes();
        routingOrder.verify(contractorPaymentLiveRoutingService).reserveForPaymentLink(any(PaymentLink.class));
        ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(saved.capture());
        assertEquals(723L, saved.getAllValues().getLast().getContractorAllocationId());
    }

    @Test
    void blockedLiveRoutingFailsClosedInsteadOfBankLink() {
        PaymentLinkService service = service(properties());
        Order order = order(725L, "ООО Заблокированный LIVE", BigDecimal.valueOf(1000));
        PaymentProfile manualProfile = profile(6L, "manual", "Ручной профиль", "terminal");
        manualProfile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        manualProfile.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        manualProfile.setManualPhone("+79990001122");
        manualProfile.setManualRecipientName("Старый получатель");
        manualProfile.setManualMonthlyHardLimitKopecks(200_000L);

        when(orderRepository.findByIdForCounterUpdate(725L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(725L)).thenReturn(List.of());
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(725L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(paymentProfileService.selectForManager(any())).thenReturn(manualProfile);
        when(paymentProfileService.lockForRouting(manualProfile)).thenReturn(manualProfile);
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(false);
        when(contractorPaymentLiveRoutingService.configuredButBlockedForNewRoutes()).thenReturn(true);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(726L);
            }
            return saved;
        });

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(725L)
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertTrue(failure.getReason().contains("маршрут получателя не зафиксирован"));
        ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(saved.capture());
        PaymentLink link = saved.getValue();
        assertEquals(PaymentLinkStatus.FAILED, link.getStatus());
        assertTrue(link.getLastError().contains("configured_but_blocked"));
        verify(paymentIssueReminderService).notifyOrderIssue(
                eq(725L),
                eq("PAYMENT_LIVE_ROUTING_FAIL_CLOSED"),
                eq(725L),
                contains("Платёж требует внимания"),
                contains("configured_but_blocked")
        );
        verify(contractorPaymentLiveRoutingService, never()).reserveForPaymentLink(any());
    }

    @Test
    void enabledLiveRoutingFailsClosedWhenAllocationIsNotCreated() {
        PaymentLinkService service = service(properties());
        Order order = order(729L, "ООО LIVE без allocation", BigDecimal.valueOf(1000));
        PaymentProfile profile = profile(9L, "primary", "Основной магазин", "terminal");

        when(orderRepository.findByIdForCounterUpdate(729L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(729L)).thenReturn(List.of());
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(729L), anyCollection(), any(LocalDateTime.class))).thenReturn(Optional.empty());
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(true);
        when(contractorPaymentLiveRoutingService.reserveForPaymentLink(any(PaymentLink.class))).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(730L);
            }
            return saved;
        });

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(729L)
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertTrue(failure.getReason().contains("маршрут получателя не зафиксирован"));
        ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(saved.capture());
        PaymentLink link = saved.getValue();
        assertEquals(PaymentLinkStatus.FAILED, link.getStatus());
        assertTrue(link.getLastError().contains("reserve_returned_null"));
        verify(paymentIssueReminderService).notifyOrderIssue(
                eq(729L),
                eq("PAYMENT_LIVE_ROUTING_FAIL_CLOSED"),
                eq(729L),
                contains("Платёж требует внимания"),
                contains("reserve_returned_null")
        );
        verify(contractorPaymentLiveRoutingService).reserveForPaymentLink(link);
    }
    @Test
    void liveOwnerAllocationUsesTbankAcquiringInsteadOfLegacyManualProfile() {
        PaymentLinkService service = service(properties());
        Order order = order(726L, "ООО Владелец через эквайринг", BigDecimal.valueOf(1000));
        PaymentProfile manualProfile = profile(8L, "owner-manual", "Ручной профиль владельца", "owner-terminal");
        manualProfile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        manualProfile.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        manualProfile.setManualPhone("+79990001122");
        manualProfile.setManualRecipientName("Старый владелец");
        manualProfile.setManualMonthlyHardLimitKopecks(500_000L);
        ContractorPaymentAllocation ownerAllocation = new ContractorPaymentAllocation();
        ownerAllocation.setId(727L);
        ownerAllocation.setRecipientType(ContractorRecipientType.OWNER);

        when(orderRepository.findByIdForMutation(726L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(726L), anyCollection(), any(LocalDateTime.class))).thenReturn(Optional.empty());
        when(paymentProfileService.selectForManager(any())).thenReturn(manualProfile);
        when(paymentProfileService.lockForRouting(manualProfile)).thenReturn(manualProfile);
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(true);
        when(contractorPaymentLiveRoutingService.reserveForPaymentLink(any(PaymentLink.class)))
                .thenReturn(ownerAllocation);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(728L);
            }
            return saved;
        });

        ManagerPaymentLinkResponse response = service.createForOrder(726L);

        ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(saved.capture());
        PaymentLink link = saved.getAllValues().getLast();
        assertEquals(PaymentMethod.BANK_FORM, link.getPaymentMethod());
        assertEquals(PaymentLinkStatus.CREATED, link.getStatus());
        assertEquals(727L, link.getContractorAllocationId());
        assertNull(link.getManualSource());
        assertNull(link.getManualPaymentType());
        assertNull(link.getManualPhone());
        assertTrue(response.copyText().contains("Ссылка на оплату: https://example.ru/pay/"));
        assertNull(response.telegramCopyTransferNumber());
    }

    @ParameterizedTest
    @EnumSource(value = ManualPaymentSource.class, names = {
            "CONTRACTOR_PAYMENT_PROFILE", "PROFILE_MONTHLY_LIMIT"
    })
    void unpaidReleasedReusableLinkIsRetiredAndRoutedAsNewAttempt(
            ManualPaymentSource oldSource
    ) {
        PaymentLinkService service = service(properties());
        Order order = order(730L, "ООО Новый получатель", BigDecimal.valueOf(1000));
        PaymentLink old = new PaymentLink();
        old.setId(731L);
        old.setToken("old-recipient-token");
        old.setOrder(order);
        old.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        old.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        old.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        old.setManualSource(oldSource);
        old.setManualPhone("+70000000001");
        old.setManualRecipientName("Старый получатель");
        old.setAmountKopecks(100_000L);
        old.setReservedAmountKopecks(100_000L);
        old.setContractorAllocationId(732L);
        old.setExpiresAt(LocalDateTime.now().plusDays(1));

        ContractorPaymentAllocation next = new ContractorPaymentAllocation();
        next.setId(733L);
        next.setRecipientType(ContractorRecipientType.SPECIALIST);
        next.setRecipientNameSnapshot("Новый получатель");
        next.setPaymentPhoneSnapshot("+79990007766");
        next.setBankNameSnapshot("Новый банк");
        next.setPaymentCommentSnapshot("Новая попытка");

        when(orderRepository.findByIdForCounterUpdate(730L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(730L)).thenReturn(List.of(old));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(730L), anyCollection(), any(LocalDateTime.class)
        )).thenReturn(Optional.of(old));
        when(contractorPaymentLiveRoutingService.frozenPaymentLinkAction(731L, 732L))
                .thenReturn(ContractorPaymentLiveRoutingService.FrozenPaymentLinkAction.START_NEW_ATTEMPT);
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(true);
        when(contractorPaymentLiveRoutingService.reserveForPaymentLink(any(PaymentLink.class)))
                .thenReturn(next);
        when(contractorPaymentLiveRoutingService.activePaymentLinkRequisites(any(PaymentLink.class)))
                .thenReturn(Optional.of(requisites(
                        733L,
                        "Новый получатель",
                        "+79990007766",
                        "Новый банк",
                        "Новая попытка"
                )));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(734L);
            }
            return saved;
        });

        ManagerPaymentLinkResponse response = service.createForOrder(730L);

        assertEquals(PaymentLinkStatus.EXPIRED, old.getStatus());
        assertNotEquals("old-recipient-token", response.token());
        assertTrue(response.instructionText().contains("Новый получатель"));
        assertTrue(response.instructionText().contains("+79990007766"));
        InOrder routeOrder = inOrder(contractorPaymentLiveRoutingService);
        routeOrder.verify(contractorPaymentLiveRoutingService).frozenPaymentLinkAction(731L, 732L);
        routeOrder.verify(contractorPaymentLiveRoutingService).releaseClosedPaymentLink(old);
        routeOrder.verify(contractorPaymentLiveRoutingService).reserveForPaymentLink(any(PaymentLink.class));
        ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(3)).save(saved.capture());
        PaymentLink replacement = saved.getAllValues().getLast();
        assertEquals(733L, replacement.getContractorAllocationId());
        assertEquals(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE, replacement.getManualSource());
        assertNull(replacement.getManualPhone());
        assertNull(replacement.getManualRecipientName());
        assertNull(replacement.getManualComment());
        assertEquals("Новый банк", replacement.getManualBankName());
        assertTrue(response.instructionText().contains("Новая попытка"));
    }

    @Test
    void createForOrderBlocksStandaloneRouteWhenOrderIsAlreadyInActiveCommonInvoice() {
        CommonBillingService commonBillingService = org.mockito.Mockito.mock(CommonBillingService.class);
        PaymentLinkService service = service(properties(), new TbankTokenSigner(), commonBillingService);
        Order order = order(901L, "ООО Общий счет", BigDecimal.valueOf(1000));
        when(orderRepository.findByIdForMutation(901L)).thenReturn(Optional.of(order));
        when(commonBillingService.isOrderInActiveCommonInvoice(901L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(901L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("активный общий счет"));
        verify(paymentLinkRepository, never()).expireManualLinks(
                anyCollection(),
                anyCollection(),
                any(PaymentLinkStatus.class),
                anyString(),
                any(LocalDateTime.class)
        );
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void publicInitBlocksLegacyStandaloneTokenAfterOrderMovesToCommonInvoice() {
        CommonBillingService commonBillingService = org.mockito.Mockito.mock(CommonBillingService.class);
        PaymentLinkService service = service(properties(), new TbankTokenSigner(), commonBillingService);
        Order order = order(903L, "ООО Общий счет", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(9030L);
        link.setToken("legacy-public-token");
        link.setOrder(order);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setAmountKopecks(100_000L);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("legacy-public-token")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(903L)).thenReturn(Optional.of(order));
        when(commonBillingService.isOrderInActiveCommonInvoice(903L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "legacy-public-token",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.9",
                        "JUnit UA"
                )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("активный общий счет"));
        verify(paymentLinkRepository, never()).findByTokenForUpdate("legacy-public-token");
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void createForOrderBlocksNewRouteAfterVerifiedManualNonPaymentUntilCommonInvoiceAttach() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(902L, "ООО Перенос в общий счет", BigDecimal.valueOf(1000));
        OrderStatus originalOrderStatus = new OrderStatus();
        originalOrderStatus.setTitle("Ожидает оплаты");
        order.setStatus(originalOrderStatus);

        PaymentLink closedManualLink = new PaymentLink();
        closedManualLink.setId(9020L);
        closedManualLink.setOrder(order);
        closedManualLink.setStatus(PaymentLinkStatus.CANCELED);
        closedManualLink.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        closedManualLink.setLastError(
                "manual_payment_absent_verified: перевод не поступил; checked_by=owner; note=выписка проверена"
        );

        when(orderRepository.findByIdForCounterUpdate(902L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(902L)).thenReturn(List.of(closedManualLink));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(902L)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("перенос заказа в общий счет"));
        assertEquals(PaymentLinkStatus.CANCELED, closedManualLink.getStatus());
        assertNull(closedManualLink.getPaidAt());
        assertNull(closedManualLink.getManualConfirmedAt());
        assertSame(originalOrderStatus, order.getStatus());
        verify(manualPaymentTaskService, never()).findRoutableTask(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                any()
        );
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void createForOrderSelectsSecondaryProfileForConfiguredManager() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile secondaryProfile = profile(2L, TbankPaymentProfile.SECONDARY_CODE, "Второй магазин", "secondary-terminal");
        when(paymentProfileService.selectForManager(any())).thenReturn(secondaryProfile);
        Order order = order(11L, "ООО Второй", BigDecimal.valueOf(500));
        order.setManager(manager("second-manager"));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(11L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(11L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(11L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createForOrder(11L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        assertEquals(TbankPaymentProfile.SECONDARY_CODE, captor.getValue().getPaymentProfileCode());
        assertEquals("Второй магазин", captor.getValue().getPaymentProfileName());
    }

    @Test
    void createForOrderRoutesToManualPaymentWhenPolicyAndMonthlyLimitAllowIt() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("+79990000000");
        profile.setManualRecipientName("Иван И.");
        profile.setManualComment("Оплата заказа №{orderId}");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(3L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(40000L);
        Order order = order(12L, "ООО Ручная", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(12L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(12L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(12L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(12L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK, link.getPaymentMethod());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, link.getStatus());
        assertEquals(50000L, link.getReservedAmountKopecks());
        assertEquals("+79990000000", link.getManualPhone());
        assertEquals("Иван И.", link.getManualRecipientName());
        assertEquals("Оплата заказа №12", link.getManualComment());
        assertEquals(PaymentReceiptStatus.PENDING, link.getReceiptStatus());
        assertEquals("MANUAL_MOBILE_BANK", response.paymentMethod());
        assertTrue(response.instructionText().contains("Оплата по мобильному банку: +79990000000"));
        assertTrue(response.instructionText().contains("Получатель: Иван И."));
        assertFalse(response.instructionText().contains("Банк:"));
        assertTrue(response.instructionText().contains("Комментарий: Оплата заказа №12"));
        assertFalse(response.copyText().contains("https://example.ru/pay/"));
        assertTrue(response.copyText().contains("После оплаты отправьте чек в этот чат."));
        assertEquals("+79990000000", response.telegramCopyTransferNumber());
    }

    @Test
    void createForOrderRejectsSettledOrderBeforeCreatingAnyLink() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(101L, "Уже оплачено", BigDecimal.valueOf(1250));
        when(orderRepository.findByIdForMutation(101L)).thenReturn(Optional.of(order));
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Заказ уже полностью оплачен"
        )).when(orderPaymentIntegrityService).assertPaymentCycleAllowed(order);

        assertThrows(ResponseStatusException.class, () -> service.createForOrder(101L));

        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
        verify(paymentProfileService, never()).selectForManager(any());
    }

    @Test
    void createForOrderLabelsLongManualNumberAsCardPayment() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(33L, "manual-card", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("2202201901120051");
        profile.setManualRecipientName("Мария Олеговна Р.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(33L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        Order order = order(33L, "ООО Карта", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(33L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(33L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(33L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(33L);

        assertTrue(response.instructionText().contains("Оплата по мобильному банку: 2202201901120051"));
        assertTrue(response.instructionText().contains("Получатель: Мария Олеговна Р."));
        assertEquals("2202201901120051", response.telegramCopyTransferNumber());
    }

    @Test
    void createForOrderKeepsFifteenDigitTransferNumberLabeledAsMobileBank() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(34L, "manual-phone-long", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("123 456 789-012-345");
        profile.setManualRecipientName("Анна П.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(34L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        Order order = order(34L, "ООО Телефон", BigDecimal.valueOf(500));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(34L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(34L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(34L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(34L);

        assertTrue(response.instructionText().contains(
                "Оплата по мобильному банку: 123 456 789-012-345"
        ));
        assertFalse(response.instructionText().contains("Оплата по номеру карты:"));
    }

    @Test
    void createForOrderRoutesToExternalManualPaymentWhenProfileUsesPaymentLink() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(4L, "manual-link", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR");
        profile.setManualPaymentButtonLabel("Оплатить через Альфа-Банк");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(4L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        Order order = order(17L, "ООО Альфа", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(17L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(17L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(17L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(17L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_EXTERNAL_LINK, link.getPaymentMethod());
        assertEquals(ManualPaymentType.EXTERNAL_LINK, link.getManualPaymentType());
        assertEquals("https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR", link.getManualPaymentUrl());
        assertEquals("Оплатить через Альфа-Банк", link.getManualPaymentButtonLabel());
        assertEquals("Сивохин И.И.", link.getManualRecipientName());
        assertEquals("MANUAL_EXTERNAL_LINK", response.paymentMethod());
        assertNull(response.telegramCopyTransferNumber());
        assertTrue(response.instructionText().contains("Ссылка на оплату: https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR"));
        assertTrue(response.instructionText().contains("Получатель: Сивохин И.И."));
        assertFalse(response.instructionText().contains("Банк:"));
        assertFalse(response.instructionText().contains("Получатель: Оплатить через Альфа-Банк"));
        assertNull(link.getManualComment());
        assertFalse(response.instructionText().contains("Комментарий:"));
    }

    @Test
    void createForOrderUsesEditablePaymentLinkCopyTextTemplate() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(5L, "manual-template", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("https://pay.example/link");
        profile.setManualRecipientName("Получатель П.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(5L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        when(appSettingService.getString(
                eq(AppSettingService.CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT),
                anyString()
        )).thenReturn("{companyAndFilial}\n\nИтого {sum}\n\n{paymentInstruction}\n\nФинал: {paymentAfterword}");
        Order order = order(24L, "ООО Шаблон", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(24L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(24L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(24L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(24L);

        assertEquals(
                "ООО Шаблон\n\nИтого 500\n\nСсылка на оплату: https://pay.example/link\nПолучатель: Получатель П.\n\nФинал: После оплаты отправьте чек в этот чат.",
                response.copyText()
        );
    }

    @Test
    void createForOrderFallsBackToTbankWhenManualMonthlyLimitIsExceeded() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("+79990000000");
        profile.setManualRecipientName("Иван И.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(3L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(80000L);
        Order order = order(13L, "ООО Лимит", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(13L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(13L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(13L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(13L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.BANK_FORM, link.getPaymentMethod());
        assertEquals(PaymentLinkStatus.CREATED, link.getStatus());
        assertEquals("BANK_FORM", response.paymentMethod());
        assertNull(response.telegramCopyTransferNumber());
    }

    @Test
    void createForOrderRoutesToManualTaskBeforeProfileMonthlyLimit() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.T_BANK_ONLY);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);

        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(77L);
        task.setPaymentProfile(profile);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setManualPhone("+79001234567");
        task.setManualRecipientName("Петр П.");
        task.setComment("Задание на оплату №{orderId}");
        task.setTargetAmountKopecks(100000L);
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        ManualPaymentTaskRouteSnapshot snapshot = new ManualPaymentTaskRouteSnapshot(
                77L,
                2L,
                new com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef(
                        com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        160L,
                        "source-160"),
                "TASK:77:2",
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER,
                null,
                "Владелец",
                ManualPaymentType.MOBILE_BANK,
                "+79001234567",
                "Петр П.",
                null,
                null,
                50_000L,
                null,
                ""
        );
        when(manualPaymentTaskRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(task));

        Order order = order(16L, "ООО Задание", BigDecimal.valueOf(500));
        Manager taskManager = manager("manager-task");
        taskManager.setId(31L);
        order.setManager(taskManager);
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(16L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(16L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(16L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(160L);
            return saved;
        });
        when(taskReceiptIntegrationService.reserveForPaymentLink(
                any(PaymentLink.class), eq(31L), eq(3L))).thenReturn(Optional.of(snapshot));

        ManagerPaymentLinkResponse response = service.createForOrder(16L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK, link.getPaymentMethod());
        assertEquals(ManualPaymentSource.MANUAL_TASK, link.getManualSource());
        assertSame(task, link.getManualPaymentTask());
        assertEquals("+79001234567", link.getManualPhone());
        assertEquals("Петр П.", link.getManualRecipientName());
        assertEquals("Задание на оплату №16", link.getManualComment());
        assertEquals("MANUAL_MOBILE_BANK", response.paymentMethod());
        assertTrue(response.instructionText().contains("Оплата по мобильному банку: +79001234567"));
        assertFalse(response.instructionText().contains("Ссылка на оплату:"));
        assertEquals(
                "ООО Задание\n\n"
                        + "Здравствуйте, ваш заказ выполнен. К оплате: 500 руб.\n\n"
                        + "Оплата по мобильному банку: +79001234567\n"
                        + "Получатель: Петр П.\n\n"
                        + "После оплаты отправьте чек в этот чат.",
                response.copyText()
        );
        assertTrue(response.copyText().startsWith("ООО Задание\n\n"));
        assertEquals("+79001234567", response.telegramCopyTransferNumber());
        assertEquals("source-160", link.getManualTaskSourceGeneration());
        assertEquals(2L, link.getManualTaskGeneration());
        verify(taskReceiptIntegrationService).reserveForPaymentLink(link, 31L, 3L);
        verify(manualPaymentTaskService, never()).findRoutableTask(any(), any(), anyLong(), any());
        verify(paymentLinkRepository, never()).sumManualReservedAndConfirmedForPeriod(
                any(),
                any(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        );
    }

    @Test
    void createForOrderPublishesExternalManualTaskAsMobileBankTransfer() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.T_BANK_ONLY);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);

        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(78L);
        task.setPaymentProfile(profile);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        task.setManualPhone("2202208238396676");
        task.setManualRecipientName("Получатель задания");
        task.setManualPaymentUrl("https://pay.alfabank.ru/sc/legacy-task");
        task.setManualPaymentButtonLabel("Оплатить по старой ссылке");
        task.setComment("Платёжное задание №{orderId}");
        task.setTargetAmountKopecks(100000L);
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        ManualPaymentTaskRouteSnapshot snapshot = new ManualPaymentTaskRouteSnapshot(
                78L,
                4L,
                new com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef(
                        com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        161L,
                        "source-161"),
                "TASK:78:4",
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER,
                null,
                "Владелец",
                ManualPaymentType.EXTERNAL_LINK,
                "2202208238396676",
                "Получатель задания",
                "https://pay.alfabank.ru/sc/legacy-task",
                "Оплатить по старой ссылке",
                50_000L,
                null,
                ""
        );
        when(manualPaymentTaskRepository.findByIdForUpdate(78L)).thenReturn(Optional.of(task));

        Order order = order(161L, "ООО Старое задание", BigDecimal.valueOf(500));
        Manager taskManager = manager("manager-task-external");
        taskManager.setId(32L);
        order.setManager(taskManager);
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(161L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(161L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(161L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(161L);
            return saved;
        });
        when(taskReceiptIntegrationService.reserveForPaymentLink(
                any(PaymentLink.class), eq(32L), eq(3L))).thenReturn(Optional.of(snapshot));

        ManagerPaymentLinkResponse response = service.createForOrder(161L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK, link.getPaymentMethod());
        assertEquals(ManualPaymentType.MOBILE_BANK, link.getManualPaymentType());
        assertEquals(ManualPaymentSource.MANUAL_TASK, link.getManualSource());
        assertNull(link.getManualPaymentUrl());
        assertNull(link.getManualPaymentButtonLabel());
        assertTrue(response.instructionText().contains("Оплата по мобильному банку: 2202208238396676"));
        assertTrue(response.instructionText().contains("Получатель: Получатель задания"));
        assertFalse(response.instructionText().contains("Ссылка на оплату:"));
        assertEquals("2202208238396676", response.telegramCopyTransferNumber());
    }
    @Test
    void createForOrderDoesNotReserveTaskWhenTypedAccountingIsDisabled() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(161L, "ООО Без task route", BigDecimal.valueOf(500));
        Manager manager = manager("manager-task-disabled");
        manager.setId(32L);
        order.setManager(manager);
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(false);
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(161L), anyCollection(), any(LocalDateTime.class))).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(161L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(161L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(161L);
            return saved;
        });

        service.createForOrder(161L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        PaymentLink link = captor.getAllValues().getLast();
        assertNotEquals(ManualPaymentSource.MANUAL_TASK, link.getManualSource());
        assertNull(link.getManualPaymentTask());
        assertNull(link.getManualTaskSourceGeneration());
        assertNull(link.getManualTaskGeneration());
        verifyNoInteractions(taskReceiptIntegrationService);
        verify(manualPaymentTaskService, never()).findRoutableTask(any(), any(), anyLong(), any());
    }

    @Test
    void paymentInstructionCreationRollsBackEveryWriteOnUnsafeTaskRouteFailure() throws Exception {
        for (var method : List.of(
                PaymentLinkService.class.getMethod("createForOrder", Long.class),
                PaymentLinkService.class.getMethod(
                        "createForOrderAuthorized", Long.class, Authentication.class),
                PaymentLinkService.class.getMethod(
                        "prepareForOrderAuthorized", Long.class, Authentication.class))) {
            org.springframework.transaction.annotation.Transactional transaction =
                    method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertNotNull(transaction);
            assertEquals(0, transaction.noRollbackFor().length,
                    method.getName() + " must roll back an unsafe task route");
            assertEquals(0, transaction.noRollbackForClassName().length,
                    method.getName() + " must not whitelist business exceptions");
        }
    }

    @Test
    void mobileTaskRouteNeverValidatesOrPublishesQuarantinedExternalUrl() {
        PaymentLinkService service = service(properties());
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(7_701L);
        task.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        task.setManualPhone("+79990001122");
        task.setManualRecipientName("Получатель мобильного перевода");
        task.setManualPaymentUrl("javascript:alert(document.cookie)");
        task.setManualPaymentButtonLabel("Перевести по номеру");
        when(manualPaymentTaskRepository.findByIdForUpdate(7_701L))
                .thenReturn(Optional.of(task));
        ManualPaymentTaskRouteSnapshot snapshot = new ManualPaymentTaskRouteSnapshot(
                7_701L,
                3L,
                new com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef(
                        com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        8_801L,
                        "task-source-generation"),
                "TASK:7701:3",
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER,
                null,
                "Владелец",
                ManualPaymentType.MOBILE_BANK,
                "+79990001122",
                "Получатель мобильного перевода",
                "javascript:alert(document.cookie)",
                "Перевести по номеру",
                50_000L,
                null,
                ""
        );
        PaymentLink link = new PaymentLink();
        link.setId(8_801L);
        link.setOrder(order(8_801L, "ООО Мобильный маршрут", BigDecimal.valueOf(500)));

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                service, "applyManualTaskPayment", link, snapshot));

        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK, link.getPaymentMethod());
        assertEquals(ManualPaymentSource.MANUAL_TASK, link.getManualSource());
        assertEquals("+79990001122", link.getManualPhone());
        assertNull(link.getManualPaymentUrl());
        assertEquals("task-source-generation", link.getManualTaskSourceGeneration());
    }

    @Test
    void commonInvoiceOwnerAcquiringRouteIgnoresManagerTextAndLegacyManualProfile() {
        PaymentLinkService service = service(properties());
        Manager manager = manager("manager-owner-common");
        PaymentProfile profile = profile(9L, "owner-common", "Ручной профиль владельца", "owner-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        profile.setManualPhone("+79990001122");
        profile.setManualRecipientName("Старый владелец");
        profile.setManualMonthlyHardLimitKopecks(500_000L);
        lenient().when(runtimeSettingsService.paymentInstructionSource())
                .thenReturn(TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT);
        when(paymentProfileService.selectForManager(manager)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(new TbankPaymentProfile(
                9L,
                "owner-common",
                "Ручной профиль владельца",
                true,
                "owner-terminal",
                "password",
                false
        ));

        var route = service.selectCommonInvoiceOwnerAcquiringRoute(manager, 230_000L);

        assertEquals(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK, route.routeType());
        assertEquals(9L, route.paymentProfileId());
        assertNull(route.manualSource());
        assertNull(route.manualPaymentType());
        assertEquals("", route.manualPhone());
        assertEquals("", route.manualRecipientName());
        assertEquals("", route.instructionText());
        verify(paymentLinkRepository, never()).sumManualReservedAndConfirmedForPeriod(
                any(), any(), anyCollection(), any(LocalDateTime.class), any(LocalDateTime.class),
                any(LocalDateTime.class), any(PaymentLinkStatus.class), any()
        );
    }
    @Test
    void commonInvoiceRouteUsesManagerTextWithoutRequiringTbankProfile() {
        PaymentLinkService service = service(properties());
        Manager manager = manager("manager-text");
        manager.setPayText("Оплатите по реквизитам Альфа-Банка и пришлите чек.");
        when(runtimeSettingsService.paymentInstructionSource())
                .thenReturn(TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT);

        var route = service.selectCommonInvoiceRoute(manager, 230_000L);

        assertEquals(TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT, route.routeType());
        assertEquals("Оплатите по реквизитам Альфа-Банка и пришлите чек.", route.instructionText());
        assertNull(route.paymentProfileId());
        verify(paymentProfileService, never()).selectForManager(any());
        verify(manualPaymentTaskService, never()).findRoutableTask(any(), any(), anyLong(), any());
    }

    @Test
    void commonInvoiceFallbackNeverRediscoversLegacyTaskAfterEmptyLedgerReserve() {
        PaymentLinkService service = service(properties());
        Manager manager = manager("manager-task");
        manager.setId(30L);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(77L);
        task.setPaymentProfile(profile);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        task.setManualPaymentUrl("https://pay.alfabank.ru/sc/common");
        task.setManualPaymentButtonLabel("Оплатить через Альфа-Банк");
        task.setManualRecipientName("Получатель");
        task.setComment("Общий счет");
        task.setTargetAmountKopecks(500_000L);

        when(runtimeSettingsService.paymentInstructionSource())
                .thenReturn(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK);
        when(paymentProfileService.selectForManager(manager)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(new TbankPaymentProfile(
                3L,
                "manual",
                "Ручной профиль",
                true,
                "manual-terminal",
                "password",
                false
        ));
        lenient().when(manualPaymentTaskService.findRoutableTask(manager, profile, 230_000L, null))
                .thenReturn(Optional.of(task));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        com.hunt.otziv.common_billing.model.CommonInvoice invoice =
                new com.hunt.otziv.common_billing.model.CommonInvoice();
        invoice.setId(700L);
        when(taskReceiptIntegrationService.reserveForCommonInvoice(
                invoice, 30L, 3L, 230_000L)).thenReturn(Optional.empty());

        assertTrue(service.selectCommonInvoiceTaskRoute(invoice, manager, 230_000L).isEmpty());
        var route = service.selectCommonInvoiceRoute(manager, 230_000L);

        assertNotEquals(ManualPaymentSource.MANUAL_TASK.name(), route.manualSource());
        assertNull(route.manualTaskId());
        assertNull(route.manualTaskSourceGeneration());
        assertNull(route.manualTaskGeneration());
        assertNull(invoice.getPaymentRouteManualTaskSourceGeneration());
        verify(manualPaymentTaskService, never())
                .findRoutableTask(any(), any(), anyLong(), any());
    }

    @Test
    void commonInvoiceBlockedLiveRoutingFallsBackToBankLinkInsteadOfLegacyManualProfile() {
        PaymentLinkService service = service(properties());
        Manager manager = manager("manager-common-blocked");
        PaymentProfile profile = profile(7L, "manual-common", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        profile.setManualPhone("+79990001122");
        profile.setManualRecipientName("Старый получатель");
        profile.setManualMonthlyHardLimitKopecks(500_000L);

        when(runtimeSettingsService.paymentInstructionSource())
                .thenReturn(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK);
        when(paymentProfileService.selectForManager(manager)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(new TbankPaymentProfile(
                7L,
                "manual-common",
                "Ручной профиль",
                true,
                "manual-terminal",
                "password",
                false
        ));
        when(contractorPaymentLiveRoutingService.configuredButBlockedForNewRoutes()).thenReturn(true);

        var route = service.selectCommonInvoiceRoute(manager, 230_000L);

        assertEquals(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK, route.routeType());
        assertNull(route.manualSource());
        assertNull(route.manualTaskId());
        assertEquals("", route.manualPhone());
        assertEquals("", route.manualRecipientName());
        assertEquals("", route.instructionText());
        verify(manualPaymentTaskService, never())
                .findRoutableTask(any(), any(), anyLong(), any());
    }

    @Test
    void commonInvoiceTaskRouteIsNotReservedWhenTypedAccountingIsDisabled() {
        PaymentLinkService service = service(properties());
        Manager manager = manager("manager-disabled-task");
        manager.setId(31L);
        com.hunt.otziv.common_billing.model.CommonInvoice invoice =
                new com.hunt.otziv.common_billing.model.CommonInvoice();
        invoice.setId(701L);
        invoice.setPaymentRouteManualTaskSourceGeneration("stale-generation");
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(false);

        assertTrue(service.selectCommonInvoiceTaskRoute(invoice, manager, 10_000L).isEmpty());

        assertNull(invoice.getPaymentRouteManualTaskSourceGeneration());
        verifyNoInteractions(taskReceiptIntegrationService);
        verify(paymentProfileService, never()).selectForManager(any());
    }

    @Test
    void createForOrderExpiresInitiatedLinkWhenPayableAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(18L, "ООО Доплата", BigDecimal.valueOf(1000));
        PaymentLink existing = new PaymentLink();
        existing.setId(18L);
        existing.setOrder(order);
        existing.setToken("old-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.INITIATED);
        existing.setPaymentMethod(PaymentMethod.BANK_FORM);
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(18L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(18L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(18L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250), BigDecimal.ZERO));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(18L);

        assertEquals(PaymentLinkStatus.EXPIRED, existing.getStatus());
        assertEquals(125000L, response.amountKopecks());
        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(3)).save(captor.capture());
        assertSame(existing, captor.getAllValues().get(0));
        assertEquals(125000L, captor.getAllValues().getLast().getAmountKopecks());
    }

    @Test
    void createForOrderReusesStartedSbpPaymentInsteadOfTreatingCustomerMethodAsRouteChange() {
        PaymentLinkService service = service(properties());
        Order order = order(36L, "ООО Активный СБП", BigDecimal.valueOf(1000));
        PaymentProfile profile = profile(1L, TbankPaymentProfile.PRIMARY_CODE, "Основной магазин", "terminal");
        PaymentLink existing = new PaymentLink();
        existing.setId(360L);
        existing.setOrder(order);
        existing.setToken("active-sbp-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.INITIATED);
        existing.setPaymentMethod(PaymentMethod.SBP_QR);
        existing.setPaymentProfile(profile);
        existing.setPaymentProfileCode(TbankPaymentProfile.PRIMARY_CODE);
        existing.setPaymentProfileName("Основной магазин");
        existing.setTbankPaymentId("8981883114");
        existing.setTbankOrderId("o36-existing");
        existing.setTbankTerminalKey("terminal");
        existing.setPaymentUrl("https://securepayments.tinkoff.ru/existing");
        existing.setSbpQrPayload("https://qr.nspk.ru/existing");
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(36L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(36L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(36L)).thenReturn(List.of(existing));

        ManagerPaymentLinkResponse response = service.createForOrder(36L);

        assertEquals("active-sbp-token", response.token());
        assertEquals("https://example.ru/pay/active-sbp-token", response.url());
        assertEquals("SBP_QR", response.paymentMethod());
        assertEquals("INITIATED", response.status());
        verify(manualPaymentTaskService, never()).findRoutableTask(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                any()
        );
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderBlocksInitiatedBankLinkWithPaymentIdWhenPayableAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(35L, "ООО Банк в процессе", BigDecimal.valueOf(1000));
        PaymentLink existing = new PaymentLink();
        existing.setId(35L);
        existing.setOrder(order);
        existing.setToken("old-bank-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.INITIATED);
        existing.setPaymentMethod(PaymentMethod.BANK_FORM);
        existing.setTbankPaymentId("8634010699");
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(35L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(35L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(35L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250), BigDecimal.ZERO));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.createForOrder(35L));

        assertEquals(PaymentLinkStatus.INITIATED, existing.getStatus());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderBlocksAuthorizedLinkWhenPayableAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(19L, "ООО Авторизация", BigDecimal.valueOf(1000));
        PaymentLink existing = new PaymentLink();
        existing.setId(19L);
        existing.setOrder(order);
        existing.setToken("authorized-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.AUTHORIZED);
        existing.setPaymentMethod(PaymentMethod.BANK_FORM);
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(19L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(19L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(19L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250), BigDecimal.ZERO));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.createForOrder(19L));

        assertEquals(PaymentLinkStatus.AUTHORIZED, existing.getStatus());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderBlocksNewInvoiceWhileBankPaymentNeedsReconciliation() {
        PaymentLinkService service = service(properties());
        Order order = order(191L, "ООО Сверка", BigDecimal.valueOf(1000));
        when(orderRepository.findByIdForMutation(191L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(eq(191L), anyCollection()))
                .thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(191L)
        );

        assertEquals(409, error.getStatusCode().value());
        ArgumentCaptor<Collection<PaymentLinkStatus>> statuses = paymentStatusCollectionCaptor();
        verify(paymentLinkRepository).existsByOrder_IdAndStatusIn(eq(191L), statuses.capture());
        assertTrue(statuses.getValue().contains(PaymentLinkStatus.NEEDS_RECONCILIATION));
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderBlocksSecondLinkWhenBankInitReservationOutlivesLinkExpiry() {
        PaymentLinkService service = service(properties());
        Order order = order(192L, "ООО Init на границе срока", BigDecimal.valueOf(1000));
        PaymentLink activeInit = new PaymentLink();
        activeInit.setId(1920L);
        activeInit.setOrder(order);
        activeInit.setStatus(PaymentLinkStatus.CREATED);
        activeInit.setAmountKopecks(100000L);
        activeInit.setBankInitNonce("active-init");
        activeInit.setBankInitLeaseUntil(LocalDateTime.now().plusMinutes(1));
        activeInit.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(orderRepository.findByIdForCounterUpdate(192L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(192L)).thenReturn(List.of(activeInit));

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(192L)
        );

        assertEquals(409, conflict.getStatusCode().value());
        verify(paymentLinkRepository, never())
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(192L),
                        anyCollection(),
                        any(LocalDateTime.class)
                );
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderCannotReuseCleanLinkWhileAnotherBankPaymentExists() {
        PaymentLinkService service = service(properties());
        Order order = order(193L, "ООО Две ссылки", BigDecimal.valueOf(1000));
        PaymentLink reusable = new PaymentLink();
        reusable.setId(1931L);
        reusable.setOrder(order);
        reusable.setToken("clean-link");
        reusable.setStatus(PaymentLinkStatus.CREATED);
        reusable.setAmountKopecks(100000L);
        reusable.setExpiresAt(LocalDateTime.now().plusDays(1));
        PaymentLink started = new PaymentLink();
        started.setId(1930L);
        started.setOrder(order);
        started.setToken("started-link");
        started.setStatus(PaymentLinkStatus.INITIATED);
        started.setPaymentMethod(PaymentMethod.BANK_FORM);
        started.setAmountKopecks(100000L);
        started.setTbankPaymentId("payment-1930");
        started.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(orderRepository.findByIdForCounterUpdate(193L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(193L)).thenReturn(List.of(started, reusable));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(193L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(reusable));

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(193L)
        );

        assertEquals(409, conflict.getStatusCode().value());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderCannotReuseCleanLinkWhilePaymentWithoutProviderIdNeedsReconciliation() {
        PaymentLinkService service = service(properties());
        Order order = order(194L, "ООО Сверка без PaymentId", BigDecimal.valueOf(1000));
        PaymentLink reusable = new PaymentLink();
        reusable.setId(1941L);
        reusable.setOrder(order);
        reusable.setToken("clean-link-1941");
        reusable.setStatus(PaymentLinkStatus.CREATED);
        reusable.setAmountKopecks(100000L);
        reusable.setExpiresAt(LocalDateTime.now().plusDays(1));
        PaymentLink quarantined = new PaymentLink();
        quarantined.setId(1940L);
        quarantined.setOrder(order);
        quarantined.setToken("quarantined-link-1940");
        quarantined.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        quarantined.setPaymentMethod(PaymentMethod.BANK_FORM);
        quarantined.setAmountKopecks(100000L);
        quarantined.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(orderRepository.findByIdForCounterUpdate(194L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(194L)).thenReturn(List.of(quarantined, reusable));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(194L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(reusable));

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(194L)
        );

        assertEquals(409, conflict.getStatusCode().value());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void reportManualPaymentMarksLinkAsReportedWithoutConfirmingOrder() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(14L, "ООО Оплатил", BigDecimal.valueOf(500));
        PaymentLink link = new PaymentLink();
        link.setId(140L);
        link.setOrder(order);
        link.setToken("manual-token");
        link.setAmountKopecks(50000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPhone("+79000000000");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenForUpdate("manual-token")).thenReturn(Optional.of(link));

        service.reportManualPayment("manual-token");

        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, link.getStatus());
        assertNotNull(link.getManualReportedAt());
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).findByTokenForUpdate("manual-token");
        verify(paymentLinkRepository, never()).findByTokenWithOrder("manual-token");
        verify(paymentLinkRepository).save(link);
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(140L);
    }

    @Test
    void reportManualPaymentExpiresTaskRouteOnlyAfterReleasingItsReservation() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(142L);
        link.setOrder(order(142L, "ООО Истекший резерв", BigDecimal.valueOf(500)));
        link.setToken("expired-manual-task");
        link.setAmountKopecks(50_000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualTaskSourceGeneration("source-generation");
        link.setManualTaskGeneration(4L);
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(paymentLinkRepository.findByTokenForUpdate(link.getToken())).thenReturn(Optional.of(link));

        ResponseStatusException gone = assertThrows(
                ResponseStatusException.class,
                () -> service.reportManualPayment(link.getToken())
        );

        assertEquals(410, gone.getStatusCode().value());
        assertEquals(PaymentLinkStatus.EXPIRED, link.getStatus());
        assertEquals("source-generation", link.getManualTaskSourceGeneration());
        assertEquals(4L, link.getManualTaskGeneration());
        InOrder ordered = inOrder(taskReceiptIntegrationService, paymentLinkRepository);
        ordered.verify(taskReceiptIntegrationService)
                .release(link, "Срок действия платёжной ссылки истек");
        verify(paymentLinkRepository, never()).save(link);
    }

    @Test
    void reportManualPaymentReconcilesContractorRouteOnlyAfterCommit() throws Exception {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(141L);
        link.setOrder(order(141L, "ООО Отложенная сверка", BigDecimal.valueOf(500)));
        link.setToken("manual-report-after-commit");
        link.setAmountKopecks(50_000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPhone("+79000000000");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenForUpdate(link.getToken())).thenReturn(Optional.of(link));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.reportManualPayment(link.getToken());

            verify(contractorPaymentShadowService, never()).reconcilePaymentLinkId(any());
            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCommit();
            verify(contractorPaymentShadowService).reconcilePaymentLinkId(141L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentLinkStatus.class, names = {
            "CONFIRMED", "TEST_CONFIRMED", "AMOUNT_MISMATCH", "CANCELED", "EXPIRED",
            "REJECTED", "FAILED", "REFUNDED", "REVERSED"
    })
    void terminalContractorLinkPublicResponseRedactsRecipientSnapshots(PaymentLinkStatus status) {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(14_500L);
        link.setOrder(order(145L, "ООО Закрытые реквизиты", BigDecimal.valueOf(500)));
        link.setToken("closed-contractor-token");
        link.setAmountKopecks(50_000L);
        link.setDescription("Оплата услуг");
        link.setStatus(status);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setContractorAllocationId(900L);
        link.setManualPhone("+79000000000");
        link.setManualRecipientName("Иван Получатель");
        link.setManualBankName("Банк получателя");
        link.setManualComment("Персональный комментарий");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        PublicPaymentLinkResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "toPublicResponse",
                link
        );

        assertNotNull(response);
        assertFalse(response.payable());
        assertEquals("", response.manualPhone());
        assertEquals("", response.manualRecipientName());
        assertEquals("", response.manualBankName());
        assertEquals("", response.manualComment());
        assertEquals("Иван Получатель", link.getManualRecipientName());
    }

    @Test
    void reportedButUnconfirmedContractorLinkKeepsRequisitesVisible() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(146L, "ООО Ожидаем сверку", BigDecimal.valueOf(500)));
        link.setToken("reported-contractor-token");
        link.setAmountKopecks(50_000L);
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setContractorAllocationId(901L);
        link.setManualComment("PLAINTEXT LEGACY COMMENT MUST NOT LEAK");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(contractorPaymentLiveRoutingService.activePaymentLinkRequisites(link))
                .thenReturn(Optional.of(requisites(
                        901L,
                        "Пётр Получатель",
                        "+79000000001",
                        "Тест Банк",
                        "Комментарий только из зашифрованного snapshot"
                )));

        PublicPaymentLinkResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "toPublicResponse",
                link
        );
        AdminPaymentLinkResponse admin = ReflectionTestUtils.invokeMethod(
                service,
                "toAdminResponse",
                link
        );

        assertNotNull(response);
        assertNotNull(admin);
        assertTrue(response.payable());
        assertEquals("+79000000001", response.manualPhone());
        assertEquals("Пётр Получатель", response.manualRecipientName());
        assertEquals("Тест Банк", response.manualBankName());
        assertEquals("Комментарий только из зашифрованного snapshot", response.manualComment());
        assertEquals("+79000000001", admin.manualPhone());
        assertEquals("Пётр Получатель", admin.manualRecipientName());
        assertEquals("Тест Банк", admin.manualBankName());
        assertEquals("Комментарий только из зашифрованного snapshot", admin.manualComment());
    }

    @Test
    void payableLookingContractorLinkIsFailClosedAfterAllocationRelease() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(14_700L);
        link.setOrder(order(147L, "ООО Освобождённый резерв", BigDecimal.valueOf(500)));
        link.setToken("released-contractor-token");
        link.setAmountKopecks(50_000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setContractorAllocationId(902L);
        link.setManualPhone("+79000000002");
        link.setManualRecipientName("Скрытый Получатель");
        link.setManualBankName("Тест Банк");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(contractorPaymentLiveRoutingService.activePaymentLinkRequisites(link))
                .thenReturn(Optional.empty());

        PublicPaymentLinkResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "toPublicResponse",
                link
        );
        AdminPaymentLinkResponse admin = ReflectionTestUtils.invokeMethod(
                service,
                "toAdminResponse",
                link
        );

        assertNotNull(response);
        assertNotNull(admin);
        assertFalse(response.payable());
        assertEquals("", response.manualPhone());
        assertEquals("", response.manualRecipientName());
        assertEquals("", response.manualBankName());
        assertEquals("", admin.manualPhone());
        assertEquals("", admin.manualRecipientName());
        assertEquals("", admin.manualBankName());
    }

    @Test
    void clientCannotReportPaymentToReleasedContractorAttempt() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(14_800L);
        link.setOrder(order(148L, "ООО Старый маршрут", BigDecimal.valueOf(500)));
        link.setToken("released-contractor-report-token");
        link.setAmountKopecks(50_000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setContractorAllocationId(903L);
        link.setManualPhone("+79000000003");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenForUpdate(link.getToken())).thenReturn(Optional.of(link));
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "stale route"))
                .when(contractorPaymentLiveRoutingService)
                .validatePaymentLinkClientReportedRoute(link);

        assertThrows(
                ResponseStatusException.class,
                () -> service.reportManualPayment(link.getToken())
        );

        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, link.getStatus());
        verify(paymentLinkRepository, never()).save(link);
    }

    @Test
    void confirmManualPaymentAppliesOrderTransitionAndMarksReceiptPending() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(15L, "ООО Сверка", BigDecimal.valueOf(500));
        PaymentLink link = new PaymentLink();
        link.setId(15L);
        link.setOrder(order);
        link.setToken("manual-token");
        link.setAmountKopecks(50000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(15L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(15L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmManual(15L, "admin@example.ru");

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals(50000L, link.getConfirmedAmountKopecks());
        assertEquals(PaymentReceiptStatus.PENDING, link.getReceiptStatus());
        assertEquals("admin@example.ru", link.getManualConfirmedBy());
        assertNotNull(link.getManualConfirmedAt());
        assertNotNull(link.getPaidAt());
        verify(orderTransactionService).handlePaymentStatus(order);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBanInNewTransaction(15L, "Ручная оплата подтверждена");
        verify(paymentLinkRepository).save(link);
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(15L);
        InOrder lockOrder = inOrder(orderRepository, paymentLinkRepository);
        lockOrder.verify(paymentLinkRepository).findByIdWithOrder(15L);
        lockOrder.verify(orderRepository).findByIdForCounterUpdate(15L);
        lockOrder.verify(paymentLinkRepository).findByIdForUpdate(15L);
    }

    @Test
    void partialLateContractorConfirmationIsBoundToExpiredSourceAndReleasesNewAttempt() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(1_515L, "ООО Источник", BigDecimal.valueOf(1000));
        PaymentLink expiredA = contractorManualLink(
                1_516L,
                order,
                100_000L,
                PaymentLinkStatus.EXPIRED,
                9_901L
        );
        PaymentLink activeB = contractorManualLink(
                1_517L,
                order,
                100_000L,
                PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
                9_902L
        );
        when(paymentLinkRepository.findByIdWithOrder(1_516L)).thenReturn(Optional.of(expiredA));
        when(orderRepository.findByIdForCounterUpdate(1_515L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(1_515L)).thenReturn(List.of(expiredA, activeB));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirmContractorPaymentSource(
                1_516L,
                40_000L,
                LocalDateTime.now().minusHours(2),
                "Проверена выписка получателя",
                "owner@example.ru"
        );

        assertEquals(PaymentLinkStatus.AMOUNT_MISMATCH, expiredA.getStatus());
        assertEquals(40_000L, expiredA.getConfirmedAmountKopecks());
        assertEquals(PaymentLinkStatus.CANCELED, activeB.getStatus());
        assertTrue(expiredA.getLastError().contains("contractor_source_confirmation"));
        verify(contractorPaymentLiveRoutingService).releaseClosedPaymentLink(activeB);
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(1_516L);
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(1_517L);
        verify(manualPaymentRecipientTelegramNotificationService, never()).notifyAfterCommit(anyLong());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void sourceBoundConfirmationRejectsCompetingClientReportedAttempt() {
        PaymentLinkService service = service(properties());
        Order order = order(1_525L, "ООО Двойная сверка", BigDecimal.valueOf(1000));
        PaymentLink expiredA = contractorManualLink(
                1_526L,
                order,
                100_000L,
                PaymentLinkStatus.EXPIRED,
                9_911L
        );
        PaymentLink reportedB = contractorManualLink(
                1_527L,
                order,
                100_000L,
                PaymentLinkStatus.MANUAL_REPORTED,
                9_912L
        );
        when(paymentLinkRepository.findByIdWithOrder(1_526L)).thenReturn(Optional.of(expiredA));
        when(orderRepository.findByIdForCounterUpdate(1_525L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(1_525L)).thenReturn(List.of(expiredA, reportedB));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmContractorPaymentSource(
                        1_526L,
                        100_000L,
                        LocalDateTime.now().minusHours(1),
                        "Проверена выписка получателя",
                        "owner@example.ru"
                )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(PaymentLinkStatus.EXPIRED, expiredA.getStatus());
        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, reportedB.getStatus());
        verify(contractorPaymentShadowService, never()).reconcilePaymentLinkId(anyLong());
    }

    @Test
    void legacyFullConfirmationRejectsContractorPaymentSource() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(1_530L, "ООО Только точная сверка", BigDecimal.valueOf(1000));
        PaymentLink source = contractorManualLink(
                1_531L,
                order,
                100_000L,
                PaymentLinkStatus.MANUAL_REPORTED,
                9_915L
        );
        when(paymentLinkRepository.findByIdWithOrder(1_531L)).thenReturn(Optional.of(source));
        when(orderRepository.findByIdForCounterUpdate(1_530L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(1_531L)).thenReturn(Optional.of(source));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmManual(1_531L, "owner@example.ru")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, source.getStatus());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(contractorPaymentShadowService, never()).reconcilePaymentLinkId(anyLong());
    }

    @Test
    void contractorPrepaymentKeepsSourceAuditUntilItCanBeApplied() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(1_535L, "ООО Предоплата получателю", BigDecimal.valueOf(1000));
        order.setAmount(2);
        order.setCounter(1);
        PaymentLink source = contractorManualLink(
                1_536L,
                order,
                100_000L,
                PaymentLinkStatus.EXPIRED,
                9_921L
        );
        when(paymentLinkRepository.findByIdWithOrder(1_536L)).thenReturn(Optional.of(source));
        when(orderRepository.findByIdForCounterUpdate(1_535L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(1_535L)).thenReturn(List.of(source));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirmContractorPaymentSource(
                1_536L,
                100_000L,
                LocalDateTime.now().minusMinutes(30),
                "Проверена выписка перед завершением заказа",
                "owner@example.ru"
        );

        assertEquals(PaymentLinkStatus.CONFIRMED, source.getStatus());
        assertTrue(source.getLastError().startsWith("prepaid_waiting_order_completion"));
        assertTrue(source.getLastError().contains("contractor_source_confirmation"));
        assertTrue(source.getLastError().contains("Проверена выписка"));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(manualPaymentRecipientTelegramNotificationService).notifyAfterCommit(1_536L);

        order.setCounter(2);
        when(paymentLinkRepository
                .findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc(
                        1_535L,
                        PaymentLinkStatus.CONFIRMED,
                        "prepaid_waiting_order_completion"
                ))
                .thenReturn(Optional.of(source));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        assertTrue(service.applyConfirmedPrepaymentIfReady(order));
        assertTrue(source.getLastError().startsWith("contractor_source_confirmation"));
        assertTrue(source.getLastError().contains("Проверена выписка"));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void closeManualAsUnpaidCancelsOnlyInstructionAndPreservesAuditTrail() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(151L, "ООО Ручная сверка", BigDecimal.valueOf(1000));
        OrderStatus originalOrderStatus = new OrderStatus();
        originalOrderStatus.setTitle("Ожидает оплаты");
        order.setStatus(originalOrderStatus);
        PaymentLink link = new PaymentLink();
        link.setId(1510L);
        link.setOrder(order);
        link.setToken("manual-unpaid-token");
        link.setAmountKopecks(100000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualReportedAt(LocalDateTime.now().minusHours(2));
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setExpiresAt(LocalDateTime.now().plusDays(10));

        when(paymentLinkRepository.findByIdWithOrder(1510L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(151L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(1510L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);

        AdminPaymentLinkResponse response = service.closeManualAsUnpaid(
                1510L,
                true,
                true,
                "Выписка карты получателя проверена за 09.07, перевода нет",
                "owner@example.ru",
                authentication
        );

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertTrue(link.getLastError().startsWith("manual_payment_absent_verified:"));
        assertTrue(link.getLastError().contains("checked_by=owner@example.ru"));
        assertTrue(link.getLastError().contains("Выписка карты получателя проверена"));
        assertEquals("CANCELED", response.status());
        assertNull(link.getPaidAt());
        assertNull(link.getManualConfirmedAt());
        assertNull(link.getManualConfirmedBy());
        assertNull(link.getConfirmedAmountKopecks());
        assertSame(originalOrderStatus, order.getStatus());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(paymentInvoiceRetryScheduler, never()).cancelBadReviewAutoBan(any(Order.class), anyString());
        verify(paymentInvoiceRetryScheduler, never()).cancelBadReviewAutoBanInNewTransaction(any(), anyString());
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(1510L);

        InOrder lockOrder = inOrder(orderRepository, paymentLinkRepository);
        lockOrder.verify(paymentLinkRepository).findByIdWithOrder(1510L);
        lockOrder.verify(orderRepository).findByIdForCounterUpdate(151L);
        lockOrder.verify(paymentLinkRepository).findByIdForUpdate(1510L);
        lockOrder.verify(paymentLinkRepository).save(link);
    }

    @Test
    void closeManualAsUnpaidAllowsExpiredInstructionAfterStatementVerification() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(1512L, "ООО Истекшая инструкция", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(15120L);
        link.setOrder(order);
        link.setToken("expired-manual-unpaid-token");
        link.setAmountKopecks(100000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setLastError("Платежная ссылка пересоздана из-за изменения суммы или маршрута оплаты");
        link.setExpiresAt(LocalDateTime.now().plusDays(30));

        when(paymentLinkRepository.findByIdWithOrder(15120L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(1512L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(15120L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);

        AdminPaymentLinkResponse response = service.closeManualAsUnpaid(
                15120L,
                true,
                true,
                "Выписка получателя проверена за весь период действия инструкции",
                "owner@example.ru",
                authentication
        );

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertEquals("CANCELED", response.status());
        assertTrue(link.getLastError().startsWith("manual_payment_absent_verified:"));
        assertNull(link.getPaidAt());
        assertNull(link.getManualConfirmedAt());
        verify(paymentLinkRepository).save(link);
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(15120L);
    }

    @Test
    void closeManualAsUnpaidReconcilesContractorRouteOnlyAfterCommit() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(1511L, "ООО Отложенное освобождение", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(15110L);
        link.setOrder(order);
        link.setToken("manual-unpaid-after-commit");
        link.setAmountKopecks(100000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualReportedAt(LocalDateTime.now().minusHours(2));
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setExpiresAt(LocalDateTime.now().plusDays(10));

        when(paymentLinkRepository.findByIdWithOrder(15110L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(1511L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(15110L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.closeManualAsUnpaid(
                    15110L,
                    true,
                    true,
                    "Выписка проверена, перевода нет",
                    "owner@example.ru",
                    authentication
            );

            verify(contractorPaymentShadowService, never()).reconcilePaymentLinkId(any());
            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCommit();
            verify(contractorPaymentShadowService).reconcilePaymentLinkId(15110L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void closeManualAsUnpaidRequiresBothAssertionsAndNoteBeforeReadingData() {
        PaymentLinkService service = service(properties());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.closeManualAsUnpaid(1510L, true, false, " ", "owner", authentication)
        );

        assertEquals(400, error.getStatusCode().value());
        verify(paymentLinkRepository, never()).findByIdWithOrder(any(Long.class));
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void closeManualAsUnpaidRejectsAnyExistingPaidEvidence() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(152L, "ООО Оплата обнаружена", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(1520L);
        link.setOrder(order);
        link.setToken("manual-paid-evidence-token");
        link.setAmountKopecks(100000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setConfirmedAmountKopecks(100000L);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(1520L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(152L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(1520L)).thenReturn(Optional.of(link));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.closeManualAsUnpaid(
                        1520L,
                        true,
                        true,
                        "Выписка проверена, но в записи уже есть подтвержденная сумма",
                        "owner",
                        authentication
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, link.getStatus());
        verify(paymentLinkRepository, never()).save(link);
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void closeManualAsUnpaidRejectsPrivilegedActorWithoutOrderAccess() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(153L, "ООО Чужой заказ", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(1530L);
        link.setOrder(order);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);

        when(paymentLinkRepository.findByIdWithOrder(1530L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(153L)).thenReturn(Optional.of(order));
        ResponseStatusException denied = new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к заказу");
        doThrow(denied).when(managerAccessService).requireOrderAccess(153L, authentication);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.closeManualAsUnpaid(
                        1530L,
                        true,
                        true,
                        "Выписка проверена, перевода нет",
                        "owner",
                        authentication
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(paymentLinkRepository, never()).findByIdForUpdate(1530L);
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void reportManualPaymentRejectsMissingOrUnsafeExternalPaymentTarget() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(141L, "ООО Безопасная ручная оплата", BigDecimal.valueOf(500)));
        link.setToken("manual-external-unsafe");
        link.setAmountKopecks(50000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_EXTERNAL_LINK);
        link.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        link.setManualPaymentUrl("javascript:alert(document.cookie)");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenForUpdate("manual-external-unsafe"))
                .thenReturn(Optional.of(link));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reportManualPayment("manual-external-unsafe")
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, link.getStatus());
        assertNull(link.getManualReportedAt());
        verify(paymentLinkRepository, never()).save(link);
    }

    @Test
    void blankLegacyExternalTargetUsesTheSameEffectiveDefaultForReadAndReport() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(143L, "ООО Резервная ручная оплата", BigDecimal.valueOf(500)));
        link.setToken("manual-external-default");
        link.setAmountKopecks(50000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_EXTERNAL_LINK);
        link.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        link.setManualPaymentUrl(null);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenWithOrder("manual-external-default"))
                .thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("manual-external-default"))
                .thenReturn(Optional.of(link));

        var response = service.publicLink("manual-external-default");
        service.reportManualPayment("manual-external-default");

        assertEquals(ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL, response.manualPaymentUrl());
        assertTrue(response.payable());
        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, link.getStatus());
        assertNotNull(link.getManualReportedAt());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void reportManualPaymentRejectsMobileBankTargetWithoutPhone() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(142L, "ООО Без телефона", BigDecimal.valueOf(500)));
        link.setToken("manual-mobile-no-phone");
        link.setAmountKopecks(50000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualPhone("  ");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenForUpdate("manual-mobile-no-phone"))
                .thenReturn(Optional.of(link));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reportManualPayment("manual-mobile-no-phone")
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, link.getStatus());
        assertNull(link.getManualReportedAt());
        verify(paymentLinkRepository, never()).save(link);
    }

    @Test
    void confirmManualPaymentForCommonInvoiceDoesNotOpenNextOrderBeforeInvoiceCloses() throws Exception {
        CommonBillingService commonBillingService = org.mockito.Mockito.mock(CommonBillingService.class);
        PaymentLinkService service = service(properties(), new TbankTokenSigner(), commonBillingService);
        Order order = order(16L, "ООО Общий счет", BigDecimal.valueOf(500));
        PaymentLink link = new PaymentLink();
        link.setId(16L);
        link.setOrder(order);
        link.setToken("manual-common-token");
        link.setAmountKopecks(50000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(16L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(16L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commonBillingService.isOrderInActiveCommonInvoice(16L)).thenReturn(true);
        when(orderTransactionService.handlePaymentStatus(order, false)).thenReturn(true);

        service.confirmManual(16L, "admin@example.ru");

        verify(orderTransactionService).handlePaymentStatus(order, false);
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(commonBillingService).applyConfirmedOrderPayment(
                eq(16L),
                any(LocalDateTime.class),
                eq("Ручная оплата заказа")
        );
    }

    @Test
    void confirmedWebhookInTestModeDoesNotTouchOrderPaymentTransition() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(20L, "ООО Тест", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(20L);
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o20-test");
        link.setAmountKopecks(1111L);
        link.setPayerEmail("PAYER@EXAMPLE.RU");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o20-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12345");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o20-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(20L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("12345", link.getTbankPaymentId());
        assertNotNull(link.getPaidAt());
        assertEquals("payer@example.ru", order.getCompany().getLastPayerEmail());
        assertNotNull(order.getCompany().getLastPayerEmailAt());
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void unknownFailedWebhookCannotReleaseReconciliationQuarantineOrAllowNewInvoice() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(23L, "ООО Карантин", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(23L);
        link.setOrder(order);
        link.setToken("quarantined-token");
        link.setTbankOrderId("o23-quarantine");
        link.setTbankPaymentId("payment-created-by-bank");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o23-quarantine");
        payload.put("Success", "false");
        payload.put("Status", "UNKNOWN_PROVIDER_STATE");
        payload.put("PaymentId", "payment-created-by-bank");
        payload.put("ErrorCode", "999");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o23-quarantine"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(23L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(23L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertTrue(link.getLastError().startsWith("bank_status_reconciliation_error:"));

        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(eq(23L), anyCollection()))
                .thenReturn(true);

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.createForOrder(23L)
        );

        assertEquals(409, conflict.getStatusCode().value());
        verify(tbankClient, never()).init(any(), any(TbankInitCommand.class));
    }

    @Test
    void confirmedWebhookAppliesOrderPaymentTransitionOnlyWhenEnabled() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        properties.setApplyConfirmedPayments(true);
        TbankTokenSigner signer = new TbankTokenSigner();
        CommonBillingService commonBillingService = org.mockito.Mockito.mock(CommonBillingService.class);
        PaymentLinkService service = service(properties, signer, commonBillingService);
        when(paymentProfileService.toRuntimeForTerminal(any(PaymentProfile.class), eq("terminal"))).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                false
        ));
        when(paymentProfileService.isTestTerminal("terminal")).thenReturn(false);
        Order order = order(21L, "ООО Боевой тест", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(21L);
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o21-test");
        link.setAmountKopecks(1111L);
        link.setPayerEmail("BOY@EXAMPLE.RU");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o21-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12346");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o21-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(21L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(link));
        when(commonBillingService.isOrderInActiveCommonInvoice(21L)).thenReturn(true);
        when(orderTransactionService.handlePaymentStatus(order, false)).thenReturn(true);

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("12346", link.getTbankPaymentId());
        assertNotNull(link.getPaidAt());
        assertEquals("boy@example.ru", order.getCompany().getLastPayerEmail());
        assertNotNull(order.getCompany().getLastPayerEmailAt());
        assertNull(link.getPaymentSuccessNotifiedAt());
        assertTrue(link.isPaymentSuccessNotificationRetryEligible());
        assertNull(link.getPaymentSuccessNotificationError());
        verify(orderTransactionService).handlePaymentStatus(order, false);
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentSuccessNotificationDeliveryService).deliverAfterCommit(link.getId());
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBanInNewTransaction(21L, "T-Bank/SBP оплата подтверждена");
        verify(commonBillingService).applyConfirmedOrderPayment(
                eq(21L),
                any(LocalDateTime.class),
                eq("T-Bank/SBP оплата заказа")
        );
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmedWebhookBeforeOrderCompletionStoresPrepaymentWithoutClosingOrder() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        properties.setApplyConfirmedPayments(true);
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        when(paymentProfileService.toRuntimeForTerminal(any(PaymentProfile.class), eq("terminal"))).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                false
        ));
        when(paymentProfileService.isTestTerminal("terminal")).thenReturn(false);
        Order order = order(210L, "ООО Предоплата", BigDecimal.valueOf(11.11));
        order.setAmount(2);
        order.setCounter(1);
        PaymentLink link = new PaymentLink();
        link.setId(210L);
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o210-test");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o210-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "123210");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o210-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(210L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(210L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
        assertEquals(PaymentReceiptStatus.PENDING, link.getReceiptStatus());
        assertNotNull(link.getPaidAt());
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void reportManualPaymentDoesNotDowngradeWebhookConfirmedLinkAfterTokenLock() {
        PaymentLinkService service = service(properties());
        PaymentLink confirmed = new PaymentLink();
        confirmed.setId(1401L);
        confirmed.setOrder(order(1401L, "ООО Уже оплачено", BigDecimal.valueOf(500)));
        confirmed.setToken("manual-confirmed-race");
        confirmed.setAmountKopecks(50000L);
        confirmed.setDescription("Оплата услуг");
        confirmed.setStatus(PaymentLinkStatus.CONFIRMED);
        confirmed.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        confirmed.setManualPhone("+79000000000");
        confirmed.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenForUpdate("manual-confirmed-race"))
                .thenReturn(Optional.of(confirmed));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reportManualPayment("manual-confirmed-race")
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.CONFIRMED, confirmed.getStatus());
        assertNull(confirmed.getManualReportedAt());
        verify(paymentLinkRepository).findByTokenForUpdate("manual-confirmed-race");
        verify(paymentLinkRepository, never()).findByTokenWithOrder("manual-confirmed-race");
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void publicLinkDoesNotExposeStoredOrCompanyPayerEmail() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(22L, "ООО Автозаполнение", BigDecimal.valueOf(200));
        order.getCompany().setLastPayerEmail("Client@Example.Ru");
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(20000L);
        link.setDescription("Оплата услуг");
        link.setPayerEmail("Previous.Payer@Example.Ru");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("token")).thenReturn(Optional.of(link));

        assertEquals("", service.publicLink("token").payerEmail());
    }

    @Test
    void publicManualLinkHidesUnsafeLegacyUrlWithoutBreakingThePage() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(22L, "ООО Старые реквизиты", BigDecimal.valueOf(200)));
        link.setToken("legacy-unsafe-manual");
        link.setAmountKopecks(20000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_EXTERNAL_LINK);
        link.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        link.setManualPaymentUrl("javascript:alert(document.cookie)");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenWithOrder("legacy-unsafe-manual"))
                .thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("legacy-unsafe-manual"))
                .thenReturn(Optional.of(link));

        var response = service.publicLink("legacy-unsafe-manual");

        assertEquals("", response.manualPaymentUrl());
        assertFalse(response.payable());
    }

    @Test
    void createForOrderRemovesReceiptRequestFromTbankCopyText() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(6L, TbankPaymentProfile.PRIMARY_CODE, "Основной магазин", "primary-terminal");
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(appSettingService.getString(
                eq(AppSettingService.CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT),
                anyString()
        )).thenReturn("{companyAndFilial}\n\n{paymentInstruction}\n\nПришлите чек, пожалуйста, как оплатите.");
        Order order = order(34L, "ООО T-Bank", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(34L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(34L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(34L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(34L);

        assertTrue(response.copyText().contains("Ссылка на оплату: https://example.ru/pay/"));
        assertFalse(response.copyText().toLowerCase(Locale.ROOT).contains("пришлите чек"));
    }

    @Test
    void publicLinkReturnsLatestActiveLinkWhenOldTokenWasRetired() {
        PaymentLinkService service = service(properties());
        Order order = order(22115L, "ООО Старая ссылка", BigDecimal.valueOf(3000));
        PaymentLink oldLink = new PaymentLink();
        oldLink.setId(1L);
        oldLink.setOrder(order);
        oldLink.setToken("old-token");
        oldLink.setAmountKopecks(300000L);
        oldLink.setDescription("Оплата услуг");
        oldLink.setStatus(PaymentLinkStatus.EXPIRED);
        oldLink.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink newLink = new PaymentLink();
        newLink.setId(2L);
        newLink.setOrder(order);
        newLink.setToken("new-token");
        newLink.setAmountKopecks(300000L);
        newLink.setDescription("Оплата услуг");
        newLink.setStatus(PaymentLinkStatus.CREATED);
        newLink.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findByTokenForUpdate("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(newLink));
        when(paymentLinkRepository.findByIdWithOrder(2L)).thenReturn(Optional.of(newLink));
        when(orderRepository.findByIdForCounterUpdate(22115L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(22115L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(newLink));

        var response = service.publicLink("old-token");

        assertEquals("new-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
        InOrder lockOrder = inOrder(paymentLinkRepository, orderRepository);
        lockOrder.verify(paymentLinkRepository).findByTokenForUpdate("old-token");
        lockOrder.verify(orderRepository).findByIdForCounterUpdate(22115L);
        lockOrder.verify(paymentLinkRepository)
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(22115L),
                        anyCollection(),
                        any(LocalDateTime.class)
                );
        lockOrder.verify(paymentLinkRepository).findByIdForUpdate(2L);
    }

    @Test
    void publicLinkCreatesReplacementWhenOldTokenHasNoActiveSuccessor() {
        PaymentLinkService service = service(properties());
        Order order = order(22116L, "ООО Автозамена", BigDecimal.valueOf(3000));
        PaymentLink oldLink = new PaymentLink();
        oldLink.setId(1L);
        oldLink.setOrder(order);
        oldLink.setToken("old-token");
        oldLink.setAmountKopecks(300000L);
        oldLink.setDescription("Оплата услуг");
        oldLink.setStatus(PaymentLinkStatus.EXPIRED);
        oldLink.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink newLink = new PaymentLink();
        newLink.setId(2L);
        newLink.setOrder(order);
        newLink.setToken("created-token");
        newLink.setAmountKopecks(300000L);
        newLink.setDescription("Оплата услуг");
        newLink.setStatus(PaymentLinkStatus.CREATED);
        newLink.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findByTokenForUpdate("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(newLink));
        when(paymentLinkRepository.findByIdWithOrder(2L)).thenReturn(Optional.of(newLink));
        when(orderRepository.findByIdForCounterUpdate(22116L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(22116L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty(), Optional.empty(), Optional.of(newLink));
        when(badReviewTaskService.getSummaryForOrder(22116L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publicLink("old-token");

        assertEquals("created-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
        verify(paymentLinkRepository, times(2)).save(any(PaymentLink.class));
    }

    @Test
    void publicLinkReturnsFreshSuccessorForProviderCanceledSbpSession() {
        PaymentLinkService service = service(properties());
        Order order = order(25534L, "Топ лазер про", BigDecimal.valueOf(1000));
        PaymentLink canceledSbp = new PaymentLink();
        canceledSbp.setId(6914L);
        canceledSbp.setOrder(order);
        canceledSbp.setToken("canceled-sbp-token");
        canceledSbp.setAmountKopecks(100000L);
        canceledSbp.setDescription("Оплата услуг");
        canceledSbp.setStatus(PaymentLinkStatus.CANCELED);
        canceledSbp.setPaymentMethod(PaymentMethod.SBP_QR);
        canceledSbp.setTbankPaymentId("9101622338");
        canceledSbp.setProviderTerminalStatus("CANCELED");
        canceledSbp.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink replacement = new PaymentLink();
        replacement.setId(7001L);
        replacement.setOrder(order);
        replacement.setToken("fresh-payment-token");
        replacement.setAmountKopecks(100000L);
        replacement.setDescription("Оплата услуг");
        replacement.setStatus(PaymentLinkStatus.CREATED);
        replacement.setPaymentMethod(PaymentMethod.BANK_FORM);
        replacement.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("canceled-sbp-token"))
                .thenReturn(Optional.of(canceledSbp));
        when(paymentLinkRepository.findByTokenForUpdate("canceled-sbp-token"))
                .thenReturn(Optional.of(canceledSbp));
        when(paymentLinkRepository.findByIdForUpdate(7001L)).thenReturn(Optional.of(replacement));
        when(paymentLinkRepository.findByIdWithOrder(7001L)).thenReturn(Optional.of(replacement));
        when(orderRepository.findByIdForCounterUpdate(25534L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(25534L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(replacement));

        var response = service.publicLink("canceled-sbp-token");

        assertEquals("fresh-payment-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
        verify(paymentLinkRepository, never()).save(canceledSbp);
    }

    @Test
    void publicLinkDoesNotReopenInternalCancellationWithoutProviderEvidence() {
        PaymentLinkService service = service(properties());
        Order order = order(25535L, "Внутренняя отмена", BigDecimal.valueOf(1000));
        PaymentLink canceled = new PaymentLink();
        canceled.setId(6915L);
        canceled.setOrder(order);
        canceled.setToken("internally-canceled-token");
        canceled.setAmountKopecks(100000L);
        canceled.setDescription("Оплата услуг");
        canceled.setStatus(PaymentLinkStatus.CANCELED);
        canceled.setPaymentMethod(PaymentMethod.BANK_FORM);
        canceled.setTbankPaymentId("bank-payment-still-ambiguous");
        canceled.setLastError("Внешнее сообщение достоверно не отправлено");
        canceled.setExpiresAt(LocalDateTime.now().plusDays(80));

        when(paymentLinkRepository.findByTokenWithOrder("internally-canceled-token"))
                .thenReturn(Optional.of(canceled));
        when(paymentLinkRepository.findByTokenForUpdate("internally-canceled-token"))
                .thenReturn(Optional.of(canceled));
        when(orderRepository.findByIdForCounterUpdate(25535L)).thenReturn(Optional.of(order));

        var response = service.publicLink("internally-canceled-token");

        assertEquals("internally-canceled-token", response.token());
        assertEquals("CANCELED", response.status());
        assertFalse(response.payable());
        verify(paymentLinkRepository, never())
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        anyLong(), anyCollection(), any(LocalDateTime.class));
    }

    @Test
    void publicLinkCreatesReplacementForManualPaidRetiredLinkWhenOrderReturnedToReminder() {
        PaymentLinkService service = service(properties());
        Order order = order(22671L, "КЛИМАТпроф", BigDecimal.valueOf(2500));
        order.setStatus(OrderStatus.builder().title("Напоминание").build());

        PaymentLink oldLink = new PaymentLink();
        oldLink.setId(800L);
        oldLink.setOrder(order);
        oldLink.setToken("old-token");
        oldLink.setAmountKopecks(250000L);
        oldLink.setDescription("Оплата услуг");
        oldLink.setStatus(PaymentLinkStatus.CANCELED);
        oldLink.setLastError("Заказ отмечен оплаченным вручную; старая ссылка закрыта");
        oldLink.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink newLink = new PaymentLink();
        newLink.setId(801L);
        newLink.setOrder(order);
        newLink.setToken("new-token");
        newLink.setAmountKopecks(250000L);
        newLink.setDescription("Оплата услуг");
        newLink.setStatus(PaymentLinkStatus.CREATED);
        newLink.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findByTokenForUpdate("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findByIdForUpdate(801L)).thenReturn(Optional.of(newLink));
        when(paymentLinkRepository.findByIdWithOrder(801L)).thenReturn(Optional.of(newLink));
        when(orderRepository.findByIdForCounterUpdate(22671L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(22671L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty(), Optional.empty(), Optional.of(newLink));
        when(badReviewTaskService.getSummaryForOrder(22671L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publicLink("old-token");

        assertEquals("new-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
        verify(paymentLinkRepository, times(2)).save(any(PaymentLink.class));
    }

    @Test
    void publicLinkSynchronizesInitiatedPaymentFromTbankGetState() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(23L, "ООО Возврат", BigDecimal.valueOf(250));
        PaymentLink link = new PaymentLink();
        link.setId(23L);
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(25000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-23");
        link.setTbankTerminalKey("terminal");
        link.setPayerEmail("RETURN@EXAMPLE.RU");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("token")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(23L)).thenReturn(Optional.of(order));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-23"))).thenReturn(new TbankGetStateResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "CONFIRMED",
                "payment-23",
                "order-23",
                25000L
        ));

        var response = service.publicLink("token");

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("TEST_CONFIRMED", response.status());
        assertFalse(response.payable());
        assertNotNull(link.getPaidAt());
        assertEquals("return@example.ru", order.getCompany().getLastPayerEmail());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-23"));
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void publicLinkIgnoresStaleBankDowngradeAfterWebhookConfirmedLockedRow() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(2301L, "ООО Гонка webhook", BigDecimal.valueOf(250));

        PaymentLink observed = new PaymentLink();
        observed.setId(2301L);
        observed.setOrder(order);
        observed.setToken("public-bank-race");
        observed.setAmountKopecks(25000L);
        observed.setDescription("Оплата услуг");
        observed.setStatus(PaymentLinkStatus.INITIATED);
        observed.setTbankPaymentId("payment-2301");
        observed.setTbankTerminalKey("terminal");
        observed.setExpiresAt(LocalDateTime.now().plusDays(1));

        PaymentLink confirmed = new PaymentLink();
        confirmed.setId(2301L);
        confirmed.setOrder(order);
        confirmed.setToken("public-bank-race");
        confirmed.setAmountKopecks(25000L);
        confirmed.setDescription("Оплата услуг");
        confirmed.setStatus(PaymentLinkStatus.CONFIRMED);
        confirmed.setTbankPaymentId("payment-2301");
        confirmed.setTbankTerminalKey("terminal");
        confirmed.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("public-bank-race"))
                .thenReturn(Optional.of(observed));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-2301")))
                .thenReturn(new TbankGetStateResponse(
                        false,
                        "1051",
                        "rejected",
                        null,
                        "terminal",
                        "REJECTED",
                        "payment-2301",
                        "order-2301",
                        25000L
                ));
        when(paymentLinkRepository.findByTokenForUpdate("public-bank-race"))
                .thenReturn(Optional.of(confirmed));
        when(orderRepository.findByIdForCounterUpdate(2301L)).thenReturn(Optional.of(order));

        var response = service.publicLink("public-bank-race");

        assertEquals(PaymentLinkStatus.CONFIRMED, confirmed.getStatus());
        assertEquals("CONFIRMED", response.status());
        assertFalse(response.payable());
        InOrder ordered = inOrder(tbankClient, orderRepository, paymentLinkRepository);
        ordered.verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-2301"));
        ordered.verify(orderRepository).findByIdForCounterUpdate(2301L);
        ordered.verify(paymentLinkRepository).findByTokenForUpdate("public-bank-race");
        verify(paymentLinkRepository, never()).save(confirmed);
    }

    @Test
    void publicLinkDebouncesRepeatedProviderStateFailures() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(23011L, "ООО Debounce", BigDecimal.valueOf(250));
        PaymentLink link = new PaymentLink();
        link.setId(23011L);
        link.setOrder(order);
        link.setToken("public-bank-debounce");
        link.setAmountKopecks(25000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-23011");
        link.setTbankTerminalKey("terminal");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("public-bank-debounce"))
                .thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("public-bank-debounce"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(23011L)).thenReturn(Optional.of(order));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-23011")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider timeout"));

        var first = service.publicLink("public-bank-debounce");
        var second = service.publicLink("public-bank-debounce");

        assertEquals("INITIATED", first.status());
        assertEquals("INITIATED", second.status());
        org.junit.jupiter.api.Assertions.assertNotNull(link.getBankReconciliationAttemptedAt());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-23011"));
    }

    @Test
    void publicLinkExpirationRechecksLockedStatusAndKeepsConfirmedTerminal() {
        PaymentLinkService service = service(properties());
        Order order = order(2302L, "ООО Гонка срока", BigDecimal.valueOf(250));

        PaymentLink stale = new PaymentLink();
        stale.setId(2302L);
        stale.setOrder(order);
        stale.setToken("public-expiry-race");
        stale.setAmountKopecks(25000L);
        stale.setDescription("Оплата услуг");
        stale.setStatus(PaymentLinkStatus.CREATED);
        stale.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        PaymentLink confirmed = new PaymentLink();
        confirmed.setId(2302L);
        confirmed.setOrder(order);
        confirmed.setToken("public-expiry-race");
        confirmed.setAmountKopecks(25000L);
        confirmed.setDescription("Оплата услуг");
        confirmed.setStatus(PaymentLinkStatus.CONFIRMED);
        confirmed.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(paymentLinkRepository.findByTokenWithOrder("public-expiry-race"))
                .thenReturn(Optional.of(stale));
        when(paymentLinkRepository.findByTokenForUpdate("public-expiry-race"))
                .thenReturn(Optional.of(confirmed));

        var response = service.publicLink("public-expiry-race");

        assertEquals(PaymentLinkStatus.CONFIRMED, confirmed.getStatus());
        assertEquals("CONFIRMED", response.status());
        assertFalse(response.payable());
        verify(paymentLinkRepository).findByTokenForUpdate("public-expiry-race");
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void publicLinkReconcilesQuarantinedProviderPaymentAndKeepsItNonPayable() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(231L, "ООО Сверка ссылки", BigDecimal.valueOf(250));
        PaymentLink link = new PaymentLink();
        link.setId(231L);
        link.setOrder(order);
        link.setToken("needs-reconciliation");
        link.setAmountKopecks(25000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setTbankPaymentId("payment-231");
        link.setTbankTerminalKey("terminal");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenWithOrder("needs-reconciliation"))
                .thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("needs-reconciliation"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(231L)).thenReturn(Optional.of(order));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-231")))
                .thenReturn(new TbankGetStateResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "CONFIRMED",
                        "payment-231",
                        "order-231",
                        25000L
                ));

        var response = service.publicLink("needs-reconciliation");

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("TEST_CONFIRMED", response.status());
        assertFalse(response.payable());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-231"));
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void publicLinkDoesNotReplaceOrExposeQuarantinedProviderPaymentAsPayable() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(232L);
        link.setOrder(order(232L, "ООО Ссылка на сверке", BigDecimal.valueOf(250)));
        link.setToken("quarantined-provider-payment");
        link.setAmountKopecks(25000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setTbankPaymentId("payment-232");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenWithOrder("quarantined-provider-payment"))
                .thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("quarantined-provider-payment"))
                .thenReturn(Optional.of(link));

        var response = service.publicLink("quarantined-provider-payment");

        assertEquals("quarantined-provider-payment", response.token());
        assertEquals("NEEDS_RECONCILIATION", response.status());
        assertFalse(response.payable());
        verify(paymentLinkRepository, never())
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(232L),
                        anyCollection(),
                        any(LocalDateTime.class)
                );
        verify(orderRepository, never()).findByIdForMutation(232L);
    }

    @Test
    void initDoesNotExpireOrRetryQuarantinedProviderPaymentAfterPublicTtl() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(233L);
        link.setOrder(order(233L, "ООО Просроченная сверка", BigDecimal.valueOf(250)));
        link.setToken("expired-reconciliation");
        link.setAmountKopecks(25000L);
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setTbankPaymentId("payment-233");
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(paymentLinkRepository.findByTokenWithOrder("expired-reconciliation"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(233L)).thenReturn(Optional.of(link.getOrder()));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "expired-reconciliation",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.9",
                        "JUnit UA"
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
        verify(paymentLinkRepository, never()).save(link);
    }

    @Test
    void initMovesExpiredReservationToRecoverableQuarantineBeforePayableValidation() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(235L);
        link.setOrder(order(235L, "ООО Истекший Init", BigDecimal.valueOf(250)));
        link.setToken("expired-init-reservation");
        link.setAmountKopecks(25000L);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setBankInitNonce("expired-init-nonce");
        link.setBankInitLeaseUntil(LocalDateTime.now().minusMinutes(1));
        link.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(paymentLinkRepository.findByTokenWithOrder("expired-init-reservation"))
                .thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("expired-init-reservation"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(235L)).thenReturn(Optional.of(link.getOrder()));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "expired-init-reservation",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.9",
                        "JUnit UA"
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertNull(link.getBankInitNonce());
        assertNull(link.getBankInitLeaseUntil());
        assertTrue(link.getLastError().startsWith("bank_init_ambiguous:"));
        verify(paymentLinkRepository).save(link);
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void initDoesNotCreateAnotherBankPaymentAfterAuthorization() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setId(234L);
        link.setOrder(order(234L, "ООО Авторизовано", BigDecimal.valueOf(250)));
        link.setToken("authorized-payment");
        link.setAmountKopecks(25000L);
        link.setStatus(PaymentLinkStatus.AUTHORIZED);
        link.setTbankPaymentId("payment-234");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(paymentLinkRepository.findByTokenWithOrder("authorized-payment"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(234L)).thenReturn(Optional.of(link.getOrder()));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "authorized-payment",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.9",
                        "JUnit UA"
                )
        );

        assertEquals(409, error.getStatusCode().value());
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void initRejectsSecondPublicTokenWhileAnotherOrderLinkHasActiveReservation() {
        PaymentLinkService service = service(properties());
        Order order = order(236L, "ООО Два публичных токена", BigDecimal.valueOf(250));
        PaymentLink first = new PaymentLink();
        first.setId(2361L);
        first.setOrder(order);
        first.setToken("first-public-token");
        first.setStatus(PaymentLinkStatus.CREATED);
        first.setAmountKopecks(25000L);
        first.setBankInitNonce("first-active-init");
        first.setBankInitLeaseUntil(LocalDateTime.now().plusMinutes(1));
        first.setExpiresAt(LocalDateTime.now().plusDays(1));
        PaymentLink second = new PaymentLink();
        second.setId(2362L);
        second.setOrder(order);
        second.setToken("second-public-token");
        second.setStatus(PaymentLinkStatus.CREATED);
        second.setAmountKopecks(25000L);
        second.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("second-public-token"))
                .thenReturn(Optional.of(second));
        when(paymentLinkRepository.findByTokenForUpdate("second-public-token"))
                .thenReturn(Optional.of(second));
        when(orderRepository.findByIdForCounterUpdate(236L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(236L)).thenReturn(List.of(first, second));

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "second-public-token",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.9",
                        "JUnit UA"
                )
        );

        assertEquals(409, conflict.getStatusCode().value());
        assertNull(second.getBankInitNonce());
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void initUsesShortBankRedirectDueInsteadOfPublicLinkTtl() {
        TbankPaymentProperties properties = properties();
        properties.setLinkTtl(Duration.ofDays(90));
        properties.setRedirectDue(Duration.ofDays(7));
        PaymentLinkService service = service(properties);
        Order order = order(30L, "ООО Платеж", BigDecimal.valueOf(123.45));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        org.mockito.Mockito.lenient().when(paymentLinkRepository.findByTokenWithOrder("token"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(30L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class))).thenAnswer(invocation -> new TbankInitResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "NEW",
                "payment-1",
                invocation.getArgument(1, TbankInitCommand.class).orderId(),
                12345L,
                "https://securepay.tinkoff.ru/pay"
        ));

        OffsetDateTime minExpected = OffsetDateTime.now(ZoneId.of("Europe/Moscow")).plusDays(7).minusSeconds(2);
        PublicPaymentInitResponse response = service.init(
                "token",
                "PAYER@EXAMPLE.RU",
                true,
                true,
                true,
                "203.0.113.7",
                "JUnit UA"
        );

        ArgumentCaptor<TbankInitCommand> captor = ArgumentCaptor.forClass(TbankInitCommand.class);
        ArgumentCaptor<TbankPaymentProfile> profileCaptor = ArgumentCaptor.forClass(TbankPaymentProfile.class);
        verify(tbankClient).init(profileCaptor.capture(), captor.capture());
        TbankInitCommand command = captor.getValue();
        OffsetDateTime maxExpected = OffsetDateTime.now(ZoneId.of("Europe/Moscow")).plusDays(7).plusSeconds(2);

        assertEquals(TbankPaymentProfile.PRIMARY_CODE, profileCaptor.getValue().code());
        assertEquals("payer@example.ru", command.email());
        assertTrue(command.redirectDueDate().isAfter(minExpected));
        assertTrue(command.redirectDueDate().isBefore(maxExpected));
        assertEquals("203.0.113.7", link.getConsentIp());
        assertEquals("JUnit UA", link.getConsentUserAgent());
        assertEquals("https://example.ru/offer", link.getOfferDocumentUrl());
        assertEquals("https://example.ru/privacy", link.getPrivacyDocumentUrl());
        assertEquals("https://example.ru/receipt-consent", link.getReceiptConsentDocumentUrl());
        assertNotNull(link.getOfferConsentAt());
        assertNotNull(link.getPrivacyConsentAt());
        assertNotNull(link.getReceiptConsentAt());
        assertEquals(PaymentLinkStatus.INITIATED, link.getStatus());
        assertEquals("https://securepay.tinkoff.ru/pay", link.getPaymentUrl());
        assertEquals("https://securepay.tinkoff.ru/pay", response.paymentUrl());
    }

    @Test
    void bankInitResponseAfterConcurrentPrepaymentPreservesMarker() {
        PaymentLinkService service = service(properties());
        Order order = order(304L, "ООО Предоплата во время Init", BigDecimal.valueOf(123.45));
        PaymentLink link = payableLink(order, "concurrent-prepayment-init", 12345L);
        when(paymentLinkRepository.findByTokenWithOrder(link.getToken())).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(304L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> {
                    TbankInitCommand command = invocation.getArgument(1, TbankInitCommand.class);
                    link.setStatus(PaymentLinkStatus.CONFIRMED);
                    link.setTbankPaymentId("payment-prepaid-init");
                    link.setLastError("prepaid_waiting_order_completion");
                    return new TbankInitResponse(
                            true,
                            "0",
                            null,
                            null,
                            "terminal",
                            "CONFIRMED",
                            "payment-prepaid-init",
                            command.orderId(),
                            12345L,
                            "https://securepay.tinkoff.ru/pay"
                    );
                });

        PublicPaymentInitResponse response = service.init(
                link.getToken(),
                "payer@example.ru",
                true,
                true,
                true,
                "203.0.113.7",
                "JUnit UA"
        );

        assertEquals("CONFIRMED", response.status());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
    }

    @Test
    void matchingConfirmedWebhookBeforeInitResponseDoesNotReplayOrderTransition() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        properties.setApplyConfirmedPayments(true);
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(307L, "ООО Webhook раньше Init", BigDecimal.valueOf(123.45));
        PaymentLink link = payableLink(order, "webhook-before-init-response", 12345L);
        link.setId(3070L);

        when(paymentProfileService.isTestTerminal("terminal")).thenReturn(false);
        when(paymentLinkRepository.findByTokenWithOrder(link.getToken())).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate(link.getToken())).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByOrderIdForUpdate(307L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByTbankOrderIdWithOrder(anyString())).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(3070L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(307L)).thenReturn(Optional.of(order));
        doAnswer(invocation -> {
            if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже оплачен");
            }
            return null;
        }).when(orderPaymentIntegrityService).assertPaymentCycleAllowed(order);
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> {
                    TbankInitCommand command = invocation.getArgument(1, TbankInitCommand.class);
                    assertNotNull(link.getBankInitNonce());
                    service.handleTbankWebhook(signedWebhook(
                            signer,
                            command.orderId(),
                            "payment-webhook-first",
                            "CONFIRMED",
                            12345L
                    ));
                    assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
                    assertNotNull(link.getBankInitNonce());
                    return new TbankInitResponse(
                            true,
                            "0",
                            null,
                            null,
                            "terminal",
                            "CONFIRMED",
                            "payment-webhook-first",
                            command.orderId(),
                            12345L,
                            "https://securepay.tinkoff.ru/pay"
                    );
                });

        PublicPaymentInitResponse response = service.init(
                link.getToken(),
                "payer@example.ru",
                true,
                true,
                true,
                "203.0.113.7",
                "JUnit UA"
        );

        assertEquals("CONFIRMED", response.status());
        assertEquals("payment-webhook-first", response.paymentId());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("payment-webhook-first", link.getTbankPaymentId());
        assertNull(link.getBankInitNonce());
        assertNull(link.getBankInitLeaseUntil());
        verify(orderTransactionService, times(1)).handlePaymentStatus(order);
    }

    @Test
    void ambiguousBankInitFailureAfterConcurrentPrepaymentPreservesMarker() {
        PaymentLinkService service = service(properties());
        Order order = order(305L, "ООО Предоплата при ошибке Init", BigDecimal.valueOf(123.45));
        PaymentLink link = payableLink(order, "concurrent-prepayment-init-failure", 12345L);
        when(paymentLinkRepository.findByTokenWithOrder(link.getToken())).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(305L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> {
                    link.setStatus(PaymentLinkStatus.CONFIRMED);
                    link.setTbankPaymentId("payment-prepaid-init-failure");
                    link.setLastError("prepaid_waiting_order_completion");
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider timeout");
                });

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        link.getToken(),
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.7",
                        "JUnit UA"
                )
        );

        assertEquals(502, failure.getStatusCode().value());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
        assertNull(link.getBankInitNonce());
    }

    @Test
    void bankInitResponseAfterConcurrentOrderSettlementIsQuarantined() {
        PaymentLinkService service = service(properties());
        Order order = order(306L, "ООО Закрытие во время Init", BigDecimal.valueOf(123.45));
        PaymentLink link = payableLink(order, "concurrent-order-settlement", 12345L);
        when(paymentLinkRepository.findByTokenWithOrder(link.getToken())).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(306L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> {
                    doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже оплачен"))
                            .when(orderPaymentIntegrityService)
                            .assertPaymentCycleAllowed(order);
                    TbankInitCommand command = invocation.getArgument(1, TbankInitCommand.class);
                    return new TbankInitResponse(
                            true,
                            "0",
                            null,
                            null,
                            "terminal",
                            "NEW",
                            "payment-after-settlement",
                            command.orderId(),
                            12345L,
                            "https://securepay.tinkoff.ru/pay"
                    );
                });

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        link.getToken(),
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.7",
                        "JUnit UA"
                )
        );

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertEquals("payment-after-settlement", link.getTbankPaymentId());
        assertTrue(link.getLastError().contains("order_settled_during_init_response"));
    }

    @Test
    void initRejectsUnsafeProviderUrlAndRetainsPaymentIdForReconciliation() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(301L, "ООО Опасная ссылка", BigDecimal.valueOf(123.45)));
        link.setToken("unsafe-provider-url");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("unsafe-provider-url")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(301L)).thenReturn(Optional.of(link.getOrder()));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class))).thenAnswer(invocation -> new TbankInitResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "NEW",
                "payment-unsafe-url",
                invocation.getArgument(1, TbankInitCommand.class).orderId(),
                12345L,
                "javascript:alert(document.cookie)"
        ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "unsafe-provider-url",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.7",
                        "JUnit UA"
                )
        );

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertEquals("payment-unsafe-url", link.getTbankPaymentId());
        assertNull(link.getPaymentUrl());
        assertTrue(link.getLastError().startsWith("unsafe_tbank_payment_url:"));
        verify(paymentLinkRepository, times(3)).save(link);

        ResponseStatusException retryException = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "unsafe-provider-url",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.7",
                        "JUnit UA"
                )
        );
        assertEquals(409, retryException.getStatusCode().value());
        verify(tbankClient, times(1)).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void initQuarantinesUnsafeCachedProviderUrlWithoutCreatingAnotherPayment() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(303L, "ООО Старая опасная ссылка", BigDecimal.valueOf(123.45)));
        link.setToken("unsafe-cached-provider-url");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setTbankPaymentId("cached-payment-id");
        link.setPaymentUrl("javascript:cached-recipient()");
        link.setExpiresAt(LocalDateTime.now().plusDays(90));
        when(paymentLinkRepository.findByTokenWithOrder("unsafe-cached-provider-url"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(303L)).thenReturn(Optional.of(link.getOrder()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.init(
                        "unsafe-cached-provider-url",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "203.0.113.7",
                        "JUnit UA"
                )
        );

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertNull(link.getPaymentUrl());
        assertTrue(link.getLastError().startsWith("unsafe_cached_tbank_payment_url:"));
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void initSbpQuarantinesUnsafeFallbackUrlAfterBankPaymentWasCreated() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(302L, "ООО Опасная резервная ссылка", BigDecimal.valueOf(123.45)));
        link.setToken("unsafe-sbp-provider-url");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));
        when(paymentLinkRepository.findByTokenWithOrder("unsafe-sbp-provider-url"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(302L)).thenReturn(Optional.of(link.getOrder()));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> new TbankInitResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "NEW",
                        "payment-unsafe-sbp-url",
                        invocation.getArgument(1, TbankInitCommand.class).orderId(),
                        12345L,
                        "data:text/html,<script>alert(1)</script>"
                ));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.initSbp(
                        "unsafe-sbp-provider-url",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        null,
                        "203.0.113.8",
                        "JUnit UA"
                )
        );

        assertEquals(502, error.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertEquals(PaymentMethod.SBP_QR, link.getPaymentMethod());
        assertEquals("payment-unsafe-sbp-url", link.getTbankPaymentId());
        assertNull(link.getPaymentUrl());
        assertTrue(link.getLastError().startsWith("unsafe_tbank_payment_url:"));
        verify(tbankClient, never()).getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class));
        verify(paymentLinkRepository, times(3)).save(link);
    }

    @Test
    void initRejectsPaymentWithoutRequiredConsents() {
        PaymentLinkService service = service(properties());
        Order order = order(31L, "ООО Без согласий", BigDecimal.valueOf(123.45));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.init("token", "PAYER@EXAMPLE.RU", true, false, true, "203.0.113.7", "JUnit UA")
        );
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void initSbpCreatesPaymentPayloadAfterBankInitAndStoresPaymentMethod() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(32L, "ООО СБП", BigDecimal.valueOf(321));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(32L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class))).thenAnswer(invocation -> new TbankInitResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "NEW",
                "payment-sbp",
                invocation.getArgument(1, TbankInitCommand.class).orderId(),
                32100L,
                "https://securepay.tinkoff.ru/pay"
        ));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class))).thenReturn(new TbankGetQrResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "payment-sbp",
                "https://qr.nspk.ru/AS100000000111"
        ));

        PublicPaymentInitResponse response = service.initSbp(
                "token",
                "SBP@EXAMPLE.RU",
                true,
                true,
                true,
                null,
                "203.0.113.8",
                "JUnit UA"
        );

        ArgumentCaptor<TbankGetQrCommand> qrCaptor = ArgumentCaptor.forClass(TbankGetQrCommand.class);
        verify(tbankClient).getQr(any(TbankPaymentProfile.class), qrCaptor.capture());
        assertEquals("payment-sbp", qrCaptor.getValue().paymentId());
        assertEquals("PAYLOAD", qrCaptor.getValue().dataType());
        assertEquals(null, qrCaptor.getValue().bankId());
        assertEquals(PaymentMethod.SBP_QR, link.getPaymentMethod());
        assertEquals("https://qr.nspk.ru/AS100000000111", link.getSbpQrPayload());
        assertEquals("PAYLOAD", link.getSbpQrDataType());
        assertNotNull(link.getSbpQrCreatedAt());
        assertEquals("SBP_QR", response.method());
        assertEquals("https://qr.nspk.ru/AS100000000111", response.qrPayload());
        assertEquals("sbp@example.ru", link.getPayerEmail());
        verify(paymentLinkRepository, times(3)).save(link);
    }

    @Test
    void initSbpWithBankIdRequestsBankDeeplink() {
        PaymentLinkService service = service(properties());
        Order order = order(33L, "ООО СБП Банк", BigDecimal.valueOf(321));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token-bank");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-sbp-bank");
        link.setPaymentUrl("https://securepay.tinkoff.ru/pay");
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token-bank")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(33L)).thenReturn(Optional.of(order));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class))).thenReturn(new TbankGetQrResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "payment-sbp-bank",
                "bankapp://pay/payment-sbp-bank"
        ));

        PublicPaymentInitResponse response = service.initSbp(
                "token-bank",
                "SBP@EXAMPLE.RU",
                true,
                true,
                true,
                "bank-1",
                "203.0.113.8",
                "JUnit UA"
        );

        ArgumentCaptor<TbankGetQrCommand> qrCaptor = ArgumentCaptor.forClass(TbankGetQrCommand.class);
        verify(tbankClient).getQr(any(TbankPaymentProfile.class), qrCaptor.capture());
        assertEquals("payment-sbp-bank", qrCaptor.getValue().paymentId());
        assertEquals("PAYLOAD", qrCaptor.getValue().dataType());
        assertEquals("bank-1", qrCaptor.getValue().bankId());
        assertEquals("bankapp://pay/payment-sbp-bank", response.qrPayload());
        assertEquals("bankapp://pay/payment-sbp-bank", link.getSbpQrPayload());
    }

    @Test
    void initSbpQuarantinesFreshUnsafePayloadWithoutReturningIt() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(331L, "ООО Опасный СБП", BigDecimal.valueOf(321)));
        link.setToken("unsafe-sbp");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-unsafe-sbp");
        link.setPaymentUrl("https://securepay.tinkoff.ru/pay");
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("unsafe-sbp")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(331L)).thenReturn(Optional.of(link.getOrder()));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class))).thenReturn(new TbankGetQrResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "payment-unsafe-sbp",
                "data:text/html,<script>alert(1)</script>"
        ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.initSbp(
                        "unsafe-sbp",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        "bank-1",
                        "203.0.113.8",
                        "JUnit UA"
                )
        );

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertNull(link.getPaymentUrl());
        assertNull(link.getSbpQrPayload());
        assertTrue(link.getLastError().startsWith("unsafe_tbank_sbp_payload:"));
        verify(paymentLinkRepository, times(3)).save(link);
    }

    @Test
    void getQrResponseAfterConcurrentPrepaymentPreservesMarker() {
        PaymentLinkService service = service(properties());
        Order order = order(307L, "ООО Предоплата во время GetQr", BigDecimal.valueOf(321));
        PaymentLink link = payableLink(order, "concurrent-prepayment-qr", 32100L);
        when(paymentLinkRepository.findByTokenWithOrder(link.getToken())).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(307L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> new TbankInitResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "NEW",
                        "payment-prepaid-qr",
                        invocation.getArgument(1, TbankInitCommand.class).orderId(),
                        32100L,
                        "https://securepay.tinkoff.ru/pay"
                ));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class)))
                .thenAnswer(invocation -> {
                    link.setStatus(PaymentLinkStatus.CONFIRMED);
                    link.setLastError("prepaid_waiting_order_completion");
                    return new TbankGetQrResponse(
                            true,
                            "0",
                            null,
                            null,
                            "terminal",
                            "payment-prepaid-qr",
                            "https://qr.nspk.ru/AS100000000307"
                    );
                });

        PublicPaymentInitResponse response = service.initSbp(
                link.getToken(),
                "payer@example.ru",
                true,
                true,
                true,
                null,
                "203.0.113.8",
                "JUnit UA"
        );

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
        assertEquals("https://qr.nspk.ru/AS100000000307", response.qrPayload());
    }

    @Test
    void getQrFailureAfterConcurrentPrepaymentPreservesMarker() {
        PaymentLinkService service = service(properties());
        Order order = order(308L, "ООО Предоплата при ошибке GetQr", BigDecimal.valueOf(321));
        PaymentLink link = payableLink(order, "concurrent-prepayment-qr-failure", 32100L);
        when(paymentLinkRepository.findByTokenWithOrder(link.getToken())).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(308L)).thenReturn(Optional.of(order));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class)))
                .thenAnswer(invocation -> new TbankInitResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "NEW",
                        "payment-prepaid-qr-failure",
                        invocation.getArgument(1, TbankInitCommand.class).orderId(),
                        32100L,
                        "https://securepay.tinkoff.ru/pay"
                ));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class)))
                .thenAnswer(invocation -> {
                    link.setStatus(PaymentLinkStatus.CONFIRMED);
                    link.setLastError("prepaid_waiting_order_completion");
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GetQr timeout");
                });

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.initSbp(
                        link.getToken(),
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        null,
                        "203.0.113.8",
                        "JUnit UA"
                )
        );

        assertEquals(502, failure.getStatusCode().value());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("prepaid_waiting_order_completion", link.getLastError());
        assertNull(link.getBankInitNonce());
    }

    @Test
    void initSbpQuarantinesUnsafeCachedPayloadWithoutRefreshingIt() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(333L, "ООО Старый опасный СБП", BigDecimal.valueOf(321)));
        link.setToken("unsafe-cached-sbp");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankPaymentId("payment-unsafe-cached-sbp");
        link.setPaymentUrl("https://securepay.tinkoff.ru/pay");
        link.setSbpQrPayload("data:text/html,<script>alert(1)</script>");
        link.setExpiresAt(LocalDateTime.now().plusDays(90));
        when(paymentLinkRepository.findByTokenWithOrder("unsafe-cached-sbp"))
                .thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(333L)).thenReturn(Optional.of(link.getOrder()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.initSbp(
                        "unsafe-cached-sbp",
                        "payer@example.ru",
                        true,
                        true,
                        true,
                        null,
                        "203.0.113.8",
                        "JUnit UA"
                )
        );

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertNull(link.getPaymentUrl());
        assertNull(link.getSbpQrPayload());
        assertTrue(link.getLastError().startsWith("unsafe_cached_tbank_sbp_payload:"));
        verify(tbankClient, never()).getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class));
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void genericSbpRequestDoesNotReuseBankSpecificCachedDeeplink() {
        PaymentLinkService service = service(properties());
        PaymentLink link = new PaymentLink();
        link.setOrder(order(332L, "ООО Новый QR", BigDecimal.valueOf(321)));
        link.setToken("generic-after-bank");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankPaymentId("payment-generic-after-bank");
        link.setPaymentUrl("https://securepay.tinkoff.ru/pay");
        link.setSbpQrPayload("bank100000000111://qr.nspk.ru/AS100000000111");
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("generic-after-bank")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(332L)).thenReturn(Optional.of(link.getOrder()));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class))).thenReturn(new TbankGetQrResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "payment-generic-after-bank",
                "https://qr.nspk.ru/AS100000000222"
        ));

        PublicPaymentInitResponse response = service.initSbp(
                "generic-after-bank",
                "payer@example.ru",
                true,
                true,
                true,
                null,
                "203.0.113.8",
                "JUnit UA"
        );

        assertEquals("https://qr.nspk.ru/AS100000000222", response.qrPayload());
        assertEquals("https://qr.nspk.ru/AS100000000222", link.getSbpQrPayload());
        verify(tbankClient).getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class));
    }

    @Test
    void manualCardPaymentForOrderCancelsNewBankSessionThenSettlesAndIsIdempotent() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25047L, "Мастер на дом", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5208L, order, 100_000L);

        when(orderRepository.findByIdForCounterUpdate(25047L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25047L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5208L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5208L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5208")))
                .thenReturn(tbankState("NEW", "payment-5208", "order-5208", 100_000L));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true, "0", null, null, "terminal", "CANCELED",
                        "payment-5208", "order-5208", 100_000L, 100_000L, 0L
                ));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25047L,
                true,
                true,
                100_000L,
                "04.08 20:40, карта *1234, перевод за заказ",
                null,
                "manager",
                authentication
        );
        service.confirmPaidByManualCardTransferForOrder(
                25047L,
                true,
                true,
                100_000L,
                "повтор запроса",
                null,
                "manager",
                authentication
        );

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertEquals("CANCELED", link.getProviderTerminalStatus());
        assertNull(link.getConfirmedAmountKopecks());
        assertNull(link.getManualConfirmedBy());
        assertNull(link.getManualConfirmedAt());
        assertTrue(link.getManualComment().startsWith("Оплачено переводом на карту после отмены T-Bank"));
        assertTrue(link.getLastError().startsWith("manual_card_payment_completed:"));
        assertNull(link.getBankCancelNonce());
        assertNull(link.getBankCancelOriginStatus());
        ArgumentCaptor<PaymentLink> savedLinks = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, atLeastOnce()).save(savedLinks.capture());
        PaymentLink manualEvidence = savedLinks.getAllValues().stream()
                .filter(candidate -> candidate != link)
                .filter(candidate -> candidate.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK)
                .filter(candidate -> candidate.getStatus() == PaymentLinkStatus.CONFIRMED)
                .findFirst()
                .orElseThrow();
        assertEquals(100_000L, manualEvidence.getConfirmedAmountKopecks());
        assertEquals("manager", manualEvidence.getManualConfirmedBy());
        assertNotNull(manualEvidence.getManualConfirmedAt());
        assertEquals(PaymentReceiptStatus.PENDING, manualEvidence.getReceiptStatus());
        verify(tbankClient, times(1)).getState(any(TbankPaymentProfile.class), eq("payment-5208"));
        verify(tbankClient, times(1)).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, times(1)).handlePaymentStatus(order);
        verify(paymentInvoiceRetryScheduler, times(1)).cancelPaymentAutomation(
                25047L,
                "Заказ оплачен переводом на карту; T-Bank сессия закрыта"
        );
        verify(managerAccessService, times(12)).requireOrderAccess(25047L, authentication);
    }

    @Test
    void managerManualCardReportCancelsFormShowedAndDoesNotRequestSecondOwnerReview() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25070L, "Оплата по номеру телефона", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5270L, order, 100_000L);

        when(orderRepository.findByIdForCounterUpdate(25070L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25070L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5270L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5270L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> {
            PaymentLink saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(9270L);
            }
            return saved;
        });
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5270")))
                .thenReturn(tbankState("FORM_SHOWED", "payment-5270", "order-5270", 100_000L));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true, "0", null, null, "terminal", "CANCELED",
                        "payment-5270", "order-5270", 100_000L, 100_000L, 0L
                ));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.reportPaidByManualCardTransferForOrder(
                25070L,
                "Клиент оплатил переводом по номеру телефона",
                "manager@example.ru",
                authentication
        );

        assertTrue(link.getManualComment().startsWith("Заявлено менеджером: оплата переводом"));
        verifyNoInteractions(manualCardPaymentReviewNotificationService);
        verify(tbankClient).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void managerManualCardReportRequiresExplicitRecipientWhenActualRecipientAccountingIsEnabled() {
        PaymentLinkService service = service(properties());
        Order order = order(25071L, "Требуется получатель", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5271L, order, 100_000L);
        when(orderRepository.findByIdForCounterUpdate(25071L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25071L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5271L)).thenReturn(Optional.of(link));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.reportPaidByManualCardTransferForOrder(
                        25071L, "Клиент перевёл напрямую", "manager@example.ru", authentication
                ));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(actualPaymentAttributionService, never()).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
    }

    @Test
    void preCutoverHistoricalBankRouteContextOffersOnlyLegacySettlementOption() {
        PaymentLinkService service = service(properties());
        Order order = order(25145L, "Мир тентов", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5245L, order, 100_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setProviderTerminalStatus("DEADLINE_EXPIRED");
        link.setCreatedAt(LocalDateTime.of(2026, 8, 19, 12, 0));
        when(contractorPaymentRuntimeSwitch.completionAccountingActivatedAt())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 20, 22, 30)));
        when(orderRepository.findByIdForCounterUpdate(25145L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25145L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5245L)).thenReturn(Optional.of(link));

        ManualCardPaymentContextResponse context = service.manualCardPaymentContextForOrder(25145L, authentication);

        assertEquals(25145L, context.orderId());
        assertEquals(1, context.candidates().size());
        assertEquals("LEGACY_PRE_CUTOVER_MANUAL_CARD", context.originalRecipient().key());
        assertEquals("Историческая оплата до запуска", context.originalRecipient().displayName());
        assertNull(context.originalRecipient().recipientType());
        assertTrue(context.originalRecipient().effectText().contains("новые выплаты"));
        verifyNoInteractions(actualPaymentAttributionService);
    }

    @Test
    void preCutoverHistoricalBankRouteReportCompletesOrderWithoutLiveActualAttribution() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25146L, "Дали", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5246L, order, 100_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setProviderTerminalStatus("DEADLINE_EXPIRED");
        link.setCreatedAt(LocalDateTime.of(2026, 8, 19, 12, 0));
        when(contractorPaymentRuntimeSwitch.completionAccountingActivatedAt())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 20, 22, 30)));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        when(orderRepository.findByIdForCounterUpdate(25146L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25146L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5246L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5246L)).thenReturn(Optional.of(link));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.reportPaidByManualCardTransferForOrder(
                25146L,
                "Оплата пришла до запуска на старые реквизиты",
                null,
                null,
                null,
                "LEGACY_PRE_CUTOVER_MANUAL_CARD",
                "manager@example.ru",
                authentication
        );

        assertTrue(link.getLastError().startsWith("manual_card_payment_completed:"));
        verify(orderTransactionService).handlePaymentStatus(order);
        verify(actualPaymentAttributionService, never()).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        verify(actualPaymentAttributionService, never()).recordPaymentLinkFinalAttribution(any(), any(), any());
        verify(taskReceiptIntegrationService, never()).settle(any(PaymentLink.class), anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void preCutoverExpiredManualTaskWithoutLedgerBindingUsesHistoricalRecipientAndCompletes() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(24320L, "Starway", BigDecimal.valueOf(2400));
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(14L);
        PaymentLink link = new PaymentLink();
        link.setId(5951L);
        link.setOrder(order);
        link.setAmountKopecks(240_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setManualRecipientName("Екатерина");
        link.setCreatedAt(LocalDateTime.of(2026, 8, 9, 20, 59));
        link.setUpdatedAt(LocalDateTime.of(2026, 8, 11, 15, 28));
        when(contractorPaymentRuntimeSwitch.completionAccountingActivatedAt())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 20, 21, 3)));
        when(orderRepository.findByIdForCounterUpdate(24320L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(24320L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5951L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5951L)).thenReturn(Optional.of(link));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        ManualCardPaymentContextResponse context = service.manualCardPaymentContextForOrder(
                24320L, authentication);

        assertEquals("LEGACY_PRE_CUTOVER_MANUAL_CARD", context.originalRecipient().key());
        assertEquals("Екатерина", context.originalRecipient().displayName());
        assertEquals(1, context.candidates().size());

        service.reportPaidByManualCardTransferForOrder(
                24320L,
                "Старая оплата поступила по ранее выданным реквизитам",
                null,
                null,
                null,
                "LEGACY_PRE_CUTOVER_MANUAL_CARD",
                "manager@example.ru",
                authentication
        );

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals(240_000L, link.getConfirmedAmountKopecks());
        verify(orderTransactionService).handlePaymentStatus(order);
        verify(actualPaymentAttributionService, never()).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        verify(actualPaymentAttributionService, never())
                .recordPaymentLinkFinalAttribution(any(), any(), any());
        verify(taskReceiptIntegrationService, never()).settle(
                any(PaymentLink.class), anyString(), anyLong(), anyString(), any(), any());
        verify(manualPaymentTaskService, never()).completeIfConfirmedTargetReached(any());
    }

    @Test
    void postCutoverBankRouteCannotUseHistoricalManualCardSettlementKey() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25147L, "Новая оплата", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5247L, order, 100_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setProviderTerminalStatus("DEADLINE_EXPIRED");
        link.setCreatedAt(LocalDateTime.of(2026, 8, 21, 12, 0));
        when(contractorPaymentRuntimeSwitch.completionAccountingActivatedAt())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 20, 22, 30)));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        when(orderRepository.findByIdForCounterUpdate(25147L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25147L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5247L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5247L)).thenReturn(Optional.of(link));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.reportPaidByManualCardTransferForOrder(
                        25147L,
                        "Новая оплата не должна идти историческим путём",
                        null,
                        null,
                        null,
                        "LEGACY_PRE_CUTOVER_MANUAL_CARD",
                        "manager@example.ru",
                        authentication
                ));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(actualPaymentAttributionService, never()).recordPaymentLinkFinalAttribution(any(), any(), any());
    }

    @Test
    void managerManualCardReportRequiresReasonBeforeReadingPaymentData() {
        PaymentLinkService service = service(properties());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reportPaidByManualCardTransferForOrder(25070L, "  ", "manager", authentication)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(orderRepository, never()).findByIdForCounterUpdate(anyLong());
        verify(paymentLinkRepository, never()).findByOrderIdForUpdate(anyLong());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
    }

    @Test
    void manualCardPaymentSelectsCurrentActiveRouteAndIgnoresProviderExpiredHistory() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25057L, "Активная и старые ссылки", BigDecimal.valueOf(1000));
        PaymentLink active = initiatedBankLink(5220L, order, 100_000L);
        active.setUpdatedAt(LocalDateTime.now());
        PaymentLink oldExpired = initiatedBankLink(5219L, order, 100_000L);
        oldExpired.setStatus(PaymentLinkStatus.EXPIRED);
        oldExpired.setProviderTerminalStatus("DEADLINE_EXPIRED");
        oldExpired.setUpdatedAt(LocalDateTime.now().minusDays(10));

        when(orderRepository.findByIdForCounterUpdate(25057L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25057L)).thenReturn(List.of(oldExpired, active));
        when(paymentLinkRepository.findByIdWithOrder(5220L)).thenReturn(Optional.of(active));
        when(paymentLinkRepository.findByIdForUpdate(5220L)).thenReturn(Optional.of(active));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5220")))
                .thenReturn(tbankState("NEW", "payment-5220", "order-5220", 100_000L));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true, "0", null, null, "terminal", "CANCELED",
                        "payment-5220", "order-5220", 100_000L, 100_000L, 0L
                ));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25057L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertTrue(active.getLastError().startsWith("manual_card_payment_completed:"));
        assertEquals(PaymentLinkStatus.EXPIRED, oldExpired.getStatus());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5220"));
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), eq("payment-5219"));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void manualCardPaymentReconcilesLegacyExpiredHistoryBeforeCurrentRoute() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25059L, "Активная и локально истекшая ссылки", BigDecimal.valueOf(1000));
        PaymentLink active = initiatedBankLink(5223L, order, 100_000L);
        PaymentLink locallyExpired = initiatedBankLink(5224L, order, 100_000L);
        locallyExpired.setStatus(PaymentLinkStatus.EXPIRED);

        when(orderRepository.findByIdForCounterUpdate(25059L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25059L))
                .thenReturn(List.of(locallyExpired, active));
        when(paymentLinkRepository.findByIdWithOrder(5224L)).thenReturn(Optional.of(locallyExpired));
        when(paymentLinkRepository.findByIdWithOrder(5223L)).thenReturn(Optional.of(active));
        when(paymentLinkRepository.findByIdForUpdate(5224L)).thenReturn(Optional.of(locallyExpired));
        when(paymentLinkRepository.findByIdForUpdate(5223L)).thenReturn(Optional.of(active));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5224")))
                .thenReturn(tbankState("DEADLINE_EXPIRED", "payment-5224", "order-5224", 100_000L));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5223")))
                .thenReturn(tbankState("NEW", "payment-5223", "order-5223", 100_000L));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true, "0", null, null, "terminal", "CANCELED",
                        "payment-5223", "order-5223", 100_000L, 100_000L, 0L
                ));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25059L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertEquals("DEADLINE_EXPIRED", locallyExpired.getProviderTerminalStatus());
        assertEquals(PaymentLinkStatus.CANCELED, active.getStatus());
        assertEquals("CANCELED", active.getProviderTerminalStatus());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5224"));
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5223"));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NEW", "CONFIRMED"})
    void manualCardPaymentBlocksWhenLegacyExpiredRouteIsStillActiveOrPaid(String providerStatus) throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25061L, "Неоднозначная старая ссылка", BigDecimal.valueOf(1000));
        PaymentLink current = initiatedBankLink(5226L, order, 100_000L);
        PaymentLink legacy = initiatedBankLink(5227L, order, 100_000L);
        legacy.setStatus(PaymentLinkStatus.EXPIRED);

        when(orderRepository.findByIdForCounterUpdate(25061L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25061L)).thenReturn(List.of(legacy, current));
        when(paymentLinkRepository.findByIdWithOrder(5227L)).thenReturn(Optional.of(legacy));
        when(paymentLinkRepository.findByIdForUpdate(5227L)).thenReturn(Optional.of(legacy));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5227")))
                .thenReturn(tbankState(providerStatus, "payment-5227", "order-5227", 100_000L));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25061L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        if ("CONFIRMED".equals(providerStatus)) {
            assertEquals(PaymentLinkStatus.AMOUNT_MISMATCH, legacy.getStatus());
        } else {
            assertEquals(PaymentLinkStatus.EXPIRED, legacy.getStatus());
        }
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), eq("payment-5226"));
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void manualCardPaymentBlocksWhenLegacyStatusLookupFails() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25062L, "Старая ссылка недоступна", BigDecimal.valueOf(1000));
        PaymentLink current = initiatedBankLink(5228L, order, 100_000L);
        PaymentLink legacy = initiatedBankLink(5229L, order, 100_000L);
        legacy.setStatus(PaymentLinkStatus.EXPIRED);

        when(orderRepository.findByIdForCounterUpdate(25062L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25062L)).thenReturn(List.of(legacy, current));
        when(paymentLinkRepository.findByIdWithOrder(5229L)).thenReturn(Optional.of(legacy));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5229")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider unavailable"));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25062L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
                )
        );

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        verify(paymentLinkRepository, never()).findByIdForUpdate(5229L);
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), eq("payment-5228"));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void manualCardPaymentReconcilesMultipleLegacyTerminalRoutesBeforeCurrentRoute() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25063L, "Несколько старых ссылок", BigDecimal.valueOf(1000));
        PaymentLink current = initiatedBankLink(5230L, order, 100_000L);
        PaymentLink firstLegacy = initiatedBankLink(5231L, order, 100_000L);
        firstLegacy.setStatus(PaymentLinkStatus.EXPIRED);
        PaymentLink secondLegacy = initiatedBankLink(5232L, order, 100_000L);
        secondLegacy.setStatus(PaymentLinkStatus.CANCELED);

        when(orderRepository.findByIdForCounterUpdate(25063L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25063L))
                .thenReturn(List.of(firstLegacy, secondLegacy, current));
        when(paymentLinkRepository.findByIdWithOrder(5231L)).thenReturn(Optional.of(firstLegacy));
        when(paymentLinkRepository.findByIdWithOrder(5232L)).thenReturn(Optional.of(secondLegacy));
        when(paymentLinkRepository.findByIdWithOrder(5230L)).thenReturn(Optional.of(current));
        when(paymentLinkRepository.findByIdForUpdate(5231L)).thenReturn(Optional.of(firstLegacy));
        when(paymentLinkRepository.findByIdForUpdate(5232L)).thenReturn(Optional.of(secondLegacy));
        when(paymentLinkRepository.findByIdForUpdate(5230L)).thenReturn(Optional.of(current));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5231")))
                .thenReturn(tbankState("DEADLINE_EXPIRED", "payment-5231", "order-5231", 100_000L));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5232")))
                .thenReturn(tbankState("REJECTED", "payment-5232", "order-5232", 100_000L));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5230")))
                .thenReturn(tbankState("NEW", "payment-5230", "order-5230", 100_000L));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true, "0", null, null, "terminal", "CANCELED",
                        "payment-5230", "order-5230", 100_000L, 100_000L, 0L
                ));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25063L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertEquals("DEADLINE_EXPIRED", firstLegacy.getProviderTerminalStatus());
        assertEquals(PaymentLinkStatus.REJECTED, secondLegacy.getStatus());
        assertEquals("REJECTED", secondLegacy.getProviderTerminalStatus());
        assertEquals("CANCELED", current.getProviderTerminalStatus());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5231"));
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5232"));
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5230"));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void manualCardPaymentRecoversExpiredManualRouteAndIgnoresUnissuedBankFailure() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25060L, "Обычный ручной маршрут", BigDecimal.valueOf(1000));
        PaymentLink manual = new PaymentLink();
        manual.setId(5225L);
        manual.setOrder(order);
        manual.setStatus(PaymentLinkStatus.EXPIRED);
        manual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        manual.setAmountKopecks(100_000L);
        manual.setManualComment("Проверен перевод на карту; ожидается закрытие T-Bank: тестовый комментарий");
        manual.setLastError("manual_card_payment_pending: test");

        PaymentLink unissuedFailure = new PaymentLink();
        unissuedFailure.setId(5226L);
        unissuedFailure.setOrder(order);
        unissuedFailure.setStatus(PaymentLinkStatus.FAILED);
        unissuedFailure.setPaymentMethod(PaymentMethod.BANK_FORM);
        unissuedFailure.setAmountKopecks(100_000L);
        unissuedFailure.setLastError("contractor_live_routing_missing_allocation:configured_but_blocked");

        PaymentLink staleManual = new PaymentLink();
        staleManual.setId(5227L);
        staleManual.setOrder(order);
        staleManual.setStatus(PaymentLinkStatus.EXPIRED);
        staleManual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        staleManual.setAmountKopecks(120_000L);
        staleManual.setUpdatedAt(LocalDateTime.now());

        when(orderRepository.findByIdForCounterUpdate(25060L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25060L))
                .thenReturn(List.of(unissuedFailure, staleManual, manual));
        when(paymentLinkRepository.findByIdWithOrder(5225L)).thenReturn(Optional.of(manual));
        when(paymentLinkRepository.findByIdForUpdate(5225L)).thenReturn(Optional.of(manual));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25060L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertEquals(PaymentLinkStatus.CONFIRMED, manual.getStatus());
        assertEquals(PaymentLinkStatus.FAILED, unissuedFailure.getStatus());
        assertEquals(PaymentLinkStatus.EXPIRED, staleManual.getStatus());
        assertEquals(100_000L, manual.getConfirmedAmountKopecks());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void managerOwnerSelectionCreatesApprovalWithoutReadingOrCancelingTbank() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25270L, "Старые реквизиты владельца", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5370L, order, 100_000L);
        when(orderRepository.findByIdForCounterUpdate(25270L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25270L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5370L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5370L)).thenReturn(Optional.of(link));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            PaymentLink frozen = invocation.getArgument(1);
            frozen.setManualActualRecipientType(ContractorRecipientType.OWNER);
            frozen.setManualActualRecipientProfileId(null);
            frozen.setManualActualCashDestinationKind(ContractorCashDestinationKind.OWNER);
            frozen.setManualActualReason(invocation.getArgument(5));
            frozen.setManualActualReceiptUrl(invocation.getArgument(6));
            frozen.setManualActualActor(invocation.getArgument(7));
            frozen.setManualActualRecipientFrozenAt(LocalDateTime.now());
            return null;
        }).when(actualPaymentAttributionService).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        when(ownerManualCardPaymentApprovalRepository.findByPaymentLinkIdForUpdate(5370L))
                .thenReturn(Optional.empty());
        when(ownerManualCardPaymentApprovalRepository.saveAndFlush(any(OwnerManualCardPaymentApproval.class)))
                .thenAnswer(invocation -> {
                    OwnerManualCardPaymentApproval approval = invocation.getArgument(0);
                    approval.setId(91L);
                    return approval;
                });

        var result = service.submitManagerManualCardPaymentForOrder(
                25270L,
                "Проверить перевод на старый Альфа-Банк владельца",
                null,
                ContractorRecipientType.OWNER,
                null,
                "OWNER",
                "manager@example.ru",
                authentication
        );

        assertEquals("OWNER_APPROVAL_PENDING", result.status());
        assertEquals(PaymentLinkStatus.INITIATED, link.getStatus());
        assertNull(link.getBankCancelNonce());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        ArgumentCaptor<ManualCardPaymentReviewNotificationService.OwnerApprovalRequest> request =
                ArgumentCaptor.forClass(ManualCardPaymentReviewNotificationService.OwnerApprovalRequest.class);
        verify(manualCardPaymentReviewNotificationService).notifyOwnerApprovalAfterCommit(request.capture());
        assertEquals(91L, request.getValue().approvalId());
        assertFalse(request.getValue().callbackToken().isBlank());
    }

    @Test
    void ownerTelegramApprovalRechecksCancelsAndCompletesOnlyOnce() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25271L, "Подтверждение владельца", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5371L, order, 100_000L);
        java.util.concurrent.atomic.AtomicReference<OwnerManualCardPaymentApproval> approvalRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(orderRepository.findByIdForCounterUpdate(25271L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25271L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5371L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5371L)).thenReturn(Optional.of(link));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            PaymentLink frozen = invocation.getArgument(1);
            if (frozen.getManualActualRecipientFrozenAt() == null) {
                frozen.setManualActualRecipientType(ContractorRecipientType.OWNER);
                frozen.setManualActualRecipientProfileId(null);
                frozen.setManualActualCashDestinationKind(ContractorCashDestinationKind.OWNER);
                frozen.setManualActualReason(invocation.getArgument(5));
                frozen.setManualActualReceiptUrl(invocation.getArgument(6));
                frozen.setManualActualActor(invocation.getArgument(7));
                frozen.setManualActualRecipientFrozenAt(LocalDateTime.now());
            }
            return null;
        }).when(actualPaymentAttributionService).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        when(ownerManualCardPaymentApprovalRepository.findByPaymentLinkIdForUpdate(5371L))
                .thenReturn(Optional.empty());
        when(ownerManualCardPaymentApprovalRepository.saveAndFlush(any(OwnerManualCardPaymentApproval.class)))
                .thenAnswer(invocation -> {
                    OwnerManualCardPaymentApproval approval = invocation.getArgument(0);
                    approval.setId(92L);
                    approvalRef.set(approval);
                    return approval;
                });
        when(ownerManualCardPaymentApprovalRepository.findByIdForUpdate(92L))
                .thenAnswer(invocation -> Optional.ofNullable(approvalRef.get()));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5371")))
                .thenReturn(tbankState("NEW", "payment-5371", "order-5371", 100_000L));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true, "0", null, null, "terminal", "CANCELED",
                        "payment-5371", "order-5371", 100_000L, 100_000L, 0L
                ));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.submitManagerManualCardPaymentForOrder(
                25271L, "Поступило владельцу", null,
                ContractorRecipientType.OWNER, null, "OWNER",
                "manager@example.ru", authentication
        );
        ArgumentCaptor<ManualCardPaymentReviewNotificationService.OwnerApprovalRequest> request =
                ArgumentCaptor.forClass(ManualCardPaymentReviewNotificationService.OwnerApprovalRequest.class);
        verify(manualCardPaymentReviewNotificationService).notifyOwnerApprovalAfterCommit(request.capture());
        User owner = new User();
        owner.setId(7L);
        owner.setUsername("owner@example.ru");
        owner.setActive(true);
        Role ownerRole = new Role();
        ownerRole.setName("ROLE_OWNER");
        owner.setRoles(List.of(ownerRole));
        when(authentication.getName()).thenReturn("owner@example.ru");

        var first = service.approveOwnerManualCardPayment(
                92L, request.getValue().callbackToken(), owner, authentication);
        var replay = service.approveOwnerManualCardPayment(
                92L, request.getValue().callbackToken(), owner, authentication);

        assertFalse(first.alreadyCompleted());
        assertTrue(replay.alreadyCompleted());
        assertEquals(OwnerManualCardPaymentApprovalStatus.CONFIRMED, approvalRef.get().getStatus());
        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        verify(tbankClient, times(1)).getState(any(TbankPaymentProfile.class), eq("payment-5371"));
        verify(tbankClient, times(1)).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, times(1)).handlePaymentStatus(order);
    }

    @Test
    void managerOwnerSelectionOnSpecialistRequisitesStillRequiresOwnerApproval() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25273L, "Клиент оплатил старые реквизиты владельца", BigDecimal.valueOf(1000));
        PaymentLink link = contractorManualLink(
                5373L, order, 100_000L, PaymentLinkStatus.MANUAL_REPORTED, 802L);
        when(orderRepository.findByIdForCounterUpdate(25273L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25273L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5373L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5373L)).thenReturn(Optional.of(link));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            PaymentLink frozen = invocation.getArgument(1);
            frozen.setManualActualRecipientType(ContractorRecipientType.OWNER);
            frozen.setManualActualRecipientProfileId(null);
            frozen.setManualActualCashDestinationKind(ContractorCashDestinationKind.OWNER);
            frozen.setManualActualReason(invocation.getArgument(5));
            frozen.setManualActualActor(invocation.getArgument(7));
            frozen.setManualActualRecipientFrozenAt(LocalDateTime.now());
            return null;
        }).when(actualPaymentAttributionService).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        when(ownerManualCardPaymentApprovalRepository.findByPaymentLinkIdForUpdate(5373L))
                .thenReturn(Optional.empty());
        when(ownerManualCardPaymentApprovalRepository.saveAndFlush(any(OwnerManualCardPaymentApproval.class)))
                .thenAnswer(invocation -> {
                    OwnerManualCardPaymentApproval approval = invocation.getArgument(0);
                    approval.setId(93L);
                    return approval;
                });

        var result = service.submitManagerManualCardPaymentForOrder(
                25273L, "Поступило на старый Альфа-Банк владельца", null,
                ContractorRecipientType.OWNER, null, "OWNER",
                "manager@example.ru", authentication
        );

        assertEquals("OWNER_APPROVAL_PENDING", result.status());
        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, link.getStatus());
        verify(manualCardPaymentReviewNotificationService).notifyOwnerApprovalAfterCommit(any());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void ownerTelegramApprovalCompletesSpecialistRequisitesRouteWithoutTbankCall() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25274L, "Старый счет владельца", BigDecimal.valueOf(1000));
        PaymentLink link = contractorManualLink(
                5374L, order, 100_000L, PaymentLinkStatus.MANUAL_REPORTED, 803L);
        java.util.concurrent.atomic.AtomicReference<OwnerManualCardPaymentApproval> approvalRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(orderRepository.findByIdForCounterUpdate(25274L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25274L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5374L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5374L)).thenReturn(Optional.of(link));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            PaymentLink frozen = invocation.getArgument(1);
            if (frozen.getManualActualRecipientFrozenAt() == null) {
                frozen.setManualActualRecipientType(ContractorRecipientType.OWNER);
                frozen.setManualActualRecipientProfileId(null);
                frozen.setManualActualCashDestinationKind(ContractorCashDestinationKind.OWNER);
                frozen.setManualActualReason(invocation.getArgument(5));
                frozen.setManualActualActor(invocation.getArgument(7));
                frozen.setManualActualRecipientFrozenAt(LocalDateTime.now());
            }
            return null;
        }).when(actualPaymentAttributionService).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        when(ownerManualCardPaymentApprovalRepository.findByPaymentLinkIdForUpdate(5374L))
                .thenReturn(Optional.empty());
        when(ownerManualCardPaymentApprovalRepository.saveAndFlush(any(OwnerManualCardPaymentApproval.class)))
                .thenAnswer(invocation -> {
                    OwnerManualCardPaymentApproval approval = invocation.getArgument(0);
                    approval.setId(94L);
                    approvalRef.set(approval);
                    return approval;
                });
        when(ownerManualCardPaymentApprovalRepository.findByIdForUpdate(94L))
                .thenAnswer(invocation -> Optional.ofNullable(approvalRef.get()));
        when(paymentLinkRepository.saveAndFlush(link)).thenReturn(link);
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.submitManagerManualCardPaymentForOrder(
                25274L, "Поступило на старый счет владельца", null,
                ContractorRecipientType.OWNER, null, "OWNER",
                "manager@example.ru", authentication
        );
        ArgumentCaptor<ManualCardPaymentReviewNotificationService.OwnerApprovalRequest> request =
                ArgumentCaptor.forClass(ManualCardPaymentReviewNotificationService.OwnerApprovalRequest.class);
        verify(manualCardPaymentReviewNotificationService).notifyOwnerApprovalAfterCommit(request.capture());
        User owner = new User();
        owner.setId(8L);
        owner.setUsername("owner@example.ru");
        owner.setActive(true);
        Role ownerRole = new Role();
        ownerRole.setName("ROLE_OWNER");
        owner.setRoles(List.of(ownerRole));
        when(authentication.getName()).thenReturn("owner@example.ru");

        var result = service.approveOwnerManualCardPayment(
                94L, request.getValue().callbackToken(), owner, authentication);

        assertFalse(result.alreadyCompleted());
        assertEquals(OwnerManualCardPaymentApprovalStatus.CONFIRMED, approvalRef.get().getStatus());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void managerSpecialistRequisitesStillConfirmDirectlyWithoutOwnerApproval() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25272L, "Реквизиты специалиста", BigDecimal.valueOf(1000));
        PaymentLink link = contractorManualLink(
                5372L, order, 100_000L, PaymentLinkStatus.MANUAL_REPORTED, 801L);
        when(orderRepository.findByIdForCounterUpdate(25272L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25272L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5372L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5372L)).thenReturn(Optional.of(link));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            PaymentLink frozen = invocation.getArgument(1);
            frozen.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
            frozen.setManualActualRecipientProfileId(25L);
            frozen.setManualActualCashDestinationKind(ContractorCashDestinationKind.CONTRACTOR_PROFILE);
            frozen.setManualActualReason(invocation.getArgument(5));
            frozen.setManualActualActor(invocation.getArgument(7));
            frozen.setManualActualRecipientFrozenAt(LocalDateTime.now());
            return null;
        }).when(actualPaymentAttributionService).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);
        when(paymentLinkRepository.saveAndFlush(link)).thenReturn(link);

        var result = service.submitManagerManualCardPaymentForOrder(
                25272L, "Специалист подтвердил поступление", null,
                ContractorRecipientType.SPECIALIST, 25L, "SPECIALIST:25",
                "manager@example.ru", authentication
        );

        assertEquals("COMPLETED", result.status());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        verifyNoInteractions(ownerManualCardPaymentApprovalRepository);
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void manualCardPaymentPrefersLiveManualRouteOverExpiredReminderHistory() {
        PaymentLinkService service = service(properties());
        Order order = order(25246L, "Автокар", BigDecimal.valueOf(1000));

        PaymentLink expiredTaskReminder = new PaymentLink();
        expiredTaskReminder.setId(6304L);
        expiredTaskReminder.setOrder(order);
        expiredTaskReminder.setStatus(PaymentLinkStatus.EXPIRED);
        expiredTaskReminder.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        expiredTaskReminder.setManualSource(ManualPaymentSource.MANUAL_TASK);
        expiredTaskReminder.setAmountKopecks(100_000L);
        expiredTaskReminder.setManualRecipientName("Анастасия");
        expiredTaskReminder.setCreatedAt(LocalDateTime.of(2026, 8, 16, 19, 45));
        expiredTaskReminder.setUpdatedAt(LocalDateTime.of(2026, 8, 21, 10, 31));

        PaymentLink liveManual = new PaymentLink();
        liveManual.setId(7158L);
        liveManual.setOrder(order);
        liveManual.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        liveManual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        liveManual.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        liveManual.setAmountKopecks(100_000L);
        liveManual.setCreatedAt(LocalDateTime.of(2026, 8, 25, 10, 34));
        liveManual.setUpdatedAt(LocalDateTime.of(2026, 8, 25, 10, 34));

        ManualCardPaymentContextResponse expected = new ManualCardPaymentContextResponse(
                25246L, 100_000L, null, List.of(), null, false, null, null, null);
        when(orderRepository.findByIdForCounterUpdate(25246L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25246L))
                .thenReturn(List.of(expiredTaskReminder, liveManual));
        when(paymentLinkRepository.findByIdForUpdate(7158L)).thenReturn(Optional.of(liveManual));
        when(actualPaymentAttributionService.manualCardPaymentContext(order, liveManual))
                .thenReturn(expected);

        ManualCardPaymentContextResponse context = service.manualCardPaymentContextForOrder(
                25246L, authentication);

        assertSame(expected, context);
        verify(paymentLinkRepository).findByIdForUpdate(7158L);
        verify(paymentLinkRepository, never()).findByIdForUpdate(6304L);
        verify(actualPaymentAttributionService).manualCardPaymentContext(order, liveManual);
        verifyNoInteractions(tbankClient);
    }

    @Test
    void manualCardPaymentDoesNotRecoverExpiredManualRouteWithStaleAmount() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25065L, "Старая сумма ручного маршрута", BigDecimal.valueOf(1000));
        PaymentLink manual = new PaymentLink();
        manual.setId(5234L);
        manual.setOrder(order);
        manual.setStatus(PaymentLinkStatus.EXPIRED);
        manual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        manual.setAmountKopecks(90_000L);

        when(orderRepository.findByIdForCounterUpdate(25065L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25065L)).thenReturn(List.of(manual));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.confirmPaidByManualCardTransferForOrder(
                        25065L, true, true, 90_000L, "выписка проверена", null, "owner", authentication
                ));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void manualCardPaymentStillBlocksFailedBankRouteWithProviderEvidence() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25066L, "Неоднозначный банковский сбой", BigDecimal.valueOf(1000));
        PaymentLink failed = initiatedBankLink(5235L, order, 100_000L);
        failed.setStatus(PaymentLinkStatus.FAILED);

        when(orderRepository.findByIdForCounterUpdate(25066L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25066L)).thenReturn(List.of(failed));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.confirmPaidByManualCardTransferForOrder(
                        25066L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
                ));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void manualCardPaymentSelectsLatestOfMultipleSafeTerminalRoutes() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25058L, "Несколько завершенных ссылок", BigDecimal.valueOf(1000));
        PaymentLink older = initiatedBankLink(5221L, order, 100_000L);
        older.setStatus(PaymentLinkStatus.EXPIRED);
        older.setProviderTerminalStatus("DEADLINE_EXPIRED");
        older.setUpdatedAt(LocalDateTime.now().minusDays(5));
        PaymentLink latest = initiatedBankLink(5222L, order, 100_000L);
        latest.setStatus(PaymentLinkStatus.EXPIRED);
        latest.setProviderTerminalStatus("DEADLINE_EXPIRED");
        latest.setUpdatedAt(LocalDateTime.now().minusDays(1));

        when(orderRepository.findByIdForCounterUpdate(25058L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25058L)).thenReturn(List.of(latest, older));
        when(paymentLinkRepository.findByIdWithOrder(5222L)).thenReturn(Optional.of(latest));
        when(paymentLinkRepository.findByIdForUpdate(5222L)).thenReturn(Optional.of(latest));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25058L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertTrue(latest.getLastError().startsWith("manual_card_payment_completed:"));
        assertEquals(PaymentLinkStatus.EXPIRED, older.getStatus());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), eq("payment-5222"));
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), eq("payment-5221"));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void manualCardPaymentLocallyClosesCreatedBankRouteWithoutCallingProvider() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(25054L, "Ссылка еще не открыта", BigDecimal.valueOf(1000));
        PaymentLink link = new PaymentLink();
        link.setId(5216L);
        link.setOrder(order);
        link.setToken("created-bank-route");
        link.setAmountKopecks(100_000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setExpiresAt(LocalDateTime.now().plusDays(30));

        when(orderRepository.findByIdForCounterUpdate(25054L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25054L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5216L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5216L)).thenReturn(Optional.of(link));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25054L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertTrue(link.getLastError().startsWith("manual_card_payment_completed:"));
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void manualCardPaymentAcceptsFreshProviderVerifiedCanceledRoute() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25055L, "Ссылка закрылась перед подтверждением", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5217L, order, 100_000L);
        link.setStatus(PaymentLinkStatus.CANCELED);

        when(orderRepository.findByIdForCounterUpdate(25055L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25055L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5217L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5217L)).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5217")))
                .thenReturn(tbankState("CANCELED", "payment-5217", "order-5217", 100_000L));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransferForOrder(
                25055L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertTrue(link.getLastError().startsWith("manual_card_payment_completed:"));
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5217"));
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @Test
    void manualCardPaymentLinkEndpointReconcilesLegacyExpiredRoute() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25064L, "Прямая сверка старой ссылки", BigDecimal.valueOf(1000));
        PaymentLink legacy = initiatedBankLink(5233L, order, 100_000L);
        legacy.setStatus(PaymentLinkStatus.EXPIRED);

        when(orderRepository.findByIdForCounterUpdate(25064L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25064L)).thenReturn(List.of(legacy));
        when(paymentLinkRepository.findByIdWithOrder(5233L)).thenReturn(Optional.of(legacy));
        when(paymentLinkRepository.findByIdForUpdate(5233L)).thenReturn(Optional.of(legacy));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5233")))
                .thenReturn(tbankState("DEADLINE_EXPIRED", "payment-5233", "order-5233", 100_000L));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmPaidByManualCardTransfer(
                5233L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
        );

        assertEquals("DEADLINE_EXPIRED", legacy.getProviderTerminalStatus());
        assertTrue(legacy.getLastError().startsWith("manual_card_payment_completed:"));
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5233"));
        verify(orderTransactionService).handlePaymentStatus(order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVERSED", "REFUNDED"})
    void manualCardPaymentFailsClosedForReversedOrRefundedBankRoute(String providerStatus) throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25056L, "Возврат нельзя переписать", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5218L, order, 100_000L);
        link.setStatus("REVERSED".equals(providerStatus) ? PaymentLinkStatus.REVERSED : PaymentLinkStatus.REFUNDED);

        when(orderRepository.findByIdForCounterUpdate(25056L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25056L)).thenReturn(List.of(link));
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25056L, true, true, 100_000L, "выписка проверена", null, "owner", authentication
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(paymentInvoiceRetryScheduler, never()).cancelPaymentAutomation(any(Long.class), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "AUTHORIZED", "PROCESSING"})
    void manualCardPaymentFailsClosedWhenProviderIsNotNewOrSafelyClosed(String providerStatus) throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25048L, "Чужой активный платеж", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5209L, order, 100_000L);

        when(orderRepository.findByIdForCounterUpdate(25048L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25048L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5209L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5209L)).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5209")))
                .thenReturn(tbankState(providerStatus, "payment-5209", "order-5209", 100_000L));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25048L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );

        assertEquals(409, error.getStatusCode().value());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
        verify(paymentInvoiceRetryScheduler, never()).cancelPaymentAutomation(any(Long.class), anyString());
    }

    @Test
    void manualCardPaymentCancelTimeoutNeverSettlesOrderAndCanResumeAfterCanceledReconciliation() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25049L, "Неоднозначная отмена", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5210L, order, 100_000L);

        when(orderRepository.findByIdForCounterUpdate(25049L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25049L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5210L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(5210L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5210")))
                .thenReturn(
                        tbankState("NEW", "payment-5210", "order-5210", 100_000L),
                        tbankState("CANCELED", "payment-5210", "order-5210", 100_000L)
                );
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider timeout"));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        ResponseStatusException timeout = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25049L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );

        assertEquals(502, timeout.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertEquals(PaymentLinkStatus.INITIATED, link.getBankCancelOriginStatus());
        assertTrue(link.getManualComment().startsWith("Проверен перевод на карту; ожидается закрытие T-Bank"));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));

        assertTrue(service.reconcileBankLink(5210L, LocalDateTime.now()));
        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertNull(link.getBankCancelOriginStatus());

        service.confirmPaidByManualCardTransferForOrder(
                25049L, true, true, 100_000L, "повтор после сверки", null, "manager", authentication
        );

        assertNull(link.getConfirmedAmountKopecks());
        assertTrue(link.getLastError().startsWith("manual_card_payment_completed:"));
        verify(orderTransactionService, times(1)).handlePaymentStatus(order);
        verify(tbankClient, times(1)).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
    }

    @Test
    void manualCardPaymentRequiresExactAmountBeforeCallingProvider() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25050L, "Неверная сумма", BigDecimal.valueOf(1000));
        PaymentLink link = initiatedBankLink(5211L, order, 100_000L);
        when(orderRepository.findByIdForCounterUpdate(25050L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25050L)).thenReturn(List.of(link));
        when(paymentLinkRepository.findByIdWithOrder(5211L)).thenReturn(Optional.of(link));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25050L, true, true, 99_900L, "выписка проверена", null, "manager", authentication
                )
        );

        assertEquals(409, error.getStatusCode().value());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void manualCardPaymentRejectsZeroOrMultipleCandidatesBeforeCallingProvider() {
        PaymentLinkService service = service(properties());
        Order order = order(25051L, "Неоднозначные ссылки", BigDecimal.valueOf(1000));
        PaymentLink first = initiatedBankLink(5212L, order, 100_000L);
        PaymentLink second = initiatedBankLink(5213L, order, 100_000L);
        when(orderRepository.findByIdForCounterUpdate(25051L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25051L))
                .thenReturn(List.of(), List.of(first, second));

        ResponseStatusException empty = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25051L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );
        ResponseStatusException multiple = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25051L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );

        assertEquals(409, empty.getStatusCode().value());
        assertEquals(409, multiple.getStatusCode().value());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
    }

    @Test
    void manualCardPaymentRejectsAnotherAuthorizedRouteAndActiveCommonInvoice() throws Exception {
        CommonBillingService commonBillingService = org.mockito.Mockito.mock(CommonBillingService.class);
        PaymentLinkService service = service(properties(), new TbankTokenSigner(), commonBillingService);
        Order order = order(25052L, "Общий или второй платеж", BigDecimal.valueOf(1000));
        PaymentLink selected = initiatedBankLink(5214L, order, 100_000L);
        PaymentLink competing = initiatedBankLink(5215L, order, 100_000L);
        competing.setStatus(PaymentLinkStatus.AUTHORIZED);
        when(orderRepository.findByIdForCounterUpdate(25052L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25052L)).thenReturn(List.of(selected, competing));

        ResponseStatusException competingError = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25052L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );
        assertEquals(409, competingError.getStatusCode().value());

        when(paymentLinkRepository.findByOrderIdForUpdate(25052L)).thenReturn(List.of(selected));
        when(commonBillingService.isOrderInActiveCommonInvoice(25052L)).thenReturn(true);
        ResponseStatusException commonError = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25052L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );

        assertEquals(409, commonError.getStatusCode().value());
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void manualCardPaymentRechecksCompetingRouteAfterProviderObservationBeforeCancel() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25072L, "Конкурентный платёж", BigDecimal.valueOf(1000));
        PaymentLink selected = initiatedBankLink(5272L, order, 100_000L);
        PaymentLink competing = initiatedBankLink(5273L, order, 100_000L);
        competing.setStatus(PaymentLinkStatus.AUTHORIZED);
        java.util.concurrent.atomic.AtomicInteger lockedReads = new java.util.concurrent.atomic.AtomicInteger();
        when(orderRepository.findByIdForCounterUpdate(25072L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25072L)).thenAnswer(invocation ->
                lockedReads.incrementAndGet() >= 5 ? List.of(selected, competing) : List.of(selected));
        when(paymentLinkRepository.findByIdWithOrder(5272L)).thenReturn(Optional.of(selected));
        when(paymentLinkRepository.findByIdForUpdate(5272L)).thenReturn(Optional.of(selected));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5272")))
                .thenReturn(tbankState("NEW", "payment-5272", "order-5272", 100_000L));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.reportPaidByManualCardTransferForOrder(
                        25072L, "Клиент перевёл напрямую", null,
                        ContractorRecipientType.OWNER, null, "OWNER", "manager@example.ru", authentication
                ));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(lockedReads.get() >= 5);
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5272"));
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(actualPaymentAttributionService, never()).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
    }

    @Test
    void manualCardPaymentRechecksSettledEvidenceAfterProviderObservationBeforeCancel() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(25073L, "Позднее поступление", BigDecimal.valueOf(1000));
        PaymentLink selected = initiatedBankLink(5274L, order, 100_000L);
        when(orderRepository.findByIdForCounterUpdate(25073L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(25073L)).thenReturn(List.of(selected));
        when(paymentLinkRepository.findByIdWithOrder(5274L)).thenReturn(Optional.of(selected));
        when(paymentLinkRepository.findByIdForUpdate(5274L)).thenReturn(Optional.of(selected));
        when(actualPaymentAttributionService.actualRecipientAccountingEnabled()).thenReturn(true);
        when(orderPaymentIntegrityService.hasSettledPaymentEvidence(order)).thenReturn(false, true);
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-5274")))
                .thenReturn(tbankState("NEW", "payment-5274", "order-5274", 100_000L));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.reportPaidByManualCardTransferForOrder(
                        25073L, "Клиент перевёл напрямую", null,
                        ContractorRecipientType.OWNER, null, "OWNER", "manager@example.ru", authentication
                ));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-5274"));
        verify(tbankClient, never()).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        verify(actualPaymentAttributionService, never()).freezePaymentLinkRecipientIntent(
                any(), any(), any(), any(), any(), anyString(), any(), anyString()
        );
    }

    @Test
    void manualCardPaymentManagerDenialHappensAfterOrderLockAndBeforeLinkOrProviderRead() {
        PaymentLinkService service = service(properties());
        Order order = order(25053L, "Чужой заказ", BigDecimal.valueOf(1000));
        ResponseStatusException denied = new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
        when(orderRepository.findByIdForCounterUpdate(25053L)).thenReturn(Optional.of(order));
        doThrow(denied).when(managerAccessService).requireOrderAccess(25053L, authentication);

        ResponseStatusException actual = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransferForOrder(
                        25053L, true, true, 100_000L, "выписка проверена", null, "manager", authentication
                )
        );

        assertSame(denied, actual);
        InOrder ordered = inOrder(orderRepository, managerAccessService);
        ordered.verify(orderRepository).findByIdForCounterUpdate(25053L);
        ordered.verify(managerAccessService).requireOrderAccess(25053L, authentication);
        verify(paymentLinkRepository, never()).findByOrderIdForUpdate(25053L);
        verify(tbankClient, never()).getState(any(TbankPaymentProfile.class), anyString());
    }

    @Test
    void publicSbpBanksLoadsBankListFromTbankAndMarksFeatured() {
        PaymentLinkService service = service(properties());
        Order order = order(34L, "ООО Банки СБП", BigDecimal.valueOf(321));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token-banks");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token-banks")).thenReturn(Optional.of(link));
        when(tbankClient.getQrBankList(any(TbankPaymentProfile.class), any(TbankGetQrBankListCommand.class)))
                .thenReturn(new TbankGetQrBankListResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        List.of(
                                new TbankGetQrBankListResponse.TbankSbpBank(
                                        "bank-other",
                                        "100000000999",
                                        "Банк Зета",
                                        null,
                                        1
                                ),
                                new TbankGetQrBankListResponse.TbankSbpBank(
                                        "bank-sber",
                                        "100000000111",
                                        "СберБанк",
                                        "https://example.ru/sber.svg",
                                        2
                                )
                        )
                ));

        List<PublicSbpBankResponse> response = service.publicSbpBanks("token-banks", "desktop", "Windows");

        ArgumentCaptor<TbankGetQrBankListCommand> commandCaptor = ArgumentCaptor.forClass(TbankGetQrBankListCommand.class);
        verify(tbankClient).getQrBankList(any(TbankPaymentProfile.class), commandCaptor.capture());
        assertEquals("desktop", commandCaptor.getValue().deviceType());
        assertEquals("Windows", commandCaptor.getValue().os());
        assertEquals("bank-sber", response.get(0).bankId());
        assertTrue(response.get(0).featured());
        assertEquals("bank-other", response.get(1).bankId());
        assertFalse(response.get(1).featured());
    }

    @Test
    void tochkaPublicLinkExposesOnlyFailClosedBankCapabilities() {
        PaymentLinkService service = service(properties());
        Order order = order(35L, "ООО Точка", BigDecimal.valueOf(321));
        PaymentProfile tochka = profile(3L, "tochka-primary", "Точка Банк", "tochka-profile");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        PaymentLink link = payableLink(order, "tochka-public", 32_100L);
        link.setId(35L);
        link.setPaymentProfile(tochka);
        link.setPaymentProfileCode(tochka.getCode());
        link.setPaymentProfileName(tochka.getName());

        when(paymentLinkRepository.findByTokenWithOrder("tochka-public")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("tochka-public")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(35L)).thenReturn(Optional.of(order));
        PublicPaymentLinkResponse response = service.publicLink("tochka-public");

        assertEquals(PaymentProfile.PROVIDER_TOCHKA, response.provider());
        assertEquals("BANK_ONLY", response.paymentPageMode());
        assertFalse(response.sbpBankSelectionSupported());
        assertFalse(response.tpayEnabled());
        assertFalse(response.sberpayEnabled());
        assertFalse(response.mirpayEnabled());
        verifyNoInteractions(tbankClient);
    }

    @Test
    void tochkaPublicBankActionsFailBeforeAnyTbankCall() {
        PaymentLinkService service = service(properties());
        Order order = order(36L, "ООО Точка", BigDecimal.valueOf(321));
        PaymentProfile tochka = profile(3L, "tochka-primary", "Точка Банк", "tochka-profile");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        PaymentLink link = payableLink(order, "tochka-actions", 32_100L);
        link.setId(36L);
        link.setPaymentProfile(tochka);
        link.setPaymentProfileCode(tochka.getCode());
        link.setPaymentProfileName(tochka.getName());

        when(paymentLinkRepository.findByTokenWithOrder("tochka-actions")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("tochka-actions")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByOrderIdForUpdate(36L)).thenReturn(List.of(link));
        when(orderRepository.findByIdForCounterUpdate(36L)).thenReturn(Optional.of(order));

        ResponseStatusException bankForm = assertThrows(ResponseStatusException.class, () -> service.init(
                "tochka-actions", "payer@example.ru", true, true, true, "203.0.113.7", "JUnit UA"
        ));
        ResponseStatusException sbp = assertThrows(ResponseStatusException.class, () -> service.initSbp(
                "tochka-actions", "payer@example.ru", true, true, true, null, "203.0.113.7", "JUnit UA"
        ));
        ResponseStatusException banks = assertThrows(ResponseStatusException.class, () ->
                service.publicSbpBanks("tochka-actions", "mobile", "Android")
        );

        assertEquals(409, bankForm.getStatusCode().value());
        assertTrue(bankForm.getReason().contains("пока не активирован"));
        assertEquals(409, sbp.getStatusCode().value());
        assertTrue(sbp.getReason().contains("СБП через Точку"));
        assertEquals(409, banks.getStatusCode().value());
        assertTrue(banks.getReason().contains("СБП через Точку"));
        verifyNoInteractions(tbankClient);
    }

    @Test
    void publicPaperInvoiceDoesNotResolveItsSpecialProfileCodeAsBankProvider() {
        PaymentLinkService service = service(properties());
        Order order = order(37L, "ООО Бумажный счёт", BigDecimal.valueOf(321));
        PaymentLink link = payableLink(order, "paper-public", 32_100L);
        link.setId(37L);
        link.setPaymentMethod(PaymentMethod.OWNER_PAPER_INVOICE);
        link.setPaymentProfile(null);
        link.setPaymentProfileCode("OWNER_PAPER_INVOICE");
        link.setPaymentProfileName("Бумажный счёт владельца");

        when(paymentLinkRepository.findByTokenWithOrder("paper-public")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByTokenForUpdate("paper-public")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(37L)).thenReturn(Optional.of(order));

        PublicPaymentLinkResponse response = service.publicLink("paper-public");

        assertEquals(PaymentProfile.PROVIDER_TBANK, response.provider());
        verify(paymentProfileService, never()).findByCode("OWNER_PAPER_INVOICE");
        verifyNoInteractions(tbankClient);
    }

    @Test
    void cancelRefundablePaymentCallsTbankCancelAndStoresRefundedStatus() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(40L, "ООО Возврат", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(1L);
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(1000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
        link.setTbankPaymentId("payment-1");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());

        when(paymentLinkRepository.findByIdWithOrder(1L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(40L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(link));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class))).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "REFUNDED",
                "payment-1",
                "order-sbp",
                1000L,
                1000L,
                0L
        ));

        AdminPaymentLinkResponse response = service.cancel(1L);

        ArgumentCaptor<TbankCancelCommand> captor = ArgumentCaptor.forClass(TbankCancelCommand.class);
        ArgumentCaptor<TbankPaymentProfile> profileCaptor = ArgumentCaptor.forClass(TbankPaymentProfile.class);
        verify(tbankClient).cancel(profileCaptor.capture(), captor.capture());
        assertEquals(TbankPaymentProfile.PRIMARY_CODE, profileCaptor.getValue().code());
        assertEquals("payment-1", captor.getValue().paymentId());
        assertEquals(1000L, captor.getValue().amountKopecks());
        assertEquals(PaymentLinkStatus.REFUNDED, link.getStatus());
        assertNull(link.getBankCancelOriginStatus());
        assertEquals("REFUNDED", response.status());
        assertFalse(response.refundable());
        verify(paymentLinkRepository, times(2)).save(link);
        verify(contractorPaymentShadowService).reconcilePaymentLinkId(1L);
        InOrder ordered = inOrder(tbankClient, orderRepository, paymentLinkRepository);
        ordered.verify(orderRepository).findByIdForCounterUpdate(40L);
        ordered.verify(paymentLinkRepository).findByIdForUpdate(1L);
        ordered.verify(tbankClient).cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class));
        ordered.verify(orderRepository).findByIdForCounterUpdate(40L);
        ordered.verify(paymentLinkRepository).findByIdForUpdate(1L);
    }

    @Test
    void lateCancelRefundedResponseAdvancesConcurrentReversedWebhook() {
        PaymentLinkService service = service(properties());
        Order order = order(401L, "ООО Монотонный возврат", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(401L);
        link.setOrder(order);
        link.setToken("late-refund-after-reverse");
        link.setAmountKopecks(1000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setTbankPaymentId("payment-401");
        link.setTbankOrderId("order-401");
        link.setTbankTerminalKey("terminal");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(401L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(401L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(401L)).thenReturn(Optional.of(link));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenAnswer(invocation -> {
                    link.setStatus(PaymentLinkStatus.REVERSED);
                    link.setBankCancelNonce(null);
                    link.setBankCancelLeaseUntil(null);
                    link.setBankCancelOriginStatus(null);
                    link.setBankCancelOriginError(null);
                    return new TbankCancelResponse(
                            true,
                            "0",
                            null,
                            null,
                            "terminal",
                            "REFUNDED",
                            "payment-401",
                            "order-401",
                            1000L,
                            1000L,
                            0L
                    );
                });

        AdminPaymentLinkResponse response = service.cancel(401L);

        assertEquals(PaymentLinkStatus.REFUNDED, link.getStatus());
        assertEquals("REFUNDED", response.status());
    }

    @Test
    void lateCancelCanceledResponseAppliesDuringRecoveryWatch() {
        PaymentLinkService service = service(properties());
        Order order = order(402L, "ООО Поздний ответ отмены", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(402L);
        link.setOrder(order);
        link.setToken("late-canceled-during-watch");
        link.setAmountKopecks(1000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setLastError("prepaid_waiting_order_completion");
        link.setTbankPaymentId("payment-402");
        link.setTbankOrderId("order-402");
        link.setTbankTerminalKey("terminal");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(402L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(402L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(402L)).thenReturn(Optional.of(link));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenAnswer(invocation -> {
                    link.setStatus(PaymentLinkStatus.CONFIRMED);
                    link.setBankCancelNonce(null);
                    link.setBankCancelLeaseUntil(LocalDateTime.now().plusHours(24));
                    link.setBankCancelOriginStatus(PaymentLinkStatus.CONFIRMED);
                    link.setBankCancelOriginError("prepaid_waiting_order_completion");
                    return new TbankCancelResponse(
                            true,
                            "0",
                            null,
                            null,
                            "terminal",
                            "CANCELED",
                            "payment-402",
                            "order-402",
                            1000L,
                            1000L,
                            0L
                    );
                });

        AdminPaymentLinkResponse response = service.cancel(402L);

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertEquals("CANCELED", response.status());
        assertNull(link.getBankCancelOriginStatus());
        assertNull(link.getBankCancelOriginError());
    }

    @Test
    void cancelDoesNotApplyProviderResponseAfterPaymentBindingChanged() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(41L, "ООО Гонка возврата", BigDecimal.valueOf(10));
        PaymentLink snapshot = new PaymentLink();
        snapshot.setId(2L);
        snapshot.setOrder(order);
        snapshot.setToken("cancel-race");
        snapshot.setAmountKopecks(1000L);
        snapshot.setDescription("Оплата услуг");
        snapshot.setStatus(PaymentLinkStatus.CONFIRMED);
        snapshot.setTbankPaymentId("payment-old");
        snapshot.setExpiresAt(LocalDateTime.now().plusDays(1));

        PaymentLink current = new PaymentLink();
        current.setId(2L);
        current.setOrder(order);
        current.setToken("cancel-race");
        current.setAmountKopecks(1000L);
        current.setDescription("Оплата услуг");
        current.setStatus(PaymentLinkStatus.CONFIRMED);
        current.setTbankPaymentId("payment-new");
        current.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(2L)).thenReturn(Optional.of(snapshot));
        when(paymentLinkRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(snapshot), Optional.of(current));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class))).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "REFUNDED",
                "payment-old",
                "order-old",
                1000L,
                1000L,
                0L
        ));
        when(orderRepository.findByIdForCounterUpdate(41L)).thenReturn(Optional.of(order));

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(2L)
        );

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(PaymentLinkStatus.CONFIRMED, current.getStatus());
        verify(paymentLinkRepository, never()).save(current);
    }

    @Test
    void cancelTimeoutLeavesDurableReconciliationQuarantine() {
        PaymentLinkService service = service(properties());
        Order order = order(42L, "ООО Неоднозначный возврат", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(3L);
        link.setOrder(order);
        link.setToken("cancel-timeout");
        link.setAmountKopecks(1000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setLastError("prepaid_waiting_order_completion");
        link.setTbankPaymentId("payment-timeout");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(3L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(42L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(link));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider timeout"));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(3L)
        );

        assertEquals(502, failure.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertNotNull(link.getBankCancelNonce());
        assertNotNull(link.getBankCancelLeaseUntil());
        assertTrue(link.getBankCancelLeaseUntil().isAfter(LocalDateTime.now()));
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getBankCancelOriginStatus());
        assertEquals("prepaid_waiting_order_completion", link.getBankCancelOriginError());
        assertTrue(link.getLastError().startsWith("bank_cancel_ambiguous:"));
        verify(paymentLinkRepository, times(2)).save(link);
    }

    @Test
    void cancelUnknownSuccessfulStatusNeverBecomesCanceled() {
        PaymentLinkService service = service(properties());
        Order order = order(43L, "ООО Неизвестный статус возврата", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(4L);
        link.setOrder(order);
        link.setToken("cancel-unknown");
        link.setAmountKopecks(1000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
        link.setTbankPaymentId("payment-unknown");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(4L)).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(43L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(link));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class)))
                .thenReturn(new TbankCancelResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        "PROCESSING_REFUND",
                        "payment-unknown",
                        null,
                        1000L,
                        1000L,
                        0L
                ));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(4L)
        );

        assertEquals(502, failure.getStatusCode().value());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertNotEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertNotNull(link.getBankCancelNonce());
        assertNotNull(link.getBankCancelLeaseUntil());
        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getBankCancelOriginStatus());
        assertTrue(link.getLastError().startsWith("bank_cancel_ambiguous:"));
    }

    @Test
    void confirmedWebhookDuringActiveCancelLeaseKeepsQuarantine() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(450L, "ООО Возврат выполняется", BigDecimal.valueOf(100));
        PaymentLink link = cancelWatchLink(450L, order);
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setBankCancelNonce("active-cancel-450");
        link.setBankCancelLeaseUntil(LocalDateTime.now().plusMinutes(4));
        link.setLastError("bank_cancel_ambiguous: timeout");

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("order-450")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(450L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(450L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(signedWebhook(signer, "order-450", "payment-450", "CONFIRMED", 10000L));

        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, link.getStatus());
        assertEquals("active-cancel-450", link.getBankCancelNonce());
        assertEquals(PaymentLinkStatus.CONFIRMED, link.getBankCancelOriginStatus());
        assertEquals("prepaid_waiting_order_completion", link.getBankCancelOriginError());
        assertTrue(link.getLastError().startsWith("bank_cancel_in_progress:"));
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void delayedCanceledWebhookDuringCancelWatchTransitionsToCanceled() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(451L, "ООО Поздняя отмена", BigDecimal.valueOf(100));
        PaymentLink link = cancelWatchLink(451L, order);
        link.setBankCancelLeaseUntil(LocalDateTime.now().plusHours(12));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("order-451")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(451L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(451L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(signedWebhook(signer, "order-451", "payment-451", "CANCELED", 10000L));

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        assertNull(link.getLastError());
        assertNull(link.getBankCancelNonce());
        assertNull(link.getBankCancelLeaseUntil());
        assertNull(link.getBankCancelOriginStatus());
        assertNull(link.getBankCancelOriginError());
        verify(orderTransactionService, never()).handlePaymentStatus(any(Order.class));
    }

    @Test
    void adminFailedFilterAndRejectedSummaryIncludeReconciliationQuarantine() {
        PaymentLinkService service = service(properties());
        when(paymentLinkRepository.findAdminPage(
                anyString(),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyBoolean(),
                any()
        )).thenReturn(new PageImpl<>(List.of()));

        service.adminLinks(0, 25, "failed", null, null, null, "live");

        ArgumentCaptor<Collection<PaymentLinkStatus>> pageFailedStatuses = paymentStatusCollectionCaptor();
        verify(paymentLinkRepository).findAdminPage(
                eq("failed"),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                pageFailedStatuses.capture(),
                anyCollection(),
                eq(false),
                any()
        );
        ArgumentCaptor<Collection<PaymentLinkStatus>> summaryFailedStatuses = paymentStatusCollectionCaptor();
        ArgumentCaptor<Collection<PaymentLinkStatus>> rejectedStatuses = paymentStatusCollectionCaptor();
        verify(paymentLinkRepository).summarizeAdminPage(
                eq("failed"),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                summaryFailedStatuses.capture(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                rejectedStatuses.capture(),
                any(),
                any(),
                eq(false)
        );
        assertTrue(pageFailedStatuses.getValue().contains(PaymentLinkStatus.NEEDS_RECONCILIATION));
        assertTrue(summaryFailedStatuses.getValue().contains(PaymentLinkStatus.NEEDS_RECONCILIATION));
        assertTrue(rejectedStatuses.getValue().contains(PaymentLinkStatus.NEEDS_RECONCILIATION));
    }

    @Test
    void adminLinksDoesNotPerformPerRowTbankGetStateCalls() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(50L, "ООО Синхронизация", BigDecimal.valueOf(900));
        PaymentProfile profile = profile(2L, TbankPaymentProfile.SECONDARY_CODE, "Второй магазин", "secondary-terminal");
        PaymentLink link = new PaymentLink();
        link.setId(5L);
        link.setOrder(order);
        link.setPaymentProfile(profile);
        link.setPaymentProfileCode(profile.getCode());
        link.setPaymentProfileName(profile.getName());
        link.setToken("token");
        link.setAmountKopecks(90000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.AUTHORIZED);
        link.setTbankPaymentId("payment-50");
        link.setTbankTerminalKey("secondary-terminal");
        link.setPayerEmail("CLIENT@EXAMPLE.RU");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findAdminPage(
                anyString(),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyBoolean(),
                any()
        )).thenReturn(new PageImpl<>(List.of(link)));
        when(paymentLinkRepository.summarizeAdminPage(
                anyString(),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                anyBoolean()
        )).thenReturn(new PaymentLinkAdminSummary(
                1L, 90000L, 1L, 0L, 1L, 0L, 0L, 1L, 0L, 0L, 0L, 0L
        ));
        AdminPaymentLinksPageResponse response = service.adminLinks(0, 100, "all", null, null, null, "live");

        assertEquals(PaymentLinkStatus.AUTHORIZED, link.getStatus());
        assertEquals("AUTHORIZED", response.items().get(0).status());
        assertEquals("UNKNOWN", response.items().get(0).clientChatPlatform());
        assertFalse(response.items().get(0).clientChatReady());
        assertEquals("ссылка на чат не указана", response.items().get(0).clientChatWarning());
        assertNull(link.getPaidAt());
        verify(tbankClient, never()).getState(any(), anyString());
        verify(paymentLinkRepository, never()).save(link);
    }

    @Test
    void restrictedOwnerAdminListUsesTheSameDurableTargetFilterForRowsAndSummary() {
        PaymentLinkService service = service(properties());
        when(contractorPaymentTargetAccessPolicy.excludePrivilegedTargets()).thenReturn(true);
        when(paymentLinkRepository.findAdminPage(
                anyString(), any(), any(), any(), any(),
                anyCollection(), anyCollection(), anyCollection(), anyCollection(), anyCollection(),
                eq(true), any()
        )).thenReturn(new PageImpl<>(List.of()));

        service.adminLinks(0, 25, "all", null, null, null, "live");

        verify(paymentLinkRepository).findAdminPage(
                anyString(), any(), any(), any(), any(),
                anyCollection(), anyCollection(), anyCollection(), anyCollection(), anyCollection(),
                eq(true), any()
        );
        verify(paymentLinkRepository).summarizeAdminPage(
                anyString(), any(), any(), any(), any(),
                anyCollection(), anyCollection(), anyCollection(), anyCollection(), anyCollection(),
                anyCollection(), anyCollection(), anyCollection(), any(), any(), eq(true)
        );
        verify(paymentLinkRepository, never()).expireManualLinks(anyCollection(), anyCollection(), any(), any(), any());
    }

    @Test
    void everyAdministrativeLinkMutationConcealsForbiddenContractorTargetBeforeReadingTheLink() {
        PaymentLinkService service = service(properties());
        ResponseStatusException concealed = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Платежная ссылка не найдена"
        );
        doThrow(concealed).when(contractorPaymentTargetAccessPolicy).requireCanManagePaymentLink(901L);

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(901L)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaidByManualCardTransfer(
                        901L, true, true, 10_000L, "Выписка проверена", null, "owner", authentication
                )
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.releaseAmbiguousBankInit(901L, true, "Банк проверен", "owner")
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.confirmManual(901L, "owner")
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.confirmContractorPaymentSource(
                        901L,
                        10_000L,
                        LocalDateTime.now(),
                        "Проверена выписка",
                        "owner"
                )
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.closeManualAsUnpaid(
                        901L, true, true, "Перевода нет", "owner", authentication
                )
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.markManualReceipt(901L, "owner")
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> service.markManualReceiptLegacyNotRequired(901L, "owner")
        ).getStatusCode());

        verify(contractorPaymentTargetAccessPolicy, times(8)).requireCanManagePaymentLink(901L);
        verifyNoInteractions(paymentLinkRepository);
    }

    @Test
    void globalArchivePolicyBlocksBothDryRunAndLiveRunBeforeCandidateLookup() {
        PaymentLinkService service = service(properties());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ запрещён"))
                .when(contractorPaymentTargetAccessPolicy).requireCanManageAllPaymentLinks();

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(
                ResponseStatusException.class,
                () -> service.archiveClosedLinks(true, 10)
        ).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(
                ResponseStatusException.class,
                () -> service.archiveClosedLinks(false, 10)
        ).getStatusCode());

        verify(contractorPaymentTargetAccessPolicy, times(2)).requireCanManageAllPaymentLinks();
        verifyNoInteractions(paymentLinkArchiveService);
    }

    @Test
    void lateAuthorizedWebhookDoesNotDowngradeTestConfirmedPayment() {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(51L, "ООО Не откатываем", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(51L);
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o51-test");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o51-test");
        payload.put("Success", "true");
        payload.put("Status", "AUTHORIZED");
        payload.put("PaymentId", "12347");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o51-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(51L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void lateRejectedWebhookDoesNotDowngradeConfirmedPaymentAndLocksOrderBeforeLink() {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(52L, "ООО Терминальный платёж", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(52L);
        link.setOrder(order);
        link.setToken("token-52");
        link.setTbankOrderId("o52-test");
        link.setTbankPaymentId("payment-52");
        link.setTbankTerminalKey("terminal");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o52-test");
        payload.put("Success", "false");
        payload.put("Status", "REJECTED");
        payload.put("PaymentId", "payment-52");
        payload.put("ErrorCode", "1051");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o52-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(52L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(52L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertNull(link.getLastError());
        InOrder ordered = inOrder(paymentLinkRepository, orderRepository);
        ordered.verify(paymentLinkRepository).findByTbankOrderIdWithOrder("o52-test");
        ordered.verify(orderRepository).findByIdForCounterUpdate(52L);
        ordered.verify(paymentLinkRepository).findByIdForUpdate(52L);
        verify(paymentLinkRepository).save(link);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEADLINE_EXPIRED", "UNKNOWN_PROVIDER_STATE"})
    void lateExpiredOrUnknownFailedWebhookDoesNotDowngradeConfirmedPayment(String incomingStatus) {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(520L, "ООО Защита терминального статуса", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(520L);
        link.setOrder(order);
        link.setToken("token-520");
        link.setTbankOrderId("o520-test");
        link.setTbankPaymentId("payment-520");
        link.setTbankTerminalKey("terminal");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o520-test");
        payload.put("Success", "false");
        payload.put("Status", incomingStatus);
        payload.put("PaymentId", "payment-520");
        payload.put("ErrorCode", "999");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o520-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(520L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(520L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertNull(link.getLastError());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void webhookDoesNotApplyToPaymentLinkWhosePaymentBindingChangedBeforeLock() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(521L, "ООО Смена привязки", BigDecimal.valueOf(11.11));

        PaymentLink snapshot = new PaymentLink();
        snapshot.setId(521L);
        snapshot.setOrder(order);
        snapshot.setTbankOrderId("o521-test");
        snapshot.setTbankPaymentId("payment-old");
        snapshot.setAmountKopecks(1111L);
        snapshot.setStatus(PaymentLinkStatus.INITIATED);

        PaymentLink current = new PaymentLink();
        current.setId(521L);
        current.setOrder(order);
        current.setTbankOrderId("o521-test");
        current.setTbankPaymentId("payment-new");
        current.setAmountKopecks(1111L);
        current.setStatus(PaymentLinkStatus.INITIATED);

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o521-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "payment-old");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o521-test"))
                .thenReturn(Optional.of(snapshot));
        when(orderRepository.findByIdForCounterUpdate(521L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(521L)).thenReturn(Optional.of(current));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.INITIATED, current.getStatus());
        assertEquals("payment-new", current.getTbankPaymentId());
        verify(paymentLinkRepository, never()).save(current);
        verify(orderTransactionService, never()).handlePaymentStatus(order);
    }

    @Test
    void refundedWebhookRemainsAllowedAfterConfirmedPayment() {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        CommonBillingService commonBillingService = org.mockito.Mockito.mock(CommonBillingService.class);
        PaymentLinkService service = service(properties, signer, commonBillingService);
        Order order = order(53L, "ООО Возврат после оплаты", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setId(53L);
        link.setOrder(order);
        link.setToken("token-53");
        link.setTbankOrderId("o53-test");
        link.setTbankPaymentId("payment-53");
        link.setTbankTerminalKey("terminal");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o53-test");
        payload.put("Success", "true");
        payload.put("Status", "REFUNDED");
        payload.put("PaymentId", "payment-53");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o53-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(53L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(53L)).thenReturn(Optional.of(link));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.handleTbankWebhook(payload);

            assertEquals(PaymentLinkStatus.REFUNDED, link.getStatus());
            assertEquals("REFUNDED", link.getProviderTerminalStatus());
            verify(paymentLinkRepository).save(link);
            verify(commonBillingService, never()).applyStandalonePaymentReversal(
                    any(), any(), any()
            );

            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(2, synchronizations.size());
            synchronizations.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

            verify(commonBillingService).applyStandalonePaymentReversal(
                    53L,
                    53L,
                    PaymentLinkStatus.REFUNDED
            );
            verify(contractorPaymentShadowService).reconcilePaymentLinkId(53L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void confirmedWebhookWithStaleAmountDoesNotMarkOrderPaid() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(60L, "ООО Старая сумма", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(60L);
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o60-test");
        link.setAmountKopecks(1000L);
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o60-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12360");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1000");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o60-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(60L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(link));
        when(badReviewTaskService.getSummaryForOrder(60L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(5), BigDecimal.ZERO));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.AMOUNT_MISMATCH, link.getStatus());
        assertEquals(1000L, link.getConfirmedAmountKopecks());
        assertTrue(link.getLastError().contains("устаревшей сумме"));
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmedWebhookForRetiredLinkDoesNotMarkOrderPaidAgain() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(61L, "ООО Закрытая ссылка", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(61L);
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o61-test");
        link.setAmountKopecks(1000L);
        link.setStatus(PaymentLinkStatus.CANCELED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o61-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12361");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1000");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o61-test")).thenReturn(Optional.of(link));
        when(orderRepository.findByIdForCounterUpdate(61L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByIdForUpdate(61L)).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.AMOUNT_MISMATCH, link.getStatus());
        assertEquals(1000L, link.getConfirmedAmountKopecks());
        assertTrue(link.getLastError().contains("закрытой ссылке"));
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    private PaymentLink cancelWatchLink(Long id, Order order) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setToken("cancel-watch-" + id);
        link.setAmountKopecks(10000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setLastError("prepaid_waiting_order_completion");
        link.setTbankPaymentId("payment-" + id);
        link.setTbankOrderId("order-" + id);
        link.setTbankTerminalKey("terminal");
        link.setBankCancelOriginStatus(PaymentLinkStatus.CONFIRMED);
        link.setBankCancelOriginError("prepaid_waiting_order_completion");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        return link;
    }

    @Test
    void knownUnsentCompensationCancelsOnlyExactPristineFreshSourceAndReleasesAllocation() {
        PaymentLinkService service = service(properties());
        Order order = new Order();
        order.setId(601L);
        PaymentLink link = payableLink(order, "fresh-unsent", 10_000L);
        link.setContractorAllocationId(77L);
        when(orderRepository.findByIdForCounterUpdate(601L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByTokenForUpdate("fresh-unsent")).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);

        assertTrue(service.cancelFreshUnsentPreparationAuthorized("fresh-unsent", 601L, authentication));

        assertEquals(PaymentLinkStatus.CANCELED, link.getStatus());
        verify(managerAccessService).requireOrderAccess(601L, authentication);
        verify(contractorPaymentLiveRoutingService).releaseClosedPaymentLink(link);
    }

    @Test
    void knownUnsentCompensationRetainsSourceWithDeliveryOrPaymentEvidence() {
        PaymentLinkService service = service(properties());
        Order order = new Order();
        order.setId(602L);
        PaymentLink link = payableLink(order, "reported-source", 10_000L);
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setContractorAllocationId(78L);
        when(orderRepository.findByIdForCounterUpdate(602L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByTokenForUpdate("reported-source")).thenReturn(Optional.of(link));

        assertFalse(service.cancelFreshUnsentPreparationAuthorized("reported-source", 602L, authentication));

        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, link.getStatus());
        verify(paymentLinkRepository, never()).save(link);
        verify(contractorPaymentLiveRoutingService, never()).releaseClosedPaymentLink(link);
    }

    @Test
    void payableChangeExpiresUnstartedManualTaskLinkAndReleasesItsTaskReservation() {
        PaymentLinkService service = service(properties());
        Order order = order(603L, "ООО Пересчет задания", BigDecimal.valueOf(3400));
        PaymentLink link = payableLink(order, "manual-task-payable-change", 340_000L);
        link.setId(6031L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setContractorAllocationId(79L);
        when(orderRepository.findByIdForCounterUpdate(603L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrderIdForUpdate(603L)).thenReturn(List.of(link));
        when(paymentLinkRepository.save(link)).thenReturn(link);

        assertEquals(1, service.retireOpenLinksBeforePayableChange(
                603L,
                "Плохая работа удалена из счета"
        ));

        assertEquals(PaymentLinkStatus.EXPIRED, link.getStatus());
        assertEquals("Плохая работа удалена из счета", link.getLastError());
        InOrder releaseOrder = inOrder(
                taskReceiptIntegrationService,
                paymentLinkRepository,
                contractorPaymentLiveRoutingService
        );
        releaseOrder.verify(taskReceiptIntegrationService).release(link, "Плохая работа удалена из счета");
        releaseOrder.verify(paymentLinkRepository).save(link);
        releaseOrder.verify(contractorPaymentLiveRoutingService).releaseClosedPaymentLink(link);
    }

    private PaymentLink payableLink(Order order, String token, long amountKopecks) {
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken(token);
        link.setAmountKopecks(amountKopecks);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));
        return link;
    }

    private PaymentLink contractorManualLink(
            Long id,
            Order order,
            long amountKopecks,
            PaymentLinkStatus status,
            Long allocationId
    ) {
        PaymentLink link = payableLink(order, "contractor-source-" + id, amountKopecks);
        link.setId(id);
        link.setStatus(status);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setContractorAllocationId(allocationId);
        return link;
    }

    private PaymentLink initiatedBankLink(Long id, Order order, long amountKopecks) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setToken("manual-card-" + id);
        link.setAmountKopecks(amountKopecks);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setTbankPaymentId("payment-" + id);
        link.setTbankOrderId("order-" + id);
        link.setTbankTerminalKey("terminal");
        link.setExpiresAt(LocalDateTime.now().plusDays(30));
        link.setCreatedAt(LocalDateTime.now().minusDays(1));
        link.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        return link;
    }

    private TbankGetStateResponse tbankState(
            String status,
            String paymentId,
            String orderId,
            long amountKopecks
    ) {
        return new TbankGetStateResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                status,
                paymentId,
                orderId,
                amountKopecks
        );
    }

    private Map<String, String> signedWebhook(
            TbankTokenSigner signer,
            String orderId,
            String paymentId,
            String status,
            long amountKopecks
    ) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", orderId);
        payload.put("Success", "true");
        payload.put("Status", status);
        payload.put("PaymentId", paymentId);
        payload.put("ErrorCode", "0");
        payload.put("Amount", Long.toString(amountKopecks));
        payload.put("Token", signer.sign(payload, "password"));
        return payload;
    }

    private PaymentLinkService service(TbankPaymentProperties properties) {
        return service(properties, new TbankTokenSigner());
    }

    private PaymentLinkService service(TbankPaymentProperties properties, TbankTokenSigner signer) {
        return service(properties, signer, null);
    }

    private PaymentLinkService service(
            TbankPaymentProperties properties,
            TbankTokenSigner signer,
            CommonBillingService commonBillingService
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<CommonBillingService> commonBillingServiceProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.lenient().when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        return new PaymentLinkService(
                paymentLinkRepository,
                orderRepository,
                badReviewTaskService,
                orderTransactionService,
                properties,
                runtimeSettingsService,
                paymentProfileService,
                tbankClient,
                signer,
                paymentSuccessNotificationDeliveryService,
                manualPaymentRecipientTelegramNotificationService,
                manualPaymentTaskService,
                taskReceiptIntegrationService,
                manualPaymentTaskRepository,
                paymentInvoiceRetryScheduler,
                paymentLinkArchiveService,
                appSettingService,
                commonBillingServiceProvider,
                orderPaymentIntegrityService,
                managerAccessService,
                manualCardPaymentReviewNotificationService,
                ownerManualCardPaymentApprovalRepository,
                contractorPaymentLiveRoutingService,
                contractorPaymentShadowService,
                contractorPaymentRuntimeSwitch,
                paymentLinkReturnOutboxService,
                actualPaymentAttributionService,
                contractorPaymentTargetAccessPolicy,
                paymentIssueReminderService,
                new PaymentLinkTransactionExecutor()
        );
    }

    private TbankPaymentProperties properties() {
        TbankPaymentProperties properties = new TbankPaymentProperties();
        properties.setPublicBaseUrl("https://example.ru");
        PaymentProfile defaultProfile = profile(1L, TbankPaymentProfile.PRIMARY_CODE, "Основной магазин", "terminal");
        java.util.concurrent.atomic.AtomicLong savedLinkId = new java.util.concurrent.atomic.AtomicLong(900_000L);
        org.mockito.Mockito.lenient().when(paymentLinkRepository.saveAndFlush(any(PaymentLink.class)))
                .thenAnswer(invocation -> {
                    PaymentLink saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(savedLinkId.getAndIncrement());
                    }
                    paymentLinkRepository.save(saved);
                    return saved;
                });
        org.mockito.Mockito.lenient().when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.TEST);
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isTbankEnabled()).thenAnswer(invocation -> properties.isEnabled());
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isPaymentLinksEnabled()).thenAnswer(invocation -> properties.isPaymentLinksEnabled());
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isManagerUiEnabled()).thenAnswer(invocation -> properties.isManagerUiEnabled());
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isApplyConfirmedPayments()).thenAnswer(invocation -> properties.isApplyConfirmedPayments());
        org.mockito.Mockito.lenient().when(paymentProfileService.selectForManager(any())).thenReturn(defaultProfile);
        org.mockito.Mockito.lenient().when(paymentProfileService.lockForRouting(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(paymentProfileService.provider(any(PaymentProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, PaymentProfile.class).normalizedProvider());
        org.mockito.Mockito.lenient().when(paymentProfileService.isTochkaProvider(any(PaymentProfile.class)))
                .thenAnswer(invocation -> PaymentProfile.PROVIDER_TOCHKA.equals(
                        invocation.getArgument(0, PaymentProfile.class).normalizedProvider()
                ));
        org.mockito.Mockito.lenient().when(manualPaymentTaskService.findRoutableTask(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any()
                ))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(defaultProfile));
        org.mockito.Mockito.lenient().when(paymentProfileService.findByCode(TbankPaymentProfile.PRIMARY_CODE)).thenReturn(Optional.of(defaultProfile));
        org.mockito.Mockito.lenient().when(paymentProfileService.toRuntime(defaultProfile)).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                true
        ));
        org.mockito.Mockito.lenient().when(paymentProfileService.toRuntimeForTerminal(defaultProfile, "terminal")).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                true
        ));
        org.mockito.Mockito.lenient().when(paymentProfileService.isTestTerminal("terminal")).thenReturn(true);
        org.mockito.Mockito.lenient().when(appSettingService.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            PaymentLink link = invocation.getArgument(0);
            LocalDateTime preparedAt = invocation.getArgument(1);
            if (link == null || link.getOrder() == null) {
                return null;
            }
            Order order = link.getOrder();
            String generation = "live-generation-" + (link.getId() == null ? "new" : link.getId());
            link.setShadowRouteGeneration(generation);
            link.setShadowRouteOrderId(order.getId());
            link.setShadowRouteAmountKopecks(link.getAmountKopecks());
            link.setShadowRoutePreparedAt(preparedAt == null ? LocalDateTime.now() : preparedAt);
            link.setShadowRouteCompanyRoutingAllowed(order.getCompany() != null
                    && order.getCompany().isContractorPaymentRoutingEnabled());
            var worker = order.getWorker();
            link.setShadowRouteWorkerId(worker == null ? null : worker.getId());
            link.setShadowRouteWorkerUserId(worker == null || worker.getUser() == null
                    ? null
                    : worker.getUser().getId());
            Manager manager = order.getManager();
            if (manager == null && order.getCompany() != null) {
                manager = order.getCompany().getManager();
            }
            link.setShadowRouteManagerId(manager == null ? null : manager.getId());
            link.setShadowRouteManagerUserId(manager == null || manager.getUser() == null
                    ? null
                    : manager.getUser().getId());
            return generation;
        }).when(contractorPaymentShadowService).prepareLivePaymentLinkSource(
                any(PaymentLink.class),
                any(LocalDateTime.class)
        );
        return properties;
    }

    private Order order(Long id, String companyTitle, BigDecimal sum) {
        Company company = new Company();
        company.setTitle(companyTitle);

        Order order = new Order();
        order.setId(id);
        order.setCompany(company);
        order.setSum(sum);
        return order;
    }

    private Manager manager(String username) {
        User user = new User();
        user.setUsername(username);

        Manager manager = new Manager();
        manager.setUser(user);
        return manager;
    }

    private PaymentProfile profile(Long id, String code, String name, String terminalKey) {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(id);
        profile.setCode(code);
        profile.setName(name);
        profile.setProvider(PaymentProfile.PROVIDER_TBANK);
        profile.setTerminalKey(terminalKey);
        profile.setPasswordEnvKey("OTZIV_PAYMENTS_TBANK_PASSWORD");
        profile.setEnabled(true);
        profile.setDefaultProfile(TbankPaymentProfile.PRIMARY_CODE.equals(code));
        profile.setTestMode(true);
        return profile;
    }

    private ContractorPaymentRequisitesSnapshot requisites(
            Long allocationId,
            String recipient,
            String phone,
            String bank,
            String comment
    ) {
        return new ContractorPaymentRequisitesSnapshot(
                allocationId,
                recipient,
                phone,
                bank,
                comment
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Collection<PaymentLinkStatus>> paymentStatusCollectionCaptor() {
        return ArgumentCaptor.forClass((Class) Collection.class);
    }
}
