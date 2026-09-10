package com.hunt.otziv.config.api;

import com.hunt.otziv.client_messages.api.DeliveryOperation;
import org.springframework.http.HttpStatus;

/** Legacy clients equate every 2xx command response with provider confirmation. */
public final class DeliveryResponseCompatibility {
    public static final String HEADER = "X-Otziv-Delivery-Protocol";
    public static final String QUEUED_V1 = "queued-v1";

    private DeliveryResponseCompatibility() {}

    /** Call after the owner's enqueue transaction has committed, never inside that transaction. */
    public static void requireUnderstoodOutcome(String protocol, DeliveryOperation operation) {
        if (QUEUED_V1.equals(protocol)) return;
        if (operation != null && "SENT".equals(operation.status()) && operation.errorCode() == null) return;
        String status = operation == null ? "UNKNOWN" : operation.status();
        String message = switch (status) {
            case "QUEUED", "SENDING", "RETRYABLE" ->
                    "Сообщение сохранено в очереди. Подтверждения отправки ещё нет. Повторно отправлять не нужно; обновите экран позже.";
            case "FAILED" ->
                    "Отправка сообщения не завершена. Откройте актуальную веб-версию, чтобы проверить состояние операции.";
            default ->
                    "Результат отправки требует проверки. Не отправляйте сообщение повторно; проверьте состояние в актуальной веб-версии.";
        };
        throw new CodedResponseStatusException(HttpStatus.CONFLICT, "DELIVERY_CONFIRMATION_PENDING", message);
    }
}
