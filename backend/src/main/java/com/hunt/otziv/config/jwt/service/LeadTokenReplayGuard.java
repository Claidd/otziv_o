package com.hunt.otziv.config.jwt.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;

@Component
public class LeadTokenReplayGuard {

    private static final String DELETE_EXPIRED_TOKEN = """
            DELETE FROM lead_integration_token_claims
            WHERE token_hash = ?
              AND expires_at_epoch_seconds <= UNIX_TIMESTAMP()
            """;
    private static final String INSERT_TOKEN = """
            INSERT INTO lead_integration_token_claims (token_hash, expires_at_epoch_seconds, created_at)
            VALUES (?, ?, CURRENT_TIMESTAMP(6))
            """;
    private static final String RELEASE_TOKEN = """
            DELETE FROM lead_integration_token_claims
            WHERE token_hash = ?
            """;
    private static final String CLEANUP_EXPIRED_TOKENS = """
            DELETE FROM lead_integration_token_claims
            WHERE expires_at_epoch_seconds <= UNIX_TIMESTAMP()
            LIMIT 5000
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Cache<String, Boolean> inMemoryTestClaims;

    @Autowired
    public LeadTokenReplayGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.inMemoryTestClaims = null;
    }

    private LeadTokenReplayGuard(Cache<String, Boolean> inMemoryTestClaims) {
        this.jdbcTemplate = null;
        this.inMemoryTestClaims = inMemoryTestClaims;
    }

    static LeadTokenReplayGuard inMemoryForTests() {
        return new LeadTokenReplayGuard(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(100_000)
                .build());
    }

    /**
     * Atomically consumes a token id across every application replica and across restarts.
     * Only a SHA-256 digest is persisted, so the JWT id/fingerprint is not recoverable from the table.
     */
    public boolean consume(String tokenId, Instant validUntil) {
        if (tokenId == null || tokenId.isBlank() || validUntil == null || !validUntil.isAfter(Instant.now())) {
            return false;
        }
        if (inMemoryTestClaims != null) {
            return inMemoryTestClaims.asMap().putIfAbsent(tokenId, Boolean.TRUE) == null;
        }

        byte[] tokenHash = tokenHash(tokenId);
        jdbcTemplate.update(DELETE_EXPIRED_TOKEN, tokenHash);
        try {
            return jdbcTemplate.update(INSERT_TOKEN, tokenHash, validUntil.getEpochSecond()) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public void release(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        if (inMemoryTestClaims != null) {
            inMemoryTestClaims.invalidate(tokenId);
            return;
        }
        jdbcTemplate.update(RELEASE_TOKEN, tokenHash(tokenId));
    }

    @Scheduled(
            initialDelayString = "${lead.integration.replay-cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${lead.integration.replay-cleanup-delay-ms:600000}"
    )
    public void cleanupExpiredClaims() {
        if (jdbcTemplate != null) {
            jdbcTemplate.update(CLEANUP_EXPIRED_TOKENS);
        }
    }

    private byte[] tokenHash(String tokenId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(tokenId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
