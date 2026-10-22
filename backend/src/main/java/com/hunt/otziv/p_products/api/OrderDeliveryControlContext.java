package com.hunt.otziv.p_products.api;

import java.time.LocalDateTime;
import java.util.Optional;

/** Scalar order-cycle evidence. Caller authorizes its card and compares managerId; no delivery commands. */
public interface OrderDeliveryControlContext {
    record Snapshot(Long orderId, Long managerId, String status, long generation,
            LocalDateTime cycleAt, boolean waitingForClient, LocalDateTime waitingAt) { }
    Optional<Snapshot> read(Long orderId);
    /** Caller transaction required; lock order is card, order, then delivery queue. */
    Optional<Snapshot> lock(Long orderId);
}
