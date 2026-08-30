package com.hunt.otziv.payments.tochka.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.payments.tochka.dto.TochkaAcquiringInternetPaymentWebhook;
import com.hunt.otziv.payments.tochka.dto.TochkaWebhookExpectation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies Tochka webhook JWTs against the bank's fixed public-JWK endpoint.
 *
 * <p>The current official webhook contract specifies RS256, but neither an
 * issuer claim nor a key id. Official examples omit both, and the published
 * JWK is a single RSA key without {@code kid}. Consequently this verifier does
 * not invent an issuer value; it binds the signed event to the exact expected
 * customer, merchant and payment instead.</p>
 */
@Service
public class TochkaWebhookJwtVerifier {

    static final URI OFFICIAL_PUBLIC_JWK_URI =
            URI.create("https://enter.tochka.com/doc/openapi/static/keys/public");
    // Tochka retries a non-200 webhook for roughly five minutes. Keep the
    // cached key well inside that window so a rotated signing key can be
    // picked up while retries are still arriving.
    static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(2);
    static final Duration JWK_FETCH_FAILURE_BACKOFF = Duration.ofSeconds(10);

    private static final String EXPECTED_ALGORITHM = "RS256";
    private static final String EXPECTED_WEBHOOK_TYPE = "acquiringInternetPayment";
    private static final Set<String> ACCEPTED_STATUSES = Set.of("APPROVED", "AUTHORIZED");
    private static final int MAX_TOKEN_LENGTH = 65_536;
    private static final int MAX_HEADER_LENGTH = 8_192;
    private static final int MAX_PAYLOAD_LENGTH = 49_152;
    private static final int MAX_JWK_DOCUMENT_LENGTH = 16_384;
    private static final Duration MAX_CACHE_TTL = Duration.ofHours(24);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Object cacheMonitor = new Object();

    private volatile CachedJwk cachedJwk;
    private Instant nextJwkFetchAllowedAt = Instant.MIN;

    @Autowired
    public TochkaWebhookJwtVerifier(
            @Qualifier("tochkaRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        this(restTemplate, objectMapper, Clock.systemUTC(), DEFAULT_CACHE_TTL);
    }

    TochkaWebhookJwtVerifier(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl
    ) {
        this.restTemplate = requireNonNull(restTemplate, "restTemplate");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper");
        this.clock = requireNonNull(clock, "clock");
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()
                || cacheTtl.compareTo(MAX_CACHE_TTL) > 0) {
            throw new IllegalArgumentException("JWK cache TTL must be between zero and 24 hours");
        }
        this.cacheTtl = cacheTtl;
    }

    /**
     * Verifies the signature and the invariant acquiring webhook envelope. Tochka's test
     * delivery may contain no payment identity fields, so strict payment validation is deferred
     * until a local candidate is found and {@link #requireMatches} is called.
     * Call {@link #requireMatches(TochkaAcquiringInternetPaymentWebhook,
     * TochkaWebhookExpectation)} after resolving the local payment and before
     * applying any state transition.
     */
    public TochkaAcquiringInternetPaymentWebhook verify(String compactJwt) {
        ParsedJwt parsedJwt = parseCompactJwt(compactJwt);
        JwtHeader header = parseAndValidateHeader(parsedJwt.header());
        CachedJwk key = getCachedJwk();
        validateKeyId(header.kid(), key.kid());
        verifySignature(parsedJwt, key.publicKey());

        TochkaAcquiringInternetPaymentWebhook claims = parseClaims(parsedJwt.payload());
        validateEnvelopeClaims(claims);
        return claims;
    }

    /**
     * Verifies the bank signature and requires every local payment identity
     * field to match exactly. Amount comparison is numeric because the bank
     * serializes ruble amounts as decimal strings.
     */
    public TochkaAcquiringInternetPaymentWebhook verifyAndMatch(
            String compactJwt,
            TochkaWebhookExpectation expected
    ) {
        requireNonNull(expected, "expected");
        return requireMatches(verify(compactJwt), expected);
    }

    /**
     * Requires already signature-verified claims to match every local payment
     * identity field. This method performs no JWK load or signature check, so
     * a handler can verify once before lookup and safely match after locking
     * the resolved payment row.
     */
    public TochkaAcquiringInternetPaymentWebhook requireMatches(
            TochkaAcquiringInternetPaymentWebhook claims,
            TochkaWebhookExpectation expected
    ) {
        requireNonNull(expected, "expected");
        validatePaymentClaims(claims);
        requireExact(claims.customerCode(), expected.customerCode(), "customerCode");
        requireExact(claims.merchantId(), expected.merchantId(), "merchantId");
        requireAmount(claims.amount(), expected.amount());
        requireExact(claims.operationId(), expected.operationId(), "operationId");
        requireExact(claims.paymentLinkId(), expected.paymentLinkId(), "paymentLinkId");
        requireExact(claims.paymentType(), expected.paymentType(), "paymentType");
        return claims;
    }

