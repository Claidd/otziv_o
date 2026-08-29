package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository.Delivery;
import com.hunt.otziv.payments.service.ManualPaymentRecipientTelegramNotificationService;
import com.hunt.otziv.payments.service.ManualPaymentRecipientTelegramNotificationService.CommonInvoiceRecipientNotification;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** At-least-once delivery worker shared by immediate recovery and retries. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoicePaymentNotificationDeliveryService {

    private static final int BATCH_SIZE = 50;

    private final CommonInvoicePaymentNotificationOutboxRepository repository;
    private final CommonInvoicePaymentNotificationClaimService claimService;
    private final CommonBillingService commonBillingService;
    private final ManualPaymentRecipientTelegramNotificationService recipientNotificationService;

    @Scheduled(
            fixedDelayString = "${common-billing.payment-notifications.fixed-delay:PT15S}",
            initialDelayString = "${common-billing.payment-notifications.initial-delay:PT30S}"
    )
    public void retryPendingNotifications() {
        int sent = retryBatch();
        if (sent > 0) {
            log.info("Common invoice payment notifications delivered: {}", sent);
        }
    }

    int retryBatch() {
        List<Long> ids = repository.findDueDeliveryIds(BATCH_SIZE);
        int sent = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            try {
                Optional<Delivery> claim = claimService.tryClaim(id);
                if (claim.isPresent() && deliver(claim.get())) {
                    sent++;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Common invoice payment notification attempt failed: deliveryId={}, errorType={}",
                        id,
                        exception.getClass().getSimpleName()
                );
            }
        }
        return sent;
    }

    private boolean deliver(Delivery delivery) {
        return switch (delivery.kind()) {
            case CLIENT -> deliverClient(delivery);
            case RECIPIENT -> deliverRecipient(delivery);
        };
    }

    private boolean deliverClient(Delivery delivery) {
        CommonBillingService.ClientPaymentNotificationAttempt attempt =
                commonBillingService.deliverPaymentSuccessNotificationFromOutbox(
                        delivery.invoiceId()
                );
        if (attempt.skipped()) {
            boolean finalized = claimService.markSkipped(delivery, limit(attempt.error()));
            if (!finalized) {
                log.warn("Common invoice client notification skip was fenced: deliveryId={}",
                        delivery.deliveryId());
            }
            return false;
        }
        if (attempt.sent()) {
            boolean finalized = claimService.markSent(delivery);
            if (!finalized) {
                log.warn("Common invoice client notification success was fenced: deliveryId={}",
                        delivery.deliveryId());
            }
            return finalized;
        }
        markFailed(delivery, attempt.error());
        return false;
    }

    private boolean deliverRecipient(Delivery delivery) {
        if (delivery.recipientType() == null
                || delivery.recipientUserId() == null
                || delivery.amountKopecks() == null
                || delivery.amountKopecks() <= 0) {
            boolean finalized = claimService.markSkipped(delivery, "recipient_snapshot_invalid");
            if (!finalized) {
                log.warn("Common invoice recipient notification skip was fenced: deliveryId={}",
                        delivery.deliveryId());
            }
            return false;
        }
        ClientMessageSendResult result = recipientNotificationService.notifyCommonInvoiceRecipient(
                new CommonInvoiceRecipientNotification(
                        delivery.invoiceId(),
                        delivery.invoiceTitle(),
                        delivery.orderCount(),
                        delivery.amountKopecks(),
                        delivery.recipientType(),
                        delivery.recipientUserId(),
                        delivery.actor(),
                        delivery.confirmedAt()
                )
        );
        if (result != null && result.sent()) {
            boolean finalized = claimService.markSent(delivery);
            if (!finalized) {
                log.warn("Common invoice recipient notification success was fenced: deliveryId={}",
                        delivery.deliveryId());
            }
            return finalized;
        }
        markFailed(delivery, resultError(result));
        return false;
    }

    private void markFailed(Delivery delivery, String error) {
        String clean = limit(error);
        if (!claimService.markFailed(delivery, clean)) {
            log.warn("Common invoice notification failure was fenced: deliveryId={}",
                    delivery.deliveryId());
        }
    }

    private String resultError(ClientMessageSendResult result) {
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

    private String limit(String value) {
        String clean = normalize(value);
        if (clean.isBlank()) {
            clean = "notification_delivery_failed";
        }
        return clean.length() <= 512 ? clean : clean.substring(0, 512);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
