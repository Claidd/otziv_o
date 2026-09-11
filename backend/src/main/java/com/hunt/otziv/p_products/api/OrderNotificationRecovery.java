package com.hunt.otziv.p_products.api;

import java.time.LocalDateTime;
import java.util.Optional;

/** Order-owned part of recovery; both calls require the caller's transaction. */
public interface OrderNotificationRecovery {
    record LegacyCycle(long orderId, String status, LocalDateTime startedAt) {}

    /** Acquires Order before the caller locks message State, even when the cycle is ineligible. */
    Optional<LegacyCycle> lockLegacyCycle(long orderId);

    /** Caller has proved absence of delivery evidence while retaining the Order and State locks. */
    boolean beginLegacyCycle(long orderId, LocalDateTime expectedStartedAt);
}
