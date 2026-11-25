package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceRepricedDeliveryTest {
    @Mock CommonInvoiceSettlementService settlementService;
    @Mock CommonInvoiceRepository invoiceRepository;
    @InjectMocks CommonInvoiceDeliveryService delivery;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receiptFinalizesRepricedInvoiceWithoutReopeningUnpaidOrders(boolean reminder) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(212L);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setBadReviewPayable(true);
        invoice.setAmountKopecks(280000L);
        invoice.setPaymentMessageOperationId("existing-operation");
        invoice.setLastError(CommonInvoiceInitializationService.PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS);
        when(settlementService.lockedInvoice(212L)).thenReturn(Optional.of(invoice));
        when(settlementService.normalize(any())).thenAnswer(call -> call.getArgument(0));
        var prepared = new PreparedCommonInvoiceMessage(212L, null, "sender", "group", "К оплате 2800",
                null, reminder, false, true, "existing-operation", false, null, 280000L);
        var receipt = ClientMessageSendResult.sent("MAX", "original-message");

        assertTrue(delivery.finishPaymentMessageSend(prepared, receipt));
        assertTrue(delivery.finishPaymentMessageSend(prepared, receipt));

        assertEquals(CommonInvoiceStatus.UNPAID, invoice.getStatus());
        assertTrue(invoice.isPaymentMessageConfirmed());
        assertEquals("MAX", invoice.getPaymentMessageChannel());
        assertNull(invoice.getLastError());
        assertEquals(0L, invoice.getPaidKopecks());
        verify(invoiceRepository, times(1)).save(invoice);
    }
}
