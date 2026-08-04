package com.hunt.otziv.payments.service;

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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
