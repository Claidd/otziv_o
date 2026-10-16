package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryHoldService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceReminderApprovalWorkflowTest {

    @Mock ScheduledClientMessageStateRepository stateRepository;
    @Mock OrderRepository orderRepository;
    @Mock CommonInvoiceOrderRepository invoiceOrderRepository;
    @Mock CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;
    @Mock OrderPublicationApprovalService publicationApprovalService;
    @Mock ReviewRecoveryHoldService reviewRecoveryHoldService;
    @InjectMocks CommonInvoiceReminderApprovalWorkflow workflow;

    private ScheduledClientMessageState state;
    private Order order;
    private CommonInvoice invoice;

    @BeforeEach
    void setUp() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 1, 12, 30);
        state = ScheduledClientMessageState.builder()
                .id(11L).orderId(42L).scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .status(ScheduledMessageStateStatus.ACTIVE).sentCount(7)
                .targetKey("order:42:" + changedAt).build();
        order = new Order();
        order.setId(42L);
        order.setStatus(OrderStatus.builder().title("На проверке").build());
        order.setStatusChangedAt(changedAt);
        invoice = new CommonInvoice();
        invoice.setId(5L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(order);
        item.setInvoice(invoice);
        when(stateRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(state));
        lenient().when(orderRepository.findByIdForMutation(42L)).thenReturn(Optional.of(order));
        lenient().when(invoiceOrderRepository.findMembershipByOrderIdForRead(42L)).thenReturn(Optional.of(item));
        lenient().when(manualPaymentWorkflow.lockedInvoiceAfterOrderPrelude(5L)).thenReturn(Optional.of(invoice));
    }

    @Test
    void approvesCurrentCycleAfterSevenDeliveredReminders() {
        assertTrue(workflow.approve(11L));
        verify(publicationApprovalService).approveExistingOrderAfterReminders(eq(42L), contains("sentCount=7"));
        verify(manualPaymentWorkflow).invoiceAfterOrderPrelude(5L);
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
    }

    @Test
    void doesNotApproveBeforeSeventhDelivery() {
        state.setSentCount(6);
        assertFalse(workflow.approve(11L));
        verifyNoInteractions(publicationApprovalService);
    }

    @Test
    void doesNotApproveOldReviewCycle() {
        state.setTargetKey("order:42:2026-09-01T12:30");
        assertFalse(workflow.approve(11L));
        verifyNoInteractions(publicationApprovalService);
    }

    @Test
    void waitsForUncertainDeliveryOutcome() {
        state.setDeliveryStatus("UNKNOWN");
        assertFalse(workflow.approve(11L));
        verifyNoInteractions(publicationApprovalService);
    }

    @Test
    void doesNotApproveSentInvoice() {
        invoice.setSentAt(LocalDateTime.now());
        assertFalse(workflow.approve(11L));
        verifyNoInteractions(publicationApprovalService);
    }
}
