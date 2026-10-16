package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ReviewApprovalReminderStates;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.api.ReviewReminderOrderApprovals;
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

    @Mock ReviewApprovalReminderStates stateService;
    @Mock ReviewReminderOrderApprovals orderApprovals;
    @Mock CommonInvoiceOrderRepository invoiceOrderRepository;
    @Mock CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;
    @InjectMocks CommonInvoiceReminderApprovalWorkflow workflow;

    private ReviewApprovalReminderStates.Evidence state;
    private CommonInvoice invoice;

    @BeforeEach
    void setUp() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 1, 12, 30);
        state = new ReviewApprovalReminderStates.Evidence(42L, 7, "order:42:" + changedAt);
        invoice = new CommonInvoice();
        invoice.setId(5L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        lenient().when(stateService.lockEligible(11L, 7)).thenReturn(Optional.of(state));
        lenient().when(orderApprovals.lockEligible(42L, state.targetKey())).thenReturn(true);
        lenient().when(invoiceOrderRepository.findMembershipByOrderIdForRead(42L)).thenReturn(Optional.of(item));
        lenient().when(manualPaymentWorkflow.lockedInvoiceAfterOrderPrelude(5L)).thenReturn(Optional.of(invoice));
    }

    @Test
    void approvesCurrentCycleAfterSevenDeliveredReminders() {
        assertTrue(workflow.approve(11L));
        verify(orderApprovals).approve(eq(42L), contains("sentCount=7"));
        verify(manualPaymentWorkflow).invoiceAfterOrderPrelude(5L);
        verify(stateService).complete(11L);
    }

    @Test
    void doesNotApproveBeforeSeventhDelivery() {
        when(stateService.lockEligible(11L, 7)).thenReturn(Optional.empty());
        assertFalse(workflow.approve(11L));
        verify(orderApprovals, never()).approve(anyLong(), anyString());
    }

    @Test
    void doesNotApproveOldReviewCycle() {
        when(orderApprovals.lockEligible(42L, state.targetKey())).thenReturn(false);
        assertFalse(workflow.approve(11L));
        verify(orderApprovals, never()).approve(anyLong(), anyString());
    }

    @Test
    void waitsForUncertainDeliveryOutcome() {
        when(stateService.lockEligible(11L, 7)).thenReturn(Optional.empty());
        assertFalse(workflow.approve(11L));
        verify(orderApprovals, never()).approve(anyLong(), anyString());
    }

    @Test
    void doesNotApproveSentInvoice() {
        invoice.setSentAt(LocalDateTime.now());
        assertFalse(workflow.approve(11L));
        verify(orderApprovals, never()).approve(anyLong(), anyString());
    }
}
