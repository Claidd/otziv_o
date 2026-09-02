package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentLinkOrderPayableChangedListenerTest {

    @Test
    void refreshesOrdinaryInvoiceAfterPayableChange() {
        PaymentLinkService paymentLinkService = mock(PaymentLinkService.class);
        PaymentLinkTransactionExecutor transactionExecutor = mock(PaymentLinkTransactionExecutor.class);
        when(transactionExecutor.required(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        PaymentLinkOrderPayableChangedListener listener =
                new PaymentLinkOrderPayableChangedListener(paymentLinkService, transactionExecutor);

        listener.onOrderPayableChanged(new OrderPayableChangedEvent(52L));

        verify(transactionExecutor).required(any());
        verify(paymentLinkService).refreshLinkedOrderAmount(52L);
    }
}
