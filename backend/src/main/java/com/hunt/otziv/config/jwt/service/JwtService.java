package com.hunt.otziv.config.jwt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    public static final String ISSUER = "otziv-lead-integration";
    public static final String AUDIENCE = "otziv-backend";
    public static final String LEAD_TRANSFER_SUBJECT = "lead-transfer";
    public static final String LEAD_SYNC_SUBJECT = "lead-sync";
    public static final String IMPORT_SCOPE = "POST:/api/leads/import";

    private static final long TOKEN_TTL_SECONDS = 300;

    @Value("${jwt.secret}")
    private String secret;

    private final ObjectMapper canonicalMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret must contain at least 32 UTF-8 bytes");
        }
    }

    public String generateToken(LeadDtoTransfer dto) {
        return generate(LEAD_TRANSFER_SUBJECT, IMPORT_SCOPE, generateChecksum(dto));
    }

    public String generateLegacyTransferToken(LeadDtoTransfer dto) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(LEAD_TRANSFER_SUBJECT)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
                .claim("checksum", generateLegacyChecksum(dto))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateSyncToken(String scope) {
        return generate(LEAD_SYNC_SUBJECT, scope, null);
    }

    public String generateSyncToken(String scope, Object payload) {
        return generate(LEAD_SYNC_SUBJECT, scope, generateChecksum(payload));
    }

    public Claims parseAndValidate(String token, String expectedSubject, String expectedScope) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!ISSUER.equals(claims.getIssuer())
                || !expectedSubject.equals(claims.getSubject())
                || !expectedScope.equals(claims.get("scope", String.class))
                || claims.getExpiration() == null
                || !claims.getExpiration().after(new Date())
                || claims.getId() == null
                || claims.getId().isBlank()
                || !containsAudience(claims.get("aud"))) {
            throw new IllegalArgumentException("Invalid integration token claims");
        }
        return claims;
    }

    public Claims parseLegacyAndValidate(String token, String expectedSubject) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!expectedSubject.equals(claims.getSubject())
                || claims.getExpiration() == null
                || !claims.getExpiration().after(new Date())) {
            throw new IllegalArgumentException("Invalid legacy integration token");
        }
        return claims;
    }

    /** Retained for diagnostics/tests; validates the complete token contract. */
    public String extractChecksum(String token) {
        return parseAndValidate(token, LEAD_TRANSFER_SUBJECT, IMPORT_SCOPE).get("checksum", String.class);
    }

    public String generateChecksum(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload is required");
        }
        try {
            JsonNode canonical = canonicalize(canonicalMapper.valueToTree(payload));
            return sha256Hex(canonicalMapper.writeValueAsBytes(canonical));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot canonicalize integration payload", exception);
        }
    }

    public String generateLegacyChecksum(LeadDtoTransfer payload) {
        String data = String.valueOf(payload.getTelephoneLead())
                + payload.getCityLead()
                + payload.getCreateDate();
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    public String tokenFingerprint(String token) {
        return sha256Hex((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
    }

    private String generate(String subject, String scope, String checksum) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Integration token scope is required");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(subject)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
                .claim("scope", scope);
        if (checksum != null) {
            builder.claim("checksum", checksum);
        }
        return builder.signWith(signingKey(), SignatureAlgorithm.HS256).compact();
    }

    private boolean containsAudience(Object value) {
        if (value instanceof String audience) {
            return AUDIENCE.equals(audience);
        }
        if (value instanceof Collection<?> audiences) {
            return audiences.stream().anyMatch(AUDIENCE::equals);
        }
        return false;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode result = canonicalMapper.createArrayNode();
            node.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        ObjectNode result = canonicalMapper.createObjectNode();
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        fields.sort(Comparator.naturalOrder());
        fields.forEach(field -> result.set(field, canonicalize(node.get(field))));
        return result;
    }

    private String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
