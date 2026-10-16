package com.hunt.otziv.client_messages.api;

import java.util.List;
import java.util.Optional;

/** Transaction-scoped, scalar reminder evidence for common-invoice approval. */
public interface ReviewApprovalReminderStates {
    record Candidate(Long stateId, Long invoiceId) { }
    record Evidence(Long orderId, int sentCount, String targetKey) { }

    List<Candidate> candidatesAfter(long afterId, int limit);
    Optional<Evidence> lockEligible(Long stateId, int requiredReminders);
    void complete(Long stateId);
}
