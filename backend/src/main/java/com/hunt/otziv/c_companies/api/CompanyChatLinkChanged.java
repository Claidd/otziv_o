package com.hunt.otziv.c_companies.api;

/**
 * Companies-owned event emitted by an already authorized company save. Messaging verifies
 * this exact invite AFTER_COMMIT, outside the edit transaction; the company-owned conditional
 * write rejects concurrent link edits. This event does not authorize message delivery.
 */
public record CompanyChatLinkChanged(Long companyId, String urlChat) { }
