package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialistManualPaymentReviewScheduler {

    private static final int BATCH_SIZE = 30;

    private final SpecialistManualPaymentReviewRepository reviews;
    private final SpecialistManualPaymentReviewService notifications;
    private final SpecialistManualPaymentReviewSettlementService settlement;

    @Scheduled(fixedDelayString = "${otziv.payments.specialist-review-delay-ms:60000}")
    public void run() {
        for (Long id : reviews.findCommittedClaimIds(LocalDateTime.now().minusMinutes(1), PageRequest.of(0,BATCH_SIZE))) {
            try { settlement.creditNow(id); }
            catch (RuntimeException failure) { log.warn("Committed payment review recovery failed: reviewId={}",id,failure); }
        }
        for (Long id : reviews.findRetryableIds(
                SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,
                LocalDateTime.now().minusMinutes(1), PageRequest.of(0, BATCH_SIZE))) {
            try {
                notifications.deliver(id);
            } catch (RuntimeException failure) {
                log.warn("Specialist payment review notification failed: reviewId={}", id, failure);
            }
        }
        for (Long id : reviews.findUncreditedIds(List.of(
                SpecialistManualPaymentReviewStatus.PENDING_DELIVERY, SpecialistManualPaymentReviewStatus.WAITING,
                SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION, SpecialistManualPaymentReviewStatus.SETTLING),
                LocalDateTime.now().minusMinutes(15), PageRequest.of(0, BATCH_SIZE))) {
            try {
                settlement.creditNow(id);
            } catch (RuntimeException failure) {
                log.warn("Manager reported payment needs reconciliation: reviewId={}", id, failure);
            }
        }
        for (Long id : reviews.findCreditedDueIds(List.of(
                SpecialistManualPaymentReviewStatus.PENDING_DELIVERY, SpecialistManualPaymentReviewStatus.WAITING,
                SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION, SpecialistManualPaymentReviewStatus.SETTLING),
                LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE))) {
            try {
                settlement.settle(id);
            } catch (RuntimeException failure) {
                log.warn("Manager payment finalisation failed: reviewId={}", id, failure);
            }
        }
    }
}
