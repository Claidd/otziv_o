package com.hunt.otziv.config.jwt.service;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtServiceTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private final JwtService jwtService = new JwtService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
    }

    @Test
    void generateChecksumUsesStableLeadIdentityFields() {
        LeadDtoTransfer dto = leadTransfer();

        assertEquals(
                "237f99715bfb94a6a95cb2e61edec7279a3024211b1a4929ec2e3aa5d4294774",
                jwtService.generateChecksum(dto)
        );
    }

    @Test
    void generateTokenEmbedsTransferSubjectAndChecksum() {
        LeadDtoTransfer dto = leadTransfer();

        Claims claims = parse(jwtService.generateToken(dto));

        assertEquals("lead-transfer", claims.getSubject());
        assertEquals(jwtService.generateChecksum(dto), claims.get("checksum", String.class));
        assertFalse(claims.getExpiration().before(claims.getIssuedAt()));
    }

    @Test
    void generateSyncTokenUsesSyncSubjectWithoutLeadChecksum() {
        Claims claims = parse(jwtService.generateSyncToken());

        assertEquals("lead-sync", claims.getSubject());
        assertNull(claims.get("checksum", String.class));
        assertFalse(claims.getExpiration().before(claims.getIssuedAt()));
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET.getBytes(StandardCharsets.UTF_8))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private LeadDtoTransfer leadTransfer() {
        return LeadDtoTransfer.builder()
                .telephoneLead("79001234567")
                .cityLead("Irkutsk")
                .createDate(LocalDate.of(2026, 5, 4))
                .build();
    }
}
