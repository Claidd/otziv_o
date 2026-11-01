package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankPaperLinkLifecycleTest {
    @Test void anExpiredUrlDoesNotExpireOrReplaceTheBankInvoice() {
        var links=mock(PaymentLinkRepository.class);var receipts=mock(ManualPaymentTaskReceiptIntegrationService.class);
        var lifecycle=new PaymentLinkLifecycleService(mock(PaymentLinkAmountPolicy.class),mock(PaymentLinkPresenter.class),links,receipts,mock(OrderPaymentIntegrityService.class));
        var link=new PaymentLink();link.setPaperBankDocumentId("bank-doc");link.setPaymentMethod(PaymentMethod.OWNER_PAPER_INVOICE);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);link.setExpiresAt(LocalDateTime.now().minusDays(10));
        lifecycle.expireIfPastDue(link);
        assertFalse(lifecycle.canRetireStaleLink(link));assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT,link.getStatus());
        verifyNoInteractions(links,receipts);
    }
}
