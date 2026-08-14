package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceCloseRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceManualCardPaymentRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentInitCheckRequest;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceBoardQueryRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService.FrozenCommonRouteAction;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.deletion.service.OrderDeletionService;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.service.NextOrderFailureNotifier;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.dto.PaymentRouteSelection;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.payments.service.ManualCardPaymentReviewNotificationService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.security.cert.CertPathBuilderException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@ExtendWith(MockitoExtension.class)
class CommonBillingServiceTest {

    @Mock
    private CommonBillingAccountRepository accountRepository;
    @Mock
    private CommonBillingAccountCompanyRepository accountCompanyRepository;
    @Mock
    private CommonInvoiceBoardQueryRepository invoiceBoardQueryRepository;
    @Mock
    private CommonInvoiceRepository invoiceRepository;
    @Mock
    private CommonInvoiceOrderRepository invoiceOrderRepository;
    @Mock
    private CommonInvoicePaymentRefRepository paymentRefRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private PaymentLinkService paymentLinkService;
    @Mock
    private ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;
    @Mock
    private ManualPaymentTaskService manualPaymentTaskService;
    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private OrderDtoMapper orderDtoMapper;
    @Mock
    private OrderStatusService orderStatusService;
    @Mock
    private OrderTransactionService orderTransactionService;
    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;
    @Mock
    private NextOrderFailureNotifier nextOrderFailureNotifier;
    @Mock
    private NextOrderRequestService nextOrderRequestService;
    @Mock
    private NextOrderRequestRepository nextOrderRequestRepository;
    @Mock
    private BadReviewTaskService badReviewTaskService;
    @Mock
    private ManagerPermissionService managerPermissionService;
    @Mock
    private UserService userService;
    @Mock
    private ClientChatMessageSender messageSender;
    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    @Mock
    private CommonInvoicePublicationBlockerService publicationBlockerService;
    @Mock
    private ManualPaymentAutoConfirmationService manualPaymentAutoConfirmationService;
    @Mock
    private ManualCardPaymentReviewNotificationService manualCardPaymentReviewNotificationService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ContractorCompletionRewardService contractorCompletionRewardService;
    @Mock
    private ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;
    @Mock
    private ContractorPaymentShadowService contractorPaymentShadowService;
    @Mock
    private TbankRuntimeSettingsService runtimeSettingsService;
    @Mock
    private PaymentProfileService paymentProfileService;
    @Mock
    private TbankClient tbankClient;
    @Mock
    private TbankTokenSigner tokenSigner;
    @Mock
    private TbankPaymentProperties properties;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ReviewRecoveryGateService recoveryGateService;
    @Mock
    private OrderPublicationApprovalService publicationApprovalService;
    @Mock
    private ObjectProvider<OrderPublicationApprovalService> publicationApprovalServiceProvider;
    @Mock
    private ObjectProvider<OrderDeletionService> orderDeletionServiceProvider;
    @Mock
    private OrderDeletionService orderDeletionService;
    @Mock
    private R0ObservabilityMetrics observabilityMetrics;

    @InjectMocks
    private CommonBillingService service;
    private final Map<Long, CommonInvoicePaymentRef> paymentRefStore = new LinkedHashMap<>();
    private long nextPaymentRefId;

    @BeforeEach
    void setUpLazyDependencies() {
        paymentRefStore.clear();
        nextPaymentRefId = 10_000L;
        ReflectionTestUtils.setField(service, "orderTransactionService", orderTransactionService);
        ReflectionTestUtils.setField(service, "orderStatusTransitionService", orderStatusTransitionService);
        ReflectionTestUtils.setField(service, "nextOrderRequestService", nextOrderRequestService);
        ReflectionTestUtils.setField(service, "publicationApprovalServiceProvider", publicationApprovalServiceProvider);
        ReflectionTestUtils.setField(service, "orderDeletionServiceProvider", orderDeletionServiceProvider);
        ReflectionTestUtils.setField(service, "paymentLinkServiceProvider", paymentLinkServiceProvider);
        lenient().when(publicationApprovalServiceProvider.getObject()).thenReturn(publicationApprovalService);
        lenient().when(orderDeletionServiceProvider.getObject()).thenReturn(orderDeletionService);
        lenient().when(paymentLinkServiceProvider.getObject()).thenReturn(paymentLinkService);
        lenient().when(paymentLinkService.selectCommonInvoiceRoute(any(), anyLong())).thenReturn(
                new PaymentRouteSelection(
                        TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK,
                        1L,
                        TbankPaymentProfile.PRIMARY_CODE,
                        "Основной магазин",
                        "",
                        null,
                        null,
                        null,
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""
                )
        );
        lenient().when(paymentProfileService.lockByIdForRouting(1L)).thenAnswer(ignored -> paymentProfile());
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        lenient().when(accountRepository.findByIdWithRelationsForUpdate(anyLong())).thenAnswer(invocation -> {
            CommonBillingAccount locked = account();
            locked.setId(invocation.getArgument(0));
            return Optional.of(locked);
        });
        lenient().when(paymentRefRepository.save(any(CommonInvoicePaymentRef.class))).thenAnswer(invocation -> {
            CommonInvoicePaymentRef ref = invocation.getArgument(0);
            if (ref.getId() == null) {
                ref.setId(nextPaymentRefId++);
            }
            paymentRefStore.put(ref.getId(), ref);
            return ref;
        });
        lenient().when(paymentRefRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(paymentRefStore.get(invocation.getArgument(0)))
        );
        lenient().when(paymentRefRepository.findByTbankOrderId(anyString())).thenAnswer(invocation -> {
            String orderId = invocation.getArgument(0);
            return paymentRefStore.values().stream()
                    .filter(ref -> orderId.equals(ref.getTbankOrderId()))
                    .findFirst();
        });
        lenient().when(paymentRefRepository.findByTbankPaymentId(anyString())).thenAnswer(invocation -> {
            String paymentId = invocation.getArgument(0);
            return paymentRefStore.values().stream()
                    .filter(ref -> paymentId.equals(ref.getTbankPaymentId()))
                    .findFirst();
        });
        lenient().doAnswer(invocation -> {
            CommonInvoicePaymentRef ref = invocation.getArgument(0);
            if (ref != null && ref.getId() != null) {
                paymentRefStore.remove(ref.getId());
            }
            return null;
        }).when(paymentRefRepository).delete(any(CommonInvoicePaymentRef.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shadowReservationFailureDoesNotEscapeAfterCommitCallback() {
        doThrow(new IllegalStateException("test shadow failure"))
                .when(contractorPaymentShadowService)
                .reserveForCommonInvoiceId(8_902L, "generation");

        TransactionSynchronizationManager.initSynchronization();
        ReflectionTestUtils.invokeMethod(
                service,
                "scheduleContractorShadowRoute",
                8_902L,
                "generation"
        );
        verify(contractorPaymentShadowService, never())
                .reserveForCommonInvoiceId(anyLong(), any());
        List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());

        assertDoesNotThrow(synchronizations.getFirst()::afterCommit);

        verify(contractorPaymentShadowService)
                .reserveForCommonInvoiceId(8_902L, "generation");
    }

    @Test
    void activeCommonInvoiceLookupUsesBoundedBulkQueries() {
        List<Long> orderIds = java.util.stream.LongStream.rangeClosed(1, 501)
                .boxed()
                .toList();
        when(invoiceOrderRepository.findLinkedOrderIds(any(), any())).thenAnswer(invocation -> {
            Collection<Long> chunk = invocation.getArgument(0);
            return chunk.stream()
                    .filter(id -> id == 1L || id == 501L)
                    .toList();
        });

        Set<Long> linked = service.findOrderIdsInActiveCommonInvoices(orderIds);

        assertEquals(Set.of(1L, 501L), linked);
        verify(invoiceOrderRepository, times(2)).findLinkedOrderIds(any(), any());
    }

    @Test
    void accountsBulkLoadsLatestInvoicesAndItemsWithoutPerAccountQueries() {
        CommonBillingAccount firstAccount = account();
        CommonBillingAccount secondAccount = account();
        secondAccount.setId(2L);
        secondAccount.setName("Второй плательщик");

        CommonInvoice firstInvoice = invoice(firstAccount);
        CommonInvoice secondInvoice = invoice(secondAccount);
        secondInvoice.setId(20L);
        secondInvoice.setToken("second-token");
        CommonInvoice secondPredecessor = invoice(secondAccount);
        secondPredecessor.setId(19L);
        secondInvoice.setInvoicePurpose("BAD_REVIEW_SUCCESSOR");
        secondInvoice.setSupersedesInvoice(secondPredecessor);
        CommonInvoiceOrder firstItem = item(firstInvoice, order(101L));
        CommonInvoiceOrder secondItem = item(secondInvoice, order(202L));
        firstItem.setPaid(true);
        secondItem.setPaid(true);

        when(accountRepository.findAllForAdmin()).thenReturn(List.of(firstAccount, secondAccount));
        when(accountCompanyRepository.findByAccountIds(List.of(1L, 2L))).thenReturn(List.of());
        when(invoiceRepository.findLatestCurrentForAccounts(eq(List.of(1L, 2L)), any()))
                .thenReturn(List.of(firstInvoice, secondInvoice));
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(10L, 20L)))
                .thenReturn(List.of(firstItem, secondItem));

        List<CommonBillingAccountResponse> responses = service.accounts();

        assertEquals(List.of(1L, 2L), responses.stream().map(CommonBillingAccountResponse::id).toList());
        assertEquals(List.of(10L, 20L), responses.stream()
                .map(CommonBillingAccountResponse::currentInvoice)
                .map(CommonInvoiceSummaryResponse::id)
                .toList());
        assertEquals("BAD_REVIEW_SUCCESSOR", responses.get(1).currentInvoice().invoicePurpose());
        assertEquals(19L, responses.get(1).currentInvoice().supersedesInvoiceId());
        verify(invoiceRepository, times(1)).findLatestCurrentForAccounts(eq(List.of(1L, 2L)), any());
        verify(invoiceOrderRepository, times(1)).findByInvoiceIdsWithOrders(List.of(10L, 20L));
        verify(invoiceRepository, never()).findCurrentForAccount(any(), any(), any(Pageable.class));
        verify(invoiceOrderRepository, never()).findByInvoiceIdWithOrders(any());
    }

    @Test
    void closePaidInvoiceObservesCompletionAndCaughtOrderFailure() throws Exception {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(501L);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setAmountKopecks(10_000L);

        Order order = new Order();
        order.setId(601L);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        item.setOrder(order);
        item.setAmountKopecks(10_000L);

        doThrow(new IllegalStateException("synthetic close failure"))
                .when(orderTransactionService).handlePaymentStatus(order, false);

        ReflectionTestUtils.invokeMethod(service, "closePaidInvoice", invoice, List.of(item));

        verify(observabilityMetrics).observeTransactionCompletion(COMMON_INVOICE_CLOSE);
        verify(observabilityMetrics).recordCaughtFailure(COMMON_INVOICE_CLOSE, CLOSE_ORDER);
        verify(invoiceRepository).save(invoice);
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
    }

    @Test
    void openNextOrdersRecordsCaughtFailureWithoutChangingControlFlow() {
        CommonBillingAccount account = new CommonBillingAccount();
        account.setAutoRepeatOrders(true);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(502L);
        invoice.setAccount(account);

        Order order = new Order();
        order.setId(602L);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(order);
        item.setInvoice(invoice);
        doThrow(new IllegalStateException("synthetic next-order failure"))
                .when(nextOrderRequestService).openForPaidOrder(order);

        List<String> failures = ReflectionTestUtils.invokeMethod(
                service,
                "openNextOrdersIfEnabled",
                invoice,
                List.of(item)
        );

        assertNotNull(failures);
        assertEquals(1, failures.size());
        verify(observabilityMetrics).recordCaughtFailure(COMMON_INVOICE_CLOSE, OPEN_NEXT_ORDER);
    }

