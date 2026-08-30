package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.ManualPaymentType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentUrlPolicyTest {

    @Test
    void webPaymentUrlsAllowAbsoluteHttpAndHttpsWithoutRewriting() {
        for (String value : new String[]{
                "https://securepay.tinkoff.ru/pay/abc?order=42#payment",
                "http://payments.example.test/dev"
        }) {
            assertEquals(
                    value,
                    PaymentUrlPolicy.require(
                            value,
                            PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                            HttpStatus.BAD_GATEWAY,
                            "invalid"
                    )
            );
        }
    }

    @Test
    void tochkaHostedPaymentUrlRequiresExactHttpsHostAndStandardPort() {
        String valid = "https://merch.securepaytb.ru/payment/operation-1?mode=sbp";
        assertEquals(
                valid,
                PaymentUrlPolicy.require(
                        valid,
                        PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT,
                        HttpStatus.BAD_GATEWAY,
                        "invalid"
                )
        );
        for (String value : new String[]{
                "https://merch.securepaytb.ru.evil.example/payment/operation-1",
                "https://sub.merch.securepaytb.ru/payment/operation-1",
                "http://merch.securepaytb.ru/payment/operation-1",
                "https://merch.securepaytb.ru:8443/payment/operation-1"
        }) {
            assertFalse(
                    PaymentUrlPolicy.isValid(value, PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT),
                    value
            );
        }
    }

    @Test
    void sbpPayloadAllowsNspkWebLinksAndSupportedBankDeepLinks() {
        for (String value : new String[]{
                "https://qr.nspk.ru/AS100000000111?type=01",
                "bank100000000111://qr.nspk.ru/AS100000000111?type=01",
                "bankb2b100000000111://qr.nspk.ru/AR100000000111",
                "bankapp://pay/payment-sbp-bank"
        }) {
            assertEquals(
                    value,
                    PaymentUrlPolicy.require(
                            value,
                            PaymentUrlPolicy.Purpose.SBP_PAYLOAD,
                            HttpStatus.BAD_GATEWAY,
                            "invalid"
                    )
            );
        }
        assertTrue(PaymentUrlPolicy.isGenericSbpPayload("https://qr.nspk.ru/AS100000000111"));
        assertFalse(PaymentUrlPolicy.isGenericSbpPayload("bank100000000111://qr.nspk.ru/AS100000000111"));
    }

    @Test
    void dangerousSchemesControlsAndMalformedUrlsAreRejected() {
        for (String value : new String[]{
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "vbscript:msgbox(1)",
                "file:///etc/passwd",
                "blob:https://example.test/id",
                "https://",
                "https://example.test:99999/pay",
                "//securepay.tinkoff.ru/pay",
                "/relative/pay",
                "https://user:password@example.test/pay",
                "bankapp://user:password@pay/payment-sbp-bank",
                "bankapp://evil.example/payment-sbp-bank",
                "https://example.test/pay\r\nLocation:https://evil.test",
                "https://example.test/pay%0d%0aLocation:https://evil.test",
                "unknownbank://qr.nspk.ru/AS100000000111",
                "bank100000000111://evil.example/AS100000000111"
        }) {
            assertThrows(
                    ResponseStatusException.class,
                    () -> PaymentUrlPolicy.require(
                            value,
                            PaymentUrlPolicy.Purpose.SBP_PAYLOAD,
                            HttpStatus.BAD_GATEWAY,
                            "invalid"
                    ),
                    value
            );
        }
    }

    @Test
    void purposeLengthLimitsAreEnforced() {
        String tooLongManualUrl = "https://example.test/" + "a".repeat(512);
        String tooLongTbankUrl = "https://example.test/" + "a".repeat(1024);
        String tooLongSbpPayload = "https://qr.nspk.ru/" + "a".repeat(2048);

        assertFalse(PaymentUrlPolicy.isValid(tooLongManualUrl, PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL));
        assertFalse(PaymentUrlPolicy.isValid(tooLongTbankUrl, PaymentUrlPolicy.Purpose.TBANK_PAYMENT));
        assertFalse(PaymentUrlPolicy.isValid(tooLongSbpPayload, PaymentUrlPolicy.Purpose.SBP_PAYLOAD));
    }

    @Test
    void defaultIsUsedOnlyForBlankManualUrl() {
        assertEquals(
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.requireOrDefault(
                        "  ",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                        HttpStatus.BAD_GATEWAY,
                        "invalid"
                )
        );
        assertThrows(
                ResponseStatusException.class,
                () -> PaymentUrlPolicy.requireOrDefault(
                        "javascript:alert(1)",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                        HttpStatus.BAD_GATEWAY,
                        "invalid"
                )
        );
        assertThrows(
                ResponseStatusException.class,
                () -> PaymentUrlPolicy.requireOrDefault(
                        "\r\n",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                        HttpStatus.BAD_GATEWAY,
                        "invalid"
                )
        );
    }

    @Test
    void legacyReadMapperKeepsBlankDefaultButNeverSubstitutesUnsafeRecipient() {
        assertEquals(
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.safeOrDefault(
                        "  ",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
                )
        );
        assertEquals(
                "",
                PaymentUrlPolicy.safeOrDefault(
                        "javascript:alert(1)",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
                )
        );
        assertEquals(
                "",
                PaymentUrlPolicy.safeOrDefault(
                        "\r\n",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
                )
        );
    }

    @Test
    void unsafeConfiguredDistinguishesAbsenceFromQuarantinedRawValue() {
        assertFalse(PaymentUrlPolicy.isUnsafeConfigured(null, PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL));
        assertFalse(PaymentUrlPolicy.isUnsafeConfigured("  ", PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL));
        assertFalse(PaymentUrlPolicy.isUnsafeConfigured(
                "https://pay.example/safe",
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        ));
        assertFalse(PaymentUrlPolicy.isUnsafeConfigured(
                "  https://pay.example/safe  ",
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        ));
        assertTrue(PaymentUrlPolicy.isUnsafeConfigured(
                "javascript:alert(1)",
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        ));
        assertTrue(PaymentUrlPolicy.isUnsafeConfigured("\r\n", PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL));
    }
}
