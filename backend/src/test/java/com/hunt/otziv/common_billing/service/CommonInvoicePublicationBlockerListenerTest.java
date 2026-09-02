package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonInvoicePublicationBlockerListenerTest {

    @Test
    void reconcilesBlockerInIndependentTransaction() {
        CommonInvoicePublicationBlockerService blockerService =
                mock(CommonInvoicePublicationBlockerService.class);
        CommonBillingTransactionExecutor transactionExecutor =
                mock(CommonBillingTransactionExecutor.class);
        when(transactionExecutor.required(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        CommonInvoicePublicationBlockerListener listener =
                new CommonInvoicePublicationBlockerListener(blockerService, transactionExecutor);

        listener.onOrderStatusChanged(new OrderStatusChangedEvent(
                53L,
                "Новый",
                "Публикация",
                "Публикация"
        ));

        verify(transactionExecutor).required(any());
        verify(blockerService).reconcileOrder(53L);
    }
}
