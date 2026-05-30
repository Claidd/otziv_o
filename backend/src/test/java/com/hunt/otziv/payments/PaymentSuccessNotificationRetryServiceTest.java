package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentSuccessNotificationRetryServiceTest {

    private final PaymentLinkRepository paymentLinkRepository = mock(PaymentLinkRepository.class);
    private final PaymentSuccessClientNotifier paymentSuccessClientNotifier = mock(PaymentSuccessClientNotifier.class);
    private final PaymentSuccessNotificationRetryService service = new PaymentSuccessNotificationRetryService(
            paymentLinkRepository,
            paymentSuccessClientNotifier
    );

    @Test
    void marksNotificationSentWhenRetrySucceeds() {
        PaymentLink link = new PaymentLink();
        link.setId(207L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaymentSuccessNotificationError("whatsapp_group_missing");

        when(paymentLinkRepository.findSuccessNotificationRetryCandidates(any(Pageable.class)))
                .thenReturn(List.of(link));
        when(paymentSuccessClientNotifier.notifySuccess(link))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));

        int retried = service.retryBatch();

        assertEquals(1, retried);
        assertNotNull(link.getPaymentSuccessNotifiedAt());
        assertNull(link.getPaymentSuccessNotificationError());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void keepsErrorWhenRetryStillFails() {
        PaymentLink link = new PaymentLink();
        link.setId(207L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaymentSuccessNotificationError("whatsapp_group_missing");

        when(paymentLinkRepository.findSuccessNotificationRetryCandidates(any(Pageable.class)))
                .thenReturn(List.of(link));
        when(paymentSuccessClientNotifier.notifySuccess(link))
                .thenReturn(ClientMessageSendResult.failed("whatsapp_error", "not ready"));

        int retried = service.retryBatch();

        assertEquals(0, retried);
        assertNull(link.getPaymentSuccessNotifiedAt());
        assertEquals("whatsapp_error: not ready", link.getPaymentSuccessNotificationError());
        verify(paymentLinkRepository).save(link);
    }
}
