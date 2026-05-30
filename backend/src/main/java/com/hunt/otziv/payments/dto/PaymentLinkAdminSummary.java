package com.hunt.otziv.payments.dto;

public record PaymentLinkAdminSummary(
        Long totalElements,
        Long totalAmountKopecks,
        Long paid,
        Long manualPending,
        Long confirmed,
        Long notificationsSent,
        Long notificationErrors,
        Long refundable,
        Long refunded,
        Long rejected
) {
    public long safeTotalElements() {
        return totalElements == null ? 0 : totalElements;
    }

    public long safeTotalAmountKopecks() {
        return totalAmountKopecks == null ? 0 : totalAmountKopecks;
    }

    public long safePaid() {
        return paid == null ? 0 : paid;
    }

    public long safeManualPending() {
        return manualPending == null ? 0 : manualPending;
    }

    public long safeConfirmed() {
        return confirmed == null ? 0 : confirmed;
    }

    public long safeNotificationsSent() {
        return notificationsSent == null ? 0 : notificationsSent;
    }

    public long safeNotificationErrors() {
        return notificationErrors == null ? 0 : notificationErrors;
    }

    public long safeRefundable() {
        return refundable == null ? 0 : refundable;
    }

    public long safeRefunded() {
        return refunded == null ? 0 : refunded;
    }

    public long safeRejected() {
        return rejected == null ? 0 : rejected;
    }
}
