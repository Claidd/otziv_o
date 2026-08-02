package com.hunt.otziv.config.jwt.service;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtServiceTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private final JwtService jwtService = new JwtService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
    }

    @Test
    void generateChecksumCoversTheCompleteCanonicalPayload() {
        LeadDtoTransfer dto = leadTransfer();
        String original = jwtService.generateChecksum(dto);
        dto.setCompanyName("Changed company");

        assertNotEquals(original, jwtService.generateChecksum(dto));
    }

    @Test
    void generateTokenEmbedsTransferSubjectAndChecksum() {
        LeadDtoTransfer dto = leadTransfer();

        Claims claims = parse(jwtService.generateToken(dto));

        assertEquals("lead-transfer", claims.getSubject());
        assertEquals(JwtService.ISSUER, claims.getIssuer());
        assertEquals(JwtService.IMPORT_SCOPE, claims.get("scope", String.class));
        assertNotNull(claims.getId());
        assertNotNull(claims.getExpiration());
        assertNotNull(claims.get("aud"));
        assertEquals(jwtService.generateChecksum(dto), claims.get("checksum", String.class));
        assertFalse(claims.getExpiration().before(claims.getIssuedAt()));
    }

    @Test
    void generateSyncTokenUsesSyncSubjectWithoutLeadChecksum() {
        Claims claims = parse(jwtService.generateSyncToken("GET:/api/leads/modified"));

        assertEquals("lead-sync", claims.getSubject());
        assertEquals("GET:/api/leads/modified", claims.get("scope", String.class));
        assertNull(claims.get("checksum", String.class));
        assertFalse(claims.getExpiration().before(claims.getIssuedAt()));
    }

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private LeadDtoTransfer leadTransfer() {
        return LeadDtoTransfer.builder()
                .telephoneLead("79001234567")
                .cityLead("Irkutsk")
                .createDate(LocalDate.of(2026, 5, 4))
                .build();
    }
}
