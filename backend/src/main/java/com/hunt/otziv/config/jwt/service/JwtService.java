package com.hunt.otziv.config.jwt.service;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    public String generateToken(LeadDtoTransfer dto) {
        String checksum = generateChecksum(dto);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(300); // 5 минут

        return Jwts.builder()
                .setSubject("lead-transfer")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .claim("checksum", checksum)
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractChecksum(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get("checksum", String.class);
    }

    public String generateChecksum(LeadDtoTransfer dto) {
        String data = dto.getTelephoneLead() + dto.getCityLead() + dto.getCreateDate(); // или больше
        return sha256Hex(data);
    }

    private String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash)
                hexString.append(String.format("%02x", b));

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public String generateSyncToken() {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(300); // 5 минут

        return Jwts.builder()
                .setSubject("lead-sync")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}


