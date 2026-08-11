package com.hunt.otziv.payments;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentIntegrityServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Mock
    private OrderStatusService orderStatusService;
    @Mock
    private BusinessAuditService businessAuditService;

    @InjectMocks
    private OrderPaymentIntegrityService service;

    @Test
    void repairRestoresPaidStatusAndClosesOnlyDuplicatePaymentArtifacts() {
        OrderStatus published = status(6L, "Опубликовано");
        OrderStatus paid = status(9L, "Оплачено");
        Order order = new Order();
        order.setId(24572L);
        order.setComplete(true);
        order.setPayDay(LocalDate.of(2026, 7, 16));
        order.setStatus(published);

        PaymentLink duplicate = new PaymentLink();
        duplicate.setId(4613L);
        duplicate.setOrder(order);
        duplicate.setStatus(PaymentLinkStatus.CREATED);
        duplicate.setExpiresAt(LocalDateTime.of(2026, 8, 1, 0, 0));

        ScheduledClientMessageState paymentState = ScheduledClientMessageState.builder()
                .id(4685L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24572:payment")
                .orderId(24572L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        ScheduledClientMessageState unrelatedState = ScheduledClientMessageState.builder()
                .id(4686L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:24572:review")
                .orderId(24572L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();

        when(orderRepository.findByIdForCounterUpdate(24572L)).thenReturn(Optional.of(order));
        when(paymentLinkRepository.findByOrder_IdAndStatusIn(org.mockito.ArgumentMatchers.eq(24572L), anyCollection()))
                .thenReturn(List.of(duplicate));
        when(stateRepository.findByOrderIdIn(List.of(24572L))).thenReturn(List.of(paymentState, unrelatedState));
        when(orderStatusService.getOrderStatusByTitle("Оплачено")).thenReturn(paid);

        OrderPaymentIntegrityService.RepairResult result = service.repair(24572L);

        assertEquals(paid, order.getStatus());
        assertEquals(PaymentLinkStatus.EXPIRED, duplicate.getStatus());
        assertTrue(duplicate.getLastError().contains("уже был полностью оплачен"));
        assertEquals(ScheduledMessageStateStatus.DONE, paymentState.getStatus());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, unrelatedState.getStatus());
        assertEquals(1, result.expiredLinks());
        assertEquals(1, result.closedMessageStates());
        verify(orderRepository).save(order);
        verify(paymentLinkRepository).saveAll(List.of(duplicate));
        verify(stateRepository).saveAll(List.of(paymentState));
    }

    private OrderStatus status(Long id, String title) {
        OrderStatus status = new OrderStatus();
        status.setId(id);
        status.setTitle(title);
        return status;
    }
}
