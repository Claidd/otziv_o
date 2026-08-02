package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentSuccessNotificationRetryService {

    private static final int BATCH_SIZE = 50;

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentSuccessNotificationDeliveryService deliveryService;

    @Scheduled(
            fixedDelayString = "${otziv.payments.success-notification-retry.delay-ms:300000}",
            initialDelayString = "${otziv.payments.success-notification-retry.initial-delay-ms:180000}"
    )
    public void retryPendingSuccessNotifications() {
        int retried = retryBatch();
        if (retried > 0) {
            log.info("Payment success notification retry finished retried={}", retried);
        }
    }

    int retryBatch() {
        List<PaymentLink> candidates = paymentLinkRepository.findSuccessNotificationRetryCandidates(
                PageRequest.of(0, BATCH_SIZE)
        );
        int retried = 0;
        for (PaymentLink link : candidates) {
            try {
                if (link != null
                        && link.getId() != null
                        && deliveryService.tryDeliver(link.getId())) {
                    retried++;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Payment success notification retry failed: linkId={}, errorType={}",
                        link == null ? null : link.getId(),
                        exception.getClass().getSimpleName()
                );
            }
        }
        return retried;
    }
}
