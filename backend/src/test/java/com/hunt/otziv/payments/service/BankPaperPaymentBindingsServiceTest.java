package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.api.BankPaperInvoiceBindings;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankPaperPaymentBindingsServiceTest {
    @Test void bothStandaloneAndCommonBankDocumentsFreezeTheOrder() {
        var links=mock(PaymentLinkRepository.class);var common=mock(BankPaperInvoiceBindings.class);
        var bindings=new BankPaperPaymentBindingsService(links,common);
        when(links.findBankDocumentBindingsForUpdate(42L)).thenReturn(java.util.List.of("bank-doc"));
        assertTrue(bindings.hasDocument(42));verifyNoInteractions(common);
        when(common.hasDocument(43)).thenReturn(true);assertTrue(bindings.hasDocument(43));
        assertFalse(bindings.hasDocument(44));
    }
}
