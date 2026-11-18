package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonInvoicePaidNoticeTest {
    @Test void onlyFullyPaidUnstartedNoticeIsRetired() {
        check("QUEUED", CommonInvoiceStatus.PAID, 120000, "SUPERSEDED", "invoice_paid_before_dispatch");
        check("RETRYABLE", CommonInvoiceStatus.PAID, 120000, "SUPERSEDED", "invoice_paid_before_dispatch");
        check("QUEUED", CommonInvoiceStatus.READY, 0, "FAILED", "context_changed");
        check("QUEUED", CommonInvoiceStatus.PARTIALLY_PAID, 60000, "FAILED", "context_changed");
        check("QUEUED", CommonInvoiceStatus.PAID, 60000, "FAILED", "context_changed");
    }

    @Test void paymentDoesNotProveTheOutcomeOfAnAlreadyUncertainSend() {
        check("UNKNOWN", CommonInvoiceStatus.PAID, 120000, "UNKNOWN", "invoice_changed");
        check("SENDING", CommonInvoiceStatus.PAID, 120000, "UNKNOWN", "invoice_changed");
    }

    private void check(String previous, CommonInvoiceStatus status, long paid, String expectedState, String expectedCode) {
        var invoice = new CommonInvoice();
        invoice.setId(272L); invoice.setStatus(status);
        invoice.setAmountKopecks(120000); invoice.setPaidKopecks(paid);
        invoice.setContractorAllocationId(2948L);
        var queue = mock(CommonInvoiceMessageQueue.class);
        var service = mock(CommonInvoiceDeliveryService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageQueue", queue);
        ReflectionTestUtils.setField(service, "settlementService",
                mock(CommonInvoiceSettlementService.class, CALLS_REAL_METHODS));
        doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get()).when(service).writeTransaction(any());
        doReturn(new CommonInvoiceInitializationService.LockedInvoicePaymentPrelude(invoice, Map.of()))
                .when(service).lockedInvoiceAfterStandalonePaymentPrelude(272L);
        var claim = new CommonInvoiceMessageQueue.Claim("bad-review-invoice:272:5", 272L, previous, 0, "claim");
        when(queue.owns(claim)).thenReturn(true);
        var message = new PreparedCommonInvoiceMessage(272L, null, "client", "group", "notice", null,
                false, false, false, claim.operationId(), false, null, 120000);
        service.finishQueuedMessage(claim, message, ClientMessageSendResult.failed("invoice_changed", "held"), false);
        verify(queue).finish(eq(claim), eq(expectedState), eq(expectedCode), anyInt(), eq(false));
        verify(service, never()).sendPreparedPaymentMessage(any());
        assertEquals(status, invoice.getStatus());
        assertEquals(paid, invoice.getPaidKopecks());
        assertEquals(120000, invoice.getAmountKopecks());
        assertEquals(2948L, invoice.getContractorAllocationId());
    }
}
