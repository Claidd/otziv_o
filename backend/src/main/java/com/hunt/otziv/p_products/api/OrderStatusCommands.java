package com.hunt.otziv.p_products.api;

import org.springframework.security.core.Authentication;

/** Human order status mutations. Each entry preserves its own staff policy. */
public interface OrderStatusCommands {
    enum EntryPoint { WORKER_BOARD, MANAGER_BOARD, LEGACY_STAFF, LEGACY_WORKER_SUBMISSION }
    boolean changeStatus(Long orderId, Long expectedCompanyId, String status, Authentication actor, EntryPoint entry) throws Exception;
}
