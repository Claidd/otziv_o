package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentSuccessNotificationRetryServiceTest {

    private final PaymentLinkRepository paymentLinkRepository = mock(PaymentLinkRepository.class);
    private final PaymentSuccessNotificationDeliveryService deliveryService =
            mock(PaymentSuccessNotificationDeliveryService.class);
    private final PaymentSuccessNotificationRetryService service = new PaymentSuccessNotificationRetryService(
            paymentLinkRepository,
            deliveryService
    );

    @Test
    void countsNotificationWhenSharedDeliverySucceeds() {
        PaymentLink link = new PaymentLink();
        link.setId(207L);

        when(paymentLinkRepository.findSuccessNotificationRetryCandidates(any(Pageable.class)))
                .thenReturn(List.of(link));
        when(deliveryService.tryDeliver(link.getId())).thenReturn(true);

        int retried = service.retryBatch();

        assertEquals(1, retried);
        verify(deliveryService).tryDeliver(link.getId());
    }

    @Test
    void doesNotCountNotificationWhenSharedDeliveryDoesNotSend() {
        PaymentLink link = new PaymentLink();
        link.setId(207L);

        when(paymentLinkRepository.findSuccessNotificationRetryCandidates(any(Pageable.class)))
                .thenReturn(List.of(link));
        when(deliveryService.tryDeliver(link.getId())).thenReturn(false);

        int retried = service.retryBatch();

        assertEquals(0, retried);
        verify(deliveryService).tryDeliver(link.getId());
    }
}
