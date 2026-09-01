package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.review_recovery.event.ReviewRecoveryReleasedEvent;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Finishes bank-confirmed prepayments after the work-completion gate opens.
 *
 * <p>The release event provides the fast path.  The bounded startup/scheduled
 * scan is the durable fallback when the process stopped after the payment or
 * an asynchronous listener temporarily failed. The query only selects orders
 * whose stable payment age and completion/recovery gates are ready. Unrelated
 * notification writes therefore cannot delay eligibility. A failed attempt
 * updates the link timestamp only as a queue-priority rotation, letting the
 * bounded oldest-first scan continue with other candidates.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmedPrepaymentRecoveryService {

    private static final int BATCH_SIZE = 50;
    private static final int RETRY_MINUTES = 5;

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentLinkService paymentLinkService;

    @Async("orderPaymentPostCommitExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewRecoveryReleased(ReviewRecoveryReleasedEvent event) {
        if (event == null || event.orderId() == null || event.orderId() <= 0) {
            return;
        }
        try {
            paymentLinkService.applyConfirmedPrepaymentIfReady(event.orderId());
        } catch (RuntimeException failure) {
            // The scheduled scan below remains the durable retry path.
            log.warn(
                    "Immediate confirmed prepayment recovery failed after review release: orderId={}",
                    event.orderId(),
                    failure
            );
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            recoverReadyPrepayments();
        } catch (RuntimeException failure) {
            log.error("Startup confirmed prepayment recovery failed", failure);
        }
    }

    @Scheduled(
            fixedDelayString = "${otziv.payments.prepayment-recovery.delay-ms:300000}",
            initialDelayString = "${otziv.payments.prepayment-recovery.initial-delay-ms:120000}"
    )
    public int recoverReadyPrepayments() {
        LocalDateTime attemptBefore = LocalDateTime.now().minusMinutes(RETRY_MINUTES);
        List<Long> orderIds = paymentLinkRepository.findConfirmedPrepaymentRecoveryOrderIds(
                PaymentLinkService.PREPAID_WAITING_ORDER_COMPLETION,
                attemptBefore,
                PageRequest.of(0, BATCH_SIZE)
        );
        int applied = 0;
        for (Long orderId : orderIds) {
            try {
                if (paymentLinkService.applyConfirmedPrepaymentIfReady(orderId)) {
                    applied++;
                }
            } catch (RuntimeException failure) {
                log.warn("Scheduled confirmed prepayment recovery failed: orderId={}", orderId, failure);
            }
        }
        if (!orderIds.isEmpty()) {
            log.info(
                    "Confirmed prepayment recovery finished: checked={}, applied={}",
                    orderIds.size(),
                    applied
            );
        }
        return applied;
    }
}
