package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.*;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
class CommonInvoiceRecordedPaymentReconciliationTest {
    @Mock CommonInvoiceSettlementService settlement;
    @Mock CommonInvoiceDeliveryService delivery;
    @Mock CommonInvoiceManualPaymentWorkflow manualPayments;
    @Mock CommonManualPaymentAttributionCoordinator attribution;
    @Mock CommonInvoiceOrderRepository itemsRepository;
    @Mock CommonInvoiceRepository invoiceRepository;
    @Mock CommonInvoicePaymentRefRepository bankRefs;
    @Mock CommonInvoiceDetailsAssembler details;
    @Mock PaymentCheckService checks;
    @InjectMocks CommonInvoiceRecordedPaymentReconciliation reconciliation;
    final CommonInvoice invoice = new CommonInvoice();
    final CommonInvoiceOrder item = new CommonInvoiceOrder();
    final Order order = new Order();
    final PaymentCheck check = new PaymentCheck();
    final LocalDateTime paidAt = LocalDateTime.of(2026, 11, 24, 8, 41);
    final java.security.Principal owner = () -> "owner";
    final CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
            "verified-existing-check", true, true, paidAt, "Сверено с получателем", "",
            List.of(new CommonManualPaymentAttributionRowRequest("recipient", ContractorRecipientType.SPECIALIST, 10L, 240000L)));

    @BeforeEach void prepare() {
        invoice.setId(373L);
        invoice.setStatus(CommonInvoiceStatus.BAN);
        invoice.setCloseReason("BAN");
        invoice.setClosedAt(paidAt.minusDays(6));
        invoice.setAmountKopecks(240000L);
        order.setId(26194L);
        order.setPayDay(paidAt.toLocalDate());
        item.setOrder(order);
        item.setActiveMembership(true);
        item.setAmountKopecks(240000L);
        check.setId(23823L);
        check.setOrderId(order.getId());
        check.setActive(true);
        check.setSum(new BigDecimal("2400.00"));
        check.setCreated(paidAt.toLocalDate());
        when(delivery.lockedInvoiceAfterStandalonePaymentPrelude(373L)).thenReturn(
                new CommonInvoiceInitializationService.LockedInvoicePaymentPrelude(invoice, Map.of()));
        when(itemsRepository.findByInvoiceIdWithOrders(373L)).thenReturn(List.of(item));
    }

    @Test void linksExistingAccountingExactlyOnceWithoutSavingAnotherCheckOrSending() {
        when(settlement.isOrderPaid(order)).thenReturn(true);
        doAnswer(call -> { invoice.setStatus(CommonInvoiceStatus.PAID); return null; })
                .when(settlement).markInvoicePaidClosed(invoice);
        when(attribution.replayIfRecorded(invoice, List.of(item), request, owner)).thenReturn(true);

        reconciliation.reconcile(373L, request, owner);
        reconciliation.reconcile(373L, request, owner);

        assertTrue(item.isPaid());
        assertFalse(item.isUnpaid());
        assertEquals(240000L, invoice.getPaidKopecks());
        assertEquals(paidAt, invoice.getPaidAt());
        verify(attribution).recordFinalReceipt(invoice, List.of(item), 240000L, request, owner);
        verify(checks).requireSingleRecordedManualPayment(order.getId(), 240000L, paidAt.toLocalDate());
    }

    @Test void ambiguousExistingChecksPreventAnyNewAttribution() {
        when(settlement.isOrderPaid(order)).thenReturn(true);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(checks).requireSingleRecordedManualPayment(order.getId(), 240000L, paidAt.toLocalDate());
        assertThrows(ResponseStatusException.class, () -> reconciliation.reconcile(373L, request, owner));
        verifyNoInteractions(attribution);
        verify(invoiceRepository, never()).save(any());
        assertFalse(item.isPaid());
    }

    @Test void aPaidInvoiceWithAnotherReceiptCannotBeReassignedByRetry() {
        invoice.setStatus(CommonInvoiceStatus.PAID);
        assertThrows(ResponseStatusException.class, () -> reconciliation.reconcile(373L, request, owner));
        verify(attribution, never()).recordFinalReceipt(any(), any(), anyLong(), any(), any());
        verifyNoInteractions(checks);
    }
}
