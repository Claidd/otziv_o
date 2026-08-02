package com.hunt.otziv.webhook.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class WebhookReplayGuard {

    private final Cache<String, Boolean> acceptedSignatures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(250_000)
            .build();

    public boolean consume(String signature) {
        return signature != null
                && !signature.isBlank()
                && acceptedSignatures.asMap().putIfAbsent(signature.trim().toLowerCase(), Boolean.TRUE) == null;
    }

    public void release(String signature) {
        if (signature != null && !signature.isBlank()) {
            acceptedSignatures.invalidate(signature.trim().toLowerCase());
        }
    }
}
