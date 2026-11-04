package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpecialistManualPaymentReviewSchedulerTest {
    @Test void finalizedNoticeFailureCleanupUsesBoundedSelectionAndNeverCreditsOrNotifiesAgain() {
        var reviews=mock(SpecialistManualPaymentReviewRepository.class);
        var reconciliation=mock(SpecialistManualPaymentReviewService.class);
        var settlement=mock(SpecialistManualPaymentReviewSettlementService.class);
        var page=org.springframework.data.domain.PageRequest.of(0,30);
        when(reviews.findFinalizedNoticeFailureIds(page)).thenReturn(List.of(31L));
        var worker=new SpecialistManualPaymentReviewScheduler(reviews,reconciliation,settlement);
        worker.run();
        verify(reviews).findFinalizedNoticeFailureIds(page);
        verify(settlement).settle(31L);
        verifyNoMoreInteractions(settlement);verifyNoInteractions(reconciliation);
    }
    @Test void bankReceiptResolvesFailedManualClaimWithoutRetryingItsCreditOrSendingAMessage() {
        var reviews = mock(SpecialistManualPaymentReviewRepository.class);
        var reconciliation = mock(SpecialistManualPaymentReviewService.class);
        var settlement = mock(SpecialistManualPaymentReviewSettlementService.class);
        when(reviews.findUncreditedBankConfirmedIds(anyCollection(), any())).thenReturn(List.of(119L));

        new SpecialistManualPaymentReviewScheduler(reviews, reconciliation, settlement).run();

        verify(reconciliation).supersedeOnConfirmedBankPayment(119L);
        verifyNoMoreInteractions(reconciliation);
        verifyNoInteractions(settlement);
    }
}
