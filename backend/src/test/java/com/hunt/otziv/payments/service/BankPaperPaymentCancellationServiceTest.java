package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankPaperPaymentCancellationServiceTest {
    @Test void releasesOnlyTheExactUnpaidBankBinding() {
        var links=mock(PaymentLinkRepository.class);var link=new PaymentLink();link.setPaperBankDocumentId("document");link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        when(links.findByIdForUpdate(8L)).thenReturn(Optional.of(link));var service=new BankPaperPaymentCancellationService(links);
        assertThrows(RuntimeException.class,()->service.cancel(8,"other"));assertEquals("document",link.getPaperBankDocumentId());
        service.cancel(8,"document");assertNull(link.getPaperBankDocumentId());assertEquals(PaymentLinkStatus.CANCELED,link.getStatus());verify(links).saveAndFlush(link);
    }
    @Test void paidSourceCannotBeReleasedByDocumentCancellation() {
        var links=mock(PaymentLinkRepository.class);var link=new PaymentLink();link.setPaperBankDocumentId("document");link.setStatus(PaymentLinkStatus.CONFIRMED);
        when(links.findByIdForUpdate(8L)).thenReturn(Optional.of(link));
        assertThrows(RuntimeException.class,()->new BankPaperPaymentCancellationService(links).cancel(8,"document"));assertEquals("document",link.getPaperBankDocumentId());verify(links,never()).saveAndFlush(any());
    }
}
