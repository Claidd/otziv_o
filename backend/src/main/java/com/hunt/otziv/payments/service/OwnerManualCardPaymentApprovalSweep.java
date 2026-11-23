package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Closes owner card-payment requests that became moot because the order is already paid: by another confirmed
 * payment after the original link was cancelled, or by the bank on the same link. The owner no longer has to
 * answer them; nothing is credited, reversed or sent. A request of an unpaid order always waits for the owner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerManualCardPaymentApprovalSweep {

    static final int BATCH_SIZE = 50;

    private final OwnerManualCardPaymentApprovalRepository approvals;
    private final OwnerManualCardPaymentApprovalReconciliationService reconciliation;

    @Scheduled(
            fixedDelayString = "${otziv.payments.owner-approval-sweep.delay-ms:1800000}",
            initialDelayString = "${otziv.payments.owner-approval-sweep.initial-delay-ms:240000}"
    )
    public void closeMootRequests() {
        try {
            run();
        } catch (RuntimeException unavailable) {
            log.warn("Owner card approval sweep unavailable: errorType={}", unavailable.getClass().getSimpleName(), unavailable);
        }
    }

    int run() {
        List<Long> ids = approvals.findIdsOnPaidOrders(
                OwnerManualCardPaymentApprovalStatus.PENDING, PageRequest.of(0, BATCH_SIZE));
        int closed = 0;
        for (Long id : ids) {
            try {
                if (reconciliation.closeSupersededAutomatically(id).filter(result -> !result.alreadyClosed()).isPresent()) {
                    closed++;
                    log.info("Owner card approval closed as moot: approvalId={}", id);
                }
            } catch (RuntimeException held) {
                log.warn("Owner card approval kept: approvalId={}, errorType={}", id, held.getClass().getSimpleName(), held);
            }
        }
        return closed;
    }
}
