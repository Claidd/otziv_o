package com.hunt.otziv.l_lead.dto;

/** Returned only after the receiver's receipt and business mutation commit atomically. */
public record LeadCommandReceipt(int protocolVersion, String sourceId, long entityId,
        long entityVersion, String operationId, String kind, String payloadHash,
        String outcome, long appliedVersion) {
    public static LeadCommandReceipt of(LeadCommandIdentity identity, String hash, String outcome, long appliedVersion) {
        return new LeadCommandReceipt(identity.protocolVersion(), identity.sourceId(), identity.entityId(),
                identity.entityVersion(), identity.operationId(), identity.kind(), hash, outcome, appliedVersion);
    }
    public boolean matches(LeadCommandIdentity identity, String hash) {
        return protocolVersion == 1 && equals(of(identity, hash, outcome, appliedVersion))
                && (("APPLIED".equals(outcome) && appliedVersion == entityVersion)
                    || ("STALE".equals(outcome) && appliedVersion > entityVersion));
    }
}
