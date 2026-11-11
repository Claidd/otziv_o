package com.hunt.otziv.payments.service;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistManualPaymentReviewTelegramCallbackServiceTest {
    private final SpecialistManualPaymentReviewService reviews = mock(SpecialistManualPaymentReviewService.class);
    private final CommonInvoicePaymentDisputeService commonInvoiceDisputes = mock(CommonInvoicePaymentDisputeService.class);
    private final SpecialistManualPaymentReviewTelegramCallbackService callback =
            new SpecialistManualPaymentReviewTelegramCallbackService(reviews, commonInvoiceDisputes);

    @Test
    void groupCallbackUsesClickingUserIdentityAndGroupSeparately() {
        when(reviews.dispute(101L, 777L, -10077L)).thenReturn("Спор сохранён");
        assertEquals(Optional.of("Спор сохранён"), callback.handle("specialist-payment-dispute:101", 777L, -10077L));
    }

    @Test
    void anonymousCallbacksCannotChangePaymentReview() {
        callback.handle("specialist-payment-dispute:101", null, -10077L);
        callback.handle("specialist-payment-dispute:101", 777L, null);
        assertTrue(callback.handle("other", 777L, -10077L).isEmpty());
        verifyNoInteractions(reviews);
    }

    @Test
    void commonInvoiceReceiptButtonGoesToItsOwnDisputeAndNeverToTheOrderReview() {
        when(commonInvoiceDisputes.dispute(55L, 777L, -10077L)).thenReturn("Спор сохранён");
        assertEquals(Optional.of("Спор сохранён"), callback.handle("common-payment-dispute:55", 777L, -10077L));
        assertEquals(Optional.of("Повреждённый запрос оплаты"), callback.handle("common-payment-dispute:x", 777L, -10077L));
        assertEquals(Optional.of("Не удалось определить участника или чат"),
                callback.handle("common-payment-dispute:55", null, -10077L));
        verify(commonInvoiceDisputes, times(1)).dispute(55L, 777L, -10077L);
        verifyNoInteractions(reviews);
    }

    @Test
    void commonInvoiceDisputeRefusalIsShownToThePersonWhoPressed() {
        when(commonInvoiceDisputes.dispute(55L, 777L, -10077L)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "Оспорить поступление может только указанный получатель"));
        assertEquals(Optional.of("Оспорить поступление может только указанный получатель"),
                callback.handle("common-payment-dispute:55", 777L, -10077L));
    }
}
