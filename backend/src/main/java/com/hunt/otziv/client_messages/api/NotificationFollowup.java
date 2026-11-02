package com.hunt.otziv.client_messages.api;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;

/**
 * Messaging-owned, one informational follow-up with explicit owner acceptance of duplicate risk.
 * Callers own current business eligibility and local sending switches. No money/order creation is
 * performed here. A fresh eligibility check runs before transport; the original UNKNOWN is retained.
 */
public interface NotificationFollowup {
    enum Kind { PAYMENT_SUCCESS, ARCHIVE_CONTACT }
    record Request(Kind kind, long sourceId, String sourceVersion, String originalOperationId,
                   LocalDateTime preparedAt, ClientMessageDelivery.Target target, String clientId,
                   String message, String sourceSnapshot) { }
    record Outcome(String originalOperationId, String operationId, boolean followup,
                   ClientMessageSendResult delivery) { }
    Outcome recover(Request request, BooleanSupplier stillAuthorized);
    /** Caller business transaction is mandatory; receipt audit and business finalization commit together. */
    void markApplied(Outcome confirmed);
    static String fingerprint(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
