package com.hunt.otziv.payments.dto;

public record ManagerManualCardPaymentResultResponse(
        String status,
        Long orderId,
        Long paymentLinkId,
        String message,
        ManualPaymentReviewView manualPaymentReview
) {
    public ManagerManualCardPaymentResultResponse(String status, Long orderId, Long paymentLinkId, String message) {
        this(status, orderId, paymentLinkId, message, null);
    }

    public static ManagerManualCardPaymentResultResponse credited(Long orderId, Long linkId, ManualPaymentReviewView review) {
        String message = "CONFIRMED".equals(review.status()) ? "Оплата окончательно подтверждена"
                : "DISPUTED".equals(review.status()) ? "Оплата учтена. Спор зарегистрирован; окончательное подтверждение остановлено"
                : "Оплата учтена сразу. Специалист или получатель может сообщить менеджеру о споре в течение 24 часов от отметки; без спора подтверждение станет окончательным.";
        return new ManagerManualCardPaymentResultResponse(COMPLETED, orderId, linkId, message, review);
    }

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
                "Отметка сохранена, но зачисление ещё не завершено. Требуется сверка исходного платежа."
        );
    }
}
