package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recovers bank status updates when a webhook was delayed or lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentBankStatusReconciliationService {

    private static final Set<PaymentLinkStatus> RECONCILABLE_STATUSES = Set.of(
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.NEEDS_RECONCILIATION,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.PARTIAL_REFUNDED
    );
    private static final int BATCH_SIZE = 50;

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentLinkService paymentLinkService;

    @Scheduled(
            fixedDelayString = "${otziv.payments.bank-reconciliation.delay-ms:300000}",
            initialDelayString = "${otziv.payments.bank-reconciliation.initial-delay-ms:120000}"
    )
    public void reconcileStaleBankPayments() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> expiredInitReservations = paymentLinkRepository.findExpiredBankInitReservationIds(
                now,
                PageRequest.of(0, BATCH_SIZE)
        );
        for (Long linkId : expiredInitReservations) {
            try {
                paymentLinkService.recoverExpiredBankInitReservation(linkId, now);
            } catch (RuntimeException exception) {
                log.warn("Expired T-Bank Init reservation recovery failed: linkId={}", linkId, exception);
            }
        }

        LocalDateTime attemptBefore = now.minusMinutes(5);
        List<Long> candidates = paymentLinkRepository.findBankReconciliationCandidateIds(
                RECONCILABLE_STATUSES,
                attemptBefore,
                attemptBefore,
                PageRequest.of(0, BATCH_SIZE)
        );
        int changed = 0;
        for (Long linkId : candidates) {
            try {
                if (paymentLinkService.reconcileBankLink(linkId, attemptBefore)) {
                    changed++;
                }
            } catch (RuntimeException exception) {
                log.warn("Scheduled T-Bank reconciliation failed: linkId={}", linkId, exception);
            }
        }
        if (!candidates.isEmpty()) {
            log.info("Scheduled T-Bank reconciliation finished: checked={}, changed={}", candidates.size(), changed);
        }
    }
}
