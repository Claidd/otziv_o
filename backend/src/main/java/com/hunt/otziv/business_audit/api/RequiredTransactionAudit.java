package com.hunt.otziv.business_audit.api;

import org.springframework.security.core.Authentication;

/** Audit evidence that must commit or roll back with the caller's financial mutation. */
public interface RequiredTransactionAudit {
    void recordRequiredInCurrentTransaction(
            Authentication authentication, String action, String entityType, Object entityId,
            Long orderId, Long reviewId, Object oldValue, Object newValue, String details);
}
