package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentBankReconciliationCandidateView;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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
        List<PaymentBankReconciliationCandidateView> statusCandidates =
                paymentLinkRepository.findStatusBankReconciliationCandidates(
                RECONCILABLE_STATUSES,
                attemptBefore,
                attemptBefore,
                PageRequest.of(0, BATCH_SIZE)
        );
        List<PaymentBankReconciliationCandidateView> cancelCandidates =
                paymentLinkRepository.findCancelBankReconciliationCandidates(
                        attemptBefore,
                        attemptBefore,
                        PageRequest.of(0, BATCH_SIZE)
                );
        List<Long> candidates = mergeCandidates(statusCandidates, cancelCandidates);
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

    private List<Long> mergeCandidates(
            List<PaymentBankReconciliationCandidateView> statusCandidates,
            List<PaymentBankReconciliationCandidateView> cancelCandidates
    ) {
        Map<Long, PaymentBankReconciliationCandidateView> uniqueById = new LinkedHashMap<>();
        Stream.concat(statusCandidates.stream(), cancelCandidates.stream())
                .filter(candidate -> candidate != null && candidate.getId() != null)
                .forEach(candidate -> uniqueById.putIfAbsent(candidate.getId(), candidate));

        Comparator<PaymentBankReconciliationCandidateView> oldestFirst = Comparator
                .comparing(
                        PaymentBankReconciliationCandidateView::getAttemptedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                )
                .thenComparing(
                        PaymentBankReconciliationCandidateView::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                )
                .thenComparing(PaymentBankReconciliationCandidateView::getId);
        return uniqueById.values().stream()
                .sorted(oldestFirst)
                .limit(BATCH_SIZE)
                .map(PaymentBankReconciliationCandidateView::getId)
                .toList();
    }
}
