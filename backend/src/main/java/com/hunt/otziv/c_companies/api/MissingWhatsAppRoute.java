package com.hunt.otziv.c_companies.api;

import java.util.Optional;

/**
 * Company-owned route repair for an already authorized automated notification, not an HTTP command.
 * Messaging resolves the exact invite outside transactions; the company owner conditionally fills
 * only a still-missing route. A changed invite (including case), existing route or ban rejects the write.
 */
public interface MissingWhatsAppRoute {
    record Snapshot(Long companyId, String invite, String groupId, boolean bindingRequired) { }
    Optional<Snapshot> find(Long companyId);
    boolean restore(Snapshot expected, String groupId);
}
