package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentLinkOrderPayableChangedListenerTest {

    @Test
    void refreshesOrdinaryInvoiceAfterPayableChange() {
        PaymentLinkService paymentLinkService = mock(PaymentLinkService.class);
        PaymentLinkOrderPayableChangedListener listener =
                new PaymentLinkOrderPayableChangedListener(paymentLinkService);

        listener.onOrderPayableChanged(new OrderPayableChangedEvent(52L));

        verify(paymentLinkService).refreshLinkedOrderAmount(52L);
    }
}
