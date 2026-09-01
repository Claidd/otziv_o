package com.hunt.otziv.payments;

import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ConfirmedPrepaymentRecoveryService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.review_recovery.event.ReviewRecoveryReleasedEvent;
import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmedPrepaymentRecoveryServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private PaymentLinkService paymentLinkService;
    @InjectMocks
    private ConfirmedPrepaymentRecoveryService service;

    @Test
    void reviewRecoveryReleaseImmediatelyRetriesTheExactOrder() {
        service.onReviewRecoveryReleased(new ReviewRecoveryReleasedEvent(25_099L));

        verify(paymentLinkService).applyConfirmedPrepaymentIfReady(25_099L);
        verify(paymentLinkRepository, never()).findConfirmedPrepaymentRecoveryOrderIds(
                anyString(),
                any(LocalDateTime.class),
                any(Pageable.class)
        );
    }

    @Test
    void reviewRecoveryReleaseUsesSafeBoundedPostCommitExecutor() throws Exception {
        Method listener = ConfirmedPrepaymentRecoveryService.class.getMethod(
                "onReviewRecoveryReleased",
                ReviewRecoveryReleasedEvent.class
        );

        Async async = listener.getAnnotation(Async.class);

        assertEquals("orderPaymentPostCommitExecutor", async.value());
    }

    @Test
    void boundedFallbackRetriesEveryDurableCandidateIndependently() {
        when(paymentLinkRepository.findConfirmedPrepaymentRecoveryOrderIds(
                anyString(),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of(25_099L, 25_100L));
        when(paymentLinkService.applyConfirmedPrepaymentIfReady(25_099L)).thenReturn(true);
        when(paymentLinkService.applyConfirmedPrepaymentIfReady(25_100L)).thenReturn(false);

        assertEquals(1, service.recoverReadyPrepayments());

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentLinkRepository).findConfirmedPrepaymentRecoveryOrderIds(
                anyString(),
                any(LocalDateTime.class),
                page.capture()
        );
        assertEquals(50, page.getValue().getPageSize());
        verify(paymentLinkService).applyConfirmedPrepaymentIfReady(25_099L);
        verify(paymentLinkService).applyConfirmedPrepaymentIfReady(25_100L);
    }
}
