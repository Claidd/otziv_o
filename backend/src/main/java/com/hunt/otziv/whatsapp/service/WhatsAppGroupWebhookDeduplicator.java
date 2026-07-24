package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppGroupWebhookDeduplicator {

    private final long ttlMillis;
    private final Clock clock;
    private final Map<String, Long> completed = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();

    @Autowired
    public WhatsAppGroupWebhookDeduplicator(
            @Value("${whatsapp.webhook.dedup-ttl-ms:86400000}") long ttlMillis
    ) {
        this(ttlMillis, Clock.systemUTC());
    }

    WhatsAppGroupWebhookDeduplicator(long ttlMillis, Clock clock) {
        this.ttlMillis = Math.max(60_000L, ttlMillis);
        this.clock = clock;
    }

    public synchronized boolean acquire(WhatsAppGroupReplyDTO reply) {
        String key = key(reply);
        if (key == null) {
            return true;
        }
        cleanup();
        if (completed.containsKey(key) || inFlight.contains(key)) {
            return false;
        }
        inFlight.add(key);
        return true;
    }

    public synchronized void complete(WhatsAppGroupReplyDTO reply) {
        String key = key(reply);
        if (key == null) {
            return;
        }
        inFlight.remove(key);
        completed.put(key, clock.millis() + ttlMillis);
    }

    public synchronized void release(WhatsAppGroupReplyDTO reply) {
        String key = key(reply);
        if (key != null) {
            inFlight.remove(key);
        }
    }

    private void cleanup() {
        long now = clock.millis();
        completed.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private String key(WhatsAppGroupReplyDTO reply) {
        if (reply == null || !hasText(reply.getMessageId())) {
            return null;
        }
        return safe(reply.getClientId()) + "|" + safe(reply.getGroupId()) + "|" + reply.getMessageId().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
