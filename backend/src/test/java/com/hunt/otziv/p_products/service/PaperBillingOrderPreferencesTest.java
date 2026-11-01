package com.hunt.otziv.p_products.service;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
class PaperBillingOrderPreferencesTest {
    @Test void repeatingAnOrderReadsPersistedPreferencesThroughItsLazyProxyAccessors() {
        var lazySource=mock(Order.class);
        when(lazySource.getInvoicePaymentMode()).thenReturn(InvoicePaymentMode.OWNER_PAPER_INVOICE);
        when(lazySource.getPaperBillingPayerChoiceId()).thenReturn("persisted-choice");
        var repeated=new Order();repeated.inheritInvoicePreferences(lazySource);
        assertTrue(repeated.isBankPaperInvoice());assertEquals("persisted-choice",repeated.getPaperBillingPayerChoiceId());
    }
    @Test void explicitOrdinaryRouteIsAlsoInherited() {
        var source=new Order();source.useAutomaticInvoicePaymentMode();source.setPaperBillingPayerChoiceId("earlier-paper-choice");
        var repeated=new Order();repeated.inheritInvoicePreferences(source);
        assertTrue(repeated.isAutomaticInvoicePaymentMode());assertEquals("earlier-paper-choice",repeated.getPaperBillingPayerChoiceId());
    }
}
