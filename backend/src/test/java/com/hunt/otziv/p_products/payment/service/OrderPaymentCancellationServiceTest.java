package com.hunt.otziv.p_products.payment.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.p_products.deletion.service.OrderDeletionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.status.service.OrderCompanyStatusService;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentCancellationServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderStatusService orderStatusService;
    @Mock private OrderCompanyStatusService orderCompanyStatusService;
    @Mock private CompanyService companyService;
    @Mock private PaymentCheckRepository paymentCheckRepository;
    @Mock private ZpRepository zpRepository;
    @Mock private NextOrderRequestRepository nextOrderRequestRepository;
    @Mock private OrderDeletionService orderDeletionService;
    @Mock private PaymentLinkRepository paymentLinkRepository;
    @Mock private PaymentLinkService paymentLinkService;
    @Mock private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private CommonBillingService commonBillingService;

    @InjectMocks
    private OrderPaymentCancellationService service;

    @Test
    void cancelPaymentDetachesAutoCreatedNextOrderBeforeStandaloneDeletion() {
        Order source = order(10L, "Оплачено");
        source.setAmount(3);
        Order created = order(20L, "Новый");
        NextOrderRequest request = new NextOrderRequest();
        request.setStatus(NextOrderRequestStatus.CREATED);
        request.setSourceOrder(source);
        request.setCreatedOrder(created);
        OrderStatus reminder = status("Напоминание");
        Principal principal = () -> "admin";

        when(orderRepository.findByIdForMutation(10L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(nextOrderRequestRepository.findBySourceOrderId(10L)).thenReturn(Optional.of(request));
        when(commonBillingService.detachOrderForDeletion(20L)).thenReturn(true);
        when(orderDeletionService.deleteOrder(any(), any())).thenReturn(true);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(10L)).thenReturn(List.of());
        when(zpRepository.findByOrderIdAndActiveTrue(10L)).thenReturn(List.of());
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(reminder);

        service.cancelPayment(10L, principal);

        var deletionOrder = inOrder(commonBillingService, orderDeletionService);
        deletionOrder.verify(commonBillingService).detachOrderForDeletion(20L);
        deletionOrder.verify(orderDeletionService).deleteOrder(20L, principal);
        assertEquals("Напоминание", source.getStatus().getTitle());
        verify(paymentLinkService).createForOrder(10L);
    }

    private Order order(Long id, String statusTitle) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(status(statusTitle));
        return order;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        return status;
    }
}
