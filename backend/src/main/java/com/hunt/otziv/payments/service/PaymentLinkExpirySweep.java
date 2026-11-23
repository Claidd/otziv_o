package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Expires payment links whose term has passed and which nobody started paying. Before this sweep such a link
 * expired only when someone opened its public page, so a banned or forgotten order kept a live link (and the
 * recipient's reserve) for months. No message is sent and no bank is called: only the link status changes and
 * its reserve is released. Links with a client report, a bank operation or a paper document are left alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLinkExpirySweep {

    static final int BATCH_SIZE = 50;

    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderPaymentLinkWorkflow orderPaymentLinkWorkflow;
    private long afterId;

    @Scheduled(
            fixedDelayString = "${otziv.payments.link-expiry.delay-ms:900000}",
            initialDelayString = "${otziv.payments.link-expiry.initial-delay-ms:180000}"
    )
    public void expirePastDueLinks() {
        try {
            run(LocalDateTime.now());
        } catch (RuntimeException unavailable) {
            log.warn("Payment link expiry sweep unavailable: errorType={}", unavailable.getClass().getSimpleName(), unavailable);
        }
    }

    /** Number of links expired by one bounded pass; the next pass continues after the last checked link. */
    int run(LocalDateTime now) {
        List<Long> ids = paymentLinkRepository.findPastDueUnstartedLinkIds(
                afterId,
                OrderPaymentLinkWorkflow.PAST_DUE_EXPIRABLE_STATUSES,
                now,
                PageRequest.of(0, BATCH_SIZE)
        );
        if (ids == null || ids.isEmpty()) {
            afterId = 0;
            return 0;
        }
        int expired = 0;
        for (Long id : ids) {
            afterId = id;
            try {
                if (orderPaymentLinkWorkflow.expirePastDueLink(id, now)) {
                    expired++;
                }
            } catch (RuntimeException held) {
                log.warn("Past-due payment link kept: linkId={}, errorType={}", id, held.getClass().getSimpleName(), held);
            }
        }
        if (ids.size() < BATCH_SIZE) {
            afterId = 0;
        }
        if (expired > 0) {
            log.info("Past-due payment links expired: {}", expired);
        }
        return expired;
    }
}
