package com.hunt.otziv.payments.dto;

public record ManagerManualCardPaymentResultResponse(
        String status,
        Long orderId,
        Long paymentLinkId,
        String message
) {
    public static final String COMPLETED = "COMPLETED";
    public static final String OWNER_APPROVAL_PENDING = "OWNER_APPROVAL_PENDING";

    public static ManagerManualCardPaymentResultResponse completed(Long orderId, Long paymentLinkId) {
        return new ManagerManualCardPaymentResultResponse(
                COMPLETED,
                orderId,
                paymentLinkId,
                "Оплата подтверждена"
        );
    }

    public static ManagerManualCardPaymentResultResponse ownerApprovalPending(Long orderId, Long paymentLinkId) {
        return new ManagerManualCardPaymentResultResponse(
                OWNER_APPROVAL_PENDING,
                orderId,
                paymentLinkId,
                "Запрос отправлен владельцу в Telegram. До подтверждения заказ не считается оплаченным."
        );
    }
}
