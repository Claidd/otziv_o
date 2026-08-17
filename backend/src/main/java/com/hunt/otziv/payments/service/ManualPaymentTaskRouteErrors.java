package com.hunt.otziv.payments.service;

import com.hunt.otziv.config.api.CodedResponseStatusException;
import org.springframework.http.HttpStatus;

/** Stable, PII-free errors shared by ordinary and common manual-payment APIs. */
public final class ManualPaymentTaskRouteErrors {

    public static final String ACTUAL_RECIPIENT_REQUIRED = "ACTUAL_RECIPIENT_REQUIRED";
    public static final String PAYMENT_ROUTE_STALE = "PAYMENT_ROUTE_STALE";
    public static final String TASK_TARGET_UNRESOLVED = "TASK_TARGET_UNRESOLVED";

    private ManualPaymentTaskRouteErrors() {
    }

    public static CodedResponseStatusException actualRecipientRequired() {
        return new CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ACTUAL_RECIPIENT_REQUIRED,
                "Выберите фактического получателя оплаты и обновите приложение, если список не отображается"
        );
    }

    public static CodedResponseStatusException stale() {
        return new CodedResponseStatusException(
                HttpStatus.CONFLICT,
                PAYMENT_ROUTE_STALE,
                "Платёжный маршрут изменился. Обновите форму и повторите проверку"
        );
    }

    public static CodedResponseStatusException unresolved() {
        return new CodedResponseStatusException(
                HttpStatus.CONFLICT,
                TASK_TARGET_UNRESOLVED,
                "Получатель платёжного задания не привязан; оплату нужно сверить вручную"
        );
    }
}
