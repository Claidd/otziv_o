package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.NextOrderFailureNotifier;
import com.hunt.otziv.p_products.next_order.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryGateService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonBillingServiceTest {

    @Mock
    private CommonBillingAccountRepository accountRepository;
    @Mock
    private CommonBillingAccountCompanyRepository accountCompanyRepository;
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
    private ManualPaymentAutoConfirmationService manualPaymentAutoConfirmationService;
    @Mock
    private AppSettingService appSettingService;
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
    private ReviewRecoveryGateService recoveryGateService;

    @InjectMocks
    private CommonBillingService service;

    @BeforeEach
    void setUpLazyDependencies() {
        ReflectionTestUtils.setField(service, "orderTransactionService", orderTransactionService);
        ReflectionTestUtils.setField(service, "orderStatusTransitionService", orderStatusTransitionService);
        ReflectionTestUtils.setField(service, "nextOrderRequestService", nextOrderRequestService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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
        when(invoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(firstItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(badReviewTaskService.getPayableSum(secondOrder)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        CommonInvoiceDetailsResponse response = service.markOrderPaid(10L, 101L);

        assertTrue(firstItem.isPaid());
        assertFalse(secondItem.isPaid());
        assertEquals(CommonInvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
        assertEquals(BigDecimal.valueOf(1000).setScale(2), response.summary().paid());
        assertEquals(BigDecimal.valueOf(1000).setScale(2), response.summary().remaining());
        verify(orderTransactionService).handlePaymentStatus(firstOrder, false);
        verify(orderTransactionService, never()).handlePaymentStatus(secondOrder, false);
        verify(manualPaymentAutoConfirmationService).retireOpenLinksForPaidOrder(firstOrder);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(firstOrder, "Оплата общего счета");
        verify(nextOrderRequestService, never()).openForPaidOrder(any());
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

        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(101L, "Публикация");
        verify(orderStatusTransitionService).changeStatusForCommonBillingOrder(102L, "Публикация");
        verify(orderStatusTransitionService, never()).changeStatusForCommonBillingOrder(103L, "Публикация");
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
        assertEquals(BigDecimal.valueOf(2000).setScale(2), response.summary().paid());
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
    void publicInvoiceIsNotPayableWhileInvoiceIsCollecting() {
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

        assertFalse(response.payable());
        assertEquals(CommonInvoiceStatus.COLLECTING.name(), response.status());
    }

    @Test
    void publicInvoiceLocksInvoiceBeforeRefreshingAmounts() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(invoiceRepository.findByTokenWithAccountForUpdate("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        service.publicInvoice("token");

        verify(invoiceRepository).findByTokenWithAccountForUpdate("token");
        verify(invoiceRepository, never()).findByTokenWithAccount("token");
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
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findCurrentForAccountForUpdate(eq(1L), any(), any(Pageable.class)))
                .thenReturn(List.of(duplicateB, duplicateA, target));
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(37L, 36L)))
                .thenReturn(List.of(movedItemB, movedItemA));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(35L)).thenReturn(mergedItems);
        when(invoiceOrderRepository.findByInvoiceIdsWithOrders(List.of(35L))).thenReturn(mergedItems);

        List<OrderDTOList> cards = service.managerBoardCards("Все", "", null, null, "desc");

        assertEquals(1, cards.size());
        assertEquals(3, cards.get(0).getAmount());
        assertEquals(target, movedItemA.getInvoice());
        assertEquals(target, movedItemB.getInvoice());
        assertEquals(CommonInvoiceStatus.DISABLED, duplicateA.getStatus());
        assertEquals(CommonInvoiceStatus.DISABLED, duplicateB.getStatus());
        verify(invoiceOrderRepository).saveAll(List.of(movedItemB, movedItemA));
    }

    @Test
    void initPublicPaymentRejectsCollectingInvoice() {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = order(101L);
        order.setStatus(status("Публикация"));
        CommonInvoiceOrder item = item(invoice, order);
        item.setReady(false);

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(invoiceRepository.findByTokenWithAccount("token")).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));

        assertThrows(ResponseStatusException.class, () -> service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        ));
        verify(tbankClient, never()).init(any(), any());
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

        assertThrows(ResponseStatusException.class, () -> service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        ));

        verify(invoiceRepository).findByTokenWithAccountForUpdate("token");
        verify(invoiceRepository, never()).findByTokenWithAccount("token");
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
            return new TbankInitResponse(
                    true,
                    "0",
                    null,
                    null,
                    "terminal",
                    "NEW",
                    "payment-1",
                    invocation.getArgument(1).toString(),
                    100_000L,
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
    void initPublicPaymentMarksAttentionAndStoresUnknownBankLinkWhenTbankInitFails() {
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
        when(paymentRefRepository.findByTbankOrderId(any())).thenReturn(Optional.empty());
        when(tbankClient.init(any(), any())).thenThrow(new RuntimeException("bank down"));

        assertThrows(RuntimeException.class, () -> service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        ));

        assertEquals("old-order", invoice.getTbankOrderId());
        assertEquals("old-payment", invoice.getTbankPaymentId());
        assertEquals("https://pay/old", invoice.getPaymentUrl());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().contains("payment_init_exception"));
        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getValue();
        assertTrue(ref.getTbankOrderId().startsWith("g10-"));
        assertEquals(null, ref.getTbankPaymentId());
        assertEquals("terminal", ref.getTbankTerminalKey());
        assertEquals(100_000L, ref.getAmountKopecks());
        assertEquals("INIT_CONFLICT", ref.getStatus());
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
        when(tbankClient.init(any(), any())).thenReturn(new TbankInitResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "NEW",
                "payment-1",
                "ignored",
                100_000L,
                "https://pay/new"
        ));

        assertThrows(ResponseStatusException.class, () -> service.initPublicPayment(
                "token",
                "client@example.com",
                true,
                true,
                true
        ));

        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getValue();
        assertTrue(ref.getTbankOrderId().startsWith("g10-"));
        assertEquals("payment-1", ref.getTbankPaymentId());
        assertEquals("CANCEL_PENDING", ref.getStatus());
        assertEquals(100_000L, ref.getAmountKopecks());
        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertTrue(invoice.getLastError().startsWith("payment_init_conflict"));
    }

    @Test
    void attachOrderStartsNewCycleWhenExistingInvoiceWasAlreadySent() {
        CommonBillingAccount account = account();
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        Order order = order(101L);

        when(invoiceOrderRepository.findByOrder_Id(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(invoiceRepository.findCurrentForAccountForUpdate(any(), any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            CommonInvoice created = invocation.getArgument(0);
            created.setId(99L);
            return created;
        }).when(invoiceRepository).save(any(CommonInvoice.class));
        doAnswer(invocation -> invocation.getArgument(0))
                .when(invoiceOrderRepository).save(any(CommonInvoiceOrder.class));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(99L)).thenReturn(List.of());

        assertTrue(service.attachOrderIfNeeded(order));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<CommonInvoiceStatus>> statuses =
                ArgumentCaptor.forClass((Class) Collection.class);
        verify(accountRepository).findByIdWithRelationsForUpdate(1L);
        verify(invoiceRepository).findCurrentForAccountForUpdate(any(), statuses.capture(), any());
        assertFalse(statuses.getValue().contains(CommonInvoiceStatus.INVOICED));
        assertFalse(statuses.getValue().contains(CommonInvoiceStatus.REMINDER));
        assertFalse(statuses.getValue().contains(CommonInvoiceStatus.PARTIALLY_PAID));
    }

    @Test
    void attachOrderMergesDuplicateAttachableInvoicesBeforeAddingNewOrder() {
        CommonBillingAccount account = account();
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

        when(invoiceOrderRepository.findByOrder_Id(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findCurrentForAccountForUpdate(any(), any(), any()))
                .thenReturn(List.of(duplicate, target));
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
        when(companyRepository.findById(3041L)).thenReturn(Optional.of(company));
        when(accountCompanyRepository.findEnabledLinksForCompany(3041L)).thenReturn(List.of());
        when(accountCompanyRepository.findByAccount_IdAndCompany_Id(57L, 3041L)).thenReturn(Optional.empty());
        when(accountRepository.findByIdWithRelationsForUpdate(57L)).thenReturn(Optional.of(newAccount));
        when(invoiceRepository.findCurrentForAccountForUpdate(eq(57L), any(), any(Pageable.class))).thenReturn(List.of(newInvoice));
        when(invoiceOrderRepository.findMovableOpenItemsForCompany(eq(3041L), eq(57L), any()))
                .thenReturn(List.of(movedItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(91L)).thenReturn(List.of(existingItem, movedItem));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(90L)).thenReturn(List.of());
        when(accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(56L)).thenReturn(List.of(oldDisabledLink));
        when(orderRepository.findCommonBillingBackfillOrders(3041L, Set.of(
                "Новый",
                "Нагул",
                "В проверку",
                "Коррекция",
                "На проверке",
                "Публикация",
                "Опубликовано",
                "Выставлен счет",
                "Напоминание",
                "Ожидает общего счета"
        ))).thenReturn(List.of());

        service.addCompany(57L, 3041L);

        assertEquals(newInvoice, movedItem.getInvoice());
        assertEquals(125_000L, newInvoice.getAmountKopecks());
        assertEquals(CommonInvoiceStatus.DISABLED, oldInvoice.getStatus());
        assertEquals(0L, oldInvoice.getAmountKopecks());
        assertEquals("merged_into: common_invoice_91", oldInvoice.getLastError());
        assertFalse(oldAccount.isEnabled());
        verify(invoiceOrderRepository).saveAll(List.of(movedItem));
        verify(accountRepository).save(oldAccount);
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
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setAccount(account);
        link.setCompany(company());
        link.setEnabled(true);
        Order order = order(101L);

        when(invoiceOrderRepository.findByOrder_Id(101L)).thenReturn(Optional.empty());
        when(accountCompanyRepository.findEnabledLinksForCompany(20L)).thenReturn(List.of(link));
        when(accountRepository.findByIdWithRelationsForUpdate(1L)).thenReturn(Optional.of(account));
        when(invoiceRepository.findCurrentForAccountForUpdate(any(), any(), any())).thenReturn(List.of());
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

        when(invoiceOrderRepository.findByOrder_Id(101L)).thenReturn(Optional.of(item));
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

        when(invoiceOrderRepository.findByOrder_Id(101L)).thenReturn(Optional.of(item));
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

        when(invoiceRepository.findByTbankOrderId("old-order")).thenReturn(Optional.empty());
        when(invoiceRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
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

        when(invoiceRepository.findByTbankOrderId("old-order")).thenReturn(Optional.empty());
        when(invoiceRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
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
        when(invoiceRepository.findByTbankOrderId("old-order")).thenReturn(Optional.empty());
        when(invoiceRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
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

        when(invoiceRepository.findByTbankOrderId("old-order")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByTbankOrderId("old-order")).thenReturn(Optional.empty());
        when(paymentRefRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
        when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(profile));
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal")).thenReturn(runtimeProfile);
        when(tokenSigner.matches(payload, "password", "token")).thenReturn(true);

        assertTrue(service.handleTbankWebhook(payload));

        ArgumentCaptor<CommonInvoicePaymentRef> captor = ArgumentCaptor.forClass(CommonInvoicePaymentRef.class);
        verify(paymentRefRepository).save(captor.capture());
        CommonInvoicePaymentRef ref = captor.getValue();
        assertEquals("old-order", ref.getTbankOrderId());
        assertEquals("old-payment", ref.getTbankPaymentId());
        assertEquals("REJECTED", ref.getStatus());
        assertEquals(null, invoice.getTbankOrderId());
        assertEquals(null, invoice.getTbankPaymentId());
        assertEquals(null, invoice.getPaymentUrl());
        assertTrue(invoice.getLastError().startsWith("tbank_payment_rejected"));
    }

    @Test
    void webhookUsesArchivedRefWhenLockedInvoiceNoLongerHasMatchedPayment() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice staleCandidate = invoice(account);
        staleCandidate.setTbankOrderId("old-order");
        staleCandidate.setTbankPaymentId("old-payment");
        staleCandidate.setTbankTerminalKey("terminal");
        staleCandidate.setTbankPaymentAmountKopecks(100_000L);
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

        when(invoiceRepository.findByTbankOrderId("old-order")).thenReturn(Optional.of(staleCandidate));
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

        when(invoiceRepository.findByTbankOrderId("old-order")).thenReturn(Optional.empty());
        when(invoiceRepository.findByTbankPaymentId("old-payment")).thenReturn(Optional.empty());
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
        when(invoiceRepository.findCurrentForAccount(eq(1L), any(), any(Pageable.class))).thenReturn(List.of(invoice));
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
    }

    @Test
    void addCompanyConvertsActiveCompanyRaceIntoConflict() {
        CommonBillingAccount account = account();
        Company company = company();
        company.setId(22L);

        when(accountRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(account));
        when(companyRepository.findById(22L)).thenReturn(Optional.of(company));
        when(accountCompanyRepository.findEnabledLinksForCompany(22L)).thenReturn(List.of());
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
        invoice.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(runtimeSettingsService.isPaymentLinksEnabled()).thenReturn(true);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
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
    void confirmPaymentInitCheckReturnsInvoiceToCurrentStateAfterManualBankCheck() throws Exception {
        CommonBillingAccount account = account();
        CommonInvoice invoice = invoice(account);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("payment_init_conflict: нужна ручная сверка");
        Order order = order(101L);
        CommonInvoiceOrder item = item(invoice, order);

        when(invoiceRepository.findByIdWithAccountForUpdate(10L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(item));
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1000));
        when(orderRepository.findOrderListRows(any())).thenReturn(List.of());
        when(properties.getPublicBaseUrl()).thenReturn("https://o-ogo.ru");

        service.confirmPaymentInitCheck(10L);

        assertEquals(CommonInvoiceStatus.READY, invoice.getStatus());
        assertEquals(null, invoice.getLastError());
        verify(orderTransactionService, never()).handlePaymentStatus(any(), anyBoolean());
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
        when(paymentRefRepository.findByIdForUpdate(84L)).thenReturn(Optional.of(ref));

        int processed = service.cancelPendingArchivedPayments(20);

        assertEquals(0, processed);
        assertEquals("ARCHIVED", ref.getStatus());
        assertEquals("paid_invoice_cancel_skipped", ref.getReason());
        verify(paymentRefRepository).save(ref);
        verify(tbankClient, never()).cancel(any(), any());
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
