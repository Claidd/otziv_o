package com.hunt.otziv.payments.tochka.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.payments.tochka.dto.TochkaAcquiringInternetPaymentWebhook;
import com.hunt.otziv.payments.tochka.dto.TochkaWebhookExpectation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TochkaWebhookJwtVerifierTest {

    private static final String CUSTOMER_CODE = "1234567ab";
    private static final String MERCHANT_ID = "200000000001234";
    private static final String OPERATION_ID = "beea8a4-6047-3f38-8922-a664e6b5c43b";
    private static final String PAYMENT_LINK_ID = "payment-link-42-v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private KeyPair bankKeyPair;
    private MutableClock clock;
    private TochkaWebhookJwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        bankKeyPair = rsaKeyPair();
        clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        verifier = new TochkaWebhookJwtVerifier(
                restTemplate,
                objectMapper,
                clock,
                TochkaWebhookJwtVerifier.DEFAULT_CACHE_TTL
        );
    }

    @Test
    void verifiesOfficialRs256ShapeAndBindsEveryPaymentIdentityField() throws Exception {
        expectOfficialJwkOnce();
        Map<String, Object> claims = validClaims();
        claims.put("futureOptionalField", "ignored-after-signature-verification");
        String token = sign(Map.of("alg", "RS256", "typ", "JWT"), claims, bankKeyPair);

        var webhook = verifier.verifyAndMatch(token, expectation());

        assertEquals("acquiringInternetPayment", webhook.webhookType());
        assertEquals("sbp", webhook.paymentType());
        assertEquals("APPROVED", webhook.status());
        assertEquals(new BigDecimal("123.45"), webhook.amount());
        assertEquals(OPERATION_ID, webhook.operationId());
        assertEquals(PAYMENT_LINK_ID, webhook.paymentLinkId());
        server.verify();
    }

    @Test
    void rejectsEveryMismatchAgainstPersistedPaymentValues() throws Exception {
        expectOfficialJwkOnce();
        String token = sign(validClaims(), bankKeyPair);

        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verifyAndMatch(
                token,
                new TochkaWebhookExpectation("other0001", MERCHANT_ID, amount(), OPERATION_ID, PAYMENT_LINK_ID, "sbp")
        ));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verifyAndMatch(
                token,
                new TochkaWebhookExpectation(CUSTOMER_CODE, "200000000009999", amount(), OPERATION_ID, PAYMENT_LINK_ID, "sbp")
        ));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verifyAndMatch(
                token,
                new TochkaWebhookExpectation(CUSTOMER_CODE, MERCHANT_ID, new BigDecimal("123.46"), OPERATION_ID, PAYMENT_LINK_ID, "sbp")
        ));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verifyAndMatch(
                token,
                new TochkaWebhookExpectation(CUSTOMER_CODE, MERCHANT_ID, amount(), "other-operation", PAYMENT_LINK_ID, "sbp")
        ));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verifyAndMatch(
                token,
                new TochkaWebhookExpectation(CUSTOMER_CODE, MERCHANT_ID, amount(), OPERATION_ID, "other-link", "sbp")
        ));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verifyAndMatch(
                token,
                new TochkaWebhookExpectation(
                        CUSTOMER_CODE, MERCHANT_ID, amount(), OPERATION_ID, PAYMENT_LINK_ID, "card"
                )
        ));
        server.verify();
    }

    @Test
    void matchesAlreadyVerifiedClaimsWithoutRefetchAndRejectsMutatedClaims() throws Exception {
        expectOfficialJwkOnce();
        TochkaAcquiringInternetPaymentWebhook verified = verifier.verify(
                sign(validClaims(), bankKeyPair)
        );

        assertSame(verified, verifier.requireMatches(verified, expectation()));

        TochkaAcquiringInternetPaymentWebhook mutated = copyWithOperationId(
                verified,
                "other-operation"
        );
        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.requireMatches(mutated, expectation())
        );
        server.verify();
    }

    @Test
    void rejectsWrongEventTypeEvenWhenSignedByBankKey() throws Exception {
        expectOfficialJwkOnce();
        Map<String, Object> claims = validClaims();
        claims.put("webhookType", "incomingSbpPayment");

        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.verify(sign(claims, bankKeyPair))
        );
        server.verify();
    }

    @Test
    void rejectsAlgorithmSubstitutionBeforeFetchingAKey() throws Exception {
        String token = sign(Map.of("alg", "HS256", "typ", "JWT"), validClaims(), bankKeyPair);

        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));
        server.verify();
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        expectOfficialJwkOnce();
        KeyPair attackerKeyPair = rsaKeyPair();

        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.verify(sign(validClaims(), attackerKeyPair))
        );
        server.verify();
    }

    @Test
    void rejectsTokenKidBecauseOfficialSingleJwkHasNoKid() throws Exception {
        expectOfficialJwkOnce();
        String token = sign(
                Map.of("alg", "RS256", "typ", "JWT", "kid", "untrusted-key-choice"),
                validClaims(),
                bankKeyPair
        );

        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));
        server.verify();
    }

    @Test
    void refreshesJwkWithinDocumentedWebhookRetryWindow() throws Exception {
        assertEquals(Duration.ofMinutes(2), TochkaWebhookJwtVerifier.DEFAULT_CACHE_TTL);
        server.expect(ExpectedCount.twice(), requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jwkJson(bankKeyPair, null), MediaType.APPLICATION_OCTET_STREAM));
        String token = sign(validClaims(), bankKeyPair);

        verifier.verify(token);
        clock.advance(TochkaWebhookJwtVerifier.DEFAULT_CACHE_TTL.minusSeconds(1));
        verifier.verify(token);
        clock.advance(Duration.ofSeconds(1));
        verifier.verify(token);

        server.verify();
    }

    @Test
    void failsClosedWhenOfficialJwkEndpointIsUnavailable() throws Exception {
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withServerError());

        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.verify(sign(validClaims(), bankKeyPair))
        );
        server.verify();
    }

    @Test
    void backsOffRepeatedColdJwkFetchAfterOutageAndRecoversAfterBoundedDelay() throws Exception {
        assertEquals(Duration.ofSeconds(10), TochkaWebhookJwtVerifier.JWK_FETCH_FAILURE_BACKOFF);
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withServerError());
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withSuccess(jwkJson(bankKeyPair, null), MediaType.APPLICATION_OCTET_STREAM));
        String token = sign(validClaims(), bankKeyPair);

        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));
        clock.advance(TochkaWebhookJwtVerifier.JWK_FETCH_FAILURE_BACKOFF.minusMillis(1));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));

        clock.advance(Duration.ofMillis(1));
        assertEquals(OPERATION_ID, verifier.verify(token).operationId());
        assertEquals(OPERATION_ID, verifier.verify(token).operationId());
        server.verify();
    }

    @Test
    void expiredJwkIsNotUsedAsStaleDuringOutageAndRefreshRespectsBackoff() throws Exception {
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withSuccess(jwkJson(bankKeyPair, null), MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withServerError());
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withSuccess(jwkJson(bankKeyPair, null), MediaType.APPLICATION_OCTET_STREAM));
        String token = sign(validClaims(), bankKeyPair);

        assertEquals(OPERATION_ID, verifier.verify(token).operationId());
        clock.advance(TochkaWebhookJwtVerifier.DEFAULT_CACHE_TTL);
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));
        assertThrows(TochkaWebhookVerificationException.class, () -> verifier.verify(token));

        clock.advance(TochkaWebhookJwtVerifier.JWK_FETCH_FAILURE_BACKOFF);
        assertEquals(OPERATION_ID, verifier.verify(token).operationId());
        server.verify();
    }

    @Test
    void failsClosedForNonRsaOrPrivateJwkDocuments() throws Exception {
        String invalidJwk = """
                {"kty":"EC","e":"AQAB","n":"AQAB","d":"private"}
                """;
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andRespond(withSuccess(invalidJwk, MediaType.APPLICATION_JSON));

        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.verify(sign(validClaims(), bankKeyPair))
        );
        server.verify();
    }

    @Test
    void rejectsUnboundedJwkCacheTtlAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new TochkaWebhookJwtVerifier(
                restTemplate,
                objectMapper,
                clock,
                Duration.ofDays(2)
        ));
    }

    @Test
    void rejectsUnsupportedStatusAndNonCanonicalCompactEncoding() throws Exception {
        expectOfficialJwkOnce();
        Map<String, Object> claims = validClaims();
        claims.put("status", "FAILED");

        TochkaAcquiringInternetPaymentWebhook verified = verifier.verify(sign(claims, bankKeyPair));
        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.requireMatches(verified, expectation())
        );
        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.verify(" " + sign(validClaims(), bankKeyPair))
        );
        server.verify();
    }

    @Test
    void acceptsSignedMinimalAcquiringTestEnvelopeBeforePaymentMatching() throws Exception {
        expectOfficialJwkOnce();

        TochkaAcquiringInternetPaymentWebhook verified = verifier.verify(sign(
                Map.of("webhookType", "acquiringInternetPayment"),
                bankKeyPair
        ));

        assertEquals("acquiringInternetPayment", verified.webhookType());
        assertNull(verified.paymentLinkId());
        assertNull(verified.operationId());
        assertThrows(
                TochkaWebhookVerificationException.class,
                () -> verifier.requireMatches(verified, expectation())
        );
        server.verify();
    }

    private void expectOfficialJwkOnce() throws Exception {
        server.expect(requestTo(TochkaWebhookJwtVerifier.OFFICIAL_PUBLIC_JWK_URI))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jwkJson(bankKeyPair, null), MediaType.APPLICATION_OCTET_STREAM));
    }

    private TochkaWebhookExpectation expectation() {
        return new TochkaWebhookExpectation(
                CUSTOMER_CODE,
                MERCHANT_ID,
                amount(),
                OPERATION_ID,
                PAYMENT_LINK_ID,
                "sbp"
        );
    }

    private TochkaAcquiringInternetPaymentWebhook copyWithOperationId(
            TochkaAcquiringInternetPaymentWebhook source,
            String operationId
    ) {
        return new TochkaAcquiringInternetPaymentWebhook(
                source.customerCode(),
                source.amount(),
                source.paymentType(),
                operationId,
                source.transactionId(),
                source.purpose(),
                source.qrcId(),
                source.merchantId(),
                source.webhookType(),
                source.payerName(),
                source.consumerId(),
                source.status(),
                source.paymentLinkId(),
                source.maskedPan(),
                source.cardType(),
                source.tokenCardId()
        );
    }

    private BigDecimal amount() {
        return new BigDecimal("123.45");
    }

    private Map<String, Object> validClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("customerCode", CUSTOMER_CODE);
        claims.put("amount", "123.45");
        claims.put("paymentType", "sbp");
        claims.put("operationId", OPERATION_ID);
        claims.put("transactionId", "43c63ec1-42fa-a704-dde7-6025c20b96ce");
        claims.put("purpose", "Оплата заказа 42");
        claims.put("qrcId", "AS10006DPRTEFPFS9HJ9SQSDSVRHJD3L");
        claims.put("payerName", "Иван Иванович И.");
        claims.put("webhookType", "acquiringInternetPayment");
        claims.put("merchantId", MERCHANT_ID);
        claims.put("status", "APPROVED");
        claims.put("paymentLinkId", PAYMENT_LINK_ID);
        return claims;
    }

    private String sign(Map<String, Object> claims, KeyPair keyPair) throws Exception {
        return sign(Map.of("alg", "RS256", "typ", "JWT"), claims, keyPair);
    }

    private String sign(
            Map<String, Object> header,
            Map<String, Object> claims,
            KeyPair keyPair
    ) throws Exception {
        String encodedHeader = encode(objectMapper.writeValueAsBytes(header));
        String encodedPayload = encode(objectMapper.writeValueAsBytes(claims));
        String signingInput = encodedHeader + "." + encodedPayload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encode(signature.sign());
    }

    private String jwkJson(KeyPair keyPair, String kid) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("e", encode(unsigned(publicKey.getPublicExponent())));
        jwk.put("n", encode(unsigned(publicKey.getModulus())));
        if (kid != null) {
            jwk.put("kid", kid);
        }
        return objectMapper.writeValueAsString(jwk);
    }

    private byte[] unsigned(BigInteger value) {
        byte[] signed = value.toByteArray();
        if (signed.length > 1 && signed[0] == 0) {
            byte[] unsigned = new byte[signed.length - 1];
            System.arraycopy(signed, 1, unsigned, 0, unsigned.length);
            return unsigned;
        }
        return signed;
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048);
        return generator.generateKeyPair();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
