package com.hunt.otziv.client_messages.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only view of the existing payment deadline, never a status command. */
public interface PaymentDeadlineNoticeSource {
    record Deadline(long stateId, long orderId) {}

    List<Long> approachingIds(LocalDateTime now, LocalDateTime horizon, long afterId, int limit);
    Optional<Deadline> dueWithin(long stateId, LocalDateTime now, LocalDateTime horizon);
    boolean isActive(long stateId);
}
