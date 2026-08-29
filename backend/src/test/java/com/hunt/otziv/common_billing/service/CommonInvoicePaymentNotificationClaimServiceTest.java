package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository.Delivery;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository.NotificationKind;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonInvoicePaymentNotificationClaimServiceTest {

    private static final String CLAIM_TOKEN = "00000000-0000-0000-0000-000000000001";

    @Mock
    private CommonInvoicePaymentNotificationOutboxRepository repository;

    @Test
    void acquiresClaimWithBoundedLease() {
        Delivery delivery = clientDelivery(1);
        when(repository.tryAcquire(91L, CLAIM_TOKEN, "worker-1", Duration.ofMinutes(2)))
                .thenReturn(Optional.of(delivery));
        CommonInvoicePaymentNotificationClaimService service = service(Duration.ofMinutes(2));

        Optional<Delivery> result = service.tryClaim(91L);

        assertTrue(result.isPresent());
        assertEquals(delivery, result.get());
    }

    @Test
    void clientSuccessFinalizesInvoiceAndOutboxUnderSameClaim() {
        Delivery delivery = clientDelivery(1);
        when(repository.markClientInvoiceNotified(146L)).thenReturn(true);
        when(repository.markSent(91L, CLAIM_TOKEN)).thenReturn(true);
        CommonInvoicePaymentNotificationClaimService service = service(Duration.ofMinutes(2));

        assertTrue(service.markSent(delivery));

        verify(repository).markClientInvoiceNotified(146L);
        verify(repository).markSent(91L, CLAIM_TOKEN);
    }

    @Test
    void failureUsesExponentialRetryDelay() {
        Delivery delivery = clientDelivery(4);
        when(repository.markClientInvoiceFailed(146L, "telegram_not_sent")).thenReturn(true);
        when(repository.markFailed(91L, CLAIM_TOKEN, "telegram_not_sent", Duration.ofMinutes(8)))
                .thenReturn(true);
        CommonInvoicePaymentNotificationClaimService service = service(Duration.ofMinutes(2));

        assertTrue(service.markFailed(delivery, "telegram_not_sent"));

        verify(repository).markFailed(
                eq(91L),
                eq(CLAIM_TOKEN),
                eq("telegram_not_sent"),
                eq(Duration.ofMinutes(8))
        );
    }

    @Test
    void rejectsUnsafeLeaseConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service(Duration.ofHours(1))
        );
    }

    private CommonInvoicePaymentNotificationClaimService service(Duration lease) {
        return new CommonInvoicePaymentNotificationClaimService(
                repository,
                lease,
                () -> CLAIM_TOKEN,
                "worker-1"
        );
    }

    private Delivery clientDelivery(int attemptCount) {
        return new Delivery(
                91L,
                146L,
                NotificationKind.CLIENT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                attemptCount,
                CLAIM_TOKEN
        );
    }
}
