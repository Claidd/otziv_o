package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.api.DisputedOrderArchive;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceDisputedOrderArchiveWorkflowTest {

    @Mock CommonInvoiceOrderRepository invoiceOrderRepository;
    @Mock CommonInvoiceMembershipWorkflow membershipWorkflow;
    @Mock CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;
    @Mock DisputedOrderArchive disputedOrderArchive;
    @InjectMocks CommonInvoiceDisputedOrderArchiveWorkflow workflow;

    private CommonInvoiceOrder item;

    @BeforeEach
    void setUp() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(5L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        Order order = new Order();
        order.setId(42L);
        item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        item.setOrder(order);
        when(invoiceOrderRepository.findByOrderIdWithInvoice(42L)).thenReturn(Optional.of(item));
    }

    @Test
    void detachesAndArchivesUnpaidOrderTogether() throws Exception {
        CommonInvoiceDetailsResponse details = mock(CommonInvoiceDetailsResponse.class);
        when(disputedOrderArchive.archive(42L)).thenReturn(true);
        when(manualPaymentWorkflow.invoiceAfterOrderPrelude(5L)).thenReturn(details);

        assertSame(details, workflow.archive(5L, 42L));
        var sequence = inOrder(membershipWorkflow, disputedOrderArchive);
        sequence.verify(membershipWorkflow).detachOrderForArchive(5L, 42L);
        sequence.verify(disputedOrderArchive).archive(42L);
    }

    @Test
    void reviewApprovalFailureDoesNotPreventArchivingUnpaidDisputedPosition() throws Exception {
        item.getInvoice().setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        item.getInvoice().setLastError("review_approval_failed: order=42; problem=duplicate text");
        when(disputedOrderArchive.archive(42L)).thenReturn(true);
        workflow.archive(5L, 42L);
        verify(membershipWorkflow).detachOrderForArchive(5L, 42L);
        verify(disputedOrderArchive).archive(42L);
    }

    @Test
    void financialAttentionStillRequiresReconciliation() throws Exception {
        item.getInvoice().setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        item.getInvoice().setLastError("payment_init_conflict");
        assertThrows(ResponseStatusException.class, () -> workflow.archive(5L, 42L));
        verifyNoInteractions(membershipWorkflow);
        verify(disputedOrderArchive, never()).archive(42L);
    }

    @Test
    void rejectsPaidOrderWithoutDetaching() throws Exception {
        item.setPaid(true);
        assertThrows(ResponseStatusException.class, () -> workflow.archive(5L, 42L));
        verifyNoInteractions(membershipWorkflow);
        verify(disputedOrderArchive, never()).archive(42L);
    }

    @Test
    void rejectsInvoiceAlreadySentToClient() throws Exception {
        item.getInvoice().setSentAt(LocalDateTime.now());
        assertThrows(ResponseStatusException.class, () -> workflow.archive(5L, 42L));
        verifyNoInteractions(membershipWorkflow);
        verify(disputedOrderArchive, never()).archive(42L);
    }
}
