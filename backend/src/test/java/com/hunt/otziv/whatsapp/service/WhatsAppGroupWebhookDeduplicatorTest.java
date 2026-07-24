package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsAppGroupWebhookDeduplicatorTest {

    @Test
    void suppressesInFlightAndCompletedDelivery() {
        WhatsAppGroupWebhookDeduplicator deduplicator = new WhatsAppGroupWebhookDeduplicator(
                86_400_000L,
                Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC)
        );
        WhatsAppGroupReplyDTO reply = reply("message-1");

        assertTrue(deduplicator.acquire(reply));
        assertFalse(deduplicator.acquire(reply));
        deduplicator.complete(reply);
        assertFalse(deduplicator.acquire(reply));
    }

    @Test
    void releasedDeliveryCanBeRetried() {
        WhatsAppGroupWebhookDeduplicator deduplicator = new WhatsAppGroupWebhookDeduplicator(
                86_400_000L,
                Clock.systemUTC()
        );
        WhatsAppGroupReplyDTO reply = reply("message-2");

        assertTrue(deduplicator.acquire(reply));
        deduplicator.release(reply);
        assertTrue(deduplicator.acquire(reply));
    }

    private static WhatsAppGroupReplyDTO reply(String messageId) {
        WhatsAppGroupReplyDTO reply = new WhatsAppGroupReplyDTO();
        reply.setClientId("whatsapp_vika");
        reply.setGroupId("12001@g.us");
        reply.setMessageId(messageId);
        return reply;
    }
}
