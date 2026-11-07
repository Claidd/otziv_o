package com.hunt.otziv.whatsapp.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Health of the companies' stored WhatsApp invite links. A link is stale when the connected account
 * sees a different current invite for the company's bound group (confirmed by two different group
 * snapshots). When the account reads the group's current invite, the stored link is replaced with it:
 * the same group, so the bound group, the binding state and the link-changed flow stay untouched and
 * nothing is sent. Only a stale link that cannot be replaced that way reaches the manager.
 */
public interface WhatsAppInviteLinkHealth {

    /** currentInviteLink is null when the connected account cannot read the group's invite. */
    record StaleInvite(Long companyId, String storedInviteCode, String currentInviteLink, LocalDateTime staleSince) {
    }

    /** REFRESHED: the stale link was replaced with the group's current invite (in currentInviteLink). */
    enum RecheckResult { VALID, STALE, UNKNOWN, NOT_APPLICABLE, REFRESHED }

    /**
     * unreadableReason explains an UNKNOWN result when the gateway reported why the connected
     * account cannot read the group's invite (for example {@code not_member} or {@code not_admin}).
     */
    record Recheck(RecheckResult result, String currentInviteLink, String unreadableReason) {
        public Recheck(RecheckResult result, String currentInviteLink) {
            this(result, currentInviteLink, null);
        }
    }

    /** The account was removed from the group: it can neither read the invite nor deliver there. */
    String UNREADABLE_NOT_MEMBER = "not_member";
    /** The account is a participant without admin rights: WhatsApp does not show it the invite. */
    String UNREADABLE_NOT_ADMIN = "not_admin";

    /** Confirmed stale links of eligible companies (active, not banned, bound, not waiting for a new link). */
    List<StaleInvite> staleInvites();

    /**
     * Manager's repair of one company: checks the stored link now (invite lookup, then the group list).
     * VALID and NOT_APPLICABLE clear the recorded state; a stale link whose group's current invite is
     * readable is replaced (REFRESHED); otherwise STALE records it at once.
     */
    Recheck recheck(Long companyId);
}
