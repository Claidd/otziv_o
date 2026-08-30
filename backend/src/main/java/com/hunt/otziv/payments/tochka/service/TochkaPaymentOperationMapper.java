package com.hunt.otziv.payments.tochka.service;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponseData;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Validates that a Tochka acquiring observation belongs to the payment we expect and maps the
 * provider state into the deliberately smaller internal payment state machine.
 *
 * <p>Identity mismatches are never converted into a business status: a caller must quarantine
 * the local payment without applying data from a different provider operation. Known provider
 * states that cannot safely advance our one-stage flow are mapped to
 * {@link PaymentLinkStatus#NEEDS_RECONCILIATION} so that the quarantine can be persisted.
 */
@Component
public class TochkaPaymentOperationMapper {

    public MappedPayment map(
            PaymentOperation operation,
            ExpectedPayment expected,
            boolean preAuthorization
    ) {
        requireExpected(expected);
        if (operation == null) {
            throw providerError("Точка API не вернула платежную операцию", false);
        }
        validateIdentity(
                operation.operationId(),
                operation.paymentLinkId(),
                operation.customerCode(),
                operation.merchantId(),
                operation.amount(),
                expected,
                false
        );
        String providerStatus = requireProviderStatus(operation.status(), false);
        PaymentMethod paymentMethod = mapOperationPaymentMethod(
                operation.paymentType(),
                expected.paymentMode(),
                providerStatus
        );
        return new MappedPayment(
                mapStatus(providerStatus, paymentMethod, preAuthorization, false),
                paymentMethod,
                providerStatus
        );
    }

    public MappedPayment map(
            CreatePaymentResponse response,
            ExpectedPayment expected,
            boolean preAuthorization
    ) {
        requireExpected(expected);
        CreatePaymentResponseData data = response == null ? null : response.data();
        if (data == null) {
            throw providerError("Точка API не вернула данные созданной платежной ссылки", true);
        }
        validateIdentity(
                data.operationId(),
                data.paymentLinkId(),
                data.customerCode(),
                data.merchantId(),
                data.amount(),
                expected,
                true
        );
        PaymentMethod paymentMethod = mapCreatePaymentMethod(
                data.paymentMode(),
                expected.paymentMode()
        );
        String providerStatus = requireProviderStatus(data.status(), true);
        return new MappedPayment(
                mapStatus(providerStatus, paymentMethod, preAuthorization, true),
                paymentMethod,
                providerStatus
        );
    }

    private void validateIdentity(
            String operationId,
            String paymentLinkId,
            String customerCode,
            String merchantId,
            BigDecimal amount,
            ExpectedPayment expected,
            boolean outcomeUnknown
    ) {
        boolean matches = expected.operationId().equals(operationId)
                && expected.paymentLinkId().equals(paymentLinkId)
                && expected.customerCode().equals(customerCode)
                && expected.merchantId().equals(merchantId)
                && amount != null
                && expected.amountRubles().compareTo(amount) == 0;
        if (!matches) {
            throw providerError(
                    "Точка API вернула платеж с несовпадающими идентификаторами или суммой",
                    outcomeUnknown
            );
        }
    }

    private PaymentMethod mapOperationPaymentMethod(
            String paymentType,
            TochkaPaymentMode expectedMode,
            String providerStatus
    ) {
        if ((paymentType == null || paymentType.isBlank())
                && ("CREATED".equals(providerStatus) || "EXPIRED".equals(providerStatus))) {
            return mapPaymentMethod(expectedMode, false);
        }
        String expectedCode = expectedMode.code();
        if (!expectedCode.equals(paymentType)) {
            throw providerError(
                    "Точка API вернула другой или неизвестный способ оплаты платежной операции",
                    false
            );
        }
        return mapPaymentMethod(expectedMode, false);
    }

    private PaymentMethod mapCreatePaymentMethod(
            List<String> paymentModes,
            TochkaPaymentMode expectedMode
    ) {
        if (paymentModes == null
                || paymentModes.size() != 1
                || !expectedMode.code().equals(paymentModes.getFirst())) {
            throw providerError(
                    "Точка API вернула другой, неизвестный или неоднозначный способ оплаты ссылки",
                    true
            );
        }
        return mapPaymentMethod(expectedMode, true);
    }

    private PaymentMethod mapPaymentMethod(TochkaPaymentMode mode, boolean outcomeUnknown) {
        return switch (mode) {
            case CARD -> PaymentMethod.BANK_FORM;
            case SBP -> PaymentMethod.SBP_QR;
            default -> throw providerError(
                    "Способ оплаты Точки не поддерживается публичным платежным маршрутом",
                    outcomeUnknown
            );
        };
    }

    private String requireProviderStatus(String status, boolean outcomeUnknown) {
        if (status == null || status.isBlank()) {
            throw providerError("Точка API не вернула статус платежа", outcomeUnknown);
        }
        return status;
    }

    private PaymentLinkStatus mapStatus(
            String providerStatus,
            PaymentMethod paymentMethod,
            boolean preAuthorization,
            boolean outcomeUnknown
    ) {
        return switch (providerStatus) {
            case "CREATED" -> PaymentLinkStatus.INITIATED;
            case "APPROVED" -> PaymentLinkStatus.CONFIRMED;
            case "EXPIRED" -> PaymentLinkStatus.EXPIRED;
            case "REFUNDED" -> PaymentLinkStatus.REFUNDED;
            case "REFUNDED_PARTIALLY" -> PaymentLinkStatus.PARTIAL_REFUNDED;
            case "ON-REFUND", "WAIT_FULL_PAYMENT" -> PaymentLinkStatus.NEEDS_RECONCILIATION;
            case "AUTHORIZED" -> paymentMethod == PaymentMethod.BANK_FORM && preAuthorization
                    ? PaymentLinkStatus.AUTHORIZED
                    : PaymentLinkStatus.NEEDS_RECONCILIATION;
            default -> throw providerError("Точка API вернула неизвестный статус платежа", outcomeUnknown);
        };
    }

    private void requireExpected(ExpectedPayment expected) {
        Objects.requireNonNull(expected, "expected");
    }

    private TochkaProviderException providerError(String message, boolean outcomeUnknown) {
        return new TochkaProviderException(message, outcomeUnknown, null);
    }

    public record ExpectedPayment(
            String operationId,
            String paymentLinkId,
            String customerCode,
            String merchantId,
            long amountKopecks,
            TochkaPaymentMode paymentMode
    ) {
        public ExpectedPayment {
            operationId = requireExpectedText(operationId, "operationId");
            paymentLinkId = requireExpectedText(paymentLinkId, "paymentLinkId");
            customerCode = requireExpectedText(customerCode, "customerCode");
            merchantId = requireExpectedText(merchantId, "merchantId");
            if (amountKopecks <= 0) {
                throw new IllegalArgumentException("expected amountKopecks must be positive");
            }
            paymentMode = Objects.requireNonNull(paymentMode, "paymentMode");
        }

        private BigDecimal amountRubles() {
            return BigDecimal.valueOf(amountKopecks, 2);
        }

        private static String requireExpectedText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("expected " + field + " must not be blank");
            }
            return value;
        }
    }

    public record MappedPayment(
            PaymentLinkStatus status,
            PaymentMethod paymentMethod,
            String providerStatus
    ) {
    }
}
