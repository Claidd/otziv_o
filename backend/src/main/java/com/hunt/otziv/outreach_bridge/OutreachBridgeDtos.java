package com.hunt.otziv.outreach_bridge;

import java.time.Instant;

public final class OutreachBridgeDtos {
    private OutreachBridgeDtos() {
    }

    public record LeadResponse(long id, String phone, String gatewayClientId, Instant lastSeen,
                               boolean offerSent, boolean initialMessageSent) {
    }

    public record LastSeenUpdate(String stage, Instant lastSeen, Long managerId) {
    }

    public record StageUpdate(String stage) {
    }

    public record MessageRequest(String phone, String message) {
    }

    public record TextResponse(String text) {
    }

    public record LastSeenReport(
            String state, int clients, int processed, int eligible, int stale,
            int noWhatsApp, int unavailable, int failed
    ) {
    }

    public record DispatchReport(
            String state, int clients, int examined, int sent, int stale,
            int noWhatsApp, int failed, int missingTemplates
    ) {
    }

    public record ReplyAfterOfferNotification(String phone, String clientId, String message, boolean containsLink) {
    }

    public record FailureNotification(String operation, Long leadId, String detail) {
    }
}
