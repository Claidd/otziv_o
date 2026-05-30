package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentSuccessNotificationRetryService {

    private static final int BATCH_SIZE = 50;

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentSuccessClientNotifier paymentSuccessClientNotifier;

    @Scheduled(
            fixedDelayString = "${otziv.payments.success-notification-retry.delay-ms:300000}",
            initialDelayString = "${otziv.payments.success-notification-retry.initial-delay-ms:180000}"
    )
    @Transactional
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
            if (retry(link)) {
                retried++;
            }
        }
        return retried;
    }

    private boolean retry(PaymentLink link) {
        try {
            ClientMessageSendResult result = paymentSuccessClientNotifier.notifySuccess(link);
            if (result != null && result.sent()) {
                link.setPaymentSuccessNotifiedAt(LocalDateTime.now());
                link.setPaymentSuccessNotificationError(null);
                paymentLinkRepository.save(link);
                log.info(
                        "Payment success notification retry sent: linkId={}, orderId={}, channel={}",
                        link.getId(),
                        link.getOrder() == null ? null : link.getOrder().getId(),
                        result.channel()
                );
                return true;
            }

            String error = paymentNotificationError(result);
            link.setPaymentSuccessNotificationError(limit(error, 512));
            paymentLinkRepository.save(link);
            log.warn(
                    "Payment success notification retry was not sent: linkId={}, orderId={}, error={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    error
            );
        } catch (Exception e) {
            String error = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            link.setPaymentSuccessNotificationError(limit(error, 512));
            paymentLinkRepository.save(link);
            log.warn(
                    "Payment success notification retry failed: linkId={}, orderId={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    e
            );
        }
        return false;
    }

    private String paymentNotificationError(ClientMessageSendResult result) {
        if (result == null) {
            return "notification_result_empty";
        }
        String code = normalize(result.errorCode());
        String message = normalize(result.errorMessage());
        if (code.isBlank()) {
            return message.isBlank() ? "notification_not_sent" : message;
        }
        return message.isBlank() ? code : code + ": " + message;
    }

    private String limit(String value, int maxLength) {
        String clean = normalize(value);
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
