package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CommonInvoiceOrderPayableChangedListenerTest {

    @Test
    void refreshesLinkedInvoiceAfterPayableChange() {
        CommonBillingService commonBillingService = mock(CommonBillingService.class);
        CommonInvoiceOrderPayableChangedListener listener =
                new CommonInvoiceOrderPayableChangedListener(commonBillingService);

        listener.onOrderPayableChanged(new OrderPayableChangedEvent(51L));

        verify(commonBillingService).refreshLinkedOrderAmount(51L);
    }
}
