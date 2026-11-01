package com.hunt.otziv.payments.service;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistManualPaymentReviewTelegramCallbackServiceTest {
    private final SpecialistManualPaymentReviewService reviews = mock(SpecialistManualPaymentReviewService.class);
    private final SpecialistManualPaymentReviewTelegramCallbackService callback = new SpecialistManualPaymentReviewTelegramCallbackService(reviews);

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
}
