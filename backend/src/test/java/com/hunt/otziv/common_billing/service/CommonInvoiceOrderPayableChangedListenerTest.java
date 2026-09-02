package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonInvoiceOrderPayableChangedListenerTest {

    @Test
    void refreshesLinkedInvoiceAfterPayableChange() {
        CommonBillingService commonBillingService = mock(CommonBillingService.class);
        CommonBillingTransactionExecutor transactionExecutor = mock(CommonBillingTransactionExecutor.class);
        when(transactionExecutor.required(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        CommonInvoiceOrderPayableChangedListener listener =
                new CommonInvoiceOrderPayableChangedListener(commonBillingService, transactionExecutor);

        listener.onOrderPayableChanged(new OrderPayableChangedEvent(51L));

        verify(transactionExecutor).required(any());
        verify(commonBillingService).refreshLinkedOrderAmount(51L);
    }
}
