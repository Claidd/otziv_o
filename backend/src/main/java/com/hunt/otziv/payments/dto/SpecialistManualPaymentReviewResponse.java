package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import java.time.LocalDateTime;

/** Operational state; receipt links and Telegram identifiers are deliberately omitted. */
public record SpecialistManualPaymentReviewResponse(
        Long id,
        Long orderId,
        Long paymentLinkId,
        long amountKopecks,
        Long recipientUserId,
        SpecialistManualPaymentReviewStatus status,
        LocalDateTime requestedAt,
        LocalDateTime notifiedAt,
        LocalDateTime deadlineAt,
        String lastError,
        boolean retryNotificationAllowed,
        LocalDateTime creditedAt,
        String confirmationBasis
) {
    public SpecialistManualPaymentReviewResponse(Long id, Long orderId, Long paymentLinkId, long amountKopecks,
            Long recipientUserId, SpecialistManualPaymentReviewStatus status, LocalDateTime requestedAt,
            LocalDateTime notifiedAt, LocalDateTime deadlineAt, String lastError, boolean retryNotificationAllowed) {
        this(id, orderId, paymentLinkId, amountKopecks, recipientUserId, status, requestedAt,
                notifiedAt, deadlineAt, lastError, retryNotificationAllowed, null, null);
    }

    public static SpecialistManualPaymentReviewResponse from(SpecialistManualPaymentReview review) {
        LocalDateTime now = LocalDateTime.now();
        boolean retryAllowed = review.getStatus() == SpecialistManualPaymentReviewStatus.PENDING_DELIVERY
                && review.getRequestedAt() != null
                && now.isBefore(review.getRequestedAt().plusMinutes(5))
                && review.getDeadlineAt() != null && now.isBefore(review.getDeadlineAt())
                && (review.getLastAttemptAt() == null || review.getLastError() != null);
        return new SpecialistManualPaymentReviewResponse(review.getId(), review.getOrderId(),
                review.getPaymentLinkId(), review.getAmountKopecks(), review.getRecipientUserId(),
                review.getStatus(), review.getRequestedAt(), review.getNotifiedAt(),
                review.getDeadlineAt(), review.getLastError(), retryAllowed,
                review.getCreditedAt(), ManualPaymentReviewView.from(review).confirmationBasis());
    }
}
