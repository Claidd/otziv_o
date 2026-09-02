package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Canonical policy for the durable payment-return recovery marker.
 *
 * <p>The marker is a tuple rather than three independent optional values.  A
 * partial or unknown tuple must never be interpreted as "not processed": that
 * could apply the financial rollback for a second time.</p>
 */
public final class PaymentReturnRecoveryState {

    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_STALE_PAYMENT_CYCLE = "STALE_PAYMENT_CYCLE";
    public static final String OUTCOME_MANUAL_RECONCILIATION = "MANUAL_RECONCILIATION";
    public static final String OUTCOME_APPLIED_MANUALLY = "APPLIED_MANUALLY";
    public static final String OUTCOME_ACCEPTED_NOOP = "ACCEPTED_NOOP";

    private static final Set<PaymentLinkStatus> FULL_RETURN_STATUSES = EnumSet.of(
            PaymentLinkStatus.CANCELED,
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.REFUNDED
    );

    private PaymentReturnRecoveryState() {
    }

    public static boolean isFullReturn(PaymentLinkStatus status) {
        return status != null && FULL_RETURN_STATUSES.contains(status);
    }

    public static boolean hasLinkSpecificSettledEvidence(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (positive(link.getConfirmedAmountKopecks())
                || link.getPaidAt() != null
                || link.getManualConfirmedAt() != null
                || link.getStatus() == PaymentLinkStatus.REFUNDED) {
            return true;
        }
        PaymentLinkStatus origin = link.getBankCancelOriginStatus();
        return origin == PaymentLinkStatus.MANUAL_REPORTED
                || origin == PaymentLinkStatus.TEST_CONFIRMED
                || origin == PaymentLinkStatus.CONFIRMED
                || origin == PaymentLinkStatus.AMOUNT_MISMATCH
                || origin == PaymentLinkStatus.NEEDS_RECONCILIATION;
    }

    public static boolean isMarkerEmpty(PaymentLink link) {
        return link != null
                && link.getReturnRecoveryProcessedAt() == null
                && link.getReturnRecoveryPaymentCheckId() == null
                && link.getReturnRecoveryOutcome() == null
                && link.getReturnRecoveryResolvedAt() == null
                && link.getReturnRecoveryResolvedBy() == null
                && link.getReturnRecoveryResolutionReason() == null;
    }

    public static boolean isValidMarkerTuple(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (isMarkerEmpty(link)) {
            return true;
        }
        if (link.getReturnRecoveryProcessedAt() == null || link.getReturnRecoveryOutcome() == null) {
            return false;
        }
        if (link.getReturnRecoveryPaymentCheckId() != null
                && link.getReturnRecoveryPaymentCheckId() <= 0) {
            return false;
        }
        String outcome = link.getReturnRecoveryOutcome();
        boolean hasResolutionAudit = link.getReturnRecoveryResolvedAt() != null
                && hasText(link.getReturnRecoveryResolvedBy())
                && hasText(link.getReturnRecoveryResolutionReason());
        boolean hasNoResolutionAudit = link.getReturnRecoveryResolvedAt() == null
                && link.getReturnRecoveryResolvedBy() == null
                && link.getReturnRecoveryResolutionReason() == null;

        return switch (outcome) {
            case OUTCOME_APPLIED -> link.getReturnRecoveryPaymentCheckId() != null && hasNoResolutionAudit;
            case OUTCOME_STALE_PAYMENT_CYCLE, OUTCOME_MANUAL_RECONCILIATION -> hasNoResolutionAudit;
            case OUTCOME_APPLIED_MANUALLY, OUTCOME_ACCEPTED_NOOP -> hasResolutionAudit;
            default -> false;
        };
    }

    public static boolean isResolvedOutcome(String outcome) {
        return OUTCOME_APPLIED_MANUALLY.equals(outcome) || OUTCOME_ACCEPTED_NOOP.equals(outcome);
    }

    public static void markProcessed(PaymentLink link, Long paymentCheckId, String outcome) {
        if (link == null) {
            throw new IllegalArgumentException("Payment link is required");
        }
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(paymentCheckId);
        link.setReturnRecoveryOutcome(outcome);
        link.setReturnRecoveryResolvedAt(null);
        link.setReturnRecoveryResolvedBy(null);
        link.setReturnRecoveryResolutionReason(null);
    }

    private static boolean positive(Long amount) {
        return amount != null && amount > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
