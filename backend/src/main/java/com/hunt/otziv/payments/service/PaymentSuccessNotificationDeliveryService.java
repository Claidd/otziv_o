package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends payment-success notifications under the same durable lease for both
 * immediate and scheduled delivery. Database work is limited to short,
 * independent transactions; provider I/O happens after those transactions
 * have completed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentSuccessNotificationDeliveryService {

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentSuccessClientNotifier paymentSuccessClientNotifier;
    private final PaymentSuccessNotificationRetryClaimService claimService;
    private final PaymentLinkTransactionExecutor transactionExecutor;

    /**
     * Defers immediate delivery until the enclosing order/payment transaction
     * commits. A rollback leaves the notification invisible and therefore
     * unsent. If synchronization is unexpectedly unavailable, the durable
     * retry flag remains set for the scheduler instead of doing provider I/O
     * inside the active transaction.
     */
    public void deliverAfterCommit(Long paymentLinkId) {
        if (paymentLinkId == null || paymentLinkId <= 0) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                log.warn(
                        "Payment success notification deferred to retry because transaction synchronization is unavailable: linkId={}",
                        paymentLinkId
                );
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliverSafely(paymentLinkId);
                }
            });
            return;
        }
        deliverSafely(paymentLinkId);
    }

    /**
     * Attempts one at-least-once delivery. The durable claim is shared by the
     * direct and retry paths, so only one live lease owner reaches the provider.
     */
    public boolean tryDeliver(long paymentLinkId) {
        if (paymentLinkId <= 0) {
            return false;
        }

        final Optional<PaymentSuccessNotificationRetryClaimService.Claim> acquired;
        try {
            acquired = claimService.tryClaim(paymentLinkId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Payment success notification claim failed: linkId={}, errorType={}",
                    paymentLinkId,
                    exception.getClass().getSimpleName()
            );
            return false;
        }
        if (acquired.isEmpty()) {
            return false;
        }

        PaymentSuccessNotificationRetryClaimService.Claim claim = acquired.get();
        final PaymentLink link;
        try {
            link = transactionExecutor.required(() ->
                    paymentLinkRepository.findByIdWithOrder(paymentLinkId).orElse(null)
            );
        } catch (RuntimeException exception) {
            markFailed(claim, snapshotError(exception));
            log.warn(
                    "Payment success notification snapshot load failed: linkId={}",
                    paymentLinkId,
                    exception
            );
            return false;
        }
        if (link == null) {
            markFailed(claim, "payment_link_missing");
            return false;
        }

        final ClientMessageSendResult result;
        try {
            // The claim and snapshot transactions have both completed here.
            result = paymentSuccessClientNotifier.notifySuccess(link);
        } catch (Exception exception) {
            String error = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            markFailed(claim, limit(error, 512));
            log.warn(
                    "Payment success notification delivery failed: linkId={}, orderId={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    exception
            );
            return false;
        }

        if (result != null && result.sent()) {
            final boolean finalized;
            try {
                finalized = claimService.markSucceeded(claim);
            } catch (RuntimeException exception) {
                log.warn(
                        "Payment success notification success finalization failed: linkId={}, errorType={}",
                        link.getId(),
                        exception.getClass().getSimpleName()
                );
                return false;
            }
            if (!finalized) {
                log.warn(
                        "Payment success notification result was fenced: linkId={}",
                        link.getId()
                );
                return false;
            }
            link.setPaymentSuccessNotifiedAt(LocalDateTime.now());
            link.setPaymentSuccessNotificationError(null);
            link.setPaymentSuccessNotificationRetryEligible(false);
            log.info(
                    "Payment success notification sent: linkId={}, orderId={}, channel={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    result.channel()
            );
            return true;
        }

        String error = limit(paymentNotificationError(result), 512);
        markFailed(claim, error);
        link.setPaymentSuccessNotificationError(error);
        link.setPaymentSuccessNotificationRetryEligible(true);
        log.warn(
                "Payment success notification was not sent: linkId={}, orderId={}, error={}",
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                error
        );
        return false;
    }

    private void deliverSafely(long paymentLinkId) {
        try {
            tryDeliver(paymentLinkId);
        } catch (RuntimeException exception) {
            // A direct delivery is best effort after the business transaction
            // has committed. The durable retry flag must remain the recovery
            // mechanism instead of surfacing a false webhook/manual failure.
            log.warn(
                    "Payment success notification direct delivery deferred to retry: linkId={}, errorType={}",
                    paymentLinkId,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void markFailed(
            PaymentSuccessNotificationRetryClaimService.Claim claim,
            String error
    ) {
        try {
            if (!claimService.markFailed(claim, limit(error, 512))) {
                log.warn(
                        "Payment success notification failure was fenced: linkId={}",
                        claim.paymentLinkId()
                );
            }
        } catch (RuntimeException exception) {
            // retry_eligible was committed before claim acquisition. If failure
            // finalization is unavailable, expiry of the lease makes it
            // claimable again without losing the work item.
            log.warn(
                    "Payment success notification failure finalization failed: linkId={}, errorType={}",
                    claim.paymentLinkId(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String snapshotError(RuntimeException exception) {
        String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return limit("notification_snapshot_load_failed: " + detail, 512);
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
        if (clean.isBlank()) {
            clean = "notification_delivery_failed";
        }
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
