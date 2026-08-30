package com.hunt.otziv.payments.tochka.service;

import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.tochka.config.TochkaPaymentProperties;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TochkaPaymentProfileResolver {

    private final TochkaPaymentProperties properties;
    private final TochkaClient client;

    public TochkaPaymentProfile resolve(PaymentProfile entity) {
        TochkaPaymentProfile runtime = resolveForExistingPayment(entity);
        client.requireConfigured(runtime);
        return runtime;
    }

    /**
     * Resolves the immutable identity/configuration of an already-created payment without
     * requiring either the global switch or the pinned profile to remain enabled.
     *
     * <p>This is deliberately not suitable for creating a new provider operation. It exists so
     * a signed webhook for money already in flight can still be bound and applied after an
     * operator has disabled future Tochka payments.</p>
     */
    public TochkaPaymentProfile resolveForExistingPayment(PaymentProfile entity) {
        requireTochkaProvider(entity);

        String configuredCode = clean(properties.getProfileCode());
        String entityCode = clean(entity.getCode());
        if (configuredCode.isBlank()) {
            throw conflict("В настройках Точки не задан код платежного профиля");
        }
        if (!configuredCode.equals(entityCode)) {
            throw conflict("Платежный профиль не соответствует настроенному профилю Точки");
        }

        TochkaPaymentProfile configured = properties.defaultProfile();
        return new TochkaPaymentProfile(
                entity.getId(),
                entityCode,
                clean(entity.getName()),
                entity.isEnabled(),
                configured.customerCode(),
                configured.merchantId(),
                configured.jwtToken(),
                configured.requiredCashbox(),
                configured.testMode(),
                configured.taxSystemCode(),
                configured.vatType(),
                configured.paymentMethod(),
                configured.paymentObject(),
                configured.measure(),
                configured.receiptItemName(),
                configured.paymentModes(),
                configured.linkTtl()
        );
    }

    private void requireTochkaProvider(PaymentProfile entity) {
        if (entity == null) {
            throw conflict("Не выбран платежный профиль Точки");
        }
        try {
            if (!PaymentProfile.PROVIDER_TOCHKA.equals(entity.normalizedProvider())) {
                throw conflict("Профиль другого банка нельзя использовать для запроса в Точку");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежный профиль содержит неподдерживаемого провайдера",
                    exception
            );
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
