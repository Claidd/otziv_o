package com.hunt.otziv.payments.tochka.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponseData;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TochkaPaymentOperationMapperTest {

    private static final String OPERATION_ID = "48232c9a-ce82-1593-3cb6-5c85a1ffef8f";
    private static final String PAYMENT_LINK_ID = "payment-link-42-v1";
    private static final String CUSTOMER_CODE = "1234567ab";
    private static final String MERCHANT_ID = "200000000001097";
    private static final long AMOUNT_KOPECKS = 12_345;

    private final TochkaPaymentOperationMapper mapper = new TochkaPaymentOperationMapper();

    @ParameterizedTest
    @CsvSource({
            "CREATED, INITIATED",
            "APPROVED, CONFIRMED",
            "EXPIRED, EXPIRED",
            "REFUNDED, REFUNDED",
            "REFUNDED_PARTIALLY, PARTIAL_REFUNDED",
            "ON-REFUND, NEEDS_RECONCILIATION",
            "WAIT_FULL_PAYMENT, NEEDS_RECONCILIATION"
    })
    void mapsKnownOperationStatuses(String providerStatus, PaymentLinkStatus expectedStatus) {
        var mapped = mapper.map(
                operation(providerStatus, "sbp"),
                expected(TochkaPaymentMode.SBP),
                false
        );

        assertEquals(expectedStatus, mapped.status());
        assertEquals(PaymentMethod.SBP_QR, mapped.paymentMethod());
        assertEquals(providerStatus, mapped.providerStatus());
    }

    @Test
    void mapsCardAndSbpToSeparateInternalPaymentMethods() {
        var card = mapper.map(
                operation("CREATED", "card"),
                expected(TochkaPaymentMode.CARD),
                false
        );
        var sbp = mapper.map(
                operation("CREATED", "sbp"),
                expected(TochkaPaymentMode.SBP),
                false
        );

        assertEquals(PaymentMethod.BANK_FORM, card.paymentMethod());
        assertEquals(PaymentMethod.SBP_QR, sbp.paymentMethod());
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, INITIATED",
            "EXPIRED, EXPIRED"
    })
    void usesExpectedModeWhenUnpaidListItemOmitsPaymentType(
            String providerStatus,
            PaymentLinkStatus expectedStatus
    ) {
        var mapped = mapper.map(
                operation(providerStatus, null),
                expected(TochkaPaymentMode.SBP),
                false
        );

        assertEquals(expectedStatus, mapped.status());
        assertEquals(PaymentMethod.SBP_QR, mapped.paymentMethod());
    }

    @ParameterizedTest
    @CsvSource({
            "APPROVED",
            "AUTHORIZED",
            "ON-REFUND",
            "WAIT_FULL_PAYMENT",
            "REFUNDED",
            "REFUNDED_PARTIALLY"
    })
    void rejectsMissingPaymentTypeForPaidOrRefundingOperation(String providerStatus) {
        assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        operation(providerStatus, null),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );
    }

    @Test
    void mapsAuthorizedOnlyForCardPreauthorization() {
        var preauthorizedCard = mapper.map(
                operation("AUTHORIZED", "card"),
                expected(TochkaPaymentMode.CARD),
                true
        );
        var oneStageCard = mapper.map(
                operation("AUTHORIZED", "card"),
                expected(TochkaPaymentMode.CARD),
                false
        );
        var impossibleSbpPreauthorization = mapper.map(
                operation("AUTHORIZED", "sbp"),
                expected(TochkaPaymentMode.SBP),
                true
        );

        assertEquals(PaymentLinkStatus.AUTHORIZED, preauthorizedCard.status());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, oneStageCard.status());
        assertEquals(PaymentLinkStatus.NEEDS_RECONCILIATION, impossibleSbpPreauthorization.status());
    }

    @ParameterizedTest
    @CsvSource({
            "operationId",
            "paymentLinkId",
            "customerCode",
            "merchantId",
            "amount"
    })
    void rejectsOperationIdentityOrAmountMismatch(String field) {
        PaymentOperation operation = mismatchedOperation(field);

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(operation, expected(TochkaPaymentMode.SBP), false)
        );

        assertFalse(error.isOutcomeUnknown());
    }

    @ParameterizedTest
    @CsvSource({
            "operationId",
            "paymentLinkId",
            "customerCode",
            "merchantId",
            "amount"
    })
    void rejectsCreateIdentityOrAmountMismatchAsAmbiguous(String field) {
        CreatePaymentResponse response = mismatchedCreateResponse(field);

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(response, expected(TochkaPaymentMode.SBP), false)
        );

        assertTrue(error.isOutcomeUnknown());
    }

    @Test
    void mapsStrictSingleCreateMode() {
        var card = mapper.map(
                createResponse("CREATED", List.of("card")),
                expected(TochkaPaymentMode.CARD),
                false
        );
        var sbp = mapper.map(
                createResponse("CREATED", List.of("sbp")),
                expected(TochkaPaymentMode.SBP),
                false
        );

        assertEquals(PaymentMethod.BANK_FORM, card.paymentMethod());
        assertEquals(PaymentMethod.SBP_QR, sbp.paymentMethod());
        assertEquals(PaymentLinkStatus.INITIATED, card.status());
        assertEquals(PaymentLinkStatus.INITIATED, sbp.status());
    }

    @Test
    void rejectsMultipleOrUnexpectedCreateModesAsAmbiguous() {
        TochkaProviderException multiple = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        createResponse("CREATED", List.of("card", "sbp")),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );
        TochkaProviderException unexpected = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        createResponse("CREATED", List.of("card")),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );

        assertTrue(multiple.isOutcomeUnknown());
        assertTrue(unexpected.isOutcomeUnknown());
    }

    @Test
    void rejectsUnknownOrCaseChangedOperationMode() {
        TochkaProviderException unknown = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        operation("CREATED", "cash"),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );
        TochkaProviderException caseChanged = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        operation("CREATED", "SBP"),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );

        assertFalse(unknown.isOutcomeUnknown());
        assertFalse(caseChanged.isOutcomeUnknown());
    }

    @Test
    void rejectsUnknownStatusWithoutAdvancingPayment() {
        TochkaProviderException operationError = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        operation("NEW_PROVIDER_STATUS", "sbp"),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );
        TochkaProviderException createError = assertThrows(
                TochkaProviderException.class,
                () -> mapper.map(
                        createResponse("NEW_PROVIDER_STATUS", List.of("sbp")),
                        expected(TochkaPaymentMode.SBP),
                        false
                )
        );

        assertFalse(operationError.isOutcomeUnknown());
        assertTrue(createError.isOutcomeUnknown());
    }

    @Test
    void requiresCompletePositiveExpectedIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ExpectedPayment(
                " ", PAYMENT_LINK_ID, CUSTOMER_CODE, MERCHANT_ID, AMOUNT_KOPECKS, TochkaPaymentMode.SBP
        ));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedPayment(
                OPERATION_ID, PAYMENT_LINK_ID, CUSTOMER_CODE, MERCHANT_ID, 0, TochkaPaymentMode.SBP
        ));
        assertThrows(NullPointerException.class, () -> new ExpectedPayment(
                OPERATION_ID, PAYMENT_LINK_ID, CUSTOMER_CODE, MERCHANT_ID, AMOUNT_KOPECKS, null
        ));
    }

    private ExpectedPayment expected(TochkaPaymentMode mode) {
        return new ExpectedPayment(
                OPERATION_ID,
                PAYMENT_LINK_ID,
                CUSTOMER_CODE,
                MERCHANT_ID,
                AMOUNT_KOPECKS,
                mode
        );
    }

    private PaymentOperation operation(String status, String paymentType) {
        return operation(
                OPERATION_ID,
                PAYMENT_LINK_ID,
                CUSTOMER_CODE,
                MERCHANT_ID,
                new BigDecimal("123.45"),
                status,
                paymentType
        );
    }

    private PaymentOperation mismatchedOperation(String field) {
        return operation(
                "operationId".equals(field) ? "other-operation" : OPERATION_ID,
                "paymentLinkId".equals(field) ? "other-link" : PAYMENT_LINK_ID,
                "customerCode".equals(field) ? "other-code" : CUSTOMER_CODE,
                "merchantId".equals(field) ? "200000000009999" : MERCHANT_ID,
                "amount".equals(field) ? new BigDecimal("123.46") : new BigDecimal("123.45"),
                "APPROVED",
                "sbp"
        );
    }

    private PaymentOperation operation(
            String operationId,
            String paymentLinkId,
            String customerCode,
            String merchantId,
            BigDecimal amount,
            String status,
            String paymentType
    ) {
        return new PaymentOperation(
                customerCode,
                "usn_income",
                paymentType,
                "payment-id",
                "transaction-id",
                "2026-08-29T08:00:00+03:00",
                amount,
                status,
                operationId,
                "https://merch.securepaytb.ru/order/?uuid=42",
                merchantId,
                "2026-08-29T08:30:00+03:00",
                paymentLinkId,
                List.of()
        );
    }

    private CreatePaymentResponse mismatchedCreateResponse(String field) {
        return createResponse(
                "operationId".equals(field) ? "other-operation" : OPERATION_ID,
                "paymentLinkId".equals(field) ? "other-link" : PAYMENT_LINK_ID,
                "customerCode".equals(field) ? "other-code" : CUSTOMER_CODE,
                "merchantId".equals(field) ? "200000000009999" : MERCHANT_ID,
                "amount".equals(field) ? new BigDecimal("123.46") : new BigDecimal("123.45"),
                "CREATED",
                List.of("sbp")
        );
    }

    private CreatePaymentResponse createResponse(String status, List<String> paymentModes) {
        return createResponse(
                OPERATION_ID,
                PAYMENT_LINK_ID,
                CUSTOMER_CODE,
                MERCHANT_ID,
                new BigDecimal("123.45"),
                status,
                paymentModes
        );
    }

    private CreatePaymentResponse createResponse(
            String operationId,
            String paymentLinkId,
            String customerCode,
            String merchantId,
            BigDecimal amount,
            String status,
            List<String> paymentModes
    ) {
        return new CreatePaymentResponse(new CreatePaymentResponseData(
                "Оплата заказа 42",
                status,
                amount,
                operationId,
                "https://merch.securepaytb.ru/order/?uuid=42",
                merchantId,
                paymentLinkId,
                paymentModes,
                customerCode
        ));
    }
}