    @Test
    void markOrderPaidClosesOnlySelectedOrderAndKeepsInvoicePartiallyPaid() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem);

        invoice.setAmountKopecks(200_000L);
        invoice.setPaidKopecks(0L);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(102L, 101L, 102L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(items);
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(firstItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(secondOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse response = service.markOrderPaid(10L, 101L);

        assertTrue(firstItem.isPaid());
        assertFalse(secondItem.isPaid());
        assertEquals("MANUAL", firstItem.getPaymentMethod());
        assertEquals("system", firstItem.getManualPaidBy());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertEquals(BigDecimal.valueOf(1000).setScale(2), response.summary().paid());
        assertEquals(BigDecimal.valueOf(1000).setScale(2), response.summary().remaining());
        verify(orderTransactionService).handlePaymentStatus(firstOrder, false);
        verify(orderTransactionService, never()).handlePaymentStatus(secondOrder, false);
        verify(manualPaymentAutoConfirmationService).retireOpenLinksForPaidOrder(firstOrder);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(firstOrder, "Оплата общего счета");
        verify(nextOrderRequestService, never()).openForPaidOrder(any());

        var lockOrder = inOrder(
                invoiceOrderRepository,
                orderAggregateMutationLockService,
                invoiceRepository,
                paymentLinkRepository,
                orderTransactionService
        );
        lockOrder.verify(invoiceOrderRepository).findOrderIdsByInvoiceId(10L);
        lockOrder.verify(orderAggregateMutationLockService).lock(101L);
        lockOrder.verify(orderAggregateMutationLockService).lock(102L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(102L);
        lockOrder.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        lockOrder.verify(orderTransactionService).handlePaymentStatus(firstOrder, false);
    }

    @Test
    void markOrderPaidRejectsBankInitReservedWhileWaitingForOrderLock() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink reservedLink = new PaymentLink();
        reservedLink.setOrder(order);
        reservedLink.setStatus(PaymentLinkStatus.CREATED);
        reservedLink.setBankInitNonce("active-init");

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of(reservedLink));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.markOrderPaid(10L, 101L)
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());

        var lockOrder = inOrder(
                orderAggregateMutationLockService,
                invoiceRepository,
                paymentLinkRepository
        );
        lockOrder.verify(orderAggregateMutationLockService).lock(101L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        lockOrder.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
    }

    @Test
    void markOrderPaidRejectsNeedsReconciliationWithoutProviderPaymentId() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink quarantinedLink = new PaymentLink();
        quarantinedLink.setOrder(order);
        quarantinedLink.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of(quarantinedLink));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.markOrderPaid(10L, 101L)
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
        verify(paymentLinkRepository, times(2)).findByOrderIdForUpdate(101L);
    }

    @Test
    void invoiceMutationRejectsMembershipDriftInsteadOfLockingNewOrderAfterInvoice() throws Exception {
        CommonInvoice invoice = invoice(account());
        CommonInvoiceOrder observedItem = item(invoice, order(101L));
        CommonInvoiceOrder concurrentlyAttachedItem = item(invoice, order(102L));

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L))
                .thenReturn(List.of(observedItem, concurrentlyAttachedItem));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.invoice(10L)
        );

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("common_invoice_membership_changed"));
        verify(orderAggregateMutationLockService).lock(101L);
        verify(orderAggregateMutationLockService, never()).lock(102L);
        verify(invoiceOrderRepository, never()).findByInvoiceIdWithOrders(10L);
        verify(paymentLinkRepository, never()).findByOrderIdForUpdate(any());
    }

    @Test
    void fullPrepaymentSettlementIsDeferredUntilOwningTransactionCommits() throws Exception {
        CommonInvoice invoice = invoice(account());
        invoice.setAmountKopecks(100_000L);
        TransactionSynchronizationManager.initSynchronization();

        Boolean deferred = ReflectionTestUtils.invokeMethod(
                service,
                "deferReadyCommonInvoiceFinalizationUntilAfterCommit",
                invoice
        );

        assertTrue(Boolean.TRUE.equals(deferred));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void manualPaymentRequiresCommentOrReceipt() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        CommonInvoiceOrder invoiceItem = item(invoice, order(101L));

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(invoiceItem));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.markPaid(
                        10L,
                        new ManualPaymentConfirmationRequest(" ", " "),
                        () -> "alex"
                )
        );

        assertEquals(400, exception.getStatusCode().value());
        assertFalse(invoiceItem.isPaid());
    }

    @Test
    void applyConfirmedOrderPaymentMarksOnlyLinkedItemPaidAndClosesLinkedOrder() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.REMINDER);
        Order paidOrder = order(101L);
        paidOrder.setStatus(status("Оплачено"));
        Order openOrder = order(102L);
        CommonInvoiceOrder paidItem = item(invoice, paidOrder);
        CommonInvoiceOrder openItem = item(invoice, openOrder);
        List<CommonInvoiceOrder> items = List.of(paidItem, openItem);
        LocalDateTime paidAt = LocalDateTime.of(2026, 6, 10, 18, 54, 53);
        PaymentLink confirmed = confirmedStandaloneBankPayment(501L, paidOrder, 100_000L);
        confirmed.setPaidAt(paidAt);

        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of(confirmed));
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(paidItem));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(paidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(openOrder)).thenReturn(BigDecimal.valueOf(1000));

        boolean applied = service.applyConfirmedOrderPayment(101L, paidAt, "T-Bank/SBP оплата заказа");

        assertTrue(applied);
        assertTrue(paidItem.isPaid());
        assertFalse(openItem.isPaid());
        assertEquals(paidAt, paidItem.getPaidAt());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertEquals(200_000L, invoice.getAmountKopecks());
        assertEquals(100_000L, invoice.getPaidKopecks());
        assertEquals(501L, paidItem.getSourcePaymentLinkId());
        assertNull(confirmed.getLastError());
        verify(orderTransactionService, never()).handlePaymentStatus(paidOrder, false);
        verify(orderTransactionService, never()).handlePaymentStatus(openOrder, false);
        verify(manualPaymentAutoConfirmationService).retireOpenLinksForPaidOrder(paidOrder);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(paidOrder, "Оплата общего счета");
        verify(nextOrderRequestService, never()).openForPaidOrder(any());
        verify(invoiceOrderRepository, never()).findOrderIdsByInvoiceId(10L);
        verify(paymentLinkRepository, never())
                .findFirstByOrder_IdAndStatusAndLastErrorIsNullOrderByPaidAtDesc(
                        102L,
                        PaymentLinkStatus.CONFIRMED
                );

        var lockOrder = inOrder(
                orderAggregateMutationLockService,
                paymentLinkRepository,
                invoiceOrderRepository,
                invoiceRepository
        );
        lockOrder.verify(orderAggregateMutationLockService).lock(101L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        lockOrder.verify(invoiceOrderRepository).findByOrderIdWithInvoice(101L);
        lockOrder.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
    }

    @Test
    void ordinaryInvoiceRefreshDoesNotApplyStandalonePaymentWithoutPaymentLinkLocks() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.REMINDER);
        Order paidOrder = order(101L);
        Order openOrder = order(102L);
        CommonInvoiceOrder paidItem = item(invoice, paidOrder);
        CommonInvoiceOrder openItem = item(invoice, openOrder);
        List<CommonInvoiceOrder> items = List.of(paidItem, openItem);
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(paidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(openOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse response = service.invoice(10L);

        assertFalse(paidItem.isPaid());
        assertNull(paidItem.getPaidAt());
        assertFalse(openItem.isPaid());
        assertEquals(CommonInvoiceStatus.REMINDER, invoice.getStatus());
        assertEquals(BigDecimal.ZERO.setScale(2), response.summary().paid());
        assertEquals(BigDecimal.valueOf(2000).setScale(2), response.summary().remaining());
        verify(orderTransactionService, never()).handlePaymentStatus(paidOrder, false);
        verify(orderTransactionService, never()).handlePaymentStatus(openOrder, false);
        verify(manualPaymentAutoConfirmationService, never()).retireOpenLinksForPaidOrder(paidOrder);
        verify(paymentInvoiceRetryScheduler, never()).cancelBadReviewAutoBan(paidOrder, "Оплата общего счета");
        verify(nextOrderRequestService, never()).openForPaidOrder(any());
        verify(paymentLinkRepository, never())
                .findFirstByOrder_IdAndStatusAndLastErrorIsNullOrderByPaidAtDesc(anyLong(), any());
    }

    @Test
    void invoiceReadKeepsInactivePredecessorAmountImmutable() {
        CommonBillingAccount account = account();
        CommonInvoice predecessor = invoice(account);
        predecessor.setStatus(CommonInvoiceStatus.UNPAID);
        predecessor.setAmountKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder historicalItem = item(predecessor, order);
        historicalItem.setActiveMembership(false);
        historicalItem.setUnpaid(true);
        historicalItem.setAmountKopecks(100_000L);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(predecessor));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(historicalItem));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse response = service.invoice(10L);

        assertEquals(100_000L, historicalItem.getAmountKopecks());
        assertEquals(100_000L, predecessor.getAmountKopecks());
        assertEquals(100_000L, response.summary().amountKopecks());
        verify(badReviewTaskService, never()).getPayableSum(order);
        verify(invoiceOrderRepository, never()).saveAll(any());
    }

    @Test
    void genericMarkPaidRejectsFrozenLiveContractorCommonSource() throws Exception {
        CommonInvoice invoice = invoice(account());
        invoice.setContractorAllocationId(701L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        CommonInvoiceOrder invoiceItem = item(invoice, order(101L));
        stubLockedInvoice(invoice, invoiceItem, invoiceItem.getOrder());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.markPaid(
                        10L,
                        new ManualPaymentConfirmationRequest("Выписка", ""),
                        () -> "manager"
                )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertFalse(invoiceItem.isPaid());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void genericPerOrderPaidRejectsFrozenLiveContractorCommonSource() {
        CommonInvoice invoice = invoice(account());
        invoice.setContractorAllocationId(702L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        CommonInvoiceOrder invoiceItem = item(invoice, order(101L));
        stubLockedInvoice(invoice, invoiceItem, invoiceItem.getOrder());

        assertThrows(
                ResponseStatusException.class,
                () -> service.markOrderPaid(
                        10L,
                        101L,
                        new ManualPaymentConfirmationRequest("Выписка", ""),
                        () -> "manager"
                )
        );

        verify(invoiceOrderRepository, never()).save(invoiceItem);
    }

    @Test
    void exactLatePredecessorConfirmationRetiresProvablyUnstartedSuccessor() {
        CommonBillingAccount account = account();
        CommonInvoice predecessor = invoice(account);
        predecessor.setStatus(CommonInvoiceStatus.UNPAID);
        predecessor.setContractorAllocationId(703L);
        predecessor.setPaymentRouteType("MANUAL_MOBILE_BANK");
        predecessor.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        predecessor.setAmountKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder predecessorItem = item(predecessor, order);
        predecessorItem.setActiveMembership(false);

        CommonInvoice successor = invoice(account);
        successor.setId(11L);
        successor.setInvoicePurpose("BAD_REVIEW_SUCCESSOR");
        successor.setSupersedesInvoice(predecessor);
        successor.setStatus(CommonInvoiceStatus.READY);
        successor.setAmountKopecks(100_000L);
        CommonInvoiceOrder successorItem = item(successor, order);
        successorItem.setActiveMembership(true);

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(703L);
        allocation.setAmountKopecks(100_000L);
        stubLockedInvoice(predecessor, predecessorItem, order);
        when(invoiceRepository.findSuccessorsForUpdate(10L)).thenReturn(List.of(successor));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(predecessorItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(11L)).thenReturn(List.of(successorItem));
        when(invoiceRepository.existsBySupersedesInvoice_Id(11L)).thenReturn(false);
        when(invoiceOrderRepository.deleteByInvoiceId(11L)).thenReturn(1);
        when(contractorPaymentLiveRoutingService.validatedCommonConfirmationSource(10L, 703L))
                .thenReturn(allocation);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.confirmContractorPaymentSource(
                10L,
                100_000L,
                LocalDateTime.of(2026, 8, 13, 12, 0),
                "Поступление найдено в выписке получателя",
                () -> "admin"
        );

        assertEquals(CommonInvoiceStatus.PAID, predecessor.getStatus());
        assertEquals(LocalDateTime.of(2026, 8, 13, 12, 0), predecessor.getPaidAt());
        assertTrue(predecessorItem.isActiveMembership());
        verify(invoiceRepository).deleteById(11L);
        verify(contractorPaymentShadowService, never()).reconcileCommonInvoiceId(11L);
    }

    @Test
    void exactLatePredecessorConfirmationFailsWhenSuccessorWasStarted() throws Exception {
        CommonInvoice predecessor = invoice(account());
        predecessor.setStatus(CommonInvoiceStatus.UNPAID);
        predecessor.setContractorAllocationId(704L);
        predecessor.setPaymentRouteType("MANUAL_MOBILE_BANK");
        predecessor.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        predecessor.setAmountKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder predecessorItem = item(predecessor, order);
        predecessorItem.setActiveMembership(false);
        CommonInvoice successor = invoice(predecessor.getAccount());
        successor.setId(12L);
        successor.setInvoicePurpose("BAD_REVIEW_SUCCESSOR");
        successor.setSupersedesInvoice(predecessor);
        successor.setSentAt(LocalDateTime.now().minusMinutes(1));
        CommonInvoiceOrder successorItem = item(successor, order);

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(704L);
        allocation.setAmountKopecks(100_000L);
        stubLockedInvoice(predecessor, predecessorItem, order);
        when(invoiceRepository.findSuccessorsForUpdate(10L)).thenReturn(List.of(successor));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(12L)).thenReturn(List.of(successorItem));
        when(contractorPaymentLiveRoutingService.validatedCommonConfirmationSource(10L, 704L))
                .thenReturn(allocation);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmContractorPaymentSource(
                        10L,
                        100_000L,
                        null,
                        "Поступление найдено",
                        () -> "admin"
                )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(CommonInvoiceStatus.UNPAID, predecessor.getStatus());
        verify(invoiceRepository, never()).deleteById(12L);
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void approveReviewOrdersMovesCheckOrdersToPublication() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order toCheckOrder = order(101L);
        toCheckOrder.setStatus(status("В проверку"));
        Order inCheckOrder = order(102L);
        inCheckOrder.setStatus(status("На проверке"));
        Order newOrder = order(103L);
        newOrder.setStatus(status("Новый"));
        List<CommonInvoiceOrder> items = List.of(
                item(invoice, toCheckOrder),
                item(invoice, inCheckOrder),
                item(invoice, newOrder)
        );

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.approveReviewOrders(10L);

        verify(publicationApprovalService).validateExistingOrder(101L);
        verify(publicationApprovalService).validateExistingOrder(102L);
        verify(publicationApprovalService).approveExistingOrder(
                101L,
                "invoiceId=10;source=approve_all"
        );
        verify(publicationApprovalService).approveExistingOrder(
                102L,
                "invoiceId=10;source=approve_all"
        );
        verify(publicationApprovalService, never()).approveExistingOrder(
                eq(103L),
                any()
        );
    }

    @Test
    void approveReviewOrdersPrevalidatesEveryOrderBeforeChangingAnyOrder() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order firstOrder = order(101L);
        firstOrder.setStatus(status("В проверку"));
        Order secondOrder = order(102L);
        secondOrder.setStatus(status("На проверке"));
        List<CommonInvoiceOrder> items = List.of(
                item(invoice, firstOrder),
                item(invoice, secondOrder)
        );

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        doAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            if (Long.valueOf(102L).equals(orderId)) {
                throw new com.hunt.otziv.p_products.review.exception.PublicationApprovalException(
                        102L,
                        "есть пустой текст",
                        "заполните текст"
                );
            }
            return null;
        }).when(publicationApprovalService).validateExistingOrder(any());

        assertThrows(
                com.hunt.otziv.p_products.review.exception.PublicationApprovalException.class,
                () -> service.approveReviewOrders(10L)
        );

        verify(publicationApprovalService).validateExistingOrder(101L);
        verify(publicationApprovalService).validateExistingOrder(102L);
        verify(publicationApprovalService, never()).approveExistingOrder(any(), any());
    }

    @Test
    void approveReviewOrdersRejectsInvoiceWithoutCheckOrders() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order order = order(101L);
        order.setStatus(status("Новый"));

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item(invoice, order)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.approveReviewOrders(10L)
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(orderStatusTransitionService, never()).changeStatusForCommonBillingOrder(any(), any());
    }

    @Test
    void markPaidOpensNextOrdersOnlyAfterWholeInvoiceIsClosed() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem);

        invoice.setAmountKopecks(200_000L);
        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(firstOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(secondOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse response = service.markPaid(10L);

        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertNotNull(invoice.getClosedAt());
        assertEquals(invoice.getPaidAt(), invoice.getClosedAt());
        assertEquals("PAID", invoice.getCloseReason());
        assertEquals("system", invoice.getClosedBy());
        assertEquals(BigDecimal.valueOf(2000).setScale(2), response.summary().paid());
        assertEquals("MANUAL", invoice.getPaymentMethod());
        assertEquals("system", invoice.getManualPaidBy());
        assertEquals("MANUAL", firstItem.getPaymentMethod());
        assertEquals("Внутреннее подтверждение", firstItem.getManualPaymentComment());
        verify(orderTransactionService).handlePaymentStatus(firstOrder, false);
        verify(orderTransactionService).handlePaymentStatus(secondOrder, false);
        verify(manualPaymentAutoConfirmationService).retireOpenLinksForPaidOrder(firstOrder);
        verify(manualPaymentAutoConfirmationService).retireOpenLinksForPaidOrder(secondOrder);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(firstOrder, "Оплата общего счета");
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(secondOrder, "Оплата общего счета");
        verify(nextOrderRequestService).openForPaidOrder(firstOrder);
        verify(nextOrderRequestService).openForPaidOrder(secondOrder);
    }

    @Test
    void markPaidDoesNotOpenNextOrdersWhenAutoRepeatDisabled() throws Exception {
        CommonBillingAccount account = account();
        account.setAutoRepeatOrders(false);
        CommonInvoice invoice = invoice(account);
        CommonInvoiceOrder item = item(invoice, order(101L));
        List<CommonInvoiceOrder> items = List.of(item);

        invoice.setAmountKopecks(100_000L);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markPaid(10L);

        verify(nextOrderRequestService, never()).openForPaidOrder(any());
    }

    @Test
    void markPaidLeavesInvoiceVisibleWhenOrderClosingFails() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        List<CommonInvoiceOrder> items = List.of(item);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderTransactionService.handlePaymentStatus(order, false)).thenThrow(new RuntimeException("zp"));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markPaid(10L);

        assertFalse(item.isPaid());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("close_failed"));
        verify(nextOrderRequestService, never()).openForPaidOrder(any());
    }

    @Test
    void markPaidLeavesInvoiceVisibleAndNotifiesWhenNextOrderCreationFails() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        List<CommonInvoiceOrder> items = List.of(item);

        invoice.setAmountKopecks(100_000L);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(nextOrderRequestService.openForPaidOrder(order)).thenThrow(new RuntimeException("next"));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markPaid(10L);

        assertTrue(item.isPaid());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("next_order_failed"));
        verify(nextOrderFailureNotifier).notifyManager(any(), any(), any(), any());
    }

    @Test
    void managerCanCloseStandaloneRouteConflictWhenCommonInvoiceWasPaidToCard() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(
                "standalone_payment_route_conflict: Заказ #23987 нельзя включить в общий счет: ссылка #3967 EXPIRED"
        );
        Order firstOrder = order(23_489L);
        Order secondOrder = order(23_490L);
        Order thirdOrder = order(23_987L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        CommonInvoiceOrder thirdItem = item(invoice, thirdOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem, thirdItem);

        PaymentLink localBankDraft = new PaymentLink();
        localBankDraft.setId(4_778L);
        localBankDraft.setOrder(firstOrder);
        localBankDraft.setStatus(PaymentLinkStatus.CREATED);
        localBankDraft.setPaymentMethod(PaymentMethod.BANK_FORM);
        localBankDraft.setAmountKopecks(200_000L);
        PaymentLink expiredManual = new PaymentLink();
        expiredManual.setId(3_967L);
        expiredManual.setOrder(thirdOrder);
        expiredManual.setStatus(PaymentLinkStatus.EXPIRED);
        expiredManual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        expiredManual.setAmountKopecks(120_000L);
        expiredManual.setLastError("Платежная ссылка пересоздана из-за изменения суммы или маршрута оплаты");
        PaymentLink waitingManual = new PaymentLink();
        waitingManual.setId(4_640L);
        waitingManual.setOrder(thirdOrder);
        waitingManual.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        waitingManual.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        waitingManual.setAmountKopecks(120_000L);
        List<PaymentLink> links = List.of(localBankDraft, expiredManual, waitingManual);
        List<Long> orderIds = List.of(23_489L, 23_490L, 23_987L);

        invoice.setAmountKopecks(640_000L);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(orderIds);
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(items);
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(orderAggregateMutationLockService.lock(23_489L)).thenReturn(firstOrder);
        when(orderAggregateMutationLockService.lock(23_490L)).thenReturn(secondOrder);
        when(orderAggregateMutationLockService.lock(23_987L)).thenReturn(thirdOrder);
        when(paymentLinkRepository.findByOrderIdInForRead(orderIds)).thenReturn(links);
        when(paymentLinkRepository.findByOrderIdForUpdate(23_489L)).thenReturn(List.of(localBankDraft));
        when(paymentLinkRepository.findByOrderIdForUpdate(23_490L)).thenReturn(List.of());
        when(paymentLinkRepository.findByOrderIdForUpdate(23_987L)).thenReturn(List.of(expiredManual, waitingManual));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of());
        when(badReviewTaskService.getPayableSum(firstOrder)).thenReturn(BigDecimal.valueOf(2000));
        when(badReviewTaskService.getPayableSum(secondOrder)).thenReturn(BigDecimal.valueOf(3200));
        when(badReviewTaskService.getPayableSum(thirdOrder)).thenReturn(BigDecimal.valueOf(1200));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse response = service.reportPaidByManualCardTransfer(
                10L,
                new CommonInvoiceManualCardPaymentRequest("Клиент перевел всю сумму менеджеру на карту"),
                () -> "manager@example.ru"
        );

        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertEquals(640_000L, invoice.getPaidKopecks());
        assertEquals("MANUAL", invoice.getPaymentMethod());
        assertEquals("manager@example.ru", invoice.getManualPaidBy());
        assertEquals("Клиент перевел всю сумму менеджеру на карту", invoice.getManualPaymentComment());
        assertEquals(PaymentLinkStatus.CANCELED, localBankDraft.getStatus());
        assertEquals(PaymentLinkStatus.CANCELED, expiredManual.getStatus());
        assertEquals(PaymentLinkStatus.CANCELED, waitingManual.getStatus());
        assertEquals("PAID", response.summary().status());
        ArgumentCaptor<ManualCardPaymentReviewNotificationService.CommonInvoiceReviewRequest> notification =
                ArgumentCaptor.forClass(ManualCardPaymentReviewNotificationService.CommonInvoiceReviewRequest.class);
        verify(manualCardPaymentReviewNotificationService).notifyCommonInvoiceAfterCommit(notification.capture());
        assertEquals(640_000L, notification.getValue().amountKopecks());
        assertEquals(orderIds, notification.getValue().orderIds());
        assertEquals(Set.of(4_778L, 3_967L, 4_640L), Set.copyOf(notification.getValue().closedRouteIds()));
    }

    @Test
    void managerManualCommonPaymentFailsClosedForAuthorizedStandaloneBankPayment() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("standalone_payment_route_conflict: active bank route");
        Order order = order(23_987L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink authorized = confirmedStandaloneBankPayment(3_967L, order, 100_000L);
        authorized.setStatus(PaymentLinkStatus.AUTHORIZED);
        authorized.setProviderTerminalStatus("AUTHORIZED");
        authorized.setPaidAt(null);
        authorized.setConfirmedAmountKopecks(null);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(23_987L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(orderAggregateMutationLockService.lock(23_987L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdInForRead(List.of(23_987L))).thenReturn(List.of(authorized));
        when(paymentLinkRepository.findByOrderIdForUpdate(23_987L)).thenReturn(List.of(authorized));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.reportPaidByManualCardTransfer(
                        10L,
                        new CommonInvoiceManualCardPaymentRequest("Оплачено менеджеру"),
                        () -> "manager@example.ru"
                )
        );

        assertTrue(exception.getReason().contains("не закрыт безопасно"));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertFalse(item.isPaid());
        verify(manualCardPaymentReviewNotificationService, never()).notifyCommonInvoiceAfterCommit(any());
    }

    @Test
    void managerManualCommonPaymentCannotCancelStandaloneContractorRoute() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("standalone_payment_route_conflict: contractor route");
        Order order = order(23_988L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink contractorRoute = new PaymentLink();
        contractorRoute.setId(3_968L);
        contractorRoute.setOrder(order);
        contractorRoute.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        contractorRoute.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        contractorRoute.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        contractorRoute.setAmountKopecks(100_000L);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(23_988L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(orderAggregateMutationLockService.lock(23_988L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdInForRead(List.of(23_988L)))
                .thenReturn(List.of(contractorRoute));
        when(paymentLinkRepository.findByOrderIdForUpdate(23_988L))
                .thenReturn(List.of(contractorRoute));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.reportPaidByManualCardTransfer(
                        10L,
                        new CommonInvoiceManualCardPaymentRequest("Оплачено менеджеру"),
                        () -> "manager@example.ru"
                )
        );

        assertTrue(exception.getReason().contains("не закрыт безопасно"));
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, contractorRoute.getStatus());
        assertFalse(item.isPaid());
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void managerManualCommonPaymentCancelsFormShowedBankRouteBeforeClosingInvoice() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("standalone_payment_route_conflict: form showed");
        Order order = order(25_047L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink bankLink = new PaymentLink();
        bankLink.setId(5_208L);
        bankLink.setOrder(order);
        bankLink.setStatus(PaymentLinkStatus.INITIATED);
        bankLink.setPaymentMethod(PaymentMethod.SBP_QR);
        bankLink.setAmountKopecks(100_000L);
        bankLink.setTbankPaymentId("8959416400");
        bankLink.setTbankOrderId("o25047-f0354eff3ce1");
        bankLink.setTbankTerminalKey("terminal");
        bankLink.setProviderTerminalStatus("FORM_SHOWED");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(25_047L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(orderAggregateMutationLockService.lock(25_047L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdInForRead(List.of(25_047L))).thenReturn(List.of(bankLink));
        when(paymentLinkRepository.findByOrderIdForUpdate(25_047L)).thenReturn(List.of(bankLink));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of());
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        doAnswer(invocation -> {
            bankLink.setStatus(PaymentLinkStatus.CANCELED);
            bankLink.setProviderTerminalStatus("CANCELED");
            return null;
        }).when(paymentLinkService).cancel(5_208L);

        service.reportPaidByManualCardTransfer(
                10L,
                new CommonInvoiceManualCardPaymentRequest("Клиент оплатил переводом на карту"),
                () -> "manager@example.ru"
        );

        verify(paymentLinkService).reconcileBankLink(eq(5_208L), any(LocalDateTime.class));
        verify(paymentLinkService).cancel(5_208L);
        assertEquals(PaymentLinkStatus.CANCELED, bankLink.getStatus());
        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertTrue(item.isPaid());
    }

    @Test
    void markUnpaidMovesAllOpenItemsThroughOrderBusinessLogic() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markUnpaid(10L);

        assertEquals(CommonInvoiceStatus.UNPAID, invoice.getStatus());
        assertTrue(firstItem.isUnpaid());
        assertTrue(secondItem.isUnpaid());
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Не оплачено");
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(102L, "Не оплачено");
        verify(badReviewTaskService).createTasksForUnpaidOrder(firstOrder);
        verify(badReviewTaskService).createTasksForUnpaidOrder(secondOrder);
    }

    @Test
    void markUnpaidRejectsPaidInvoice() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PAID);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));

        assertThrows(ResponseStatusException.class, () -> service.markUnpaid(10L));
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
    }

    @Test
    void markUnpaidRejectsInvoiceWithoutUnpaidItems() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        CommonInvoiceOrder item = item(invoice, order(101L));
        item.setPaid(true);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        assertThrows(ResponseStatusException.class, () -> service.markUnpaid(10L));
        verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
    }

    @Test
    void markUnpaidDoesNotSetInvoiceUnpaidWhenAnyOrderTransitionFails() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        doAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            if (Long.valueOf(102L).equals(orderId)) {
                throw new RuntimeException("status");
            }
            return true;
        }).when(orderStatusTransitionService).changeStatusForCommonBillingOrder(any(), any());

        assertThrows(ResponseStatusException.class, () -> service.markUnpaid(10L));

        assertEquals(CommonInvoiceStatus.INVOICED, invoice.getStatus());
        assertFalse(firstItem.isUnpaid());
        assertFalse(secondItem.isUnpaid());
        verify(invoiceRepository, never()).save(invoice);
        verify(invoiceOrderRepository, never()).saveAll(any());
    }

    @Test
    void markBanRejectsManagerWhenBadReviewTasksAreStillPending() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getSummaryByOrderIds(List.of(101L)))
                .thenReturn(Map.of(101L, new com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary(
                        2,
                        1,
                        1,
                        0,
                        BigDecimal.valueOf(300),
                        BigDecimal.valueOf(300)
                )));

        assertThrows(ResponseStatusException.class, () -> service.markBan(10L));

        assertEquals(CommonInvoiceStatus.UNPAID, invoice.getStatus());
        verify(orderStatusTransitionService, never()).changeStatusForCommonBillingOrder(any(), any());
        verify(orderStatusTransitionService, never()).changeStatusForPrivilegedCommonBillingOrder(any(), any());
    }

    @Test
    void markBanMovesAllUnpaidItemsToBanWhenBadReviewTasksAreDone() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(firstItem, secondItem));
        when(badReviewTaskService.getSummaryByOrderIds(List.of(101L, 102L)))
                .thenReturn(Map.of(
                        101L, new com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(300), BigDecimal.ZERO),
                        102L, new com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(400), BigDecimal.ZERO)
                ));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markBan(10L);

        assertEquals(CommonInvoiceStatus.BAN, invoice.getStatus());
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Бан");
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(102L, "Бан");
    }

    @Test
    void privilegedMarkBanCancelsPendingBadReviewTasks() throws Exception {
        authenticateAdmin();
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getSummaryByOrderIds(List.of(101L)))
                .thenReturn(Map.of(101L, new com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary(
                        2,
                        1,
                        1,
                        0,
                        BigDecimal.valueOf(300),
                        BigDecimal.valueOf(300)
                )));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markBan(10L);

        assertEquals(CommonInvoiceStatus.BAN, invoice.getStatus());
        verify(badReviewTaskService).cancelPendingTasksForOrder(order);
        verify(orderStatusTransitionService).changeStatusForPrivilegedCommonBillingOrder(101L, "Бан");
        verify(orderStatusTransitionService, never()).changeStatusForCommonBillingOrder(any(), any());
    }

    @Test
    void terminalInvoicesRejectPaymentMessagesAndPositionChanges() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice paidInvoice = invoice(account);
        paidInvoice.setStatus(CommonInvoiceStatus.PAID);
        CommonInvoice unpaidInvoice = invoice(account);
        unpaidInvoice.setStatus(CommonInvoiceStatus.UNPAID);
        CommonInvoice banInvoice = invoice(account);
        banInvoice.setStatus(CommonInvoiceStatus.BAN);
        CommonInvoice disabledInvoice = invoice(account);
        disabledInvoice.setStatus(CommonInvoiceStatus.DISABLED);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(paidInvoice))
                .thenReturn(Optional.of(paidInvoice))
                .thenReturn(Optional.of(paidInvoice))
                .thenReturn(Optional.of(paidInvoice))
                .thenReturn(Optional.of(unpaidInvoice))
                .thenReturn(Optional.of(unpaidInvoice))
                .thenReturn(Optional.of(unpaidInvoice))
                .thenReturn(Optional.of(unpaidInvoice))
                .thenReturn(Optional.of(banInvoice))
                .thenReturn(Optional.of(banInvoice))
                .thenReturn(Optional.of(banInvoice))
                .thenReturn(Optional.of(banInvoice))
                .thenReturn(Optional.of(disabledInvoice))
                .thenReturn(Optional.of(disabledInvoice))
                .thenReturn(Optional.of(disabledInvoice))
                .thenReturn(Optional.of(disabledInvoice));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));
        assertThrows(ResponseStatusException.class, () -> service.sendManualReminder(10L));
        assertThrows(ResponseStatusException.class, () -> service.markPaid(10L));
        assertThrows(ResponseStatusException.class, () -> service.markOrderPaid(10L, 101L));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));
        assertThrows(ResponseStatusException.class, () -> service.sendManualReminder(10L));
        assertThrows(ResponseStatusException.class, () -> service.markOrderPaid(10L, 101L));
        assertThrows(ResponseStatusException.class, () -> service.detachOrder(10L, 101L));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));
        assertThrows(ResponseStatusException.class, () -> service.sendManualReminder(10L));
        assertThrows(ResponseStatusException.class, () -> service.markPaid(10L));
        assertThrows(ResponseStatusException.class, () -> service.detachOrder(10L, 101L));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));
        assertThrows(ResponseStatusException.class, () -> service.sendManualReminder(10L));
        assertThrows(ResponseStatusException.class, () -> service.markPaid(10L));
        assertThrows(ResponseStatusException.class, () -> service.detachOrder(10L, 101L));

        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void publicInvoiceIsPayableWhileInvoiceIsCollecting() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        var response = service.publicInvoice("token");

        assertTrue(response.payable());
        assertEquals(CommonInvoiceStatus.COLLECTING.name(), response.status());
        assertEquals(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK, response.paymentRouteType());
        assertNotNull(invoice.getPaymentRouteSelectedAt());
    }

    @Test
    void publicInvoiceFreezesManagerTextRouteAndReusesItAfterSettingsChange() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        CommonInvoiceOrder item = item(invoice, order(101L));
        PaymentRouteSelection managerText = new PaymentRouteSelection(
                TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT,
                null,
                "",
                "",
                "",
                null,
                null,
                null,
                "",
                "",
                "",
                "",
                "",
                "Оплатите единый счет по реквизитам менеджера."
        );

        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(paymentLinkService.selectCommonInvoiceRoute(any(), eq(100_000L))).thenReturn(managerText);

        var first = service.publicInvoice("token");
        var second = service.publicInvoice("token");

        assertEquals(TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT, first.paymentRouteType());
        assertEquals("Оплатите единый счет по реквизитам менеджера.", first.paymentInstructionText());
        assertEquals(first.paymentRouteType(), second.paymentRouteType());
        verify(paymentLinkService, times(1)).selectCommonInvoiceRoute(any(), eq(100_000L));
    }

    @Test
    void managerTextCommonInvoiceSendRetainsCustomInstructionWithoutCopyButton() throws Exception {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setPaymentRouteType(TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT);
        invoice.setPaymentRouteInstructionText("Оплатите единый счет по реквизитам менеджера.");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(1));
        invoice.setPaymentRouteAmountKopecks(100_000L);
        CommonInvoiceOrder item = item(invoice, order(101L));

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.sent("Telegram"));

        service.sendInvoice(10L, true);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageSender).send(any(), any(), any(), text.capture());
        assertTrue(text.getValue().contains("Оплатите единый счет по реквизитам менеджера."));
        verify(messageSender, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void newContractorCommonRouteKeepsLegacyPiiBlankAndRendersEncryptedSnapshot() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setShadowRouteContractorEligible(true);
        CommonInvoiceOrder item = item(invoice, order(101L));

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(499L);
        allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
        allocation.setRecipientNameSnapshot("Получатель snapshot");
        allocation.setPaymentPhoneSnapshot("+79990000499");
        allocation.setBankNameSnapshot("Snapshot банк");
        allocation.setPaymentCommentSnapshot("Snapshot комментарий");

        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.enabledForNewRoutes()).thenReturn(true);
        when(contractorPaymentLiveRoutingService.reserveForCommonInvoice(
                eq(invoice), any(), any(), eq(100_000L)
        )).thenReturn(allocation);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 100_000L))
                .thenReturn(Optional.of(requisites(
                        499L,
                        "Получатель snapshot",
                        "+79990000499",
                        "Snapshot банк",
                        "Snapshot комментарий"
                )));

        var response = service.publicInvoice("token");

        assertEquals(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE,
                invoice.getPaymentRouteManualSource());
        assertNull(invoice.getPaymentRouteManualPhone());
        assertNull(invoice.getPaymentRouteManualRecipient());
        assertNull(invoice.getPaymentRouteManualComment());
        assertNull(invoice.getPaymentRouteInstructionText());
        assertEquals("+79990000499", response.manualPhone());
        assertEquals("Получатель snapshot", response.manualRecipientName());
        assertEquals("Snapshot банк", response.manualBankName());
        assertEquals("Snapshot комментарий", response.manualComment());
    }

    @Test
    void contractorCommonInvoiceSendUsesFrozenSnapshotInTextAndTelegramCopyButton() throws Exception {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(4_990L);
        invoice.setPaymentRouteType(PaymentMethod.MANUAL_MOBILE_BANK.name());
        invoice.setPaymentRouteManualType(ManualPaymentType.MOBILE_BANK);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(1));
        invoice.setPaymentRouteAmountKopecks(100_000L);
        CommonInvoiceOrder item = item(invoice, order(101L));

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 4_990L))
                .thenReturn(FrozenCommonRouteAction.KEEP);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 100_000L))
                .thenReturn(Optional.of(requisites(
                        4_990L,
                        "Получатель snapshot",
                        "2202208238396676",
                        "Snapshot банк",
                        "Snapshot комментарий"
                )));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.sent("Telegram"));

        service.sendInvoice(10L, true);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TelegramTransferCopyButton> button =
                ArgumentCaptor.forClass(TelegramTransferCopyButton.class);
        verify(messageSender).send(any(), any(), any(), text.capture(), button.capture());
        assertTrue(text.getValue().contains("Оплата по номеру карты: 2202208238396676"));
        assertTrue(text.getValue().contains("Получатель: Получатель snapshot"));
        assertEquals("Скопировать номер карты", button.getValue().text());
        assertEquals("2202208238396676", button.getValue().copyText());
    }

    @Test
    void disabledLiveMasterKeepsAlreadyPublishedContractorRouteUnchanged() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(500L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentRouteAmountKopecks(100_000L);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualPhone("+79990001122");
        invoice.setPaymentRouteManualRecipient("Старый получатель");
        invoice.setPaymentRouteManualBankName("Старый банк");
        invoice.setPaymentRouteManualComment("Старый комментарий");
        CommonInvoiceOrder item = item(invoice, order(101L));
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 500L))
                .thenReturn(FrozenCommonRouteAction.KEEP);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 100_000L))
                .thenReturn(Optional.of(requisites(
                        500L,
                        "Получатель из snapshot",
                        "+79990009900",
                        "Snapshot банк",
                        "Snapshot комментарий"
                )));

        var response = service.publicInvoice("token");

        assertEquals("MANUAL_MOBILE_BANK", response.paymentRouteType());
        assertEquals("+79990009900", response.manualPhone());
        assertEquals("Получатель из snapshot", response.manualRecipientName());
        assertEquals("Snapshot банк", response.manualBankName());
        assertEquals(500L, invoice.getContractorAllocationId());
        verify(contractorPaymentLiveRoutingService, never()).enabledForNewRoutes();
        verify(contractorPaymentLiveRoutingService, never()).reserveForCommonInvoice(any(), any(), any(), anyLong());
    }

    @Test
    void publicInvoiceRedactsTerminalContractorRequisitesButKeepsEncryptedSourceState() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(506L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentRouteAmountKopecks(100_000L);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualPhone("+79990001122");
        invoice.setPaymentRouteManualRecipient("Закрытый получатель");
        invoice.setPaymentRouteManualBankName("Закрытый банк");
        invoice.setPaymentRouteManualComment("Закрытый комментарий");
        CommonInvoiceOrder item = item(invoice, order(101L));
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 506L))
                .thenReturn(FrozenCommonRouteAction.KEEP);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 100_000L))
                .thenReturn(Optional.empty());

        var response = service.publicInvoice("token");

        assertTrue(response.payable());
        assertEquals("", response.manualPhone());
        assertEquals("", response.manualRecipientName());
        assertEquals("", response.manualBankName());
        assertEquals("", response.manualComment());
        assertFalse(response.clientReportable());
        assertEquals("Закрытый получатель", invoice.getPaymentRouteManualRecipient());
    }

    @Test
    void publicInvoiceRedactsContractorRequisitesWhenFrozenAmountNoLongerMatches() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(507L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentRouteAmountKopecks(120_000L);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualPhone("+79990001123");
        invoice.setPaymentRouteManualRecipient("Получатель старой суммы");
        CommonInvoiceOrder item = item(invoice, order(101L));
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 507L))
                .thenReturn(FrozenCommonRouteAction.KEEP);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 100_000L))
                .thenReturn(Optional.empty());

        var response = service.publicInvoice("token");

        assertTrue(response.payable());
        assertEquals("", response.manualPhone());
        assertEquals("", response.manualRecipientName());
        verify(contractorPaymentLiveRoutingService).activeCommonInvoiceRequisites(invoice, 100_000L);
    }

    @Test
    void publicInvoiceKeepsFrozenContractorRequisitesAfterLegitimatePartialPayment() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setContractorAllocationId(508L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentRouteAmountKopecks(100_000L);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualPhone("+79990001124");
        invoice.setPaymentRouteManualRecipient("Получатель остатка");
        invoice.setPaymentRouteManualBankName("Банк остатка");
        CommonInvoiceOrder paidItem = item(invoice, order(101L));
        paidItem.setAmountKopecks(50_000L);
        paidItem.setPaid(true);
        CommonInvoiceOrder openItem = item(invoice, order(102L));
        openItem.setAmountKopecks(50_000L);
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L))
                .thenReturn(List.of(paidItem, openItem));
        when(badReviewTaskService.getPayableSum(openItem.getOrder())).thenReturn(BigDecimal.valueOf(500));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 508L))
                .thenReturn(FrozenCommonRouteAction.KEEP);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 50_000L))
                .thenReturn(Optional.of(requisites(
                        508L,
                        "Получатель остатка snapshot",
                        "+79990001125",
                        "Банк остатка snapshot",
                        ""
                )));

        var response = service.publicInvoice("token");

        assertTrue(response.payable());
        assertEquals(50_000L, response.remainingKopecks());
        assertEquals("+79990001125", response.manualPhone());
        assertEquals("Получатель остатка snapshot", response.manualRecipientName());
        assertEquals("Банк остатка snapshot", response.manualBankName());
        assertFalse(response.clientReportable());
    }

    @Test
    void publicReportedPaidDelegatesTokenOnlyEvidenceAndReturnsReportedTimestamp() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(505L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualPhone("+79990001122");
        invoice.setPaymentRouteManualRecipient("Получатель");
        invoice.setPaymentRouteAmountKopecks(100_000L);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(1));
        CommonInvoiceOrder item = item(invoice, order(101L));
        LocalDateTime reportedAt = LocalDateTime.now();

        doAnswer(invocation -> {
            invoice.setClientReportedAt(reportedAt);
            return reportedAt;
        }).when(contractorPaymentLiveRoutingService).recordCommonClientReported("token");
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 505L))
                .thenReturn(FrozenCommonRouteAction.KEEP);
        when(contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, 100_000L))
                .thenReturn(Optional.of(requisites(
                        505L,
                        "Получатель",
                        "+79990001122",
                        "",
                        ""
                )));

        var response = service.reportPublicCommonPayment("token");

        verify(contractorPaymentLiveRoutingService).recordCommonClientReported("token");
        assertEquals(reportedAt, response.clientReportedAt());
        assertFalse(response.clientReportable());
        assertEquals(0L, response.paidKopecks());
    }

    @Test
    void terminalPublicReportFailsBeforeAnyPublicRequisitesAreRendered() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Маршрут уже закрыт"))
                .when(contractorPaymentLiveRoutingService)
                .recordCommonClientReported("terminal-token");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reportPublicCommonPayment("terminal-token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(invoiceRepository, never()).findByTokenWithAccount("terminal-token");
        verify(invoiceRepository, never()).findByTokenWithAccountForUpdate("terminal-token");
    }

    @Test
    void returnedCommonRouteBlocksAutomaticReissueWithoutAttemptBoundEvidence() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(501L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentRouteAmountKopecks(100_000L);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualPhone("+70000000000");
        invoice.setPaymentRouteManualRecipient("Предыдущий получатель");
        CommonInvoiceOrder item = item(invoice, order(101L));
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 501L))
                .thenReturn(FrozenCommonRouteAction.BLOCK_RECONCILIATION);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.publicInvoice("token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("автоматическая повторная выдача"));
        assertEquals(501L, invoice.getContractorAllocationId());
        assertEquals("+70000000000", invoice.getPaymentRouteManualPhone());
        assertEquals("Предыдущий получатель", invoice.getPaymentRouteManualRecipient());
        verify(contractorPaymentLiveRoutingService, never()).enabledForNewRoutes();
        verify(contractorPaymentLiveRoutingService, never())
                .reserveForCommonInvoice(any(), any(), any(), anyLong());
    }

    @Test
    void unresolvedReturnAmountBlocksCommonRouteReissue() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(503L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(5));
        CommonInvoiceOrder item = item(invoice, order(101L));
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));
        when(contractorPaymentLiveRoutingService.frozenCommonRouteAction(10L, 503L))
                .thenReturn(FrozenCommonRouteAction.BLOCK_RECONCILIATION);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.publicInvoice("token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("требует сверки"));
        verify(contractorPaymentLiveRoutingService, never()).enabledForNewRoutes();
    }

    @Test
    void frozenCommonPaymentRouteRejectsCompositionChanges() {
        CommonInvoice invoice = invoice(account());
        invoice.setPaymentRouteType(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service,
                        "attachOrderWithoutInvoiceRefresh",
                        invoice,
                        order(101L)
                )
        );

        assertTrue(error.getReason().contains("Состав общего счета уже зафиксирован"));
        verify(invoiceOrderRepository, never()).save(any(CommonInvoiceOrder.class));
    }

    @Test
    void publicInvoiceLocksOrdersInCanonicalOrderBeforeInvoice() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceToken("token")).thenReturn(List.of(102L, 101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L))
                .thenReturn(List.of(item, item(invoice, order(102L))));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        service.publicInvoice("token");

        verify(invoiceRepository).findByTokenWithAccountForUpdate("token");

        var lockOrder = inOrder(
                invoiceOrderRepository,
                orderAggregateMutationLockService,
                accountRepository,
                invoiceRepository
        );
        lockOrder.verify(invoiceOrderRepository).findOrderIdsByInvoiceToken("token");
        lockOrder.verify(orderAggregateMutationLockService).lock(101L);
        lockOrder.verify(orderAggregateMutationLockService).lock(102L);
        lockOrder.verify(accountRepository).findByIdWithRelationsForUpdate(1L);
        lockOrder.verify(invoiceRepository).findByTokenWithAccountForUpdate("token");
    }

    @Test
    void deleteInvoiceDetachesMembershipBeforeDelegatingStandaloneOrderDeletion() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        CommonInvoiceOrder first = item(invoice, order(101L));
        CommonInvoiceOrder second = item(invoice, order(102L));
        List<CommonInvoiceOrder> items = List.of(first, second);
        java.security.Principal principal = () -> "admin";

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(102L, 101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(items);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(invoiceOrderRepository.deleteByInvoiceId(10L)).thenReturn(2);
        when(orderDeletionService.deleteOrder(any(), eq(principal))).thenReturn(true);

        service.deleteInvoiceWithOrders(10L, principal);

        var deleteOrder = inOrder(invoiceOrderRepository, orderDeletionService);
        deleteOrder.verify(invoiceOrderRepository).deleteByInvoiceId(10L);
        deleteOrder.verify(orderDeletionService).deleteOrder(101L, principal);
        deleteOrder.verify(orderDeletionService).deleteOrder(102L, principal);
        verify(invoiceRepository).deleteById(10L);
    }

    @Test
    void deleteUnsentSuccessorRestoresPredecessorMembershipWithoutDeletingOrder() {
        CommonBillingAccount account = account();
        CommonInvoice predecessor = invoice(account);
        predecessor.setStatus(CommonInvoiceStatus.UNPAID);
        CommonInvoice successor = invoice(account);
        successor.setId(11L);
        successor.setInvoicePurpose("BAD_REVIEW_SUCCESSOR");
        successor.setSupersedesInvoice(predecessor);
        successor.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder predecessorItem = item(predecessor, order);
        predecessorItem.setActiveMembership(false);
        CommonInvoiceOrder successorItem = item(successor, order);
        successorItem.setActiveMembership(true);

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(11L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(11L)).thenReturn(List.of(successorItem));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(predecessorItem));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccount(11L)).thenReturn(Optional.of(successor));
        when(invoiceRepository.findByIdWithAccountForUpdate(11L)).thenReturn(Optional.of(successor));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(predecessor));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(predecessor));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(11L)).thenReturn(List.of(successorItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(predecessorItem));
        when(invoiceOrderRepository.deleteByInvoiceId(11L)).thenReturn(1);

        service.deleteInvoiceWithOrders(11L, () -> "admin");

        assertTrue(predecessorItem.isActiveMembership());
        verify(invoiceRepository).deleteById(11L);
        verify(orderDeletionService, never()).deleteOrder(any(), any());
        verify(entityManager).flush();
    }

    @Test
    void deleteInvoiceFailsClosedForRawInProgressOperationMarkers() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        CommonInvoiceOrder item = item(invoice, order(101L));

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        for (String marker : List.of("message_send_in_progress", "payment_init_in_progress")) {
            invoice.setLastError(marker);
            ResponseStatusException error = assertThrows(
                    ResponseStatusException.class,
                    () -> service.deleteInvoiceWithOrders(10L, () -> "admin")
            );
            assertEquals(409, error.getStatusCode().value());
        }

        verify(invoiceOrderRepository, never()).deleteByInvoiceId(any());
        verify(orderDeletionService, never()).deleteOrder(any(), any());
        verify(invoiceRepository, never()).deleteById(any());
    }

    @Test
    void detachOrderForDeletionUsesCanonicalLocksAndDisablesEmptyInvoice() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order createdOrder = order(101L);
        createdOrder.setStatus(status("Новый"));
        CommonInvoiceOrder item = item(invoice, createdOrder);
        item.setReady(false);
        NextOrderRequest request = new NextOrderRequest();
        request.setStatus(NextOrderRequestStatus.CREATED);
        request.setCreatedOrder(createdOrder);

        when(orderAggregateMutationLockService.lock(101L)).thenReturn(createdOrder);
        when(nextOrderRequestRepository.findByCreatedOrderIdForUpdate(101L)).thenReturn(List.of(request));
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByOrderIdForRead(101L)).thenReturn(Optional.of(item));
        when(paymentRefRepository.existsByInvoice_IdAndStatusIn(eq(10L), any())).thenReturn(false);
        when(invoiceOrderRepository.deleteByOrderId(101L)).thenReturn(1);
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());

        assertTrue(service.detachOrderForDeletion(101L));

        assertEquals(CommonInvoiceStatus.DISABLED, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("empty:"));
        var lockOrder = inOrder(
                orderAggregateMutationLockService,
                invoiceOrderRepository,
                invoiceRepository,
                accountRepository,
                paymentRefRepository
        );
        lockOrder.verify(orderAggregateMutationLockService).lock(101L);
        lockOrder.verify(invoiceOrderRepository).findByOrderIdWithInvoice(101L);
        lockOrder.verify(invoiceRepository).findByIdWithAccount(10L);
        lockOrder.verify(accountRepository).findByIdWithRelationsForUpdate(1L);
        lockOrder.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        lockOrder.verify(invoiceOrderRepository).findMembershipByOrderIdForRead(101L);
        lockOrder.verify(paymentRefRepository).existsByInvoice_IdAndStatusIn(eq(10L), any());
        lockOrder.verify(invoiceOrderRepository).deleteByOrderId(101L);
    }

    @Test
    void managerBoardCardShowsWaitingAndReopensReadyInvoiceWhenAnyOrderIsNotReady() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        order.setAmount(5);
        order.setCounter(4);
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceRepository.findBoardInvoices(any())).thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(10L))).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        List<OrderDTOList> cards = service.managerBoardCards("Все", "", null, null, "desc");

        assertEquals(1, cards.size());
        assertEquals("Ожидает общего счета", cards.get(0).getStatus());
        assertEquals(1, cards.get(0).getAmount());
        assertEquals(0, cards.get(0).getCounter());
        assertEquals(CommonInvoiceStatus.COLLECTING, invoice.getStatus());
    }

    @Test
    void managerBoardDoesNotAliasCollectingCommonInvoiceAsNewOrder() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        order.setAmount(5);
        order.setCounter(4);
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceRepository.findBoardInvoices(any())).thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(10L))).thenReturn(List.of(item));

        List<OrderDTOList> cards = service.managerBoardCards("Новый", "", null, null, "desc");

        assertTrue(cards.isEmpty());
    }

    @Test
    void managerBoardShowsCollectingInvoiceWithOverduePublicationBlockerAsAttention() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoiceOrder advanced = item(invoice, order(101L));
        advanced.getOrder().setStatus(status("Публикация"));
        advanced.setReady(false);
        CommonInvoiceOrder blocker = item(invoice, order(102L));
        blocker.getOrder().setStatus(status("На проверке"));
        blocker.setReady(false);
        blocker.setPublicationBlockerSince(LocalDateTime.now().minusHours(49));
        List<CommonInvoiceOrder> items = List.of(advanced, blocker);

        when(invoiceRepository.findBoardInvoices(any())).thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(10L))).thenReturn(items);
        when(publicationBlockerService.hasOverdueBlockers(eq(items), any())).thenReturn(true);

        List<OrderDTOList> cards = service.managerBoardCards("Требует внимания", "", null, null, "desc");

        assertEquals(1, cards.size());
        assertEquals("Требует внимания", cards.getFirst().getStatus());
        assertEquals(CommonInvoiceStatus.COLLECTING.name(), cards.getFirst().getCommonInvoiceStatus());
    }

    @Test
    void managerBoardCardsMergeDuplicateAttachableInvoicesBeforeRendering() {
        CommonBillingAccount account = account();
        CommonInvoice target = invoice(account);
        target.setId(35L);
        target.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoice duplicateA = invoice(account);
        duplicateA.setId(36L);
        duplicateA.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoice duplicateB = invoice(account);
        duplicateB.setId(37L);
        duplicateB.setStatus(CommonInvoiceStatus.READY);
        CommonInvoiceOrder targetItem = item(target, order(101L));
        CommonInvoiceOrder movedItemA = item(duplicateA, order(102L));
        CommonInvoiceOrder movedItemB = item(duplicateB, order(103L));
        List<CommonInvoiceOrder> mergedItems = List.of(targetItem, movedItemA, movedItemB);

        when(invoiceRepository.findBoardInvoices(any()))
                .thenReturn(List.of(duplicateB, duplicateA, target))
                .thenReturn(List.of(target));
        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(duplicateB, duplicateA, target));
        when(invoiceRepository.findByIdWithAccount(35L)).thenReturn(Optional.of(target));
        when(invoiceRepository.findByIdWithAccount(36L)).thenReturn(Optional.of(duplicateA));
        when(invoiceRepository.findByIdWithAccount(37L)).thenReturn(Optional.of(duplicateB));
        when(invoiceRepository.findByIdWithAccountForUpdate(35L)).thenReturn(Optional.of(target));
        when(invoiceRepository.findByIdWithAccountForUpdate(36L)).thenReturn(Optional.of(duplicateA));
        when(invoiceRepository.findByIdWithAccountForUpdate(37L)).thenReturn(Optional.of(duplicateB));
        when(invoiceOrderRepository.findBindingsByInvoiceIds(any())).thenReturn(List.of(
                binding(101L, 35L, 1L),
                binding(102L, 36L, 1L),
                binding(103L, 37L, 1L)
        ));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(targetItem.getOrder());
        when(orderAggregateMutationLockService.lock(102L)).thenReturn(movedItemA.getOrder());
        when(orderAggregateMutationLockService.lock(103L)).thenReturn(movedItemB.getOrder());
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(36L, 37L)))
                .thenReturn(List.of(movedItemA, movedItemB));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(35L)).thenReturn(mergedItems);
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(35L))).thenReturn(mergedItems);

        List<OrderDTOList> cards = service.managerBoardCards("Все", "", null, null, "desc");

        assertEquals(1, cards.size());
        assertEquals(3, cards.get(0).getAmount());
        assertEquals(target, movedItemA.getInvoice());
        assertEquals(target, movedItemB.getInvoice());
        assertEquals(CommonInvoiceStatus.DISABLED, duplicateA.getStatus());
        assertEquals(CommonInvoiceStatus.DISABLED, duplicateB.getStatus());
        verify(invoiceOrderRepository).saveAll(List.of(movedItemA, movedItemB));
    }

    @Test
    void managerBoardPageLoadsOnlyIdsSelectedAndCountedBySql() {
        CommonBillingAccount firstAccount = account();
        CommonBillingAccount secondAccount = account();
        secondAccount.setId(2L);
        secondAccount.setName("Второй плательщик");
        CommonInvoice firstInvoice = invoice(firstAccount);
        firstInvoice.setStatus(CommonInvoiceStatus.INVOICED);
        CommonInvoice secondInvoice = invoice(secondAccount);
        secondInvoice.setId(20L);
        secondInvoice.setToken("second-board-token");
        secondInvoice.setStatus(CommonInvoiceStatus.INVOICED);
        CommonInvoiceOrder firstItem = item(firstInvoice, order(101L));
        CommonInvoiceOrder secondItem = item(secondInvoice, order(202L));
        firstItem.setPaid(true);
        secondItem.setPaid(true);

        when(invoiceRepository.findAccountIdsWithDuplicateCurrentInvoices(any())).thenReturn(List.of());
        when(invoiceBoardQueryRepository.findPage(
                eq("Все"),
                eq(""),
                eq(null),
                org.mockito.ArgumentMatchers.<Set<Long>>isNull(),
                eq(false),
                eq(1),
                eq(1),
                any(LocalDateTime.class)
        )).thenReturn(new CommonInvoiceBoardQueryRepository.PageSelection(List.of(20L), 2L, 2));
        when(invoiceRepository.findBoardInvoicesByIds(List.of(20L))).thenReturn(List.of(secondInvoice));
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(20L))).thenReturn(List.of(secondItem));

        CommonBillingService.ManagerBoardPage page = service.managerBoardPage(
                "Все",
                "",
                null,
                null,
                "desc",
                1,
                1
        );

        assertEquals(2L, page.totalCards());
        assertEquals(2, page.linkedOrderCount());
        assertEquals(List.of(20L), page.cards().stream().map(OrderDTOList::getCommonInvoiceId).toList());
        verify(invoiceRepository).findBoardInvoicesByIds(List.of(20L));
        verify(invoiceOrderRepository).findByInvoiceIdsWithOrders(List.of(20L));
        verify(invoiceOrderRepository, never()).findByInvoiceIdWithOrders(any());
        verify(invoiceRepository, never()).findBoardInvoices(any());
        verify(invoiceRepository, never()).findBoardInvoices(any(), any(Pageable.class));
    }

    @Test
    void deleteInvoiceRejectsClientReportedContractorRouteBeforeDetachingAnything() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setContractorAllocationId(7_001L);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(2));
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setClientReportedAt(LocalDateTime.now().minusMinutes(1));
        CommonInvoiceOrder item = item(invoice, order(101L));

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.deleteInvoiceWithOrders(10L, () -> "admin")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(invoiceOrderRepository, never()).deleteByInvoiceId(any());
        verify(orderDeletionService, never()).deleteOrder(any(), any());
        verify(invoiceRepository, never()).deleteById(any());
    }

    @Test
    void managerBoardMetricsUsesSqlAggregatesWithoutLoadingInvoiceGraphs() {
        Set<Long> visibleManagers = Set.of(7L);
        when(invoiceRepository.findAccountIdsWithDuplicateCurrentInvoices(any())).thenReturn(List.of());
        when(invoiceBoardQueryRepository.metrics(eq(visibleManagers), any(LocalDateTime.class)))
                .thenReturn(new CommonInvoiceBoardQueryRepository.BoardMetrics(
                        Map.of("Опубликовано", 3),
                        Map.of("В проверку", 5)
                ));

        CommonBillingService.ManagerBoardMetrics metrics = service.managerBoardMetrics(visibleManagers);

        assertEquals(Map.of("Опубликовано", 3), metrics.cardCounts());
        assertEquals(Map.of("В проверку", 5), metrics.linkedOrderCounts());
        verify(invoiceRepository, never()).findBoardInvoices(any());
        verify(invoiceRepository, never()).findBoardInvoices(any(), any(Pageable.class));
        verify(invoiceOrderRepository, never()).findByInvoiceIdsWithOrders(any());
    }

    @Test
    void initPublicPaymentAcceptsCollectingInvoiceAsPrepayment() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        stubSuccessfulTbankInit("payment-collecting", "https://pay/collecting");

        var response = service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        );

        assertEquals("https://pay/collecting", response.paymentUrl());
        verify(tbankClient).init(any(), any());
    }

    @Test
    void sendInvoiceRejectsReadyInvoiceWhenAnyOrderIsNotReady() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));

        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void markPaidAcceptsReadyInvoiceButMarkUnpaidStillRequiresSentInvoice() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice readyInvoice = invoice(account);
        readyInvoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(readyInvoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(readyInvoice))
                .thenReturn(Optional.of(readyInvoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markPaid(10L);

        assertTrue(item.isPaid());
        assertEquals(CommonInvoiceStatus.PAID, readyInvoice.getStatus());
        assertThrows(ResponseStatusException.class, () -> service.markUnpaid(10L));

        verify(orderTransactionService).handlePaymentStatus(order, false);
        verify(orderStatusTransitionService, never()).changeStatusForCommonBillingOrder(any(), any());
    }

    @Test
    void markPaidPromotesReadyCollectingInvoiceBeforeClosing() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markPaid(10L);

        assertTrue(item.isPaid());
        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        verify(orderTransactionService).handlePaymentStatus(order, false);
    }

    @Test
    void markPaidSendsCommonPaymentSuccessNotification() throws Exception {
        CommonBillingAccount account = account();
        account.setAutoRepeatOrders(false);
        Manager manager = manager(7L);
        manager.setClientId("whatsapp_vika");
        Company company = company();
        company.setGroupId("120363@test");
        company.setManager(manager);
        account.setInvoiceCompany(company);
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setPayerEmail("client@example.com");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)).thenReturn(true);
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(eq(company), eq("whatsapp_vika"), eq("120363@test"), any()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());

        service.markPaid(10L);

        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertNotNull(invoice.getPaymentSuccessNotifiedAt());
        assertEquals(null, invoice.getPaymentSuccessNotificationError());
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender).send(eq(company), eq("whatsapp_vika"), eq("120363@test"), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("Оплата прошла успешно."));
        assertTrue(messageCaptor.getValue().contains("Общий счет: Общий плательщик"));
        assertTrue(messageCaptor.getValue().contains("Сумма: 1000 руб."));
        assertTrue(messageCaptor.getValue().contains("client@example.com"));
    }

    @Test
    void initPublicPaymentLocksInvoiceBeforeCheckingPayability() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        stubSuccessfulTbankInit("payment-lock", "https://pay/lock");

        service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        );

        verify(invoiceRepository).findByTokenWithAccountForUpdate("token");
    }

    @Test
    void initPublicPaymentMarksInProgressOnlyAroundTbankInit() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        doAnswer(invocation -> {
            assertEquals("payment_init_in_progress", invoice.getLastError());
            TbankInitCommand command = invocation.getArgument(1);
            return new TbankInitResponse(
                    true,
                    "0",
                    null,
                    null,
                    "terminal",
                    "NEW",
                    "payment-1",
                    command.orderId(),
                    command.amountKopecks(),
                    "https://pay/new"
            );
        }).when(tbankClient).init(any(), any());

        var response = service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        );

        assertEquals("https://pay/new", response.paymentUrl());
        assertEquals("payment-1", invoice.getTbankPaymentId());
        assertEquals("terminal", invoice.getTbankTerminalKey());
        assertEquals(100_000L, invoice.getTbankPaymentAmountKopecks());
        assertEquals(null, invoice.getLastError());
    }

    @Test
    void initPublicPaymentPersistsTypedMarkerOnlyForCertificateHandshakeCauseChain() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));

        CertPathBuilderException certPathFailure =
                new CertPathBuilderException("unable to find valid certification path");
        SSLHandshakeException handshakeFailure = new SSLHandshakeException("certificate_unknown");
        handshakeFailure.initCause(certPathFailure);
        ResourceAccessException transportFailure =
                new ResourceAccessException("I/O error during TLS handshake", handshakeFailure);
        doThrow(transportFailure).when(tbankClient).init(any(), any());

        assertThrows(
                ResourceAccessException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertTrue(invoice.getLastError().startsWith(
                CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE + ":"
        ));
        CommonInvoicePaymentRef preparedRef = paymentRefStore.values().stream().findFirst().orElseThrow();
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        assertEquals(
                CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_REF_REASON,
                preparedRef.getReason()
        );

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of(preparedRef));
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of());
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.recoverUnsentPaymentInitTlsFailure(10L);

        assertEquals("ARCHIVED", preparedRef.getStatus());
        assertNull(invoice.getLastError());
    }

    @Test
    void initPublicPaymentDoesNotMarkHttpErrorBodyWithPkixWordsAsSafeToRetry() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        ResponseStatusException httpResponseFailure = new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "proxy response body: certificate_unknown; PKIX path building failed: "
                        + "unable to find valid certification path"
        );
        doThrow(httpResponseFailure).when(tbankClient).init(any(), any());

        assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertTrue(invoice.getLastError().startsWith("payment_init_exception:"));
        assertFalse(invoice.getLastError().startsWith(
                CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE
        ));
        CommonInvoicePaymentRef preparedRef = paymentRefStore.values().stream().findFirst().orElseThrow();
        assertEquals(
                CommonPaymentInitFailureClassifier.LEGACY_TLS_BEFORE_HTTP_REF_REASON,
                preparedRef.getReason()
        );

        ResponseStatusException recoveryError = assertThrows(
                ResponseStatusException.class,
                () -> service.recoverUnsentPaymentInitTlsFailure(10L)
        );
        assertEquals(409, recoveryError.getStatusCode().value());
        verify(paymentRefRepository, never()).findByInvoiceIdForUpdate(anyLong());
    }

    @Test
    void initPublicPaymentCommitsDurablePreparedAnchorBeforeCallingProvider() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        CommonInvoicePaymentRef[] preparedAnchor = new CommonInvoicePaymentRef[1];

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceOrderRepository.findOrderIdsByInvoiceToken("token")).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        doAnswer(invocation -> {
            CommonInvoicePaymentRef ref = invocation.getArgument(0);
            if ("INIT_PREPARED".equals(ref.getStatus()) && ref.getId() == null) {
                ref.setId(77L);
                preparedAnchor[0] = ref;
            }
            return ref;
        }).when(paymentRefRepository).save(any(CommonInvoicePaymentRef.class));
        when(paymentRefRepository.findByIdForUpdate(77L))
                .thenAnswer(ignored -> Optional.ofNullable(preparedAnchor[0]));
        doAnswer(invocation -> {
            TbankInitCommand command = invocation.getArgument(1);
            assertNotNull(preparedAnchor[0]);
            assertEquals("INIT_PREPARED", preparedAnchor[0].getStatus());
            assertEquals(command.orderId(), preparedAnchor[0].getTbankOrderId());
            assertEquals(command.orderId(), invoice.getTbankOrderId());
            assertEquals("payment_init_in_progress", invoice.getLastError());
            verify(transactionManager, atLeastOnce()).commit(any());
            return new TbankInitResponse(
                    true,
                    "0",
                    null,
                    null,
                    "terminal",
                    "NEW",
                    "payment-durable",
                    command.orderId(),
                    command.amountKopecks(),
                    "https://pay/durable"
            );
        }).when(tbankClient).init(any(), any());

        var response = service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        );

        assertEquals("https://pay/durable", response.paymentUrl());
        assertEquals("payment-durable", invoice.getTbankPaymentId());
        assertEquals("payment-durable", preparedAnchor[0].getTbankPaymentId());
        assertEquals("CURRENT", preparedAnchor[0].getStatus());
        verify(paymentRefRepository, never()).delete(preparedAnchor[0]);
    }

    @Test
    void initPublicPaymentQuarantinesPaymentIdAlreadyBoundToForeignReference() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        CommonInvoicePaymentRef[] preparedAnchor = new CommonInvoicePaymentRef[1];
        CommonInvoicePaymentRef foreignRef = new CommonInvoicePaymentRef();
        foreignRef.setId(88L);
        CommonInvoice foreignInvoice = invoice(account());
        foreignInvoice.setId(11L);
        foreignRef.setInvoice(foreignInvoice);
        foreignRef.setTbankPaymentId("payment-collision");

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceOrderRepository.findOrderIdsByInvoiceToken("token")).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        doAnswer(invocation -> {
            CommonInvoicePaymentRef ref = invocation.getArgument(0);
            if (ref.getId() == null) {
                ref.setId(77L);
                preparedAnchor[0] = ref;
            }
            return ref;
        }).when(paymentRefRepository).save(any(CommonInvoicePaymentRef.class));
        when(paymentRefRepository.findByIdForUpdate(77L))
                .thenAnswer(ignored -> Optional.ofNullable(preparedAnchor[0]));
        when(paymentRefRepository.findByTbankPaymentId("payment-collision"))
                .thenReturn(Optional.of(foreignRef));
        stubSuccessfulTbankInit("payment-collision", "https://pay/collision");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getTbankPaymentId());
        assertNull(invoice.getPaymentUrl());
        assertEquals("INIT_CONFLICT", preparedAnchor[0].getStatus());
        assertTrue(preparedAnchor[0].getReason().contains("payment-collision"));
    }

    @Test
    void initPublicPaymentQuarantinesCurrentRegistryConstraintAsConflict() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        int[] flushAttempts = {0};

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        stubSuccessfulTbankInit("payment-current-collision", "https://pay/current-collision");
        doAnswer(invocation -> {
            if (flushAttempts[0]++ == 0) {
                throw new DataIntegrityViolationException(
                        "Duplicate entry for key uk_common_invoice_payment_refs_current_invoice"
                );
            }
            return null;
        }).when(entityManager).flush();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getPaymentUrl());
        assertNull(invoice.getTbankPaymentId());
        CommonInvoicePaymentRef ref = paymentRefStore.values().stream().findFirst().orElseThrow();
        assertEquals("INIT_CONFLICT", ref.getStatus());
        assertEquals("payment-current-collision", ref.getTbankPaymentId());
        assertTrue(ref.getReason().startsWith("current_payment_registry_collision"));
        assertTrue(invoice.getLastError().startsWith("payment_registry_collision"));
        assertEquals(2, flushAttempts[0]);
    }

    @Test
    void initPublicPaymentQuarantinesConcurrentPaymentIdUniqueCollision() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        CommonInvoice foreignInvoice = invoice(account());
        foreignInvoice.setId(11L);
        CommonInvoicePaymentRef foreignRef = paymentRef(
                88L,
                foreignInvoice,
                "CURRENT",
                "foreign-order",
                "payment-race",
                "terminal",
                100_000L
        );

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        when(paymentRefRepository.findByTbankPaymentId("payment-race"))
                .thenReturn(Optional.empty(), Optional.of(foreignRef));
        stubSuccessfulTbankInit("payment-race", "https://pay/race");
        doAnswer(invocation -> {
            CommonInvoicePaymentRef preparedRef = paymentRefStore.values().stream()
                    .filter(ref -> Objects.equals(invoice.getId(), ref.getInvoice().getId()))
                    .findFirst()
                    .orElseThrow();
            preparedRef.setTbankPaymentId(null);
            preparedRef.setStatus("INIT_PREPARED");
            throw new DataIntegrityViolationException(
                    "Duplicate entry for key uk_common_invoice_payment_ref_payment"
            );
        }).when(entityManager).flush();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getPaymentUrl());
        assertNull(invoice.getTbankPaymentId());
        CommonInvoicePaymentRef losingRef = paymentRefStore.values().stream()
                .filter(ref -> Objects.equals(invoice.getId(), ref.getInvoice().getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("INIT_CONFLICT", losingRef.getStatus());
        assertNull(losingRef.getTbankPaymentId());
        assertTrue(losingRef.getReason().contains("payment-race"));
    }

    @Test
    void initPublicPaymentAcceptsConfirmedWebhookArrivingBeforeInitResponse() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        CommonInvoicePaymentRef[] preparedAnchor = new CommonInvoicePaymentRef[1];

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceOrderRepository.findOrderIdsByInvoiceToken("token")).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(any(), eq("password"), eq("token"))).thenReturn(true);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        doAnswer(invocation -> {
            CommonInvoicePaymentRef ref = invocation.getArgument(0);
            if ("INIT_PREPARED".equals(ref.getStatus()) && ref.getId() == null) {
                ref.setId(77L);
                preparedAnchor[0] = ref;
            }
            return ref;
        }).when(paymentRefRepository).save(any(CommonInvoicePaymentRef.class));
        when(paymentRefRepository.findByIdForUpdate(77L)).thenAnswer(ignored ->
                Optional.ofNullable(preparedAnchor[0])
        );
        when(paymentRefRepository.findByTbankOrderId(any())).thenAnswer(ignored ->
                Optional.ofNullable(preparedAnchor[0])
        );
        doAnswer(invocation -> {
            TbankInitCommand command = invocation.getArgument(1);
            Map<String, String> payload = confirmedWebhookPayload();
            payload.put("OrderId", command.orderId());
            payload.put("PaymentId", "payment-early");
            assertTrue(service.handleTbankWebhook(payload));
            return new TbankInitResponse(
                    true,
                    "0",
                    null,
                    null,
                    "terminal",
                    "NEW",
                    "payment-early",
                    command.orderId(),
                    command.amountKopecks(),
                    "https://pay/early"
            );
        }).when(tbankClient).init(any(), any());

        var response = service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        );

        assertEquals("payment-early", response.paymentId());
        assertEquals("https://pay/early", response.paymentUrl());
        assertEquals("CONFIRMED", response.status());
        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertNull(invoice.getTbankPaymentId());
        assertNull(invoice.getLastError());
        assertEquals("payment-early", preparedAnchor[0].getTbankPaymentId());
        assertEquals("CONFIRMED", preparedAnchor[0].getStatus());
        verify(paymentRefRepository, never()).delete(preparedAnchor[0]);
    }

    @Test
    void initPublicPaymentRejectsUnsafeUrlAndKeepsCreatedPaymentForReconciliation() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        stubSuccessfulTbankInit("payment-unsafe-url", "javascript:alert(document.cookie)");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(502, exception.getStatusCode().value());
        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository, atLeastOnce()).save(captor.capture());
        CommonInvoicePaymentRef savedRef = captor.getAllValues().getLast();
        assertEquals("payment-unsafe-url", savedRef.getTbankPaymentId());
        assertEquals("CANCEL_PENDING", savedRef.getStatus());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_invalid_url"));
        assertEquals(null, invoice.getPaymentUrl());
    }

    @Test
    void initPublicPaymentQuarantinesProviderResponseWithMismatchedBinding() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        doAnswer(invocation -> {
            TbankInitCommand command = invocation.getArgument(1);
            return new TbankInitResponse(
                    true,
                    "0",
                    null,
                    null,
                    "terminal",
                    "NEW",
                    "provider-payment",
                    "different-order-id",
                    command.amountKopecks(),
                    "https://pay/must-not-be-returned"
            );
        }).when(tbankClient).init(any(), any());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(502, exception.getStatusCode().value());
        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository, atLeastOnce()).save(captor.capture());
        CommonInvoicePaymentRef savedRef = captor.getAllValues().getLast();
        assertTrue(savedRef.getTbankOrderId().startsWith("g10-"));
        assertEquals("provider-payment", savedRef.getTbankPaymentId());
        assertEquals("CANCEL_PENDING", savedRef.getStatus());
        assertTrue(savedRef.getReason().contains("provider_order_mismatch=different-order-id"));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_response_mismatch"));
        assertNull(invoice.getPaymentUrl());
        assertNull(invoice.getTbankPaymentId());

        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        Map<String, String> webhook = confirmedWebhookPayload();
        webhook.put("OrderId", "different-order-id");
        webhook.put("PaymentId", "provider-payment");
        webhook.put("Status", "AUTHORIZED");
        when(tokenSigner.matches(webhook, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(webhook));
        assertEquals("CANCEL_PENDING", savedRef.getStatus());
        assertTrue(savedRef.getReason().contains("provider_order_mismatch=different-order-id"));
        assertTrue(savedRef.getReason().contains("cancel_lifecycle_webhook:AUTHORIZED"));
    }

    @Test
    void initPublicPaymentQuarantinesExpiredUnsafeCachedUrlBeforeStartingAnotherPayment() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setTbankOrderId("cached-order");
        invoice.setTbankPaymentId("cached-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now().minusHours(2));
        invoice.setPaymentUrl("javascript:cached-recipient()");
        CommonInvoiceOrder item = item(invoice, order(101L));

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(item.getOrder())).thenReturn(BigDecimal.valueOf(1000));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getPaymentUrl());
        assertTrue(invoice.getLastError().startsWith("payment_cached_invalid_url"));
        verify(tbankClient, never()).init(any(), any());
        // The recalculated total and frozen route are persisted before the
        // durable NEEDS_ATTENTION quarantine.
        verify(invoiceRepository, times(3)).save(invoice);
    }

    @Test
    void initPublicPaymentDefersReplacementWhileExpiredPaymentIsBeingCancelled() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setTbankOrderId("old-order");
        invoice.setTbankPaymentId("old-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now().minusHours(2));
        invoice.setPaymentUrl("https://pay/old");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        when(paymentRefRepository.findByTbankOrderId(any())).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals(502, error.getStatusCode().value());
        assertNull(invoice.getTbankOrderId());
        assertNull(invoice.getTbankPaymentId());
        assertNull(invoice.getPaymentUrl());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("payment_init_conflict"));
        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository, atLeastOnce()).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getAllValues().getLast();
        assertEquals("old-order", ref.getTbankOrderId());
        assertEquals("old-payment", ref.getTbankPaymentId());
        assertEquals("terminal", ref.getTbankTerminalKey());
        assertEquals(100_000L, ref.getAmountKopecks());
        assertEquals("CANCEL_PENDING", ref.getStatus());
        assertEquals("payment_link_expired_before_replacement", ref.getReason());
        verify(tbankClient, never()).init(any(), any());
    }

    @Test
    void initPublicPaymentStoresCreatedBankLinkWhenAmountChangesBeforeFinish() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order))
                .thenReturn(BigDecimal.valueOf(1000))
                .thenReturn(BigDecimal.valueOf(2000));
        when(paymentProfileService.selectForManager(null)).thenReturn(profile);
        when(paymentProfileService.lockForRouting(profile)).thenReturn(profile);
        when(paymentProfileService.toRuntime(profile)).thenReturn(runtimeProfile);
        when(properties.getRedirectDue()).thenReturn(Duration.ofMinutes(20));
        when(paymentRefRepository.findByTbankOrderId(any())).thenReturn(Optional.empty());
        when(paymentRefRepository.findByTbankPaymentId("payment-1")).thenReturn(Optional.empty());
        stubSuccessfulTbankInit("payment-1", "https://pay/new");

        assertThrows(ResponseStatusException.class, () -> service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        ));

        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository, atLeastOnce()).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getAllValues().getLast();
        assertTrue(ref.getTbankOrderId().startsWith("g10-"));
        assertEquals("payment-1", ref.getTbankPaymentId());
        assertEquals("CANCEL_PENDING", ref.getStatus());
        assertEquals(100_000L, ref.getAmountKopecks());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_conflict"));
    }

    @Test
    void attachOrderAutoClosesCreatedStandaloneTokenBeforeContinuing() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        Order order = order(101L);
        PaymentLink pristineStandaloneLink = new PaymentLink();
        pristineStandaloneLink.setId(501L);
        pristineStandaloneLink.setOrder(order);
        pristineStandaloneLink.setStatus(PaymentLinkStatus.CREATED);
        pristineStandaloneLink.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        pristineStandaloneLink.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(orderRepository.findCompanyIdByOrderId(101L)).thenReturn(Optional.of(20L));
        when(invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of(pristineStandaloneLink));

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.attachOrderIfNeeded(order)
        );

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(PaymentLinkStatus.CANCELED, pristineStandaloneLink.getStatus());
        assertTrue(pristineStandaloneLink.getLastError().startsWith("common_invoice_unstarted_route_auto_closed:"));
        verify(paymentLinkRepository).saveAll(List.of(pristineStandaloneLink));
        verify(invoiceOrderRepository, never()).save(any(CommonInvoiceOrder.class));
        verify(accountRepository).findByIdWithRelationsForUpdate(1L);
    }

    @Test
    void attachOrderWithoutPreexistingRouteIsIdempotentAndDoesNotDoubleAmount() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        Order order = order(101L);
        CommonInvoiceOrder[] attachedItem = new CommonInvoiceOrder[1];
        CommonInvoice[] createdInvoice = new CommonInvoice[1];

        when(orderRepository.findCompanyIdByOrderId(101L)).thenReturn(Optional.of(20L));
        when(invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(101L))
                .thenAnswer(ignored -> Optional.ofNullable(attachedItem[0]));
        when(invoiceOrderRepository.findMembershipByOrderIdForRead(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 20L)).thenReturn(Optional.of(link));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of());
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(invoiceRepository.findCurrentForAccount(any(), any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            CommonInvoice created = invocation.getArgument(0);
            created.setId(99L);
            createdInvoice[0] = created;
            return created;
        }).when(invoiceRepository).save(any(CommonInvoice.class));
        doAnswer(invocation -> {
            CommonInvoiceOrder saved = invocation.getArgument(0);
            attachedItem[0] = saved;
            return saved;
        }).when(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(99L)).thenAnswer(ignored ->
                attachedItem[0] == null ? List.of() : List.of(attachedItem[0])
        );

        assertTrue(service.attachOrderIfNeeded(order));
        assertTrue(service.attachOrderIfNeeded(order));

        assertEquals(100_000L, attachedItem[0].getAmountKopecks());
        assertEquals(100_000L, createdInvoice[0].getAmountKopecks());
        verify(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));
        verify(badReviewTaskService).getPayableSum(order);
    }

    @Test
    void attachOrderBlocksAlreadySentManualPaymentRouteUntilExplicitReconciliation() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        CommonBillingAccountCompany accountCompany = new CommonBillingAccountCompany();
        accountCompany.setAccount(account);
        accountCompany.setCompany(company());
        accountCompany.setEnabled(true);
        Order order = order(102L);
        PaymentLink manualLink = new PaymentLink();
        manualLink.setId(502L);
        manualLink.setOrder(order);
        manualLink.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        manualLink.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.MANUAL_MOBILE_BANK);
        manualLink.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(orderRepository.findCompanyIdByOrderId(102L)).thenReturn(Optional.of(20L));
        when(invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(102L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(accountCompany));
        when(orderAggregateMutationLockService.lock(102L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdForUpdate(102L)).thenReturn(List.of(manualLink));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.attachOrderIfNeeded(order)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("ручным реквизитам"));
        verify(paymentLinkRepository, never()).save(manualLink);
        verify(invoiceOrderRepository, never()).save(any(CommonInvoiceOrder.class));
        verify(accountRepository, never()).findByIdWithRelationsForUpdate(anyLong());
    }

    @Test
    void attachOrderConsumesVerifiedAbsentManualRouteOnlyAfterMembershipIsSaved() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        CommonBillingAccountCompany accountCompany = new CommonBillingAccountCompany();
        accountCompany.setAccount(account);
        accountCompany.setCompany(company());
        accountCompany.setEnabled(true);
        Order order = order(103L);
        PaymentLink manuallyClosedRoute = new PaymentLink();
        manuallyClosedRoute.setId(503L);
        manuallyClosedRoute.setOrder(order);
        manuallyClosedRoute.setStatus(PaymentLinkStatus.CANCELED);
        manuallyClosedRoute.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.MANUAL_MOBILE_BANK);
        manuallyClosedRoute.setLastError("manual_payment_absent_verified: checked_by=owner; checked_at=2026-08-04T12:00");
        CommonInvoiceOrder[] attachedItem = new CommonInvoiceOrder[1];

        when(orderRepository.findCompanyIdByOrderId(103L)).thenReturn(Optional.of(20L));
        when(invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(103L)).thenReturn(Optional.empty());
        when(invoiceOrderRepository.findMembershipByOrderIdForRead(103L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(accountCompany));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(20L)).thenReturn(List.of(accountCompany));
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 20L)).thenReturn(Optional.of(accountCompany));
        when(orderAggregateMutationLockService.lock(103L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdForUpdate(103L)).thenReturn(List.of(manuallyClosedRoute));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(invoiceRepository.findCurrentForAccount(any(), any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            CommonInvoice created = invocation.getArgument(0);
            created.setId(99L);
            return created;
        }).when(invoiceRepository).save(any(CommonInvoice.class));
        doAnswer(invocation -> {
            CommonInvoiceOrder saved = invocation.getArgument(0);
            attachedItem[0] = saved;
            return saved;
        }).when(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(99L)).thenAnswer(ignored ->
                attachedItem[0] == null ? List.of() : List.of(attachedItem[0])
        );

        assertTrue(service.attachOrderIfNeeded(order));

        assertEquals(PaymentLinkStatus.CANCELED, manuallyClosedRoute.getStatus());
        assertTrue(manuallyClosedRoute.getLastError().startsWith("common_invoice_route_attached; invoice=99; "));
        assertTrue(manuallyClosedRoute.getLastError().contains("manual_payment_absent_verified:"));
        var persistenceOrder = inOrder(invoiceOrderRepository, paymentLinkRepository);
        persistenceOrder.verify(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));
        persistenceOrder.verify(paymentLinkRepository).save(manuallyClosedRoute);
    }

    @Test
    void commonInvoiceRouteCheckDoesNotIgnoreConfirmedWithoutProvenance() {
        CommonInvoice invoice = invoice(account());
        Order paidOrder = order(201L);
        Order unpaidOrder = order(202L);
        CommonInvoiceOrder paidItem = item(invoice, paidOrder);
        paidItem.setPaid(true);
        CommonInvoiceOrder unpaidItem = item(invoice, unpaidOrder);
        unpaidItem.setPaid(false);

        PaymentLink historicalConfirmed = new PaymentLink();
        historicalConfirmed.setId(601L);
        historicalConfirmed.setOrder(paidOrder);
        historicalConfirmed.setStatus(PaymentLinkStatus.CONFIRMED);
        historicalConfirmed.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        historicalConfirmed.setTbankPaymentId("paid-payment");

        PaymentLink stillActive = new PaymentLink();
        stillActive.setId(604L);
        stillActive.setOrder(paidOrder);
        stillActive.setStatus(PaymentLinkStatus.INITIATED);
        stillActive.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        stillActive.setTbankPaymentId("still-active-payment");

        PaymentLink pristineUnpaidRoute = new PaymentLink();
        pristineUnpaidRoute.setId(602L);
        pristineUnpaidRoute.setOrder(unpaidOrder);
        pristineUnpaidRoute.setStatus(PaymentLinkStatus.CREATED);
        pristineUnpaidRoute.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        pristineUnpaidRoute.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<Long, List<PaymentLink>> allLinks = Map.of(
                201L, List.of(historicalConfirmed, stillActive),
                202L, List.of(pristineUnpaidRoute)
        );
        @SuppressWarnings("unchecked")
        Map<Long, List<PaymentLink>> routeLinks = ReflectionTestUtils.invokeMethod(
                service,
                "paymentLinksRequiringCommonInvoiceRouteCheck",
                allLinks,
                List.of(paidItem, unpaidItem),
                Set.of()
        );

        assertEquals(Set.of(201L, 202L), routeLinks.keySet());
        assertEquals(List.of(historicalConfirmed, stillActive), routeLinks.get(201L));
        assertEquals(List.of(pristineUnpaidRoute), routeLinks.get(202L));
        assertEquals(PaymentLinkStatus.CONFIRMED, historicalConfirmed.getStatus());
        assertEquals(PaymentLinkStatus.INITIATED, stillActive.getStatus());
        assertEquals(PaymentLinkStatus.CREATED, pristineUnpaidRoute.getStatus());
        verify(paymentLinkRepository, never()).save(pristineUnpaidRoute);
        verify(paymentLinkRepository, never()).save(historicalConfirmed);
    }

    @Test
    void competingRouteValidationNeverExpiresCreatedTokenBeforeFindingAnotherBlocker() {
        Order firstOrder = order(201L);
        PaymentLink created = new PaymentLink();
        created.setId(601L);
        created.setOrder(firstOrder);
        created.setStatus(PaymentLinkStatus.CREATED);
        created.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);

        Order secondOrder = order(202L);
        PaymentLink initiated = new PaymentLink();
        initiated.setId(602L);
        initiated.setOrder(secondOrder);
        initiated.setStatus(PaymentLinkStatus.INITIATED);
        initiated.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        initiated.setTbankPaymentId("provider-payment");

        Map<Long, List<PaymentLink>> routes = new LinkedHashMap<>();
        routes.put(201L, List.of(created));
        routes.put(202L, List.of(initiated));

        assertThrows(ResponseStatusException.class, () -> ReflectionTestUtils.invokeMethod(
                service,
                "ensureNoCompetingStandaloneRoutesOrThrow",
                routes
        ));

        assertEquals(PaymentLinkStatus.CREATED, created.getStatus());
        assertNull(created.getLastError());
        assertEquals(PaymentLinkStatus.INITIATED, initiated.getStatus());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void commonInvoiceRouteRepairClosesOnlyProvablyUnstartedBankRoutes() {
        Order firstOrder = order(201L);
        PaymentLink first = new PaymentLink();
        first.setId(601L);
        first.setOrder(firstOrder);
        first.setStatus(PaymentLinkStatus.CREATED);
        first.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        first.setExpiresAt(LocalDateTime.now().plusDays(30));

        Order secondOrder = order(202L);
        PaymentLink second = new PaymentLink();
        second.setId(602L);
        second.setOrder(secondOrder);
        second.setStatus(PaymentLinkStatus.CREATED);
        second.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        second.setExpiresAt(LocalDateTime.now().plusDays(30));

        int closed = ReflectionTestUtils.invokeMethod(
                service,
                "closeProvablyUnstartedStandaloneRoutesOrThrow",
                Map.of(201L, List.of(first), 202L, List.of(second)),
                10L
        );

        assertEquals(2, closed);
        assertEquals(PaymentLinkStatus.CANCELED, first.getStatus());
        assertEquals(PaymentLinkStatus.CANCELED, second.getStatus());
        assertTrue(first.getLastError().contains("invoice=10"));
        assertTrue(first.getLastError().contains("order=201"));
        verify(paymentLinkRepository).saveAll(org.mockito.ArgumentMatchers.argThat(saved -> {
            if (saved == null) {
                return false;
            }
            List<PaymentLink> actual = java.util.stream.StreamSupport.stream(saved.spliterator(), false).toList();
            return actual.size() == 2 && actual.containsAll(List.of(first, second));
        }));
    }

    @Test
    void commonInvoiceRouteRepairIsAtomicWhenAnyRouteHasProviderEvidence() {
        Order firstOrder = order(201L);
        PaymentLink closable = new PaymentLink();
        closable.setId(601L);
        closable.setOrder(firstOrder);
        closable.setStatus(PaymentLinkStatus.CREATED);
        closable.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        closable.setExpiresAt(LocalDateTime.now().plusDays(30));

        Order secondOrder = order(202L);
        PaymentLink started = new PaymentLink();
        started.setId(602L);
        started.setOrder(secondOrder);
        started.setStatus(PaymentLinkStatus.INITIATED);
        started.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        started.setTbankPaymentId("provider-payment");

        assertThrows(ResponseStatusException.class, () -> ReflectionTestUtils.invokeMethod(
                service,
                "closeProvablyUnstartedStandaloneRoutesOrThrow",
                Map.of(201L, List.of(closable), 202L, List.of(started)),
                10L
        ));

        assertEquals(PaymentLinkStatus.CREATED, closable.getStatus());
        assertNull(closable.getLastError());
        assertEquals(PaymentLinkStatus.INITIATED, started.getStatus());
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void confirmedStandaloneSynchronizationRejectsTwoConfirmedPaymentsWithoutPartialMutation() {
        CommonInvoice invoice = invoice(account());
        Order order = order(201L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink first = confirmedStandaloneBankPayment(601L, order, 100_000L);
        PaymentLink second = confirmedStandaloneBankPayment(602L, order, 100_000L);

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        service,
                        "synchronizeConfirmedStandalonePaymentsOrThrow",
                        invoice,
                        List.of(item),
                        Map.of(201L, List.of(first, second))
                )
        );

        assertTrue(conflict.getReason().contains("несколько подтвержденных"));
        assertFalse(item.isPaid());
        assertNull(first.getLastError());
        assertNull(second.getLastError());
        verify(invoiceOrderRepository, never()).saveAll(any());
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void confirmedStandaloneSynchronizationRejectsAmountMismatchWithoutPartialMutation() {
        CommonInvoice invoice = invoice(account());
        Order order = order(201L);
        order.setStatus(status("Оплачено"));
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink link = confirmedStandaloneBankPayment(601L, order, 100_000L);
        link.setConfirmedAmountKopecks(90_000L);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        service,
                        "synchronizeConfirmedStandalonePaymentsOrThrow",
                        invoice,
                        List.of(item),
                        Map.of(201L, List.of(link))
                )
        );

        assertTrue(conflict.getReason().contains("сумма не совпадает"));
        assertFalse(item.isPaid());
        assertNull(link.getLastError());
        verify(invoiceOrderRepository, never()).saveAll(any());
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void confirmedStandaloneSynchronizationRejectsExistingCommonPaymentSource() {
        CommonInvoice invoice = invoice(account());
        invoice.setPaymentUrl("https://securepayments.tinkoff.ru/common-payment");
        invoice.setTbankPaymentId("common-payment-id");
        Order order = order(201L);
        order.setStatus(status("Оплачено"));
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink link = confirmedStandaloneBankPayment(601L, order, 100_000L);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        service,
                        "synchronizeConfirmedStandalonePaymentsOrThrow",
                        invoice,
                        List.of(item),
                        Map.of(201L, List.of(link))
                )
        );

        assertTrue(conflict.getReason().contains("в общем счете уже есть платежный источник"));
        assertFalse(item.isPaid());
        assertNull(link.getLastError());
        verify(invoiceOrderRepository, never()).saveAll(any());
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void confirmedStandaloneSynchronizationRejectsPaidItemWithoutDurableSource() {
        CommonInvoice invoice = invoice(account());
        Order order = order(201L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setPaid(true);
        item.setPaidAt(LocalDateTime.of(2026, 8, 4, 12, 30));
        item.setPaymentMethod("TBANK");
        PaymentLink link = confirmedStandaloneBankPayment(601L, order, 100_000L);

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        service,
                        "synchronizeConfirmedStandalonePaymentsOrThrow",
                        invoice,
                        List.of(item),
                        Map.of(201L, List.of(link))
                )
        );

        assertTrue(conflict.getReason().contains("недоказанный источник оплаты"));
        assertNull(link.getLastError());
        verify(invoiceOrderRepository, never()).saveAll(any());
        verify(paymentLinkRepository, never()).saveAll(any());
    }

    @Test
    void providerConfirmedDeadlineExpiryClosesRouteButLocalExpiryWithProviderIdsDoesNot() {
        Order order = order(201L);
        PaymentLink providerExpired = new PaymentLink();
        providerExpired.setId(601L);
        providerExpired.setOrder(order);
        providerExpired.setStatus(PaymentLinkStatus.EXPIRED);
        providerExpired.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        providerExpired.setTbankPaymentId("payment-601");
        providerExpired.setTbankOrderId("order-601");
        providerExpired.setProviderTerminalStatus("DEADLINE_EXPIRED");

        PaymentLink locallyExpired = new PaymentLink();
        locallyExpired.setId(602L);
        locallyExpired.setOrder(order);
        locallyExpired.setStatus(PaymentLinkStatus.EXPIRED);
        locallyExpired.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        locallyExpired.setTbankPaymentId("payment-602");
        locallyExpired.setTbankOrderId("order-602");

        Boolean providerClosed = ReflectionTestUtils.invokeMethod(
                service,
                "isSafelyClosedStandaloneRoute",
                providerExpired
        );
        Boolean localClosed = ReflectionTestUtils.invokeMethod(
                service,
                "isSafelyClosedStandaloneRoute",
                locallyExpired
        );

        assertTrue(Boolean.TRUE.equals(providerClosed));
        assertFalse(Boolean.TRUE.equals(localClosed));
    }

    @Test
    void reversalOfDurableStandaloneSourceQuarantinesInvoiceWithoutClearingPaidEvidence() {
        CommonInvoice invoice = invoice(account());
        invoice.setStatus(CommonInvoiceStatus.PAID);
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        Order order = order(201L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setPaid(true);
        item.setPaidAt(LocalDateTime.of(2026, 8, 4, 12, 30));
        item.setPaymentMethod("TBANK");
        item.setSourcePaymentLinkId(601L);
        PaymentLink refunded = confirmedStandaloneBankPayment(601L, order, 100_000L);
        refunded.setStatus(PaymentLinkStatus.REFUNDED);
        refunded.setProviderTerminalStatus("REFUNDED");

        when(paymentLinkRepository.findByOrderIdForUpdate(201L)).thenReturn(List.of(refunded));
        when(invoiceOrderRepository.findByOrderIdWithInvoice(201L)).thenReturn(Optional.of(item));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));

        boolean quarantined = service.applyStandalonePaymentReversal(
                201L,
                601L,
                PaymentLinkStatus.REFUNDED
        );

        assertTrue(quarantined);
        assertTrue(item.isPaid());
        assertEquals(601L, item.getSourcePaymentLinkId());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getNextReminderAt());
        assertTrue(invoice.getLastError().startsWith("standalone_payment_reversed:"));
        verify(invoiceRepository).save(invoice);

        var lockOrder = inOrder(
                orderAggregateMutationLockService,
                paymentLinkRepository,
                invoiceRepository
        );
        lockOrder.verify(orderAggregateMutationLockService).lock(201L);
        lockOrder.verify(paymentLinkRepository).findByOrderIdForUpdate(201L);
        lockOrder.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
    }

    @Test
    void attachOrderMergesDuplicateAttachableInvoicesBeforeAddingNewOrder() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        CommonInvoice target = invoice(account);
        target.setId(35L);
        target.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoice duplicate = invoice(account);
        duplicate.setId(36L);
        duplicate.setStatus(CommonInvoiceStatus.COLLECTING);
        Order movedOrder = order(201L);
        CommonInvoiceOrder movedItem = item(duplicate, movedOrder);
        Order newOrder = order(101L);

        when(orderRepository.findCompanyIdByOrderId(101L)).thenReturn(Optional.of(20L));
        when(invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(101L)).thenReturn(Optional.empty());
        when(invoiceOrderRepository.findMembershipByOrderIdForRead(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 20L)).thenReturn(Optional.of(link));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(duplicate, target));
        when(invoiceRepository.findByIdWithAccount(35L)).thenReturn(Optional.of(target));
        when(invoiceRepository.findByIdWithAccount(36L)).thenReturn(Optional.of(duplicate));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(35L)).thenReturn(Optional.of(target));
        when(invoiceRepository.findByIdWithAccountForUpdate(36L)).thenReturn(Optional.of(duplicate));
        when(invoiceOrderRepository.findBindingsByInvoiceIds(any()))
                .thenReturn(List.of(binding(201L, 36L, 1L)));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(newOrder);
        when(orderAggregateMutationLockService.lock(201L)).thenReturn(movedOrder);
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(36L))).thenReturn(List.of(movedItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(35L)).thenReturn(List.of(movedItem));
        when(badReviewTaskService.getPayableSum(newOrder)).thenReturn(BigDecimal.valueOf(1000));
        doAnswer(invocation -> invocation.getArgument(0))
                .when(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));

        assertTrue(service.attachOrderIfNeeded(newOrder));

        assertEquals(target, movedItem.getInvoice());
        assertEquals(CommonInvoiceStatus.DISABLED, duplicate.getStatus());
        assertTrue(duplicate.getLastError().startsWith("merged_into"));
        ArgumentCaptor<CommonInvoiceOrder> itemCaptor = ArgumentCaptor.forClass(CommonInvoiceOrder.class);
        verify(invoiceOrderRepository).save(itemCaptor.capture());
        assertEquals(target, itemCaptor.getValue().getInvoice());
    }

    @Test
    void addCompanyMovesDetachedOpenItemsFromOldAccountIntoCurrentInvoice() {
        CommonBillingAccount oldAccount = account();
        oldAccount.setId(56L);
        oldAccount.setEnabled(true);
        CommonBillingAccount newAccount = account();
        newAccount.setId(57L);
        newAccount.setEnabled(true);
        Company company = company(3041L, null);

        CommonBillingAccountCompany oldDisabledLink = new CommonBillingAccountCompany();
        oldDisabledLink.setAccount(oldAccount);
        oldDisabledLink.setCompany(company);
        oldDisabledLink.setEnabled(false);
        CommonBillingAccountCompany targetLink = new CommonBillingAccountCompany();
        targetLink.setId(700L);
        targetLink.setAccount(newAccount);
        targetLink.setCompany(company);
        targetLink.setEnabled(false);

        CommonInvoice oldInvoice = invoice(oldAccount);
        oldInvoice.setId(90L);
        oldInvoice.setStatus(CommonInvoiceStatus.COLLECTING);
        oldInvoice.setAmountKopecks(25_000L);
        CommonInvoice newInvoice = invoice(newAccount);
        newInvoice.setId(91L);
        newInvoice.setStatus(CommonInvoiceStatus.COLLECTING);
        newInvoice.setAmountKopecks(100_000L);

        Order movedOrder = order(24670L);
        movedOrder.setCompany(company);
        CommonInvoiceOrder movedItem = item(oldInvoice, movedOrder);
        movedItem.setAmountKopecks(25_000L);
        Order existingOrder = order(24667L);
        CommonInvoiceOrder existingItem = item(newInvoice, existingOrder);
        existingItem.setAmountKopecks(100_000L);

        when(accountRepository.findByIdWithRelations(57L)).thenReturn(Optional.of(newAccount));
        when(accountRepository.findByIdWithRelations(56L)).thenReturn(Optional.of(oldAccount));
        when(companyRepository.findById(3041L)).thenReturn(Optional.of(company));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(3041L))
                .thenAnswer(ignored -> targetLink.isEnabled() ? List.of(targetLink) : List.of());
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(57L, 3041L))
                .thenReturn(Optional.of(targetLink));
        when(accountCompanyRepository.findByIdForUpdate(700L)).thenReturn(Optional.of(targetLink));
        when(accountRepository.findByIdWithRelationsForUpdate(56L)).thenReturn(Optional.of(oldAccount));
        when(accountRepository.findByIdWithRelationsForUpdate(57L)).thenReturn(Optional.of(newAccount));
        when(invoiceRepository.findCurrentForAccount(eq(57L), any(), any(Pageable.class)))
                .thenReturn(List.of(newInvoice));
        when(invoiceRepository.findByIdWithAccount(90L)).thenReturn(Optional.of(oldInvoice));
        when(invoiceRepository.findByIdWithAccount(91L)).thenReturn(Optional.of(newInvoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(90L)).thenReturn(Optional.of(oldInvoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(91L)).thenReturn(Optional.of(newInvoice));
        when(invoiceOrderRepository.findBindingsByInvoiceIds(any()))
                .thenReturn(List.of(binding(24667L, 91L, 57L)));
        when(invoiceOrderRepository.findMovableOpenBindingsForCompany(eq(3041L), eq(57L), any()))
                .thenReturn(List.of(binding(24670L, 90L, 56L)));
        when(invoiceOrderRepository.findMovableOpenItemsForCompany(eq(3041L), eq(57L), any()))
                .thenReturn(List.of(movedItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(91L)).thenReturn(List.of(existingItem, movedItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(90L)).thenReturn(List.of());
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(56L)).thenReturn(List.of(oldDisabledLink));
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(57L)).thenReturn(List.of(targetLink));
        when(orderRepository.findCommonBillingBackfillOrderIds(eq(3041L), any())).thenReturn(List.of());
        when(orderAggregateMutationLockService.lock(24667L)).thenReturn(existingOrder);
        when(orderAggregateMutationLockService.lock(24670L)).thenReturn(movedOrder);

        service.addCompany(57L, 3041L);

        assertEquals(newInvoice, movedItem.getInvoice());
        assertEquals(125_000L, newInvoice.getAmountKopecks());
        assertEquals(CommonInvoiceStatus.DISABLED, oldInvoice.getStatus());
        assertEquals(0L, oldInvoice.getAmountKopecks());
        assertEquals("merged_into: common_invoice_91", oldInvoice.getLastError());
        assertFalse(oldAccount.isEnabled());
        assertFalse(targetLink.isReconcilePending());
        verify(invoiceOrderRepository).saveAll(List.of(movedItem));
        verify(accountRepository).save(oldAccount);
        var moveLocks = inOrder(
                invoiceOrderRepository,
                orderAggregateMutationLockService,
                accountRepository,
                invoiceRepository
        );
        moveLocks.verify(invoiceOrderRepository)
                .findMovableOpenBindingsForCompany(eq(3041L), eq(57L), any());
        moveLocks.verify(orderAggregateMutationLockService).lock(24667L);
        moveLocks.verify(orderAggregateMutationLockService).lock(24670L);
        moveLocks.verify(accountRepository).findByIdWithRelationsForUpdate(56L);
        moveLocks.verify(accountRepository).findByIdWithRelationsForUpdate(57L);
        moveLocks.verify(invoiceRepository).findByIdWithAccountForUpdate(90L);
        moveLocks.verify(invoiceRepository).findByIdWithAccountForUpdate(91L);
        moveLocks.verify(invoiceOrderRepository)
                .findMovableOpenItemsForCompany(eq(3041L), eq(57L), any());
    }

    @Test
    void pendingCompanyReconcileReclaimsExpiredLeaseAfterCrash() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        Company company = company();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setId(701L);
        link.setAccount(account);
        link.setCompany(company);
        link.setEnabled(true);
        link.setReconcilePending(true);
        link.setReconcileAttempts(3);
        link.setReconcileNextAttemptAt(LocalDateTime.now().minusMinutes(5));
        link.setReconcileLeaseToken("abandoned-lease");
        link.setReconcileLeaseUntil(LocalDateTime.now().minusMinutes(1));

        when(accountCompanyRepository.findPendingReconciliationIds(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(701L));
        when(accountCompanyRepository.findByIdForUpdate(701L)).thenReturn(Optional.of(link));
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 20L)).thenReturn(Optional.of(link));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));

        int processed = service.reconcilePendingCompanyLinks(20);

        assertEquals(1, processed);
        assertFalse(link.isReconcilePending());
        assertEquals(0, link.getReconcileAttempts());
        assertNull(link.getReconcileLeaseToken());
        assertNull(link.getReconcileLeaseUntil());
        verify(accountCompanyRepository, times(2)).findByIdForUpdate(701L);
    }

    @Test
    void pendingCompanyReconcileDisablesLinkAfterBoundedFinalFailure() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        Company company = company();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setId(702L);
        link.setAccount(account);
        link.setCompany(company);
        link.setEnabled(true);
        link.setReconcilePending(true);
        link.setReconcileAttempts(19);
        link.setReconcileNextAttemptAt(LocalDateTime.now().minusMinutes(1));

        when(accountCompanyRepository.findPendingReconciliationIds(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(702L));
        when(accountCompanyRepository.findByIdForUpdate(702L)).thenReturn(Optional.of(link));
        when(accountRepository.findByIdWithRelations(1L)).thenThrow(new IllegalStateException("database unavailable"));

        int processed = service.reconcilePendingCompanyLinks(20);

        assertEquals(0, processed);
        assertFalse(link.isEnabled());
        assertFalse(link.isReconcilePending());
        assertNull(link.getReconcileLeaseToken());
        assertTrue(link.getReconcileLastError().startsWith("company_reconcile_failed_final"));
    }

    @Test
    void dueReminderReloadsInvoiceUnderLockBeforeSending() {
        CommonBillingAccount account = account();
        CommonInvoice candidate = invoice(account);
        candidate.setStatus(CommonInvoiceStatus.INVOICED);
        candidate.setNextReminderAt(LocalDateTime.now().minusMinutes(5));
        CommonInvoice locked = invoice(account);
        locked.setStatus(CommonInvoiceStatus.PAID);
        locked.setNextReminderAt(candidate.getNextReminderAt());

        when(invoiceRepository.findReminderCandidates(any(), any(), any())).thenReturn(List.of(candidate));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(locked));

        assertEquals(0, service.sendDueReminders(10));

        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(invoiceOrderRepository, never()).findByInvoiceIdWithOrders(10L);
    }

    @Test
    void dueReminderPostponesWhileReviewRecoveryIsActive() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        invoice.setNextReminderAt(LocalDateTime.now().minusMinutes(5));
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findReminderCandidates(any(), any(), any())).thenReturn(List.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(recoveryGateService.hasActiveRecoveryTasks(101L)).thenReturn(true);

        assertEquals(0, service.sendDueReminders(10));

        assertTrue(invoice.getLastError().contains("review_recovery_active"));
        assertNotNull(invoice.getNextReminderAt());
        verify(invoiceRepository, times(2)).save(invoice);
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void dueReminderPersistsAttentionAndDoesNotSendForLegacyDualPaymentRoute() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.REMINDER);
        invoice.setNextReminderAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentUrl("https://securepayments.tinkoff.ru/common-active");
        invoice.setTbankPaymentId("common-payment-117");
        invoice.setTbankOrderId("g117-active");
        Order order = order(23_824L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentLink manualRoute = new PaymentLink();
        manualRoute.setId(11_700L);
        manualRoute.setOrder(order);
        manualRoute.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        manualRoute.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.MANUAL_MOBILE_BANK);
        manualRoute.setAmountKopecks(100_000L);

        when(invoiceRepository.findReminderCandidates(any(), any(), any())).thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(23_824L));
        when(orderAggregateMutationLockService.lock(23_824L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdForUpdate(23_824L)).thenReturn(List.of(manualRoute));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        assertEquals(0, service.sendDueReminders(10));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getNextReminderAt());
        assertTrue(invoice.getLastError().startsWith("standalone_payment_route_conflict:"));
        assertTrue(invoice.getLastError().contains("Заказ #23824"));
        assertEquals("common-payment-117", invoice.getTbankPaymentId());
        assertEquals("g117-active", invoice.getTbankOrderId());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, manualRoute.getStatus());
        verify(invoiceRepository).save(invoice);
        verify(paymentLinkRepository, never()).save(manualRoute);
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void dueReminderSynchronizesLockedConfirmedStandalonePaymentBeforeRouteValidation() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.REMINDER);
        invoice.setNextReminderAt(LocalDateTime.now().minusMinutes(5));
        Order paidOrder = order(101L);
        paidOrder.setStatus(status("Оплачено"));
        Order unpaidOrder = order(102L);
        CommonInvoiceOrder paidItem = item(invoice, paidOrder);
        CommonInvoiceOrder unpaidItem = item(invoice, unpaidOrder);
        PaymentLink confirmed = confirmedStandaloneBankPayment(501L, paidOrder, 100_000L);
        List<CommonInvoiceOrder> items = List.of(paidItem, unpaidItem);

        when(invoiceRepository.findReminderCandidates(any(), any(), any())).thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L, 102L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(paidOrder);
        when(orderAggregateMutationLockService.lock(102L)).thenReturn(unpaidOrder);
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of(confirmed));
        when(paymentLinkRepository.findByOrderIdForUpdate(102L)).thenReturn(List.of());
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(items);
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(paidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(unpaidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);

        assertEquals(0, service.sendDueReminders(10));

        assertTrue(paidItem.isPaid());
        assertFalse(paidItem.isUnpaid());
        assertEquals(confirmed.getPaidAt(), paidItem.getPaidAt());
        assertEquals("TBANK", paidItem.getPaymentMethod());
        assertEquals(501L, paidItem.getSourcePaymentLinkId());
        assertNull(confirmed.getLastError());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertEquals(100_000L, invoice.getPaidKopecks());
        assertFalse(invoice.getLastError().startsWith("standalone_payment_route_conflict:"));
        verify(invoiceOrderRepository).saveAll(List.of(paidItem));
        verify(paymentLinkRepository, never()).saveAll(any());
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void manualInvoiceSendRejectsActiveReviewRecovery() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(true);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(recoveryGateService.hasActiveRecoveryTasks(101L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));

        assertEquals("Общий счет ждет завершения задач восстановления отзывов", exception.getReason());
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void managerCannotCreateAccountForHiddenManager() {
        Manager visibleManager = manager(11L);
        Manager hiddenManager = manager(12L);
        authenticateManager(visibleManager);

        when(managerRepository.findById(12L)).thenReturn(Optional.of(hiddenManager));

        CommonBillingAccountRequest request = new CommonBillingAccountRequest(
                "Чужой общий счет",
                true,
                true,
                12L,
                null,
                List.of()
        );

        assertThrows(ResponseStatusException.class, () -> service.createAccount(request));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void managerCannotCreateAccountWithHiddenCompany() {
        Manager visibleManager = manager(11L);
        Manager hiddenManager = manager(12L);
        authenticateManager(visibleManager);

        when(companyRepository.findById(22L)).thenReturn(Optional.of(company(22L, hiddenManager)));

        CommonBillingAccountRequest request = new CommonBillingAccountRequest(
                "Чужой общий счет",
                true,
                true,
                null,
                null,
                List.of(22L)
        );

        assertThrows(ResponseStatusException.class, () -> service.createAccount(request));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void managerCannotAddHiddenCompanyToVisibleAccount() {
        Manager visibleManager = manager(11L);
        Manager hiddenManager = manager(12L);
        authenticateManager(visibleManager);
        CommonBillingAccount account = account();
        account.setManager(visibleManager);

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(1L)).thenReturn(List.of());
        when(companyRepository.findById(22L)).thenReturn(Optional.of(company(22L, hiddenManager)));

        assertThrows(ResponseStatusException.class, () -> service.addCompany(1L, 22L));

        verify(accountCompanyRepository, never()).save(any());
    }

    @Test
    void manualReminderRespectsClientMessagesDryRun() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.sendManualReminder(10L);

        assertEquals(CommonInvoiceStatus.REMINDER, invoice.getStatus());
        assertNotNull(invoice.getLastReminderAt());
        assertEquals(null, invoice.getNextReminderAt());
        assertTrue(invoice.getLastError().contains("dry_run"));
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Напоминание");
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void sendInvoiceDryRunKeepsAlreadySentStatus() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.REMINDER);
        LocalDateTime sentAt = LocalDateTime.now().minusDays(1);
        invoice.setSentAt(sentAt);
        invoice.setNextReminderAt(LocalDateTime.now());
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.sendInvoice(10L, true);

        assertEquals(CommonInvoiceStatus.REMINDER, invoice.getStatus());
        assertEquals(sentAt, invoice.getSentAt());
        assertEquals(null, invoice.getNextReminderAt());
        assertTrue(invoice.getLastError().contains("dry_run"));
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void manualSendInvoiceDryRunMarksReadyInvoiceAsInvoiced() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.sendInvoice(10L, true);

        assertEquals(CommonInvoiceStatus.INVOICED, invoice.getStatus());
        assertEquals(null, invoice.getNextReminderAt());
        assertTrue(invoice.getLastError().contains("dry_run"));
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Выставлен счет");
        verify(messageSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void sendInvoiceMarksInProgressOnlyAroundExternalMessageSend() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        doAnswer(invocation -> {
            assertEquals("message_send_in_progress", invoice.getLastError());
            return ClientMessageSendResult.sent("test");
        }).when(messageSender).send(any(), any(), any(), any());

        service.sendInvoice(10L, true);

        assertEquals(CommonInvoiceStatus.INVOICED, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(messageSender).send(any(), any(), any(), any());
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Выставлен счет");
    }

    @Test
    void unsentPartiallyPaidInvoiceIsSentForRemainingAmountAndGetsReminderDate() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setSentAt(null);
        invoice.setNextReminderAt(null);
        Order paidOrder = order(101L);
        Order unpaidOrder = order(102L);
        CommonInvoiceOrder paidItem = item(invoice, paidOrder);
        paidItem.setPaid(true);
        CommonInvoiceOrder unpaidItem = item(invoice, unpaidOrder);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true))
                .thenReturn(true);
        when(invoiceRepository.findUnsentActionCandidates(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(invoice));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L))
                .thenReturn(List.of(paidItem, unpaidItem));
        when(badReviewTaskService.getPayableSum(unpaidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("test"));
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        assertEquals(1, service.sendUnsentActionInvoices(20));

        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertNotNull(invoice.getSentAt());
        assertNotNull(invoice.getNextReminderAt());
        verify(messageSender).send(any(), any(), any(), messageCaptor.capture());
        String compactMessage = messageCaptor.getValue().replace(" ", "").replace("\u00A0", "");
        assertTrue(compactMessage.contains("Коплате:1000"));
        verify(orderStatusTransitionService, never())
                .changeStatusForCommonBillingOrder(101L, "Выставлен счет");
        verify(orderStatusTransitionService)
                .changeStatusForCommonBillingOrder(102L, "Выставлен счет");
    }

    @Test
    void reminderChangesStatusOnlyForUnpaidInvoiceOrders() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setAmountKopecks(200_000L);
        invoice.setPaidKopecks(100_000L);
        Order paidOrder = order(101L);
        Order unpaidOrder = order(102L);
        CommonInvoiceOrder paidItem = item(invoice, paidOrder);
        paidItem.setPaid(true);
        CommonInvoiceOrder unpaidItem = item(invoice, unpaidOrder);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L))
                .thenReturn(List.of(paidItem, unpaidItem));
        when(badReviewTaskService.getPayableSum(unpaidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("test"));

        service.sendManualReminder(10L);

        verify(orderStatusTransitionService, never())
                .changeStatusForCommonBillingOrder(101L, "Напоминание");
        verify(orderStatusTransitionService)
                .changeStatusForCommonBillingOrder(102L, "Напоминание");
    }

    @Test
    void manualSendInvoiceFailureStillMarksInvoiceAsInvoiced() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.failed("no_chat", "чат не найден"));

        service.sendInvoice(10L, true);

        assertEquals(CommonInvoiceStatus.INVOICED, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("no_chat"));
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Выставлен счет");
        verify(messageSender).send(any(), any(), any(), any());
    }

    @Test
    void manualReminderFailureStillMarksInvoiceAsReminder() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice))
                .thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.failed("no_chat", "чат не найден"));

        service.sendManualReminder(10L);

        assertEquals(CommonInvoiceStatus.REMINDER, invoice.getStatus());
        assertNotNull(invoice.getLastReminderAt());
        assertNotNull(invoice.getNextReminderAt());
        assertTrue(invoice.getLastError().contains("no_chat"));
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Напоминание");
        verify(messageSender).send(any(), any(), any(), any());
    }

    @Test
    void sendInvoiceRecoversStaleMessageSendMarker() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setLastError("message_send_in_progress");
        invoice.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");
        when(messageSender.send(any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("test"));

        service.sendInvoice(10L, true);

        assertEquals(CommonInvoiceStatus.INVOICED, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(messageSender).send(any(), any(), any(), any());
    }

    @Test
    void sendInvoiceMovesInvoiceToAttentionWhenAmountCannotBeCalculated() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenThrow(new RuntimeException("bad tasks down"));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("amount_calc_failed"));
        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(tbankClient, never()).init(any(), any());
    }

    @Test
    void attachOrderMovesInvoiceToAttentionWhenAmountCannotBeCalculated() {
        CommonBillingAccount account = account();
        account.setEnabled(true);
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        Order order = order(101L);

        when(orderRepository.findCompanyIdByOrderId(101L)).thenReturn(Optional.of(20L));
        when(invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(101L)).thenReturn(Optional.empty());
        when(invoiceOrderRepository.findMembershipByOrderIdForRead(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 20L)).thenReturn(Optional.of(link));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findCurrentForAccount(any(), any(), any())).thenReturn(List.of());
        when(badReviewTaskService.getPayableSum(order)).thenThrow(new RuntimeException("bad tasks down"));
        CommonInvoice[] createdInvoice = new CommonInvoice[1];
        doAnswer(invocation -> {
            CommonInvoice saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99L);
            }
            createdInvoice[0] = saved;
            return saved;
        }).when(invoiceRepository).save(any(CommonInvoice.class));
        doAnswer(invocation -> invocation.getArgument(0))
                .when(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(99L)).thenAnswer(invocation -> {
            CommonInvoiceOrder item = new CommonInvoiceOrder();
            item.setInvoice(createdInvoice[0]);
            item.setOrder(order);
            item.setAmountKopecks(0);
            return List.of(item);
        });

        assertTrue(service.attachOrderIfNeeded(order));

        CommonInvoice invoice = createdInvoice[0];
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("amount_calc_failed"));
        ArgumentCaptor<CommonInvoiceOrder> itemCaptor = ArgumentCaptor.forClass(CommonInvoiceOrder.class);
        verify(invoiceOrderRepository).save(itemCaptor.capture());
        assertEquals(0, itemCaptor.getValue().getAmountKopecks());
    }

    @Test
    void refreshLinkedOrderAmountMovesInvoiceToAttentionWhenAmountCannotBeCalculated() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenThrow(new RuntimeException("bad tasks down"));

        assertTrue(service.refreshLinkedOrderAmount(101L));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("amount_calc_failed"));
        verify(invoiceOrderRepository, never()).save(item);
    }

    @Test
    void refreshDoesNotRewriteDeliveredCommonInvoiceAmount() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        invoice.setSentAt(LocalDateTime.of(2026, 8, 7, 10, 0));
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));

        assertTrue(service.refreshLinkedOrderAmount(101L));

        assertEquals(100_000L, item.getAmountKopecks());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payable_change_requires_supplement:"));
        verify(invoiceOrderRepository, never()).save(item);
    }

    @Test
    void unpaidCommonInvoiceAllowsTaskCompletionButRequiresSupplement() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setUnpaid(true);
        stubLockedInvoice(invoice, item, order);

        assertEquals(
                CommonPayableChangeDisposition.SUPPLEMENT_REQUIRED,
                service.prepareLinkedOrderPayableChange(101L)
        );

        assertEquals(CommonInvoiceStatus.UNPAID, invoice.getStatus());
        assertEquals(100_000L, item.getAmountKopecks());
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void unsafeUnpaidMembershipFailsClosedWithoutStandaloneCycle() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setUnpaid(false);
        stubLockedInvoice(invoice, item, order);

        assertTrue(service.createBadReviewSupplementSuccessor(101L, 77L));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals(CommonInvoiceStatus.UNPAID.name(), invoice.getPreviousStatus());
        assertTrue(invoice.getLastError().startsWith("bad_review_supplement_required:"));
        assertTrue(invoice.getLastError().contains("task=77"));
        assertEquals(100_000L, item.getAmountKopecks());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unpaidCommonCycleCreatesOneCollectingSuccessorAndKeepsPredecessorImmutable() {
        CommonBillingAccount account = account();
        CommonInvoice predecessor = invoice(account);
        predecessor.setStatus(CommonInvoiceStatus.UNPAID);
        predecessor.setTitle("x".repeat(180));
        Order order = order(101L);
        order.setStatus(status("Не оплачено"));
        CommonInvoiceOrder sourceItem = item(predecessor, order);
        sourceItem.setActiveMembership(true);
        sourceItem.setUnpaid(true);
        stubLockedInvoice(predecessor, sourceItem, order);
        when(invoiceRepository.findByCycleIdempotencyKeyForUpdate("BAD_REVIEW_SUCCESSOR:10"))
                .thenReturn(Optional.empty());
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(sourceItem));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1300));
        when(badReviewTaskService.getSummaryForOrder(101L)).thenReturn(
                new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(300), BigDecimal.ZERO)
        );
        when(invoiceRepository.save(any(CommonInvoice.class))).thenAnswer(invocation -> {
            CommonInvoice saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(11L);
            }
            return saved;
        });

        assertTrue(service.createBadReviewSupplementSuccessor(101L, 77L));

        assertEquals(CommonInvoiceStatus.UNPAID, predecessor.getStatus());
        assertFalse(sourceItem.isActiveMembership());
        ArgumentCaptor<Iterable> itemBatches = ArgumentCaptor.forClass(Iterable.class);
        verify(invoiceOrderRepository, times(3)).saveAll(itemBatches.capture());
        List<CommonInvoiceOrder> successorItems = new ArrayList<>();
        itemBatches.getAllValues().get(1).forEach(value -> successorItems.add((CommonInvoiceOrder) value));
        assertEquals(1, successorItems.size());
        CommonInvoiceOrder successorItem = successorItems.get(0);
        assertTrue(successorItem.isActiveMembership());
        assertFalse(successorItem.isUnpaid());
        assertEquals(130_000L, successorItem.getAmountKopecks());
        CommonInvoice successor = successorItem.getInvoice();
        assertEquals(predecessor, successor.getSupersedesInvoice());
        assertEquals("BAD_REVIEW_SUCCESSOR", successor.getInvoicePurpose());
        assertEquals("BAD_REVIEW_SUCCESSOR:10", successor.getCycleIdempotencyKey());
        assertEquals(180, successor.getTitle().length());
        assertEquals(CommonInvoiceStatus.READY, successor.getStatus());
        assertEquals("Не оплачено", order.getStatus().getTitle());
        verify(orderRepository, never()).save(order);
        verify(entityManager).flush();
    }

    @Test
    void attentionInvoiceRejectsNormalAndPositionActions() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));

        assertThrows(ResponseStatusException.class, () -> service.sendInvoice(10L, true));
        assertThrows(ResponseStatusException.class, () -> service.sendManualReminder(10L));
        assertThrows(ResponseStatusException.class, () -> service.markPaid(10L));
        assertThrows(ResponseStatusException.class, () -> service.markUnpaid(10L));
        assertThrows(ResponseStatusException.class, () -> service.markOrderPaid(10L, 101L));
        assertThrows(ResponseStatusException.class, () -> service.detachOrder(10L, 101L));
    }

    @Test
    void retryAttentionClosesInvoiceAfterIssueIsFixed() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("next_order_failed");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        List<CommonInvoiceOrder> items = List.of(item);

        invoice.setAmountKopecks(100_000L);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.retryAttention(10L);

        assertTrue(item.isPaid());
        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(orderTransactionService).handlePaymentStatus(order, false);
        verify(nextOrderRequestService).openForPaidOrder(order);
    }

    @Test
    void retryAttentionRejectsLateArchivedPaymentConflict() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));

        assertThrows(ResponseStatusException.class, () -> service.retryAttention(10L));
        assertFalse(item.isPaid());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void resolveAttentionRejectsLateArchivedPaymentConflict() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> service.resolveAttention(10L));
    }

    @Test
    void resolveAttentionDoesNotDropRecordedFullPaymentWhenOrderStillOpen() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("close_failed: платеж получен, но заказы не закрылись: 101");
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        assertThrows(ResponseStatusException.class, () -> service.resolveAttention(10L));
        assertEquals(100_000L, invoice.getPaidKopecks());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
    }

    @Test
    void invoiceRefreshPreservesRecordedFullAttentionPayment() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("close_failed: платеж получен, но заказы не закрылись: 101");
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.invoice(10L);

        assertEquals(100_000L, invoice.getPaidKopecks());
        assertEquals(0L, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
    }

    @Test
    void resolveAttentionClearsNextOrderFailureAfterManualCheckWhenItemsArePaid() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("next_order_failed: платеж закрыт, но следующие заказы не создались: Компания #101");
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setPaid(true);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.resolveAttention(10L);

        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
    }

    @Test
    void completePublishedOrderDoesNotUnlockAttentionInvoice() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        OrderStatus waitingStatus = status("Ожидает общего счета");
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));
        when(orderStatusService.getOrderStatusByTitle("Ожидает общего счета")).thenReturn(waitingStatus);

        assertTrue(service.completePublishedOrderIntoCommonInvoice(order));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals(waitingStatus, order.getStatus());
        assertFalse(item.isPaid());
        verify(orderRepository).save(order);
        verify(invoiceOrderRepository, never()).save(item);
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void completePublishedOrderWaitsThenPublishesAllOrdersWhenInvoiceIsReady() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        OrderStatus waitingStatus = status("Ожидает общего счета");
        OrderStatus publicStatus = status("Опубликовано");
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderStatusService.getOrderStatusByTitle("Ожидает общего счета")).thenReturn(waitingStatus);
        when(orderStatusService.getOrderStatusByTitle("Опубликовано")).thenReturn(publicStatus);
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        assertTrue(service.completePublishedOrderIntoCommonInvoice(order));

        assertTrue(item.isReady());
        assertEquals(100_000L, item.getAmountKopecks());
        assertEquals(publicStatus, order.getStatus());
        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("auto_send_disabled"));
        verify(orderRepository, times(2)).save(order);
        verify(invoiceOrderRepository).save(item);
    }

    @Test
    void applyLatePaymentDistributesConfirmedArchivedPaymentByWholeOrders() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        Order thirdOrder = order(103L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        CommonInvoiceOrder thirdItem = item(invoice, thirdOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem, thirdItem);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setStatus("CONFIRMED");
        ref.setAmountKopecks(200_000L);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(10L, "CONFIRMED")).thenReturn(List.of(ref));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(firstOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(secondOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(thirdOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.applyLatePayment(10L);

        assertTrue(firstItem.isPaid());
        assertTrue(secondItem.isPaid());
        assertFalse(thirdItem.isPaid());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        assertEquals("APPLIED", ref.getStatus());
        verify(orderTransactionService).handlePaymentStatus(firstOrder, false);
        verify(orderTransactionService).handlePaymentStatus(secondOrder, false);
        verify(orderTransactionService, never()).handlePaymentStatus(thirdOrder, false);
    }

    @Test
    void applyLatePaymentDoesNotSubtractManuallyPaidItemsFromArchivedPayment() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");
        Order manuallyPaidOrder = order(101L);
        manuallyPaidOrder.setStatus(status("Оплачено"));
        Order unpaidOrder = order(102L);
        CommonInvoiceOrder manuallyPaidItem = item(invoice, manuallyPaidOrder);
        manuallyPaidItem.setPaid(true);
        CommonInvoiceOrder unpaidItem = item(invoice, unpaidOrder);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setStatus("CONFIRMED");
        ref.setAmountKopecks(100_000L);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(10L, "CONFIRMED")).thenReturn(List.of(ref));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(manuallyPaidItem, unpaidItem));
        when(badReviewTaskService.getPayableSum(unpaidOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.applyLatePayment(10L);

        assertTrue(unpaidItem.isPaid());
        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        assertEquals("APPLIED", ref.getStatus());
        verify(orderTransactionService).handlePaymentStatus(unpaidOrder, false);
        verify(orderTransactionService, never()).handlePaymentStatus(manuallyPaidOrder, false);
    }

    @Test
    void applyLatePaymentKeepsConfirmedRefWhenOrderClosingFails() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");
        Order firstOrder = order(101L);
        Order secondOrder = order(102L);
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setStatus("CONFIRMED");
        ref.setAmountKopecks(200_000L);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(10L, "CONFIRMED")).thenReturn(List.of(ref));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(firstOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(badReviewTaskService.getPayableSum(secondOrder)).thenReturn(BigDecimal.valueOf(1000));
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (Long.valueOf(102L).equals(order.getId())) {
                throw new RuntimeException("zp");
            }
            return false;
        }).when(orderTransactionService).handlePaymentStatus(any(Order.class), anyBoolean());
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.applyLatePayment(10L);

        assertTrue(firstItem.isPaid());
        assertFalse(secondItem.isPaid());
        assertEquals("TBANK", firstItem.getPaymentMethod());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("late_payment_close_failed"));
        assertEquals("CONFIRMED", ref.getStatus());
        assertThrows(ResponseStatusException.class, () -> service.retryAttention(10L));
    }

    @Test
    void retryAttentionRejectsLatePaymentRecoveryStates() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_payment_unallocated: остаток позднего платежа 500 руб.");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));

        assertThrows(ResponseStatusException.class, () -> service.retryAttention(10L));
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void confirmedCurrentWebhookBeforeAllOrdersReadyStoresPrepaymentWithoutClosingOrders() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        invoice.setTbankOrderId("old-order");
        invoice.setTbankPaymentId("old-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setPaymentUrl("https://pay/current");
        CommonInvoicePaymentRef currentRef = paymentRef(
                44L,
                invoice,
                "CURRENT",
                "old-order",
                "old-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(currentRef.getId(), currentRef);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        order.setAmount(2);
        order.setCounter(1);
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("Amount", "100000");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository, atLeastOnce()).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getAllValues().getLast();
        assertEquals("PREPAID", ref.getStatus());
        assertEquals("prepaid_waiting_common_invoice_ready", ref.getReason());
        assertNull(invoice.getTbankOrderId());
        assertNull(invoice.getTbankPaymentId());
        assertNull(invoice.getPaymentUrl());
        assertEquals(CommonInvoiceStatus.COLLECTING, invoice.getStatus());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void paymentIdOnlyConfirmedWebhookUsesDurableCurrentAnchor() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setTbankOrderId("current-order");
        invoice.setTbankPaymentId("payment-only");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setPaymentUrl("https://pay/current");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        CommonInvoicePaymentRef currentRef = paymentRef(
                46L,
                invoice,
                "CURRENT",
                "current-order",
                "payment-only",
                "terminal",
                100_000L
        );
        paymentRefStore.put(currentRef.getId(), currentRef);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.remove("OrderId");
        payload.put("PaymentId", "payment-only");

        when(invoiceRepository.findIdsByTbankPaymentId("payment-only")).thenReturn(List.of(10L));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);
        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        assertNull(invoice.getTbankPaymentId());
        assertEquals("CONFIRMED", currentRef.getStatus());
        verify(orderTransactionService).handlePaymentStatus(order, false);
    }

    @Test
    void terminalWebhookWithoutPaymentIdIsRejectedBeforeMutation() {
        Map<String, String> payload = confirmedWebhookPayload();
        payload.remove("PaymentId");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.handleTbankWebhook(payload)
        );

        assertEquals(400, error.getStatusCode().value());
        verify(invoiceRepository, never()).save(any());
        verify(paymentRefRepository, never()).save(any());
    }

    @Test
    void webhookCurrentRegistryConstraintIsQuarantinedAndAcknowledged() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setTbankOrderId("prepared-order");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        invoice.setLastError("payment_init_in_progress");
        CommonInvoicePaymentRef preparedRef = paymentRef(
                49L,
                invoice,
                "INIT_PREPARED",
                "prepared-order",
                null,
                "terminal",
                100_000L
        );
        paymentRefStore.put(preparedRef.getId(), preparedRef);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "prepared-order");
        payload.put("PaymentId", "webhook-current-collision");
        payload.put("Status", "AUTHORIZED");
        int[] flushAttempts = {0};

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);
        doAnswer(invocation -> {
            if (flushAttempts[0]++ == 0) {
                throw new DataIntegrityViolationException(
                        "Duplicate entry for key uk_common_invoice_payment_refs_current_invoice"
                );
            }
            return null;
        }).when(entityManager).flush();

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(2, flushAttempts[0]);
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        assertEquals("webhook-current-collision", preparedRef.getTbankPaymentId());
        assertTrue(preparedRef.getReason().startsWith("webhook_current_registry_constraint"));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getTbankOrderId());
        assertNull(invoice.getTbankPaymentId());
        assertTrue(invoice.getLastError().startsWith("payment_registry_collision"));
    }

    @Test
    void paymentIdDuplicateAcrossLegacyInvoicesQuarantinesAllEvenWhenRegistryAnchorExists() {
        CommonBillingAccount firstAccount = account();
        CommonBillingAccount secondAccount = account();
        secondAccount.setId(2L);
        CommonInvoice first = invoice(firstAccount);
        first.setPaymentUrl("https://pay/first");
        first.setTbankPaymentId("duplicate-payment");
        CommonInvoice second = invoice(secondAccount);
        second.setId(11L);
        second.setPaymentUrl("https://pay/second");
        second.setTbankPaymentId("duplicate-payment");
        CommonInvoicePaymentRef anchor = paymentRef(
                47L,
                first,
                "CURRENT",
                "first-order",
                "duplicate-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(anchor.getId(), anchor);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.remove("OrderId");
        payload.put("PaymentId", "duplicate-payment");

        when(invoiceRepository.findIdsByTbankPaymentId("duplicate-payment"))
                .thenReturn(List.of(10L, 11L));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(first));
        when(invoiceRepository.findByIdWithAccount(11L)).thenReturn(Optional.of(second));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(first));
        when(invoiceRepository.findByIdWithAccountForUpdate(11L)).thenReturn(Optional.of(second));
        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(firstAccount));
        when(accountRepository.findByIdWithRelations(2L)).thenReturn(Optional.of(secondAccount));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(firstAccount));
        when(accountRepository.findByIdWithRelationsForUpdate(2L)).thenReturn(Optional.of(secondAccount));
        when(invoiceOrderRepository.findBindingsByInvoiceIds(any())).thenReturn(List.of());
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, first.getStatus());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, second.getStatus());
        assertNull(first.getPaymentUrl());
        assertNull(second.getPaymentUrl());
        assertEquals("duplicate-payment", first.getTbankPaymentId());
        assertEquals("duplicate-payment", second.getTbankPaymentId());
        assertTrue(first.getLastError().contains("PaymentId duplicate-payment"));
        assertTrue(second.getLastError().contains("PaymentId duplicate-payment"));
        verify(paymentRefRepository, never()).save(any());
    }

    @Test
    void orderIdDuplicateAcrossLegacyInvoicesQuarantinesAllBeforeRegistryRouting() {
        CommonBillingAccount firstAccount = account();
        CommonBillingAccount secondAccount = account();
        secondAccount.setId(2L);
        CommonInvoice first = invoice(firstAccount);
        first.setTbankOrderId("duplicate-order");
        first.setPaymentUrl("https://pay/first");
        CommonInvoice second = invoice(secondAccount);
        second.setId(11L);
        second.setTbankOrderId("duplicate-order");
        second.setPaymentUrl("https://pay/second");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "duplicate-order");
        payload.put("PaymentId", "new-payment");

        when(invoiceRepository.findIdsByTbankOrderId("duplicate-order"))
                .thenReturn(List.of(10L, 11L));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(first));
        when(invoiceRepository.findByIdWithAccount(11L)).thenReturn(Optional.of(second));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(first));
        when(invoiceRepository.findByIdWithAccountForUpdate(11L)).thenReturn(Optional.of(second));
        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(firstAccount));
        when(accountRepository.findByIdWithRelations(2L)).thenReturn(Optional.of(secondAccount));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(firstAccount));
        when(accountRepository.findByIdWithRelationsForUpdate(2L)).thenReturn(Optional.of(secondAccount));
        when(invoiceOrderRepository.findBindingsByInvoiceIds(any())).thenReturn(List.of());
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, first.getStatus());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, second.getStatus());
        assertNull(first.getPaymentUrl());
        assertNull(second.getPaymentUrl());
        assertEquals("duplicate-order", first.getTbankOrderId());
        assertEquals("duplicate-order", second.getTbankOrderId());
        assertTrue(first.getLastError().contains("OrderId duplicate-order"));
        assertTrue(second.getLastError().contains("OrderId duplicate-order"));
        verify(paymentRefRepository, never()).findByTbankOrderId(anyString());
    }

    @Test
    void currentWebhookWithoutDurableAnchorFailsClosedAndPreservesProviderEvidence() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setTbankOrderId("missing-order");
        invoice.setTbankPaymentId("missing-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setPaymentUrl("https://pay/missing");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "missing-order");
        payload.put("PaymentId", "missing-payment");

        when(invoiceRepository.findIdsByTbankOrderId("missing-order")).thenReturn(List.of(10L));
        when(invoiceRepository.findIdsByTbankPaymentId("missing-payment")).thenReturn(List.of(10L));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getPaymentUrl());
        assertEquals("missing-order", invoice.getTbankOrderId());
        assertEquals("missing-payment", invoice.getTbankPaymentId());
        assertTrue(invoice.getLastError().startsWith("payment_registry_missing"));
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void duplicateArchivedWebhookDoesNotReopenAppliedLatePayment() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setLastError(null);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId("old-order");
        ref.setTbankPaymentId("old-payment");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        ref.setStatus("APPLIED");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();

        when(paymentRefRepository.findByTbankOrderId("old-order")).thenReturn(Optional.of(ref));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals("APPLIED", ref.getStatus());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(paymentRefRepository, never()).save(ref);
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void archivedAuthorizedWebhookMovesCompleteInitAnchorToCancelPending() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_conflict: interrupted Init");
        CommonInvoicePaymentRef ref = paymentRef(
                48L,
                invoice,
                "INIT_PREPARED",
                "prepared-order",
                "prepared-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(ref.getId(), ref);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "prepared-order");
        payload.put("PaymentId", "prepared-payment");
        payload.put("Status", "AUTHORIZED");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals("CANCEL_PENDING", ref.getStatus());
        assertTrue(ref.getReason().contains("init_webhook_cancel_pending:AUTHORIZED"));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getPaymentUrl());
    }

    @Test
    void nonterminalWebhookCannotLeaveArchivedProviderLinkInRawAuthorizedState() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        CommonInvoicePaymentRef ref = paymentRef(
                50L,
                invoice,
                "ARCHIVED",
                "archived-order",
                "archived-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(ref.getId(), ref);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "archived-order");
        payload.put("PaymentId", "archived-payment");
        payload.put("Status", "AUTHORIZED");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals("CANCEL_PENDING", ref.getStatus());
        assertTrue(ref.getReason().contains("init_webhook_cancel_pending:AUTHORIZED"));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertNull(invoice.getPaymentUrl());
    }

    @Test
    void staleAuthorizedWebhookCannotDowngradeAppliedArchivedPayment() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        CommonInvoicePaymentRef ref = paymentRef(
                51L,
                invoice,
                "APPLIED",
                "applied-order",
                "applied-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(ref.getId(), ref);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "applied-order");
        payload.put("PaymentId", "applied-payment");
        payload.put("Status", "AUTHORIZED");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals("APPLIED", ref.getStatus());
        assertEquals(CommonInvoiceStatus.PAID, invoice.getStatus());
        verify(paymentRefRepository, never()).save(ref);
    }

    @Test
    void refundedArchivedWebhookMarksPaidInvoiceNeedsAttention() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        invoice.setLastError(null);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId("old-order");
        ref.setTbankPaymentId("old-payment");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        ref.setStatus("ARCHIVED");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("Status", "REFUNDED");

        when(paymentRefRepository.findByTbankOrderId("old-order")).thenReturn(Optional.of(ref));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals("REFUNDED", ref.getStatus());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("tbank_payment_refunded"));
        verify(paymentRefRepository).save(ref);
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void markBanArchivesAndClearsCurrentTbankPaymentBeforeLateWebhook() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        invoice.setTbankOrderId("old-order");
        invoice.setTbankPaymentId("old-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setPaymentUrl("https://pay/old");
        CommonInvoicePaymentRef currentRef = paymentRef(
                45L,
                invoice,
                "CURRENT",
                "old-order",
                "old-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(currentRef.getId(), currentRef);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        CommonInvoicePaymentRef archivedRef = new CommonInvoicePaymentRef();
        archivedRef.setInvoice(invoice);
        archivedRef.setTbankOrderId("old-order");
        archivedRef.setTbankPaymentId("old-payment");
        archivedRef.setTbankTerminalKey("terminal");
        archivedRef.setAmountKopecks(100_000L);
        archivedRef.setStatus("ARCHIVED");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getSummaryByOrderIds(List.of(101L)))
                .thenReturn(Map.of(101L, new com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary(
                        1,
                        0,
                        1,
                        0,
                        BigDecimal.valueOf(300),
                        BigDecimal.ZERO
                )));
        when(paymentRefRepository.findByTbankOrderId("old-order"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(archivedRef));
        when(paymentRefRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.markBan(10L);

        assertEquals(CommonInvoiceStatus.BAN, invoice.getStatus());
        assertEquals(null, invoice.getTbankOrderId());
        assertEquals(null, invoice.getTbankPaymentId());
        assertEquals(null, invoice.getPaymentUrl());

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("late_tbank_payment"));
        assertEquals("CONFIRMED", archivedRef.getStatus());
    }

    @Test
    void archiveInvoiceClosesWholeGroupAndRemembersOrderStatuses() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order firstOrder = order(101L);
        firstOrder.setStatus(status("В проверку"));
        Order secondOrder = order(102L);
        secondOrder.setStatus(status("Коррекция"));
        CommonInvoiceOrder firstItem = item(invoice, firstOrder);
        CommonInvoiceOrder secondItem = item(invoice, secondOrder);
        firstItem.setReady(false);
        secondItem.setReady(false);
        List<CommonInvoiceOrder> items = List.of(firstItem, secondItem);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getSummaryForOrder(101L)).thenReturn(BadReviewTaskSummary.empty());
        when(badReviewTaskService.getSummaryForOrder(102L)).thenReturn(BadReviewTaskSummary.empty());
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            Order target = orderId.equals(101L) ? firstOrder : secondOrder;
            target.setStatus(status("Архив"));
            return true;
        }).when(orderStatusTransitionService).changeStatusForCommonBillingOrder(any(), eq("Архив"));

        CommonInvoiceDetailsResponse result = service.archiveInvoice(
                10L,
                new CommonInvoiceCloseRequest(true, ""),
                () -> "manager"
        );

        assertEquals(CommonInvoiceStatus.ARCHIVED, invoice.getStatus());
        assertEquals("MANUAL_ARCHIVE", invoice.getCloseReason());
        assertEquals("manager", invoice.getClosedBy());
        assertNotNull(invoice.getClosedAt());
        assertEquals("В проверку", firstItem.getArchiveSourceOrderStatusTitle());
        assertEquals("Коррекция", secondItem.getArchiveSourceOrderStatusTitle());
        assertEquals("ARCHIVED", result.summary().status());
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Архив");
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(102L, "Архив");
    }

    @Test
    void restoreLiveArchiveFallsBackToToCheckForUnknownRememberedStatus() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.ARCHIVED);
        invoice.setClosedAt(LocalDateTime.now());
        invoice.setClosedBy("manager");
        invoice.setCloseReason("MANUAL_ARCHIVE");
        Order order = order(101L);
        order.setStatus(status("Архив"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setArchiveSourceOrderStatusTitle("устаревший статус");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(orderStatusTransitionService.changeStatusForPrivilegedCommonBillingOrder(101L, "В проверку"))
                .thenReturn(true);

        CommonInvoiceDetailsResponse result = service.restoreLiveArchivedInvoice(10L, () -> "manager");

        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertNull(invoice.getClosedAt());
        assertNull(invoice.getClosedBy());
        assertNull(invoice.getCloseReason());
        assertEquals("READY", result.summary().status());
        verify(orderStatusTransitionService)
                .changeStatusForPrivilegedCommonBillingOrder(101L, "В проверку");
    }

    @Test
    void rejectedCurrentWebhookArchivesAndClearsCurrentPaymentLink() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        invoice.setTbankOrderId("old-order");
        invoice.setTbankPaymentId("old-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setPaymentUrl("https://pay/old");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("Success", "false");
        payload.put("Status", "REJECTED");
        payload.put("ErrorCode", "51");
        CommonInvoicePaymentRef currentRef = paymentRef(
                45L,
                invoice,
                "CURRENT",
                "old-order",
                "old-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(currentRef.getId(), currentRef);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository, atLeastOnce()).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getAllValues().getLast();
        assertEquals("old-order", ref.getTbankOrderId());
        assertEquals("old-payment", ref.getTbankPaymentId());
        assertEquals("REJECTED", ref.getStatus());
        assertEquals(null, invoice.getTbankOrderId());
        assertEquals(null, invoice.getTbankPaymentId());
        assertEquals(null, invoice.getPaymentUrl());
        assertEquals("tbank_payment_terminal: 51", invoice.getLastError());
    }

    @Test
    void webhookUsesArchivedRefWhenLockedInvoiceNoLongerHasMatchedPayment() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice lockedInvoice = invoice(account);
        lockedInvoice.setStatus(CommonInvoiceStatus.BAN);
        CommonInvoicePaymentRef archivedRef = new CommonInvoicePaymentRef();
        archivedRef.setInvoice(lockedInvoice);
        archivedRef.setTbankOrderId("old-order");
        archivedRef.setTbankPaymentId("old-payment");
        archivedRef.setTbankTerminalKey("terminal");
        archivedRef.setAmountKopecks(100_000L);
        archivedRef.setStatus("ARCHIVED");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(lockedInvoice));
        when(paymentRefRepository.findByTbankOrderId("old-order")).thenReturn(Optional.of(archivedRef));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, lockedInvoice.getStatus());
        assertTrue(lockedInvoice.getLastError().startsWith("late_tbank_payment"));
        assertEquals("CONFIRMED", archivedRef.getStatus());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
        verify(invoiceOrderRepository, never()).findByInvoiceIdWithOrders(10L);
    }

    @Test
    void archivedWebhookDoesNotOverwriteAlreadyAppliedPaymentRef() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        CommonInvoicePaymentRef candidateRef = new CommonInvoicePaymentRef();
        candidateRef.setId(44L);
        candidateRef.setInvoice(invoice);
        candidateRef.setTbankOrderId("old-order");
        candidateRef.setTbankPaymentId("old-payment");
        candidateRef.setTbankTerminalKey("terminal");
        candidateRef.setAmountKopecks(100_000L);
        candidateRef.setStatus("CONFIRMED");
        CommonInvoicePaymentRef lockedRef = new CommonInvoicePaymentRef();
        lockedRef.setId(44L);
        lockedRef.setInvoice(invoice);
        lockedRef.setTbankOrderId("old-order");
        lockedRef.setTbankPaymentId("old-payment");
        lockedRef.setTbankTerminalKey("terminal");
        lockedRef.setAmountKopecks(100_000L);
        lockedRef.setStatus("APPLIED");
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();

        when(paymentRefRepository.findByTbankOrderId("old-order")).thenReturn(Optional.of(candidateRef));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(lockedRef));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        assertEquals("APPLIED", lockedRef.getStatus());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        verify(paymentRefRepository, never()).save(lockedRef);
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void applyLatePaymentFlagsOverpaymentEvenWhenAllOrdersAreClosed() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("late_tbank_payment: оплачена старая ссылка");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setStatus("CONFIRMED");
        ref.setAmountKopecks(150_000L);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(10L, "CONFIRMED")).thenReturn(List.of(ref));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.applyLatePayment(10L);

        assertTrue(item.isPaid());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("late_overpayment"));
        assertEquals("CONFIRMED", ref.getStatus());
        verify(orderTransactionService).handlePaymentStatus(order, false);
    }

    @Test
    void managerCannotOpenMixedInvoiceJustBecauseOneOrderIsVisible() {
        Manager visibleManager = manager(11L);
        Manager hiddenManager = manager(12L);
        authenticateManager(visibleManager);
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order visibleOrder = order(101L);
        visibleOrder.setManager(visibleManager);
        Order hiddenOrder = order(102L);
        hiddenOrder.setManager(hiddenManager);
        CommonInvoiceOrder visibleItem = item(invoice, visibleOrder);
        CommonInvoiceOrder hiddenItem = item(invoice, hiddenOrder);

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(visibleItem, hiddenItem));

        assertThrows(ResponseStatusException.class, () -> service.invoice(10L));
    }

    @Test
    void updateAccountRejectsCompanyRemovalThroughBulkSave() {
        CommonBillingAccount account = account();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(1L)).thenReturn(List.of(link));

        CommonBillingAccountRequest request = new CommonBillingAccountRequest(
                "Общий плательщик",
                true,
                true,
                null,
                null,
                List.of()
        );

        assertThrows(ResponseStatusException.class, () -> service.updateAccount(1L, request));
    }

    @Test
    void updateAccountDisablesCompanyLinksWhenAccountIsDisabled() {
        CommonBillingAccount account = account();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);

        when(accountRepository.findByIdWithRelations(1L))
                .thenReturn(Optional.of(account))
                .thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(1L)).thenReturn(List.of(link));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class))).thenReturn(List.of());

        CommonBillingAccountRequest request = new CommonBillingAccountRequest(
                "Общий плательщик",
                false,
                true,
                null,
                null,
                null
        );

        service.updateAccount(1L, request);

        assertFalse(account.isEnabled());
        assertFalse(link.isEnabled());
        verify(accountCompanyRepository).saveAndFlush(link);
    }

    @Test
    void updateAccountDetachesOpenOrdersAndArchivesCurrentInvoiceWhenAccountIsDisabled() {
        CommonBillingAccount account = account();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Ожидает общего счета"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setOriginalOrderStatusTitle("Публикация");

        when(accountRepository.findByIdWithRelations(1L))
                .thenReturn(Optional.of(account))
                .thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(1L)).thenReturn(List.of(link));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(invoice))
                .thenReturn(List.of());
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(orderStatusService.getOrderStatusByTitle("Публикация")).thenReturn(status("Публикация"));

        CommonBillingAccountRequest request = new CommonBillingAccountRequest(
                "Общий плательщик",
                false,
                true,
                null,
                null,
                null
        );

        service.updateAccount(1L, request);

        assertFalse(account.isEnabled());
        assertFalse(link.isEnabled());
        assertEquals("Публикация", order.getStatus().getTitle());
        assertEquals(CommonInvoiceStatus.DISABLED, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("общий счет выключен"));
        verify(invoiceOrderRepository).deleteAll(List.of(item));
        verify(orderRepository).save(order);
        verify(accountCompanyRepository).saveAndFlush(link);
        var locksAndWrites = inOrder(
                orderAggregateMutationLockService,
                accountRepository,
                invoiceRepository,
                accountCompanyRepository
        );
        locksAndWrites.verify(orderAggregateMutationLockService).lock(101L);
        locksAndWrites.verify(accountRepository).findByIdWithRelationsForUpdate(1L);
        locksAndWrites.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        locksAndWrites.verify(accountRepository).save(account);
        locksAndWrites.verify(accountCompanyRepository).saveAndFlush(link);
    }

    @Test
    void removeCompanyDetachesUnderCanonicalLocksBeforeDisablingLink() {
        CommonBillingAccount account = account();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setId(55L);
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setOriginalOrderStatusTitle("Публикация");

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 20L)).thenReturn(Optional.of(link));
        when(accountCompanyRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(link));
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(1L)).thenReturn(List.of(link));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(orderStatusService.getOrderStatusByTitle("Публикация")).thenReturn(status("Публикация"));

        service.removeCompany(1L, 20L, true);

        assertFalse(link.isEnabled());
        assertEquals("Публикация", order.getStatus().getTitle());
        assertEquals(CommonInvoiceStatus.DISABLED, invoice.getStatus());
        var locksAndWrites = inOrder(
                orderAggregateMutationLockService,
                accountRepository,
                invoiceRepository,
                accountCompanyRepository
        );
        locksAndWrites.verify(orderAggregateMutationLockService).lock(101L);
        locksAndWrites.verify(accountRepository).findByIdWithRelationsForUpdate(1L);
        locksAndWrites.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        locksAndWrites.verify(accountCompanyRepository).findByIdForUpdate(55L);
        locksAndWrites.verify(accountCompanyRepository).saveAndFlush(link);
    }

    @Test
    void removeCompanyCannotDetachOrdersAfterPaymentRouteWasFrozen() {
        CommonBillingAccount account = account();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setId(56L);
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now().minusMinutes(1));
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setContractorAllocationId(7_100L);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(invoice));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.removeCompany(1L, 20L, true)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(link.isEnabled());
        verify(invoiceOrderRepository, never()).deleteAll(any());
        verify(accountCompanyRepository, never()).saveAndFlush(link);
    }

    @Test
    void updateAccountRejectsConcurrentEnabledStateChangeInsteadOfOverwritingFreshAccount() {
        CommonBillingAccount snapshot = account();
        snapshot.setEnabled(false);
        CommonBillingAccount lockedCurrent = account();
        lockedCurrent.setEnabled(true);
        lockedCurrent.setName("Параллельно изменено");

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(snapshot));
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class))).thenReturn(List.of());
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(lockedCurrent));

        CommonBillingAccountRequest request = new CommonBillingAccountRequest(
                "Сохранение старой формы",
                null,
                true,
                null,
                null,
                null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateAccount(1L, request)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("Параллельно изменено", lockedCurrent.getName());
        verify(entityManager).refresh(lockedCurrent);
        verify(accountRepository, never()).save(any());
        verify(accountCompanyRepository, never()).saveAndFlush(any());
    }

    @Test
    void addCompanyConvertsActiveCompanyRaceIntoConflict() {
        CommonBillingAccount account = account();
        Company company = company();
        company.setId(22L);

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(companyRepository.findById(22L)).thenReturn(Optional.of(company));
        when(accountCompanyRepository.findConfiguredEnabledLinksForCompany(22L)).thenReturn(List.of());
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(1L, 22L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.addCompany(1L, 22L));

        assertEquals(409, error.getStatusCode().value());
    }

    @Test
    void initPublicPaymentMovesStaleInitMarkerToManualAttention() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setLastError("payment_init_in_progress");
        invoice.setTbankOrderId("stale-order");
        invoice.setTbankPaymentId("stale-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now().minusMinutes(31));
        invoice.setPaymentUrl("https://pay/stale");
        CommonInvoicePaymentRef currentRef = paymentRef(
                70L,
                invoice,
                "CURRENT",
                "stale-order",
                "stale-payment",
                "terminal",
                100_000L
        );
        paymentRefStore.put(currentRef.getId(), currentRef);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        assertThrows(ResponseStatusException.class, () -> service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        ));

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_stale"));
        assertEquals("CANCEL_PENDING", currentRef.getStatus());
        assertEquals("payment_init_stale_timeout", currentRef.getReason());
        assertNull(invoice.getTbankOrderId());
        assertNull(invoice.getTbankPaymentId());
        assertNull(invoice.getPaymentUrl());
        verify(tbankClient, never()).init(any(), any());
    }

    @Test
    void authorizedWebhookClaimsPreparedAnchorAndStaleInitQueuesCancellation() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setLastError("payment_init_in_progress");
        invoice.setTbankOrderId("crash-order");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        CommonInvoicePaymentRef preparedRef = paymentRef(
                71L,
                invoice,
                "INIT_PREPARED",
                "crash-order",
                null,
                "terminal",
                100_000L
        );
        paymentRefStore.put(preparedRef.getId(), preparedRef);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();
        Map<String, String> payload = confirmedWebhookPayload();
        payload.put("OrderId", "crash-order");
        payload.put("PaymentId", "crash-payment");
        payload.put("Status", "AUTHORIZED");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));
        assertEquals("CURRENT", preparedRef.getStatus());
        assertEquals("crash-payment", preparedRef.getTbankPaymentId());
        assertEquals("crash-payment", invoice.getTbankPaymentId());
        assertEquals("payment_init_in_progress", invoice.getLastError());

        invoice.setTbankPaymentCreatedAt(LocalDateTime.now().minusMinutes(31));
        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        assertThrows(
                ResponseStatusException.class,
                () -> service.initPublicPayment("token", "client@example.com", true, true, true)
        );

        assertEquals("CANCEL_PENDING", preparedRef.getStatus());
        assertEquals("payment_init_stale_timeout", preparedRef.getReason());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_stale"));
        assertNull(invoice.getTbankOrderId());
        assertNull(invoice.getTbankPaymentId());
        verify(tbankClient, never()).init(any(), any());
    }

    @Test
    void retryAndResolveAttentionRejectPaymentInitConflict() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_conflict: нужна ручная сверка");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> service.retryAttention(10L));
        assertThrows(ResponseStatusException.class, () -> service.resolveAttention(10L));
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void retryAndResolveAttentionRejectMigrationPaymentRegistryQuarantine() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("migration_common_payment_registry: provider evidence preserved; manual reconciliation required");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());

        ResponseStatusException retryError = assertThrows(
                ResponseStatusException.class,
                () -> service.retryAttention(10L)
        );
        ResponseStatusException resolveError = assertThrows(
                ResponseStatusException.class,
                () -> service.resolveAttention(10L)
        );

        assertEquals(409, retryError.getStatusCode().value());
        assertEquals(409, resolveError.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("migration_common_payment_registry:"));
        verify(paymentRefRepository, never()).findByInvoiceIdForUpdate(anyLong());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void readingMigrationPaymentRegistryQuarantinePreservesProviderBindingAndRefsWhenAmountChanged() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("migration_common_payment_registry:nonterminal_or_unknown_payment_ref_on_invoice; "
                + "provider evidence preserved; manual reconciliation required");
        invoice.setAmountKopecks(475_000L);
        invoice.setTbankOrderId("g97-78a1733109c8");
        invoice.setTbankPaymentId("8927282485");
        invoice.setTbankTerminalKey("provider-terminal");
        invoice.setTbankPaymentAmountKopecks(475_000L);
        invoice.setPaymentUrl("https://securepay.tinkoff.ru/8927282485");
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now().minusDays(1));
        Order order = order(101L);
        order.setSum(BigDecimal.valueOf(4000));
        CommonInvoiceOrder item = item(invoice, order);
        item.setAmountKopecks(475_000L);
        CommonInvoicePaymentRef preparedRef = paymentRef(
                97L,
                invoice,
                "INIT_CONFLICT",
                "g97-7bf9c45ca7bc",
                null,
                "provider-terminal",
                475_000L
        );
        preparedRef.setReason("migration_invoice_provider_evidence_preserved");

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(paymentRefRepository.findByInvoiceIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(preparedRef));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse details = service.invoice(10L);

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals("g97-78a1733109c8", invoice.getTbankOrderId());
        assertEquals("8927282485", invoice.getTbankPaymentId());
        assertEquals("provider-terminal", invoice.getTbankTerminalKey());
        assertEquals(475_000L, invoice.getTbankPaymentAmountKopecks());
        assertEquals("https://securepay.tinkoff.ru/8927282485", invoice.getPaymentUrl());
        assertNotNull(invoice.getTbankPaymentCreatedAt());
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        assertEquals("migration_invoice_provider_evidence_preserved", preparedRef.getReason());
        assertEquals(475_000L, invoice.getAmountKopecks());
        assertEquals(475_000L, item.getAmountKopecks());
        assertEquals(475_000L, details.summary().remainingKopecks());
        assertEquals(475_000L, details.summary().tbankPaymentAmountKopecks());
        assertTrue(paymentRefStore.values().stream().noneMatch(ref -> "CANCEL_PENDING".equals(ref.getStatus())));
        verify(invoiceRepository, never()).save(any(CommonInvoice.class));
        verify(invoiceOrderRepository, never()).saveAll(any());
        verify(paymentRefRepository, never()).save(any(CommonInvoicePaymentRef.class));
        verify(paymentRefRepository, never()).findByTbankOrderId(anyString());
        verify(paymentRefRepository, never()).findByTbankPaymentId(anyString());
        verify(badReviewTaskService, never()).getPayableSum(order);
        verify(paymentLinkRepository, never())
                .findFirstByOrder_IdAndStatusAndLastErrorIsNullOrderByPaidAtDesc(anyLong(), any());
    }

    @Test
    void confirmMigrationPaymentRegistryCheckPreservesDistinctProviderAttempts() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("migration_common_payment_registry: nonterminal_or_unknown_payment_ref_on_invoice");
        invoice.setTbankOrderId("g97-78a1733109c8");
        invoice.setTbankPaymentId("8927282485");
        invoice.setTbankTerminalKey("provider-terminal");
        invoice.setAmountKopecks(475_000L);
        invoice.setTbankPaymentAmountKopecks(475_000L);
        invoice.setPaymentUrl("https://securepay.tinkoff.ru/8927282485");
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now().minusDays(1));
        Order order = order(101L);
        order.setSum(BigDecimal.valueOf(4750));
        CommonInvoiceOrder item = item(invoice, order);
        item.setAmountKopecks(475_000L);
        CommonInvoicePaymentRef preparedRef = paymentRef(
                97L,
                invoice,
                "INIT_CONFLICT",
                "g97-7bf9c45ca7bc",
                null,
                "provider-terminal",
                475_000L
        );
        preparedRef.setReason("migration_invoice_provider_evidence_preserved");

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of(preparedRef));
        when(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(10L, "INIT_PREPARED"))
                .thenReturn(List.of());
        when(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(10L, "INIT_CONFLICT"))
                .thenReturn(List.of(preparedRef));
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of());
        when(paymentRefRepository.findByInvoiceIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(preparedRef));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(4750));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse freshDetails = service.invoice(10L);
        assertNotNull(freshDetails.paymentEvidenceToken());
        assertEquals(475_000L, freshDetails.summary().tbankPaymentAmountKopecks());
        assertEquals("provider-terminal", freshDetails.summary().tbankTerminalKey());
        assertEquals("provider-terminal", freshDetails.summary().tbankTerminalLabel());
        assertEquals("provider-terminal", freshDetails.paymentRefs().getFirst().terminalKey());
        assertEquals("provider-terminal", freshDetails.paymentRefs().getFirst().terminalLabel());

        service.confirmPaymentInitCheck(
                10L,
                new CommonInvoicePaymentInitCheckRequest(freshDetails.paymentEvidenceToken())
        );

        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertNull(invoice.getLastError());
        assertNull(invoice.getTbankOrderId());
        assertNull(invoice.getTbankPaymentId());
        assertNull(invoice.getTbankTerminalKey());
        assertNull(invoice.getTbankPaymentAmountKopecks());
        assertNull(invoice.getPaymentUrl());
        assertNull(invoice.getTbankPaymentCreatedAt());
        assertEquals("g97-7bf9c45ca7bc", preparedRef.getTbankOrderId());
        assertNull(preparedRef.getTbankPaymentId());
        assertEquals("provider-terminal", preparedRef.getTbankTerminalKey());
        assertEquals(475_000L, preparedRef.getAmountKopecks());
        assertEquals("ARCHIVED", preparedRef.getStatus());
        assertTrue(preparedRef.getReason().startsWith("payment_init_manually_checked_by=unknown"));
        assertTrue(preparedRef.getReason().contains("previous=migration_invoice_provider_evidence_preserved"));

        ArgumentCaptor<CommonInvoicePaymentRef> archivedInvoiceBinding =
                ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository).saveAll(List.of(preparedRef));
        verify(paymentRefRepository).save(archivedInvoiceBinding.capture());
        CommonInvoicePaymentRef invoiceBindingRef = archivedInvoiceBinding.getValue();
        assertFalse(invoiceBindingRef == preparedRef);
        assertEquals(invoice, invoiceBindingRef.getInvoice());
        assertEquals("g97-78a1733109c8", invoiceBindingRef.getTbankOrderId());
        assertEquals("8927282485", invoiceBindingRef.getTbankPaymentId());
        assertEquals("provider-terminal", invoiceBindingRef.getTbankTerminalKey());
        assertEquals(475_000L, invoiceBindingRef.getAmountKopecks());
        assertEquals("ARCHIVED", invoiceBindingRef.getStatus());
        assertTrue(invoiceBindingRef.getReason().startsWith("payment_init_manually_checked_by=unknown"));
        assertTrue(invoiceBindingRef.getReason().contains("previous=migration_common_payment_registry:"));
        verify(entityManager).flush();
    }

    @Test
    void recoverUnsentPaymentInitTlsFailureArchivesRefWithoutProviderEvidenceOrActiveLinks() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(unsentPaymentInitTlsError());
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        CommonInvoicePaymentRef preparedRef = paymentRef(
                51L,
                invoice,
                "INIT_CONFLICT",
                "g51-unsent-request",
                null,
                "provider-terminal",
                200_000L
        );
        preparedRef.setReason("init_exception_before_response");
        CommonInvoicePaymentRef previouslyVerifiedTlsRef = paymentRef(
                50L,
                invoice,
                "ARCHIVED",
                "g50-verified-unsent-request",
                null,
                "provider-terminal",
                200_000L
        );
        previouslyVerifiedTlsRef.setReason(
                "payment_init_tls_failed_before_http_request; previous=init_exception_before_response"
        );

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L))
                .thenReturn(List.of(previouslyVerifiedTlsRef, preparedRef));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of());
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.recoverUnsentPaymentInitTlsFailure(10L);

        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertNull(invoice.getLastError());
        assertEquals("ARCHIVED", preparedRef.getStatus());
        assertTrue(preparedRef.getReason().startsWith("payment_init_tls_failed_before_http_request"));
        assertTrue(preparedRef.getReason().contains("previous=init_exception_before_response"));
        assertEquals("ARCHIVED", previouslyVerifiedTlsRef.getStatus());
        assertTrue(previouslyVerifiedTlsRef.getReason().startsWith("payment_init_tls_failed_before_http_request"));
        verify(paymentRefRepository).saveAll(List.of(preparedRef));
        verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
        org.mockito.InOrder locks = inOrder(
                orderAggregateMutationLockService,
                paymentLinkRepository,
                invoiceRepository,
                paymentRefRepository
        );
        locks.verify(orderAggregateMutationLockService).lock(101L);
        locks.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        locks.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        locks.verify(paymentRefRepository).findByInvoiceIdForUpdate(10L);
    }

    @Test
    void recoverUnsentPaymentInitTlsFailureRejectsWaitingManualPaymentLink() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(unsentPaymentInitTlsError());
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        CommonInvoicePaymentRef preparedRef = paymentRef(
                131L,
                invoice,
                "INIT_CONFLICT",
                "g131-unsent-request",
                null,
                "provider-terminal",
                300_000L
        );
        preparedRef.setReason("init_exception_before_response");
        PaymentLink manualLink = new PaymentLink();
        manualLink.setOrder(order);
        manualLink.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of(preparedRef));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of(manualLink));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recoverUnsentPaymentInitTlsFailure(10L)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals(unsentPaymentInitTlsError(), invoice.getLastError());
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        assertEquals("init_exception_before_response", preparedRef.getReason());
        verify(paymentRefRepository, never()).saveAll(any());
    }

    @Test
    void recoverUnsentPaymentInitTlsFailureRejectsCurrentInvoiceProviderEvidence() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(unsentPaymentInitTlsError());
        invoice.setTbankOrderId("provider-order-already-recorded");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recoverUnsentPaymentInitTlsFailure(10L)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals("provider-order-already-recorded", invoice.getTbankOrderId());
        verify(paymentRefRepository, never()).findByInvoiceIdForUpdate(anyLong());
        verify(paymentRefRepository, never()).saveAll(any());
    }

    @Test
    void recoverUnsentPaymentInitTlsFailureRejectsAmbiguousArchivedProviderRef() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(unsentPaymentInitTlsError());
        CommonInvoicePaymentRef preparedRef = paymentRef(
                151L,
                invoice,
                "INIT_CONFLICT",
                "g151-unsent-request",
                null,
                "provider-terminal",
                300_000L
        );
        preparedRef.setReason("init_exception_before_response");
        CommonInvoicePaymentRef ambiguousArchivedRef = paymentRef(
                150L,
                invoice,
                "ARCHIVED",
                "g150-previous-request",
                "unreconciled-payment-id",
                "provider-terminal",
                300_000L
        );
        ambiguousArchivedRef.setReason("remaining_changed");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L))
                .thenReturn(List.of(ambiguousArchivedRef, preparedRef));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recoverUnsentPaymentInitTlsFailure(10L)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals(unsentPaymentInitTlsError(), invoice.getLastError());
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        assertEquals("init_exception_before_response", preparedRef.getReason());
        assertEquals("ARCHIVED", ambiguousArchivedRef.getStatus());
        assertEquals("remaining_changed", ambiguousArchivedRef.getReason());
        verify(paymentRefRepository, never()).saveAll(any());
        verify(paymentLinkRepository, never()).findByOrderIdForUpdate(anyLong());
    }

    @Test
    void resolveWhatsappGroupTailRereadsLockedInvoiceAndPreservesConcurrentPaymentState() {
        CommonBillingAccount account = account();
        CommonInvoice staleSnapshot = invoice(account);
        staleSnapshot.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        staleSnapshot.setLastError("whatsapp_group_missing: старая ошибка");

        CommonInvoice lockedCurrent = invoice(account);
        lockedCurrent.setStatus(CommonInvoiceStatus.PAID);
        lockedCurrent.setPaidKopecks(100_000L);
        lockedCurrent.setTbankOrderId("provider-order");
        lockedCurrent.setTbankPaymentId("provider-payment");
        lockedCurrent.setTbankTerminalKey("provider-terminal");
        lockedCurrent.setTbankPaymentAmountKopecks(100_000L);
        lockedCurrent.setLastError("whatsapp_group_missing: старая ошибка");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(staleSnapshot));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(lockedCurrent));

        service.resolveWhatsappGroupTail(10L);

        assertNull(lockedCurrent.getLastError());
        assertEquals(CommonInvoiceStatus.PAID, lockedCurrent.getStatus());
        assertEquals(100_000L, lockedCurrent.getPaidKopecks());
        assertEquals("provider-order", lockedCurrent.getTbankOrderId());
        assertEquals("provider-payment", lockedCurrent.getTbankPaymentId());
        assertEquals("provider-terminal", lockedCurrent.getTbankTerminalKey());
        assertEquals(100_000L, lockedCurrent.getTbankPaymentAmountKopecks());
        verify(invoiceRepository).save(lockedCurrent);
        verify(invoiceRepository, never()).save(staleSnapshot);
    }

    @Test
    void confirmPaymentInitCheckReturnsInvoiceToCurrentStateAfterManualBankCheck() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_conflict: нужна ручная сверка");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceOrderRepository.findOrderIdsByInvoiceId(10L)).thenReturn(List.of(101L));
        when(orderAggregateMutationLockService.lock(101L)).thenReturn(order);
        when(paymentLinkRepository.findByOrderIdForUpdate(101L)).thenReturn(List.of());
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(10L)).thenReturn(List.of(item));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of());
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        String evidenceToken = paymentEvidenceToken(invoice, List.of());
        service.confirmPaymentInitCheck(10L, new CommonInvoicePaymentInitCheckRequest(evidenceToken));

        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
        org.mockito.InOrder locks = inOrder(
                orderAggregateMutationLockService,
                paymentLinkRepository,
                invoiceRepository,
                paymentRefRepository
        );
        locks.verify(orderAggregateMutationLockService).lock(101L);
        locks.verify(paymentLinkRepository).findByOrderIdForUpdate(101L);
        locks.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        locks.verify(paymentRefRepository).findByInvoiceIdForUpdate(10L);
    }

    @Test
    void confirmPaymentInitCheckRejectsMissingEvidenceTokenForOrdinaryConflict() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_conflict: нужна ручная сверка");
        CommonInvoicePaymentRef preparedRef = paymentRef(
                151L,
                invoice,
                "INIT_CONFLICT",
                "provider-order",
                null,
                "provider-terminal",
                100_000L
        );

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of(preparedRef));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaymentInitCheck(10L, null)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        verify(paymentRefRepository, never()).saveAll(any());
    }

    @Test
    void confirmPaymentInitCheckRejectsStaleEvidenceTokenForOrdinaryConflict() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_exception: нужна ручная сверка");
        CommonInvoicePaymentRef preparedRef = paymentRef(
                151L,
                invoice,
                "INIT_CONFLICT",
                "provider-order-new",
                null,
                "provider-terminal",
                100_000L
        );

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of(preparedRef));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaymentInitCheck(
                        10L,
                        new CommonInvoicePaymentInitCheckRequest("stale-evidence-token")
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals("INIT_CONFLICT", preparedRef.getStatus());
        verify(paymentRefRepository, never()).saveAll(any());
    }

    @Test
    void confirmPaymentInitCheckBlocksEveryLiveCancelOrAppliedPaymentLifecycle() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_conflict: нужна ручная сверка");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());
        when(paymentRefRepository.existsByInvoice_IdAndStatusIn(eq(10L), any())).thenAnswer(invocation -> {
            Collection<String> statuses = invocation.getArgument(1);
            assertTrue(statuses.containsAll(Set.of(
                    "CURRENT",
                    "CANCEL_PENDING",
                    "CANCELING",
                    "CANCEL_FAILED",
                    "CANCEL_FAILED_FINAL",
                    "CONFIRMED",
                    "PREPAID",
                    "APPLYING",
                    "APPLIED"
            )));
            return true;
        });

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPaymentInitCheck(
                        10L,
                        new CommonInvoicePaymentInitCheckRequest(paymentEvidenceToken(invoice, List.of()))
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_conflict"));
        verify(paymentRefRepository, never()).saveAll(any());
    }

    @Test
    void resolveAttentionDisablesEmptyInvoiceInsteadOfMarkingPaid() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("manual_check: позиции уже убраны");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());

        service.resolveAttention(10L);

        assertEquals(CommonInvoiceStatus.DISABLED, invoice.getStatus());
        assertEquals("empty: в общем счете нет заказов", invoice.getLastError());
        assertEquals(0, invoice.getPaidKopecks());
        assertEquals(null, invoice.getPaidAt());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void disableEmptyInvoiceRetiresPaymentFreeCollectingShell() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of());

        CommonInvoiceDetailsResponse response = service.disableEmptyInvoice(10L);

        assertEquals(CommonInvoiceStatus.DISABLED, invoice.getStatus());
        assertEquals("empty: в общем счете нет заказов", invoice.getLastError());
        assertEquals(CommonInvoiceStatus.DISABLED.name(), response.summary().status());
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void disableEmptyInvoiceRejectsAnyPaymentRegistryEvidence() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoicePaymentRef paymentRef = new CommonInvoicePaymentRef();
        paymentRef.setId(91L);
        paymentRef.setInvoice(invoice);
        paymentRef.setStatus("ARCHIVED");

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());
        when(paymentRefRepository.findByInvoiceIdForUpdate(10L)).thenReturn(List.of(paymentRef));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.disableEmptyInvoice(10L)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(CommonInvoiceStatus.COLLECTING, invoice.getStatus());
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void retryAndResolveAttentionRejectFinalPaymentCancelFailure() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_cancel_failed_final: нужна ручная проверка банка");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> service.retryAttention(10L));
        assertThrows(ResponseStatusException.class, () -> service.resolveAttention(10L));
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void confirmFinalPaymentCancelCheckReturnsInvoiceToCurrentStateAfterManualBankCheck() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_cancel_failed_final: старая T-Bank ссылка не отменена");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.confirmFinalPaymentCancelCheck(10L);

        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void confirmFinalPaymentCancelCheckRejectsRecordedFullPaymentWithOpenItems() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_cancel_failed_final: старая T-Bank ссылка не отменена");
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(100_000L);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        assertThrows(ResponseStatusException.class, () -> service.confirmFinalPaymentCancelCheck(10L));
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_cancel_failed_final"));
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
    }

    @Test
    void invoiceRefreshArchivesStaleTbankPaymentReferenceBeforeClearingIt() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        invoice.setTbankOrderId("old-order");
        invoice.setTbankPaymentId("old-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(200_000L);
        invoice.setPaymentUrl("https://pay/old");

        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(paymentRefRepository.findByTbankOrderId("old-order")).thenReturn(Optional.empty());
        when(paymentRefRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.invoice(10L);

        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getValue();
        assertEquals("old-order", ref.getTbankOrderId());
        assertEquals("old-payment", ref.getTbankPaymentId());
        assertEquals(200_000L, ref.getAmountKopecks());
        assertEquals("CANCEL_PENDING", ref.getStatus());
        assertEquals("remaining_changed", ref.getReason());
        assertEquals(null, invoice.getTbankOrderId());
        assertEquals(null, invoice.getTbankPaymentId());
        verify(tbankClient, never()).cancel(any(), any());
    }

    @Test
    void paidInvoiceRefreshDoesNotCancelConfirmedTbankPaymentReference() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(100_000L);
        invoice.setPaidAt(LocalDateTime.now());
        invoice.setTbankOrderId("paid-order");
        invoice.setTbankPaymentId("paid-payment");
        invoice.setTbankTerminalKey("terminal");
        invoice.setTbankPaymentAmountKopecks(100_000L);
        invoice.setPaymentUrl("https://pay/paid");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);
        item.setPaid(true);
        item.setPaidAt(LocalDateTime.now());

        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));

        var response = service.publicInvoice("token");

        assertEquals(CommonInvoiceStatus.PAID.name(), response.status());
        assertFalse(response.payable());
        assertEquals("paid-order", invoice.getTbankOrderId());
        assertEquals("paid-payment", invoice.getTbankPaymentId());
        assertEquals("https://pay/paid", invoice.getPaymentUrl());
        verify(paymentRefRepository, never()).save(any(CommonInvoicePaymentRef.class));
        verify(tbankClient, never()).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsCancelsBankLinkOutsideArchiveTransaction() {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(77L);
        ref.setStatus("CANCEL_PENDING");
        ref.setTbankPaymentId("payment-1");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(77L))
                .thenReturn(Optional.of(ref))
                .thenReturn(Optional.of(ref));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tbankClient.cancel(any(), any())).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "CANCELED",
                "payment-1",
                "old-order",
                100_000L,
                100_000L,
                0L
        ));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(1, processed);
        assertEquals("CANCELED", ref.getStatus());
        verify(tbankClient).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsSkipsPaidInvoiceRefs() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(84L);
        ref.setInvoice(invoice);
        ref.setStatus("CANCEL_PENDING");
        ref.setTbankPaymentId("payment-1");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(invoiceRepository.findByIdWithAccount(10L)).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByIdForUpdate(84L)).thenReturn(Optional.of(ref));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(0, processed);
        assertEquals("ARCHIVED", ref.getStatus());
        assertEquals("paid_invoice_cancel_skipped", ref.getReason());
        verify(paymentRefRepository).save(ref);
        verify(tbankClient, never()).cancel(any(), any());
        var lockOrder = inOrder(
                invoiceOrderRepository,
                accountRepository,
                invoiceRepository,
                paymentRefRepository
        );
        lockOrder.verify(invoiceOrderRepository).findOrderIdsByInvoiceId(10L);
        lockOrder.verify(accountRepository).findByIdWithRelationsForUpdate(1L);
        lockOrder.verify(invoiceRepository).findByIdWithAccountForUpdate(10L);
        lockOrder.verify(invoiceOrderRepository).findMembershipByInvoiceIdForRead(10L);
        lockOrder.verify(paymentRefRepository).findByIdForUpdate(84L);
    }

    @Test
    void cancelPendingArchivedPaymentsRetriesFailedBankLinkAfterDelay() {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(78L);
        ref.setStatus("CANCEL_FAILED");
        ref.setTbankPaymentId("payment-2");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(78L))
                .thenReturn(Optional.of(ref))
                .thenReturn(Optional.of(ref));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tbankClient.cancel(any(), any())).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "CANCELED",
                "payment-2",
                "old-order",
                100_000L,
                100_000L,
                0L
        ));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(1, processed);
        assertEquals("CANCELED", ref.getStatus());
        assertEquals(1, ref.getCancelAttempts());
        verify(tbankClient).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsCancelsLegacyInitConflictWithBankLink() {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(79L);
        ref.setStatus("INIT_CONFLICT");
        ref.setTbankPaymentId("payment-3");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(79L))
                .thenReturn(Optional.of(ref))
                .thenReturn(Optional.of(ref));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tbankClient.cancel(any(), any())).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "CANCELED",
                "payment-3",
                "old-order",
                100_000L,
                100_000L,
                0L
        ));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(1, processed);
        assertEquals("CANCELED", ref.getStatus());
        assertEquals(1, ref.getCancelAttempts());
        verify(tbankClient).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsFinalizesFailedBankLinkAfterLastAttempt() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(80L);
        ref.setInvoice(invoice);
        ref.setStatus("CANCEL_FAILED");
        ref.setCancelAttempts(143);
        ref.setTbankPaymentId("payment-4");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(80L))
                .thenReturn(Optional.of(ref))
                .thenReturn(Optional.of(ref));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tbankClient.cancel(any(), any())).thenReturn(new TbankCancelResponse(
                false,
                "1",
                "declined",
                null,
                "terminal",
                "REJECTED",
                "payment-4",
                "old-order",
                100_000L,
                100_000L,
                0L
        ));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(1, processed);
        assertEquals("CANCEL_FAILED_FINAL", ref.getStatus());
        assertEquals(144, ref.getCancelAttempts());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_cancel_failed_final"));
        verify(tbankClient).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsMarksInvoiceAttentionWhenStaleCancelingReachedMaxAttempts() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(83L);
        ref.setInvoice(invoice);
        ref.setStatus("CANCELING");
        ref.setCancelAttempts(144);
        ref.setTbankPaymentId("payment-7");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        ref.setUpdatedAt(LocalDateTime.now().minusMinutes(31));

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(83L)).thenReturn(Optional.of(ref));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(0, processed);
        assertEquals("CANCEL_FAILED_FINAL", ref.getStatus());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_cancel_failed_final"));
        verify(tbankClient, never()).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsRetriesStaleCancelingBankLink() {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(81L);
        ref.setStatus("CANCELING");
        ref.setCancelAttempts(1);
        ref.setTbankPaymentId("payment-5");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        ref.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        PaymentProfile profile = paymentProfile();
        TbankPaymentProfile runtimeProfile = runtimeProfile();

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(81L))
                .thenReturn(Optional.of(ref))
                .thenReturn(Optional.of(ref));
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tbankClient.cancel(any(), any())).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "CANCELED",
                "payment-5",
                "old-order",
                100_000L,
                100_000L,
                0L
        ));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(1, processed);
        assertEquals("CANCELED", ref.getStatus());
        assertEquals(2, ref.getCancelAttempts());
        verify(tbankClient).cancel(any(), any());
    }

    @Test
    void cancelPendingArchivedPaymentsLeavesFreshCancelingBankLinkAlone() {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(82L);
        ref.setStatus("CANCELING");
        ref.setCancelAttempts(1);
        ref.setTbankPaymentId("payment-6");
        ref.setTbankTerminalKey("terminal");
        ref.setAmountKopecks(100_000L);
        ref.setUpdatedAt(LocalDateTime.now());

        when(paymentRefRepository.findCancelableRefs(
                eq("CANCEL_PENDING"),
                eq("CANCEL_FAILED"),
                eq("INIT_CONFLICT"),
                eq("CANCELING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                any(Pageable.class)
        ))
                .thenReturn(List.of(ref));
        when(paymentRefRepository.findByIdForUpdate(82L)).thenReturn(Optional.of(ref));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(0, processed);
        assertEquals("CANCELING", ref.getStatus());
        assertEquals(1, ref.getCancelAttempts());
        verify(tbankClient, never()).cancel(any(), any());
    }

    private CommonBillingAccount account() {
        CommonBillingAccount account = new CommonBillingAccount();
        account.setId(1L);
        account.setName("Общий плательщик");
        return account;
    }

    private CommonInvoice invoice(CommonBillingAccount account) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(10L);
        invoice.setAccount(account);
        invoice.setToken("token");
        invoice.setTitle("Общий счет");
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        return invoice;
    }

    private CommonInvoiceOrder item(CommonInvoice invoice, Order order) {
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        item.setOrder(order);
        item.setReady(true);
        item.setAmountKopecks(100_000L);
        return item;
    }

    private void stubLockedInvoice(CommonInvoice invoice, CommonInvoiceOrder item, Order order) {
        lenient().when(invoiceOrderRepository.findByOrderIdWithInvoice(order.getId())).thenReturn(Optional.of(item));
        when(invoiceOrderRepository.findOrderIdsByInvoiceId(invoice.getId())).thenReturn(List.of(order.getId()));
        when(orderAggregateMutationLockService.lock(order.getId())).thenReturn(order);
        when(invoiceRepository.findByIdWithAccount(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdWithRelationsForUpdate(invoice.getAccount().getId()))
                .thenReturn(Optional.of(invoice.getAccount()));
        when(invoiceRepository.findByIdWithAccountForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findMembershipByInvoiceIdForRead(invoice.getId())).thenReturn(List.of(item));
    }

    private PaymentLink confirmedStandaloneBankPayment(Long id, Order order, long amountKopecks) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        link.setAmountKopecks(amountKopecks);
        link.setConfirmedAmountKopecks(amountKopecks);
        link.setPaidAt(LocalDateTime.of(2026, 8, 4, 12, 0).plusSeconds(id == null ? 0 : id));
        link.setTbankPaymentId("payment-" + id);
        link.setTbankOrderId("order-" + id);
        link.setTbankTerminalKey("terminal");
        return link;
    }

    private CommonInvoicePaymentRef paymentRef(
            Long id,
            CommonInvoice invoice,
            String status,
            String orderId,
            String paymentId,
            String terminalKey,
            Long amountKopecks
    ) {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setId(id);
        ref.setInvoice(invoice);
        ref.setStatus(status);
        ref.setTbankOrderId(orderId);
        ref.setTbankPaymentId(paymentId);
        ref.setTbankTerminalKey(terminalKey);
        ref.setAmountKopecks(amountKopecks);
        return ref;
    }

    private String paymentEvidenceToken(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> refs
    ) {
        String token = ReflectionTestUtils.invokeMethod(service, "paymentEvidenceToken", invoice, refs);
        assertNotNull(token);
        return token;
    }

    private String unsentPaymentInitTlsError() {
        return "payment_init_exception: I/O error on POST request for "
                + "\"https://securepay.tinkoff.ru/v2/Init\": (certificate_unknown) "
                + "PKIX path building failed: "
                + "sun.security.provider.certpath.SunCertPathBuilderException: "
                + "unable to find valid certification path to requested target; "
                + "проверьте банк вручную перед повторной оплатой";
    }

    private CommonInvoiceOrderRepository.OrderInvoiceBindingView binding(
            Long orderId,
            Long invoiceId,
            Long accountId
    ) {
        return new CommonInvoiceOrderRepository.OrderInvoiceBindingView() {
            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public Long getInvoiceId() {
                return invoiceId;
            }

            @Override
            public Long getAccountId() {
                return accountId;
            }
        };
    }

    private Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        order.setSum(BigDecimal.valueOf(1000));
        order.setStatus(status("Ожидает общего счета"));
        order.setCompany(company());
        return order;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        return status;
    }

    private Company company() {
        Company company = new Company();
        company.setId(20L);
        company.setTitle("Компания");
        return company;
    }

    private Company company(Long id, Manager manager) {
        Company company = company();
        company.setId(id);
        company.setManager(manager);
        return company;
    }

    private Manager manager(Long id) {
        Manager manager = new Manager();
        manager.setId(id);
        return manager;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private void authenticateManager(Manager visibleManager) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        ));
        User user = user(77L, "manager");
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(false);
        when(managerPermissionService.hasRole(any(), eq("OWNER"))).thenReturn(false);
        when(managerPermissionService.hasRole(any(), eq("MANAGER"))).thenReturn(true);
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(managerRepository.findByUserId(77L)).thenReturn(Optional.of(visibleManager));
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
        when(managerPermissionService.hasRole(any(), eq("ADMIN"))).thenReturn(true);
        when(managerPermissionService.hasAnyRole(any(), eq("ADMIN"), eq("OWNER"))).thenReturn(true);
    }

    private PaymentProfile paymentProfile() {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(1L);
        profile.setCode(TbankPaymentProfile.PRIMARY_CODE);
        profile.setName("Основной магазин");
        profile.setProvider(PaymentProfile.PROVIDER_TBANK);
        profile.setTerminalKey("terminal");
        profile.setPasswordEnvKey("OTZIV_PAYMENTS_TBANK_PASSWORD");
        profile.setEnabled(true);
        profile.setDefaultProfile(true);
        profile.setTestMode(false);
        return profile;
    }

    private void stubSuccessfulTbankInit(String paymentId, String paymentUrl) {
        doAnswer(invocation -> {
            TbankPaymentProfile profile = invocation.getArgument(0);
            TbankInitCommand command = invocation.getArgument(1);
            return new TbankInitResponse(
                    true,
                    "0",
                    null,
                    null,
                    profile.terminalKey(),
                    "NEW",
                    paymentId,
                    command.orderId(),
                    command.amountKopecks(),
                    paymentUrl
            );
        }).when(tbankClient).init(any(), any());
    }

    private TbankPaymentProfile runtimeProfile() {
        return new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                false
        );
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

    private Map<String, String> confirmedWebhookPayload() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "old-order");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "old-payment");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "100000");
        payload.put("Token", "token");
        return payload;
    }
}
