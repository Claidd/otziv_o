package com.hunt.otziv.business_audit.api;

import java.time.LocalDateTime;

/** Audit-owned evidence projection. Returns no credentials, entities or audit payloads. */
public interface CredentialRevealEvidence {
    boolean hasBothCredentials(String actor, String entityType, Long entityId, Long botId,
            LocalDateTime since, LocalDateTime until);
}
