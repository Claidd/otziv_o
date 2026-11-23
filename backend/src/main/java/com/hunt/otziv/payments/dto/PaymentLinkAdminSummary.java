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
        Long rejected,
        Long receiptPending,
        Long receiptOverdue,
        Long active,
        Long created,
        Long manual,
        Long failed
) {
    /** Older call sites and the empty page: the per-filter counts are zero. */
    public PaymentLinkAdminSummary(Long totalElements, Long totalAmountKopecks, Long paid, Long manualPending,
            Long confirmed, Long notificationsSent, Long notificationErrors, Long refundable, Long refunded,
            Long rejected, Long receiptPending, Long receiptOverdue) {
        this(totalElements, totalAmountKopecks, paid, manualPending, confirmed, notificationsSent, notificationErrors,
                refundable, refunded, rejected, receiptPending, receiptOverdue, 0L, 0L, 0L, 0L);
    }

    private static long safe(Long value) {
        return value == null ? 0 : value;
    }

    public long safeActive() { return safe(active); }

    public long safeCreated() { return safe(created); }

    public long safeManual() { return safe(manual); }

    public long safeFailed() { return safe(failed); }

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

    public long safeReceiptPending() {
        return receiptPending == null ? 0 : receiptPending;
    }

    public long safeReceiptOverdue() {
        return receiptOverdue == null ? 0 : receiptOverdue;
    }
}
