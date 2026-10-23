package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import java.time.LocalDateTime;

/** Manager-safe receipt state; no bank details, receipt URLs or recipient identifiers. */
public record ManualPaymentReviewView(Long id, Long orderId, String status,
        LocalDateTime deadlineAt, LocalDateTime creditedAt, String confirmationBasis,
        boolean disputeAllowed) {
    public static ManualPaymentReviewView from(SpecialistManualPaymentReview review) {
        String basis = "ADMIN_VERIFIED_RECEIPT".equals(review.getResolutionKind()) ? "RECIPIENT_STATEMENT"
                : "NO_OBJECTION_24H".equals(review.getResolutionKind()) ? "AUTO_NO_OBJECTION"
                : review.getCreditedAt() != null ? "MANAGER_REPORTED" : null;
        boolean allowed = review.getStatus() != SpecialistManualPaymentReviewStatus.CONFIRMED
                && review.getStatus() != SpecialistManualPaymentReviewStatus.SUPERSEDED
                && review.getStatus() != SpecialistManualPaymentReviewStatus.DISPUTED
                && review.getStatus() != SpecialistManualPaymentReviewStatus.ADMIN_SETTLING
                && review.getStatus() != SpecialistManualPaymentReviewStatus.SETTLING
                && review.getDeadlineAt() != null && LocalDateTime.now().isBefore(review.getDeadlineAt());
        return new ManualPaymentReviewView(review.getId(), review.getOrderId(), review.getStatus().name(),
                review.getDeadlineAt(), review.getCreditedAt(), basis, allowed);
    }
}
