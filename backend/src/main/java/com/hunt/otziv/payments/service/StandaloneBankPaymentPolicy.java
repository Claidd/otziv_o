package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import java.util.Set;

/**
 * Shared fail-safe classification of standalone payment links that make an
 * automatic common-invoice TLS recovery unsafe.
 */
public final class StandaloneBankPaymentPolicy {

    private static final Set<PaymentLinkStatus> COMMON_INVOICE_TLS_RECOVERY_BLOCKING_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.PARTIAL_REFUNDED,
            PaymentLinkStatus.NEEDS_RECONCILIATION,
            PaymentLinkStatus.FAILED
    );

    private StandaloneBankPaymentPolicy() {
    }

    public static boolean blocksCommonInvoiceTlsRecovery(PaymentLink link) {
        return link != null
                && (COMMON_INVOICE_TLS_RECOVERY_BLOCKING_STATUSES.contains(link.getStatus())
                || hasStartedProviderPayment(link));
    }

    public static boolean hasStartedProviderPayment(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (hasText(link.getBankInitNonce())) {
            return true;
        }
        if (hasText(link.getBankCancelNonce()) || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
            return true;
        }
        if (!hasText(link.getTbankPaymentId())) {
            return false;
        }
        PaymentLinkStatus status = link.getStatus();
        if (status == PaymentLinkStatus.REJECTED
                || status == PaymentLinkStatus.REVERSED
                || status == PaymentLinkStatus.REFUNDED) {
            return false;
        }
        if (status == PaymentLinkStatus.CANCELED || status == PaymentLinkStatus.EXPIRED) {
            return hasText(link.getLastError());
        }
        return true;
    }

    /**
     * A route can be retired automatically only while it is still a purely
     * local placeholder. Any customer trace, provider identity, manual route
     * or in-flight reservation makes the outcome ambiguous and requires a
     * normal reconciliation instead.
     */
    public static boolean canAutoCloseForCommonInvoice(PaymentLink link) {
        if (link == null
                || link.getStatus() != PaymentLinkStatus.CREATED
                || (link.getPaymentMethod() != PaymentMethod.BANK_FORM
                && link.getPaymentMethod() != PaymentMethod.SBP_QR)) {
            return false;
        }
        return !hasText(link.getTbankPaymentId())
                && !hasText(link.getTbankOrderId())
                && !hasText(link.getTbankTerminalKey())
                && !hasText(link.getPaymentUrl())
                && !hasText(link.getSbpQrPayload())
                && !hasText(link.getSbpQrImage())
                && !hasText(link.getSbpQrDataType())
                && link.getSbpQrCreatedAt() == null
                && !hasText(link.getBankInitNonce())
                && link.getBankInitLeaseUntil() == null
                && !hasText(link.getBankCancelNonce())
                && link.getBankCancelLeaseUntil() == null
                && link.getBankCancelOriginStatus() == null
                && !hasText(link.getBankCancelOriginError())
                && link.getInitiatedAt() == null
                && link.getPaidAt() == null
                && link.getBankReconciliationAttemptedAt() == null
                && !hasText(link.getProviderTerminalStatus())
                && !hasText(link.getLastError())
                && link.getManualSource() == null
                && link.getManualPaymentTask() == null
                && link.getManualPaymentType() == null
                && !hasText(link.getManualPhone())
                && !hasText(link.getManualRecipientName())
                && !hasText(link.getManualPaymentUrl())
                && !hasText(link.getManualPaymentButtonLabel())
                && !hasText(link.getManualComment())
                && link.getManualReportedAt() == null
                && link.getManualConfirmedAt() == null
                && !hasText(link.getManualConfirmedBy())
                && link.getOfferConsentAt() == null
                && link.getPrivacyConsentAt() == null
                && link.getReceiptConsentAt() == null
                && !hasText(link.getConsentIp())
                && !hasText(link.getConsentUserAgent());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