    private ParsedJwt parseCompactJwt(String compactJwt) {
        if (compactJwt == null || compactJwt.isBlank()) {
            throw rejected("Webhook JWT is empty");
        }
        if (!compactJwt.equals(compactJwt.trim()) || compactJwt.length() > MAX_TOKEN_LENGTH) {
            throw rejected("Webhook JWT has an invalid size or surrounding whitespace");
        }
        String[] segments = compactJwt.split("\\.", -1);
        if (segments.length != 3) {
            throw rejected("Webhook JWT must have three compact segments");
        }
        byte[] header = decodeSegment(segments[0], MAX_HEADER_LENGTH, "header");
        byte[] payload = decodeSegment(segments[1], MAX_PAYLOAD_LENGTH, "payload");
        byte[] signature = decodeSegment(segments[2], MAX_HEADER_LENGTH, "signature");
        return new ParsedJwt(
                segments[0] + "." + segments[1],
                header,
                payload,
                signature
        );
    }

    private byte[] decodeSegment(String encoded, int maxEncodedLength, String segmentName) {
        if (encoded.isEmpty() || encoded.length() > maxEncodedLength
                || encoded.indexOf('=') >= 0 || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw rejected("Webhook JWT " + segmentName + " is not canonical base64url");
        }
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw rejected("Webhook JWT " + segmentName + " is invalid", exception);
        }
    }

    private JwtHeader parseAndValidateHeader(byte[] encodedHeader) {
        JsonNode header;
        try {
            header = objectMapper.readTree(encodedHeader);
        } catch (Exception exception) {
            throw rejected("Webhook JWT header is invalid JSON", exception);
        }
        if (header == null || !header.isObject()) {
            throw rejected("Webhook JWT header must be a JSON object");
        }
        if (!EXPECTED_ALGORITHM.equals(textField(header, "alg", true))) {
            throw rejected("Webhook JWT algorithm must be RS256");
        }
        if (header.has("typ") && !"JWT".equals(textField(header, "typ", true))) {
            throw rejected("Webhook JWT type is invalid");
        }
        if (header.has("crit") || header.has("b64") || header.has("jku")
                || header.has("jwk") || header.has("x5u") || header.has("x5c")) {
            throw rejected("Webhook JWT contains unsupported key or critical headers");
        }
        String kid = header.has("kid") ? textField(header, "kid", true) : null;
        return new JwtHeader(kid);
    }

    private CachedJwk getCachedJwk() {
        Instant now = clock.instant();
        CachedJwk local = cachedJwk;
        if (local != null && now.isBefore(local.expiresAt())) {
            return local;
        }
        synchronized (cacheMonitor) {
            now = clock.instant();
            local = cachedJwk;
            if (local != null && now.isBefore(local.expiresAt())) {
                return local;
            }
            if (now.isBefore(nextJwkFetchAllowedAt)) {
                throw rejected("Tochka public JWK refresh is temporarily backed off");
            }
            try {
                CachedJwk refreshed = fetchJwk(now);
                cachedJwk = refreshed;
                nextJwkFetchAllowedAt = Instant.MIN;
                return refreshed;
            } catch (TochkaWebhookVerificationException exception) {
                nextJwkFetchAllowedAt = clock.instant().plus(JWK_FETCH_FAILURE_BACKOFF);
                throw exception;
            }
        }
    }

    private CachedJwk fetchJwk(Instant fetchedAt) {
        byte[] body;
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    OFFICIAL_PUBLIC_JWK_URI,
                    HttpMethod.GET,
                    null,
                    byte[].class
            );
            body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw rejected("Tochka public JWK endpoint rejected the request");
            }
        } catch (RestClientException exception) {
            throw rejected("Unable to load Tochka public JWK", exception);
        }
        if (body == null || body.length == 0 || body.length > MAX_JWK_DOCUMENT_LENGTH) {
            throw rejected("Tochka public JWK document has an invalid size");
        }

        JsonNode jwk;
        try {
            jwk = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw rejected("Tochka public JWK document is invalid JSON", exception);
        }
        if (jwk == null || !jwk.isObject()) {
            throw rejected("Tochka public JWK must be a JSON object");
        }
        if (!"RSA".equals(textField(jwk, "kty", true))) {
            throw rejected("Tochka public JWK is not an RSA key");
        }
        if (jwk.has("alg") && !EXPECTED_ALGORITHM.equals(textField(jwk, "alg", true))) {
            throw rejected("Tochka public JWK algorithm is not RS256");
        }
        if (jwk.has("use") && !"sig".equals(textField(jwk, "use", true))) {
            throw rejected("Tochka public JWK is not a signing key");
        }
        if (jwk.has("key_ops")) {
            JsonNode keyOperations = jwk.get("key_ops");
            if (!keyOperations.isArray() || keyOperations.isEmpty()
                    || !containsText(keyOperations, "verify") || containsText(keyOperations, "sign")) {
                throw rejected("Tochka public JWK key operations are invalid");
            }
        }
        if (jwk.has("d") || jwk.has("p") || jwk.has("q") || jwk.has("dp")
                || jwk.has("dq") || jwk.has("qi")) {
            throw rejected("Tochka public JWK unexpectedly contains private key material");
        }

        String modulusValue = textField(jwk, "n", true);
        String exponentValue = textField(jwk, "e", true);
        String kid = jwk.has("kid") ? textField(jwk, "kid", true) : null;
        RSAPublicKey publicKey = buildPublicKey(modulusValue, exponentValue);
        return new CachedJwk(publicKey, kid, fetchedAt.plus(cacheTtl));
    }

    private RSAPublicKey buildPublicKey(String modulusValue, String exponentValue) {
        byte[] modulusBytes = decodeJwkInteger(modulusValue, "modulus");
        byte[] exponentBytes = decodeJwkInteger(exponentValue, "exponent");
        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);
        if (modulus.bitLength() < 2_048 || modulus.bitLength() > 8_192
                || exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)) {
            throw rejected("Tochka public JWK has unsafe RSA parameters");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (GeneralSecurityException exception) {
            throw rejected("Tochka public JWK cannot be converted to an RSA key", exception);
        }
    }

    private byte[] decodeJwkInteger(String value, String fieldName) {
        if (value.indexOf('=') >= 0 || !value.matches("[A-Za-z0-9_-]+")) {
            throw rejected("Tochka public JWK " + fieldName + " is invalid");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length == 0 || decoded[0] == 0) {
                throw rejected("Tochka public JWK " + fieldName + " is not canonical");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw rejected("Tochka public JWK " + fieldName + " is invalid", exception);
        }
    }

    private void validateKeyId(String tokenKid, String jwkKid) {
        if (tokenKid == null && jwkKid == null) {
            return;
        }
        if (tokenKid == null || jwkKid == null || !tokenKid.equals(jwkKid)) {
            throw rejected("Webhook JWT key id does not match the official JWK");
        }
    }

    private void verifySignature(ParsedJwt parsedJwt, RSAPublicKey publicKey) {
        int expectedSignatureLength = (publicKey.getModulus().bitLength() + 7) / 8;
        if (parsedJwt.signature().length != expectedSignatureLength) {
            throw rejected("Webhook JWT signature has an invalid length");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(parsedJwt.signingInput().getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(parsedJwt.signature())) {
                throw rejected("Webhook JWT signature is invalid");
            }
        } catch (GeneralSecurityException exception) {
            throw rejected("Webhook JWT signature could not be verified", exception);
        }
    }

    private TochkaAcquiringInternetPaymentWebhook parseClaims(byte[] payload) {
        try {
            return objectMapper.readValue(payload, TochkaAcquiringInternetPaymentWebhook.class);
        } catch (Exception exception) {
            throw rejected("Webhook JWT claims are invalid", exception);
        }
    }

    private void validateEnvelopeClaims(TochkaAcquiringInternetPaymentWebhook claims) {
        if (claims == null) {
            throw rejected("Webhook JWT claims are empty");
        }
        requireExact(claims.webhookType(), EXPECTED_WEBHOOK_TYPE, "webhookType");
    }

    private void validatePaymentClaims(TochkaAcquiringInternetPaymentWebhook claims) {
        validateEnvelopeClaims(claims);
        requireClaimText(claims.customerCode(), "customerCode", 64);
        requireClaimText(claims.merchantId(), "merchantId", 64);
        requireClaimText(claims.operationId(), "operationId", 256);
        requireClaimText(claims.paymentLinkId(), "paymentLinkId", 256);
        requireClaimText(claims.paymentType(), "paymentType", 64);
        requireClaimText(claims.status(), "status", 64);
        if (!ACCEPTED_STATUSES.contains(claims.status())) {
            throw rejected("Webhook JWT payment status is unsupported");
        }
        BigDecimal amount = claims.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw rejected("Webhook JWT amount is invalid");
        }
    }

    private void requireClaimText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > maxLength) {
            throw rejected("Webhook JWT " + field + " is invalid");
        }
    }

    private void requireExact(String actual, String expected, String field) {
        if (actual == null || !actual.equals(expected)) {
            throw rejected("Webhook JWT " + field + " does not match the payment");
        }
    }

    private void requireAmount(BigDecimal actual, BigDecimal expected) {
        if (actual == null || actual.compareTo(expected) != 0) {
            throw rejected("Webhook JWT amount does not match the payment");
        }
    }

    private String textField(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw rejected("Required JWK/JWT field is missing");
            }
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()
                || !value.textValue().equals(value.textValue().trim())
                || value.textValue().length() > MAX_HEADER_LENGTH) {
            throw rejected("JWK/JWT text field is invalid");
        }
        return value.textValue();
    }

    private boolean containsText(JsonNode array, String expected) {
        for (JsonNode item : array) {
            if (item.isTextual() && expected.equals(item.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private TochkaWebhookVerificationException rejected(String message) {
        return new TochkaWebhookVerificationException(message);
    }

    private TochkaWebhookVerificationException rejected(String message, Throwable cause) {
        return new TochkaWebhookVerificationException(message, cause);
    }

    private record JwtHeader(String kid) {
    }

    private record ParsedJwt(
            String signingInput,
            byte[] header,
            byte[] payload,
            byte[] signature
    ) {
    }

    private record CachedJwk(
            RSAPublicKey publicKey,
            String kid,
            Instant expiresAt
    ) {
    }
}
