package com.hunt.otziv.client_messages.api;

import java.time.LocalDateTime;

/** Scalar, owner-controlled view of an uncertain WhatsApp operation for manual review. */
public interface UncertainClientMessageDelivery {
    record Preview(Long stateId, String operationId, String message, LocalDateTime preparedAt) { }

    boolean hasUnknownOutcome(Long stateId);

    Preview preview(Long stateId);

    Preview handoff(Long stateId, String operationId, Long actorUserId, Long controlEntityId, String note);
}
