package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Recovers committed route-change notifications missed by the fast path. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRouteChangeNotificationRetryService {

    private static final int BATCH_SIZE = 50;

    private final PaymentRouteChangeNotificationOutboxRepository repository;
    private final PaymentRouteChangeNotificationWorker worker;

    @Scheduled(
            fixedDelayString = "${otziv.payments.route-change-notification.delay-ms:15000}",
            initialDelayString = "${otziv.payments.route-change-notification.initial-delay-ms:30000}"
    )
    public void retryPendingNotifications() {
        List<Long> paymentLinkIds = repository.findDuePaymentLinkIds(BATCH_SIZE);
        int delivered = 0;
        for (Long paymentLinkId : paymentLinkIds) {
            if (paymentLinkId == null) {
                continue;
            }
            try {
                if (worker.send(paymentLinkId)) {
                    delivered++;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Payment route-change notification retry failed: paymentLinkId={}, errorType={}",
                        paymentLinkId,
                        exception.getClass().getSimpleName()
                );
            }
        }
        if (delivered > 0) {
            log.info("Payment route-change notifications delivered: {}", delivered);
        }
    }
}
