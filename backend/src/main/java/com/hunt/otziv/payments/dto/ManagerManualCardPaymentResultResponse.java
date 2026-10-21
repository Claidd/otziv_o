package com.hunt.otziv.payments.dto;

public record ManagerManualCardPaymentResultResponse(
        String status,
        Long orderId,
        Long paymentLinkId,
        String message
) {
    public static final String COMPLETED = "COMPLETED";
    public static final String OWNER_APPROVAL_PENDING = "OWNER_APPROVAL_PENDING";
    public static final String SPECIALIST_REVIEW_PENDING = "SPECIALIST_REVIEW_PENDING";

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

    public static ManagerManualCardPaymentResultResponse specialistReviewPending(Long orderId, Long paymentLinkId) {
        return new ManagerManualCardPaymentResultResponse(
                SPECIALIST_REVIEW_PENDING,
                orderId,
                paymentLinkId,
                "Запрос на проверку поступления сохранён. Отсчёт 24 часов идёт с отметки менеджера. До подтверждённой отправки специалисту и завершения проверки заказ не считается оплаченным."
        );
    }
}
