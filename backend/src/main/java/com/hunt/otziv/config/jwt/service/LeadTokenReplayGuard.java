package com.hunt.otziv.config.jwt.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LeadTokenReplayGuard {

    private final Cache<String, Boolean> consumedTokenIds = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    /** Atomically consumes a token id. */
    public boolean consume(String tokenId) {
        return tokenId != null
                && !tokenId.isBlank()
                && consumedTokenIds.asMap().putIfAbsent(tokenId, Boolean.TRUE) == null;
    }

    public void release(String tokenId) {
        if (tokenId != null && !tokenId.isBlank()) {
            consumedTokenIds.invalidate(tokenId);
        }
    }
}
