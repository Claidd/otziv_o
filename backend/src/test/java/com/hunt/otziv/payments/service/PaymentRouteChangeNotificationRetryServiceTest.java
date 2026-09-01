package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRouteChangeNotificationRetryServiceTest {

    @Mock
    private PaymentRouteChangeNotificationOutboxRepository repository;

    @Mock
    private PaymentRouteChangeNotificationWorker worker;

    @Test
    void committedPendingRowsAreRecoveredAfterFastPathCrash() {
        when(repository.findDuePaymentLinkIds(50)).thenReturn(List.of(22L, 23L));
        PaymentRouteChangeNotificationRetryService service =
                new PaymentRouteChangeNotificationRetryService(repository, worker);

        service.retryPendingNotifications();

        verify(worker).send(22L);
        verify(worker).send(23L);
    }
}
