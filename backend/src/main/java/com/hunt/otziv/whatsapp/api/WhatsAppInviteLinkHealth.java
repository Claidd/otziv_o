package com.hunt.otziv.whatsapp.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only health of the companies' stored WhatsApp invite links. A link is stale when the
 * connected account sees a different current invite for the company's bound group (confirmed by two
 * different group snapshots). Nothing here changes a company's link or bound group, fires the
 * link-changed flow or sends anything; callers own the presentation and the manager's repair.
 */
public interface WhatsAppInviteLinkHealth {

    /** currentInviteLink is null when the connected account cannot read the group's invite. */
    record StaleInvite(Long companyId, String storedInviteCode, String currentInviteLink, LocalDateTime staleSince) {
    }

    enum RecheckResult { VALID, STALE, UNKNOWN, NOT_APPLICABLE }

    record Recheck(RecheckResult result, String currentInviteLink) {
    }

    /** Confirmed stale links of eligible companies (active, not banned, bound, not waiting for a new link). */
    List<StaleInvite> staleInvites();

    /**
     * Manager's repair of one company: checks the stored link now (invite lookup, then the group list).
     * VALID and NOT_APPLICABLE clear the recorded state; STALE records it at once.
     */
    Recheck recheck(Long companyId);
}
