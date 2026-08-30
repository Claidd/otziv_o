package com.hunt.otziv.payments.tochka.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.payments.tochka.config.TochkaPaymentProperties;
import com.hunt.otziv.payments.tochka.dto.TochkaCreatePaymentCommand;
import com.hunt.otziv.payments.tochka.dto.TochkaRefundCommand;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class TochkaClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private TochkaPaymentProperties properties;
    private TochkaClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = configuredProperties();
        client = new TochkaClient(restTemplate, properties);
    }

    @Test
    void createsSandboxPaymentLinkWithCloudKassirReceiptData() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sandbox.jwt.token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonBody("""
                        {
                          "Data": {
                            "customerCode": "1234567ab",
                            "amount": 123.45,
                            "purpose": "Оплата заказа 42",
                            "redirectUrl": "https://o-ogo.ru/pay/success",
                            "failRedirectUrl": "https://o-ogo.ru/pay/fail",
                            "paymentMode": ["card", "sbp"],
                            "merchantId": "200000000001097",
                            "preAuthorization": false,
                            "ttl": 10080,
                            "paymentLinkId": "payment-link-42-v1",
                            "taxSystemCode": "usn_income",
                            "Client": {"email": "client@example.com"},
                            "Items": [{
                              "vatType": "none",
                              "name": "Репутационное сопровождение компании в сети Интернет",
                              "amount": 123.45,
                              "quantity": 1,
                              "paymentMethod": "full_payment",
                              "paymentObject": "service",
                              "measure": "шт."
                            }]
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "purpose": "Оплата заказа 42",
                            "status": "CREATED",
                            "amount": "123.45",
                            "operationId": "48232c9a-ce82-1593-3cb6-5c85a1ffef8f",
                            "paymentLink": "https://merch.securepaytb.ru/order/?uuid=42",
                            "merchantId": "200000000001097",
                            "paymentLinkId": "payment-link-42-v1",
                            "paymentMode": ["card", "sbp"],
                            "customerCode": "1234567ab"
                          },
                          "Links": {},
                          "Meta": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.createPaymentWithReceipt(new TochkaCreatePaymentCommand(
                "payment-link-42-v1",
                12_345,
                "Оплата заказа 42",
                "client@example.com",
                "https://o-ogo.ru/pay/success",
                "https://o-ogo.ru/pay/fail",
                List.of()
        ));

        assertEquals("CREATED", response.data().status());
        assertEquals("48232c9a-ce82-1593-3cb6-5c85a1ffef8f", response.data().operationId());
        assertEquals(new BigDecimal("123.45"), response.data().amount());
        assertEquals("https://merch.securepaytb.ru/order/?uuid=42", response.data().paymentLink());
        server.verify();
    }

    @Test
    void loadsRetailerReadinessIncludingCloudCashbox() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/retailers?customerCode=1234567ab"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sandbox.jwt.token"))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "Retailer": [{
                              "status": "REG",
                              "isActive": true,
                              "name": "ООО Тест",
                              "merchantId": "200000000001097",
                              "terminalId": "20000032",
                              "paymentModes": ["card", "sbp"],
                              "cashbox": "cloudKassir"
                            }]
                          },
                          "Links": {},
                          "Meta": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.getRetailers();
        var retailer = response.data().retailers().getFirst();

        assertEquals("REG", retailer.status());
        assertTrue(retailer.isActive());
        assertEquals("cloudKassir", retailer.cashbox());
        server.verify();
    }

    @Test
    void loadsPaymentStatusFromOperationArray() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "Operation": [{
                              "customerCode": "1234567ab",
                              "taxSystemCode": "usn_income",
                              "paymentType": "sbp",
                              "amount": "123.45",
                              "status": "APPROVED",
                              "operationId": "operation-42",
                              "paymentLink": "https://merch.securepaytb.ru/order/?uuid=42",
                              "merchantId": "200000000001097",
                              "paidAt": "2026-08-29T08:30:00+03:00",
                              "paymentLinkId": "payment-link-42-v1"
                            }]
                          },
                          "Links": {},
                          "Meta": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        var operation = client.getPaymentInfo("operation-42").data().operations().getFirst();

        assertEquals("APPROVED", operation.status());
        assertEquals(new BigDecimal("123.45"), operation.amount());
        assertEquals("payment-link-42-v1", operation.paymentLinkId());
        server.verify();
    }

    @Test
    void parsesRefundOrdersForSafeAmbiguousRefundReconciliation() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "Operation": [{
                              "customerCode": "1234567ab",
                              "amount": "123.45",
                              "status": "REFUNDED_PARTIALLY",
                              "operationId": "operation-42",
                              "merchantId": "200000000001097",
                              "Order": [{
                                "orderId": 17,
                                "type": "refund",
                                "amount": "23.45",
                                "time": "2026-08-29T09:15:00+03:00"
                              }]
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var order = client.getPaymentInfo("operation-42")
                .data().operations().getFirst().orders().getFirst();

        assertEquals("17", order.orderId());
        assertEquals(new BigDecimal("23.45"), order.amount());
        assertEquals("2026-08-29T09:15:00+03:00", order.time());
        server.verify();
    }

    @Test
    void rejectsMultipleOperationsFromSingleOperationEndpoint() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [
                            {"operationId": "other-operation"},
                            {"operationId": "operation-42"}
                          ]}
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.getPaymentInfo("operation-42")
        );

        assertFalse(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("ровно одну"));
        server.verify();
    }

    @Test
    void reconcilesAmbiguousCreateByScanningPaymentListWithoutRepeatingPost() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                + "&page=1&perPage=100"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [{
                            "customerCode": "1234567ab",
                            "merchantId": "200000000001097",
                            "operationId": "other-operation",
                            "paymentLinkId": "other-link",
                            "amount": "123.45",
                            "status": "CREATED",
                            "paymentLink": "https://merch.securepaytb.ru/other"
                          }]},
                          "Meta": {"currentPage": 1, "totalPages": 2, "totalItems": 2}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                + "&page=2&perPage=100"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [{
                            "customerCode": "1234567ab",
                            "merchantId": "200000000001097",
                            "operationId": "operation-42",
                            "paymentLinkId": "payment-link-42-v1",
                            "amount": "123.45",
                            "status": "CREATED",
                            "paymentLink": "https://merch.securepaytb.ru/payment-42"
                          }]},
                          "Meta": {"currentPage": 2, "totalPages": 2, "totalItems": 2}
                        }
                        """, MediaType.APPLICATION_JSON));

        var operation = client.findPaymentByPaymentLinkId(
                properties.defaultProfile(),
                "payment-link-42-v1",
                12_345,
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 29)
        ).orElseThrow();

        assertEquals("operation-42", operation.operationId());
        server.verify();
    }

    @Test
    void acceptsTerminalRecoveredPaymentsWithoutHostedPaymentLink() {
        for (String status : List.of("APPROVED", "REFUNDED", "EXPIRED")) {
            server.expect(requestTo(
                            "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                    + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                    + "&page=1&perPage=100"
                    ))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {
                              "Data": {"Operation": [{
                                "customerCode": "1234567ab",
                                "merchantId": "200000000001097",
                                "operationId": "operation-42",
                                "paymentLinkId": "payment-link-42-v1",
                                "amount": "123.45",
                                "status": "%s"
                              }]},
                              "Meta": {"currentPage": 1, "totalPages": 1, "totalItems": 1}
                            }
                            """.formatted(status), MediaType.APPLICATION_JSON));

            var operation = client.findPaymentByPaymentLinkId(
                    properties.defaultProfile(),
                    "payment-link-42-v1",
                    12_345,
                    LocalDate.of(2026, 8, 28),
                    LocalDate.of(2026, 8, 29)
            ).orElseThrow();

            assertEquals(status, operation.status());
            assertTrue(operation.paymentLink() == null || operation.paymentLink().isBlank());
            server.verify();
            server.reset();
        }
    }

    @Test
    void returnsEmptyWhenAmbiguousCreateRecoveryHasZeroPages() {
        server.expect(ExpectedCount.once(), requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                + "&page=1&perPage=100"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": []},
                          "Meta": {"totalPages": 0}
                        }
                        """, MediaType.APPLICATION_JSON));

        var operation = client.findPaymentByPaymentLinkId(
                properties.defaultProfile(),
                "payment-link-42-v1",
                12_345,
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 29)
        );

        assertTrue(operation.isEmpty());
        server.verify();
    }

    @Test
    void rejectsNonEmptyPaymentListWithZeroPages() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                + "&page=1&perPage=100"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [{"operationId": "operation-42"}]},
                          "Meta": {"totalPages": 0}
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.findPaymentByPaymentLinkId(
                        properties.defaultProfile(),
                        "payment-link-42-v1",
                        12_345,
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 29)
                )
        );

        assertFalse(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("несогласованные"));
        server.verify();
    }

    @Test
    void rejectsReconciledPaymentWhenAmountDoesNotMatchOriginalCreate() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                + "&page=1&perPage=100"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [{
                            "customerCode": "1234567ab",
                            "merchantId": "200000000001097",
                            "operationId": "operation-42",
                            "paymentLinkId": "payment-link-42-v1",
                            "amount": "999.99",
                            "status": "CREATED",
                            "paymentLink": "https://merch.securepaytb.ru/payment-42"
                          }]},
                          "Meta": {"currentPage": 1, "totalPages": 1, "totalItems": 1}
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.findPaymentByPaymentLinkId(
                        properties.defaultProfile(),
                        "payment-link-42-v1",
                        12_345,
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 29)
                )
        );

        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("другой суммой"));
        server.verify();
    }

    @Test
    void createsPartialRefundWithoutFloatingPointConversion() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42/refund"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonBody("""
                        {"Data":{"amount":23.45}}
                        """))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "isRefund": true,
                            "operationId": "operation-42",
                            "amount": "23.45",
                            "date": "2026-08-29",
                            "orderId": 17
                          },
                          "Links": {},
                          "Meta": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.refund(new TochkaRefundCommand("operation-42", 2_345));

        assertTrue(response.data().isRefund());
        assertEquals(new BigDecimal("23.45"), response.data().amount());
        assertEquals("17", response.data().orderId());
        server.verify();
    }

    @Test
    void existingOperationsRemainAvailableWhenGlobalAndProfileCreationAreDisabled() {
        properties.setEnabled(false);
        var frozenProfile = properties.defaultProfile();

        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sandbox.jwt.token"))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [{
                            "operationId": "operation-42",
                            "status": "APPROVED"
                          }]}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments"
                                + "?customerCode=1234567ab&fromDate=2026-08-28&toDate=2026-08-29"
                                + "&page=1&perPage=100"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sandbox.jwt.token"))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Operation": [{
                            "customerCode": "1234567ab",
                            "merchantId": "200000000001097",
                            "operationId": "operation-42",
                            "paymentLinkId": "payment-link-42-v1",
                            "amount": "123.45",
                            "status": "APPROVED"
                          }]},
                          "Meta": {"currentPage": 1, "totalPages": 1, "totalItems": 1}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42/refund"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sandbox.jwt.token"))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "isRefund": true,
                            "operationId": "operation-42",
                            "amount": "23.45",
                            "orderId": "17"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertEquals(
                "APPROVED",
                client.getPaymentInfo(frozenProfile, "operation-42")
                        .data().operations().getFirst().status()
        );
        assertEquals(
                "operation-42",
                client.findPaymentByPaymentLinkId(
                        frozenProfile,
                        "payment-link-42-v1",
                        12_345,
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 29)
                ).orElseThrow().operationId()
        );
        assertTrue(client.refund(
                frozenProfile,
                new TochkaRefundCommand("operation-42", 2_345)
        ).data().isRefund());
        server.verify();
    }

    @Test
    void existingOperationsStillRequireFrozenProfileIdentityAndCredentials() {
        properties.setEnabled(false);
        properties.setProfileCode("");

        ResponseStatusException missingIdentity = assertThrows(
                ResponseStatusException.class,
                () -> client.getPaymentInfo(properties.defaultProfile(), "operation-42")
        );

        assertEquals(HttpStatus.CONFLICT, missingIdentity.getStatusCode());
        assertTrue(missingIdentity.getReason().contains("профиль"));

        properties.setProfileCode("tochka-primary");
        properties.setJwtToken("");

        ResponseStatusException missingCredentials = assertThrows(
                ResponseStatusException.class,
                () -> client.refund(
                        properties.defaultProfile(),
                        new TochkaRefundCommand("operation-42", 2_345)
                )
        );

        assertEquals(HttpStatus.CONFLICT, missingCredentials.getStatusCode());
        assertTrue(missingCredentials.getReason().contains("JWT"));
        assertTrue(missingCredentials instanceof TochkaProviderException);
        assertFalse(((TochkaProviderException) missingCredentials).isOutcomeUnknown());
        server.verify();
    }

    @Test
    void rejectsMismatchedRefundResponseAsAmbiguous() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42/refund"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "isRefund": true,
                            "operationId": "operation-42",
                            "amount": "22.45",
                            "orderId": "17"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.refund(new TochkaRefundCommand("operation-42", 2_345))
        );

        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("другую сумму"));
        server.verify();
    }

    @Test
    void treatsMissingRefundFlagAsAmbiguousToPreventUnsafeRetry() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42/refund"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "operationId": "operation-42",
                            "amount": "23.45",
                            "orderId": "17"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.refund(new TochkaRefundCommand("operation-42", 2_345))
        );

        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("признак"));
        server.verify();
    }

    @Test
    void treatsNegativeRefundAcknowledgementAsAmbiguousToPreventUnsafeRetry() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42/refund"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "isRefund": false,
                            "operationId": "operation-42",
                            "amount": "23.45"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.refund(new TochkaRefundCommand("operation-42", 2_345))
        );

        assertTrue(error.isOutcomeUnknown());
        server.verify();
    }

    @Test
    void treatsEveryHttpFailureAfterRefundPostAsAmbiguous() {
        for (HttpStatus status : List.of(
                HttpStatus.REQUEST_TIMEOUT,
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.BAD_REQUEST
        )) {
            server.expect(ExpectedCount.once(), requestTo(
                            "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments/operation-42/refund"
                    ))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(status));

            TochkaProviderException error = assertThrows(
                    TochkaProviderException.class,
                    () -> client.refund(new TochkaRefundCommand("operation-42", 2_345))
            );

            assertTrue(error.isOutcomeUnknown(), "HTTP " + status.value());
            server.verify();
            server.reset();
        }
    }

    @Test
    void refusesReceiptRequestUntilAusnObjectIsConfigured() {
        properties.setTaxSystemCode("");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> client.createPaymentWithReceipt(command())
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("usn_income"));
    }

    @Test
    void refusesRetailerReadinessWhileTochkaFeatureIsDisabled() {
        properties.setEnabled(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> client.getRetailers()
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertFalse(error.getReason().contains("sandbox.jwt.token"));
    }

    @Test
    void refusesCreateWhileGlobalTochkaFeatureIsDisabled() {
        properties.setEnabled(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> client.createPaymentWithReceipt(command())
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("выключен в настройках"));
        server.verify();
    }

    @Test
    void refusesCreateForDisabledPaymentProfile() {
        properties.setEnabled(false);
        var disabledProfile = properties.defaultProfile();
        properties.setEnabled(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> client.createPaymentWithReceipt(disabledProfile, command())
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("профиль"));
        assertTrue(error.getReason().contains("выключен"));
        server.verify();
    }

    @Test
    void marksCreateServerFailureAsAmbiguousWithoutLeakingJwt() {
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                ))
                .andRespond(withServerError());

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.createPaymentWithReceipt(command())
        );

        assertTrue(error.isOutcomeUnknown());
        assertFalse(error.getReason().contains("sandbox.jwt.token"));
        server.verify();
    }

    @Test
    void marksCreateHttp424AsAmbiguousAndDoesNotRetryPost() {
        server.expect(ExpectedCount.once(), requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                ))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FAILED_DEPENDENCY));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.createPaymentWithReceipt(command())
        );

        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("HTTP 424"));
        server.verify();
    }

    @Test
    void treatsEveryHttpFailureAfterCreatePostAsAmbiguous() {
        for (HttpStatus status : List.of(
                HttpStatus.REQUEST_TIMEOUT,
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.BAD_REQUEST
        )) {
            server.expect(ExpectedCount.once(), requestTo(
                            "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                    ))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(status));

            TochkaProviderException error = assertThrows(
                    TochkaProviderException.class,
                    () -> client.createPaymentWithReceipt(command())
            );

            assertTrue(error.isOutcomeUnknown(), "HTTP " + status.value());
            server.verify();
            server.reset();
        }
    }

    @Test
    void rejectsExplicitPaymentModeOutsideProfileAllowListBeforeProviderCall() {
        properties.setPaymentModes(List.of("card"));
        ResponseStatusException sbpDisabled = assertThrows(
                ResponseStatusException.class,
                () -> client.createPaymentWithReceipt(command(List.of(TochkaPaymentMode.SBP)))
        );

        properties.setPaymentModes(List.of("sbp"));
        ResponseStatusException cardDisabled = assertThrows(
                ResponseStatusException.class,
                () -> client.createPaymentWithReceipt(command(List.of(TochkaPaymentMode.CARD)))
        );

        assertEquals(HttpStatus.CONFLICT, sbpDisabled.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, cardDisabled.getStatusCode());
        assertTrue(sbpDisabled.getReason().contains("выключен"));
        assertTrue(cardDisabled.getReason().contains("выключен"));
        assertFalse(((TochkaProviderException) sbpDisabled).isOutcomeUnknown());
        assertFalse(((TochkaProviderException) cardDisabled).isOutcomeUnknown());
        server.verify();
    }

    @Test
    void acceptsExplicitPaymentModeContainedInProfileAllowList() {
        properties.setPaymentModes(List.of("card"));
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                ))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        createPaymentResponse("https://merch.securepaytb.ru/payment"),
                        MediaType.APPLICATION_JSON
                ));

        var response = client.createPaymentWithReceipt(command(List.of(TochkaPaymentMode.CARD)));

        assertEquals("operation-42", response.data().operationId());
        server.verify();
    }

    @Test
    void acceptsOfficialPaymentUrlOnExplicitHttpsPort443() {
        String paymentLink = "https://merch.securepaytb.ru:443/payment";
        server.expect(requestTo(
                        "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                ))
                .andRespond(withSuccess(createPaymentResponse(paymentLink), MediaType.APPLICATION_JSON));

        var response = client.createPaymentWithReceipt(command());

        assertEquals(paymentLink, response.data().paymentLink());
        server.verify();
    }

    @Test
    void rejectsNonOfficialPaymentUrlsAsAmbiguousCreateOutcome() {
        for (String paymentLink : List.of(
                "http://merch.securepaytb.ru/payment",
                "https://merch.securepaytb.ru.evil.example/payment",
                "https://evil.merch.securepaytb.ru/payment",
                "https://other.example/payment",
                "https://merch.securepaytb.ru:8443/payment"
        )) {
            server.expect(requestTo(
                            "https://enter.tochka.com/sandbox/v2/acquiring/v1.0/payments_with_receipt"
                    ))
                    .andRespond(withSuccess(createPaymentResponse(paymentLink), MediaType.APPLICATION_JSON));

            TochkaProviderException error = assertThrows(
                    TochkaProviderException.class,
                    () -> client.createPaymentWithReceipt(command())
            );

            assertTrue(error.isOutcomeUnknown());
            assertTrue(error.getReason().contains("некорректную ссылку"));
            server.verify();
            server.reset();
        }
    }

    @Test
    void validatesLiveCreateResponseAgainstRequestedMerchantAndAmount() {
        properties.setTestMode(false);
        properties.setRequiredCashbox("cloudKassir");
        server.expect(requestTo(
                        "https://enter.tochka.com/uapi/acquiring/v1.0/retailers?customerCode=1234567ab"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Retailer": [{
                            "status": "REG",
                            "isActive": true,
                            "merchantId": "200000000001097",
                            "paymentModes": ["card", "sbp"],
                            "cashbox": "cloudKassir"
                          }]}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://enter.tochka.com/uapi/acquiring/v1.0/payments_with_receipt"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {
                            "status": "CREATED",
                            "amount": 999.99,
                            "operationId": "operation-42",
                            "paymentLink": "https://merch.securepaytb.ru/payment",
                            "merchantId": "200000000001097",
                            "paymentLinkId": "payment-link-42-v1",
                            "customerCode": "1234567ab"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TochkaProviderException error = assertThrows(
                TochkaProviderException.class,
                () -> client.createPaymentWithReceipt(command())
        );

        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.getReason().contains("другую сумму"));
        server.verify();
    }

    @Test
    void blocksLiveCreateWhenGetRetailersDoesNotConfirmCloudKassir() {
        properties.setTestMode(false);
        properties.setRequiredCashbox("cloudKassir");
        server.expect(requestTo(
                        "https://enter.tochka.com/uapi/acquiring/v1.0/retailers?customerCode=1234567ab"
                ))
                .andRespond(withSuccess("""
                        {
                          "Data": {"Retailer": [{
                            "status": "REG",
                            "isActive": true,
                            "merchantId": "200000000001097",
                            "paymentModes": ["card", "sbp"],
                            "cashbox": "another-cashbox"
                          }]}
                        }
                        """, MediaType.APPLICATION_JSON));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> client.createPaymentWithReceipt(command())
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("CloudKassir"));
        assertTrue(error instanceof TochkaProviderException);
        assertFalse(((TochkaProviderException) error).isOutcomeUnknown());
        server.verify();
    }

    @Test
    void refusesToSendJwtToNonOfficialBaseUrl() {
        properties.setSandboxBaseUrl("https://attacker.example/sandbox/v2");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> client.getRetailers()
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertFalse(error.getReason().contains("sandbox.jwt.token"));
        server.verify();
    }

    private TochkaCreatePaymentCommand command() {
        return command(List.of(TochkaPaymentMode.CARD, TochkaPaymentMode.SBP));
    }

    private TochkaCreatePaymentCommand command(List<TochkaPaymentMode> modes) {
        return new TochkaCreatePaymentCommand(
                "payment-link-42-v1",
                12_345,
                "Оплата заказа 42",
                "client@example.com",
                "https://o-ogo.ru/pay/success",
                "https://o-ogo.ru/pay/fail",
                modes
        );
    }

    private String createPaymentResponse(String paymentLink) {
        return """
                {
                  "Data": {
                    "status": "CREATED",
                    "amount": 123.45,
                    "operationId": "operation-42",
                    "paymentLink": "%s"
                  }
                }
                """.formatted(paymentLink);
    }

    private TochkaPaymentProperties configuredProperties() {
        TochkaPaymentProperties result = new TochkaPaymentProperties();
        result.setEnabled(true);
        result.setTestMode(true);
        result.setCustomerCode("1234567ab");
        result.setMerchantId("200000000001097");
        result.setJwtToken("sandbox.jwt.token");
        result.setTaxSystemCode("usn_income");
        return result;
    }

    private RequestMatcher jsonBody(String expectedJson) {
        return request -> {
            String actualJson = ((MockClientHttpRequest) request).getBodyAsString();
            assertEquals(OBJECT_MAPPER.readTree(expectedJson), OBJECT_MAPPER.readTree(actualJson));
        };
    }
}
