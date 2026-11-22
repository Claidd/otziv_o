package com.hunt.otziv.client_messages.api;

import java.time.LocalDateTime;

/** Scalar, owner-controlled view of an uncertain WhatsApp operation for manual review. */
public interface UncertainClientMessageDelivery {
    record Preview(Long stateId, String operationId, String message, LocalDateTime preparedAt) { }

    boolean hasUnknownOutcome(Long stateId);

    Preview preview(Long stateId);

    Preview handoff(Long stateId, String operationId, Long actorUserId, Long controlEntityId, String note);

    /** What the manager saw in the client's chat for an unconfirmed send. */
    enum DeliveryDecision {
        /** The message is in the chat: count it as delivered and continue the schedule. */
        SEEN_IN_CHAT,
        /** The message is not in the chat: send it again now as a new operation. */
        NOT_IN_CHAT,
        /** Do not send it: the task closes for this stage of the order, the manager leads the client. */
        CLOSE
    }

    record DecisionOutcome(DeliveryDecision decision, String message) { }

    /**
     * The manager's decision on an unconfirmed send. Receipts are checked again first: a confirmation found by the
     * system wins over the decision. One decision per operation.
     */
    DecisionOutcome decide(Long stateId, String operationId, DeliveryDecision decision, Long actorUserId,
                           Long controlEntityId, String note);

    /** The active autoresponder task of the order whose last send is unconfirmed, if any. */
    java.util.Optional<Long> unconfirmedStateForOrder(Long orderId);
}
