package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.time.LocalDateTime;
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
        for (Long id : reviews.findRetryableIds(
                SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,
                LocalDateTime.now().minusMinutes(1), PageRequest.of(0, BATCH_SIZE))) {
            try {
                notifications.deliver(id);
            } catch (RuntimeException failure) {
                log.warn("Specialist payment review notification failed: reviewId={}", id, failure);
            }
        }
        for (Long id : reviews.findDueIds(
                SpecialistManualPaymentReviewStatus.WAITING, LocalDateTime.now(),
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, BATCH_SIZE))) {
            try {
                if (!notifications.supersedeOnConfirmedBankPayment(id)) {
                    settlement.settle(id);
                }
            } catch (RuntimeException failure) {
                log.warn("Specialist payment review settlement failed: reviewId={}", id, failure);
            }
        }
        // Recover a process killed after claiming a row but before recording its result.
        for (Long id : reviews.findRetryableIds(
                SpecialistManualPaymentReviewStatus.SETTLING,
                LocalDateTime.now().minusMinutes(15), PageRequest.of(0, BATCH_SIZE))) {
            try {
                settlement.settle(id);
            } catch (RuntimeException failure) {
                log.warn("Specialist payment review recovery failed: reviewId={}", id, failure);
            }
        }
    }
}
