package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommonInvoicePaperDeliveryConfirmationTest {
    @Mock CommonInvoiceSettlementService settlementService;
    @Mock CommonInvoiceDeliveryService invoiceDelivery;
    @Mock CommonInvoiceRepository invoiceRepository;
    @InjectMocks CommonInvoicePaymentRouteWorkflow workflow;

    @Test void readinessNoticeCannotBeManuallyConfirmedAsBankDocumentDelivery() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(322L);
        invoice.setSentAt(java.time.LocalDateTime.now());
        when(settlementService.lockedInvoice(322L)).thenReturn(Optional.of(invoice));
        ReflectionTestUtils.setField(workflow,"automaticPaperInvoicesEnabled",true);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> workflow.markPaperInvoiceIssued(322L,()->"manager"));

        assertEquals(HttpStatus.CONFLICT,failure.getStatusCode());
        assertNull(invoice.getPaperInvoiceIssuedAt());
        assertNull(invoice.getNextReminderAt());
        verify(invoiceDelivery).ensureCommonInvoiceVisibleForCurrentUser(invoice);
        verifyNoInteractions(invoiceRepository);
    }

    @Test void existingBankDocumentsCannotBeManuallyConfirmedWhenFeatureIsDisabled() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(322L);
        invoice.setPaperBankDocumentId("bank-document");
        when(settlementService.lockedInvoice(322L)).thenReturn(Optional.of(invoice));
        assertThrows(ResponseStatusException.class,()->workflow.markPaperInvoiceIssued(322L,()->"manager"));
        assertNull(invoice.getPaperInvoiceIssuedAt());
        verifyNoInteractions(invoiceRepository);
    }
}
