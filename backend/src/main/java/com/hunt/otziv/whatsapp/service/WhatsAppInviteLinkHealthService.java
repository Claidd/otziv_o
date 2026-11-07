package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppInviteLinkHealth;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Detects revoked WhatsApp invite links before clients write. Every run reads each connected
 * account's cached group list (the same read the group sync already makes; no forced refresh, no
 * per-company provider call). When the account administers a company's bound group, WhatsApp shows
 * the group's one current invite: a different stored invite no longer opens the group. A mismatch is
 * first SUSPECTED and becomes STALE only on a later, different snapshot, so a stale gateway cache or a
 * link the manager has just replaced is never reported. A bound group whose invite no account can
 * read is counted by the gateway's reason (not a member, not an admin, refused, timeout, ...), logged
 * every run and given to the manager's re-check, so an unreadable list never looks like a clean one.
 * When two observations agree on the group's current invite, the stale stored link is replaced with
 * it: the bound group is the one messages already go to, so the bound group, the binding state and the
 * link-changed flow stay untouched and nothing is sent. Only a stale link the check cannot replace
 * (unreadable current invite, a company changed meanwhile, replacement switched off) stays STALE for
 * the manager.
 */
@Service
@Slf4j
public class WhatsAppInviteLinkHealthService implements WhatsAppInviteLinkHealth {

    private final WhatsAppProperties properties;
    private final WhatsAppService whatsAppService;
    private final WhatsAppGroupCompanyLinker groupCompanyLinker;
    private final WhatsAppInviteLinkCheckStore store;
    /** No connected account lists the bound group at all. */
    static final String NOT_LISTED = "not_listed";
    /** The gateway listed the group without an invite and without saying why (older gateway). */
    static final String UNREPORTED = "unreported";
    /** Two accounts report different invites for the group: one cache is outdated. */
    static final String CONFLICTING = "conflicting";

    private final boolean enabled;
    private final Duration confirmAfter;
    private final boolean replaceStale;

    public WhatsAppInviteLinkHealthService(
            WhatsAppProperties properties,
            WhatsAppService whatsAppService,
            WhatsAppGroupCompanyLinker groupCompanyLinker,
            WhatsAppInviteLinkCheckStore store,
            @Value("${whatsapp.invite-check.enabled:true}") boolean enabled,
            @Value("${whatsapp.invite-check.confirm-after-minutes:20}") long confirmAfterMinutes,
            @Value("${whatsapp.invite-check.replace-stale:true}") boolean replaceStale
    ) {
        this.properties = properties;
        this.whatsAppService = whatsAppService;
        this.groupCompanyLinker = groupCompanyLinker;
        this.store = store;
        this.enabled = enabled;
        this.confirmAfter = Duration.ofMinutes(Math.max(1, confirmAfterMinutes));
        this.replaceStale = replaceStale;
    }

    @Scheduled(
            fixedDelayString = "${whatsapp.invite-check.tick-delay-ms:1800000}",
            initialDelayString = "${whatsapp.invite-check.initial-delay-ms:600000}"
    )
    public void checkInviteLinks() {
        if (!enabled) {
            return;
        }
        try {
            CheckSummary summary = check(LocalDateTime.now());
            log.info("WhatsApp invite link check finished: stale={}, replaced={}, targets={}, readable={}, unreadable={}",
                    summary.stale(), summary.replaced(), summary.targets(), summary.readable(), summary.unreadable());
            if (summary.observed() && summary.targets() > 0 && summary.readable() == 0) {
                // Nothing can become stale while no invite is readable: say why instead of a silent zero.
                log.warn("WhatsApp invite link check read no invite for any of {} bound companies: {}",
                        summary.targets(), summary.unreadable());
            }
        } catch (RuntimeException failure) {
            log.warn("WhatsApp invite link check failed: {}", failure.getMessage(), failure);
        }
    }

    /** One observation of all bound companies; returns the number of confirmed stale links. */
    int runCheck(LocalDateTime now) {
        return check(now).stale();
    }

    /**
     * Outcome of one check over bound companies (targets; several may share a group). unreadable counts
     * the companies whose group's invite no account could read, by the
     * gateway's reason ({@code not_member}, {@code not_admin}, {@code refused}, {@code timeout}, ...),
     * {@code not_listed} when no account lists the group and {@code unreported} from an older gateway.
     * replaced counts the stale links replaced with their group's current invite in this run.
     */
    record CheckSummary(boolean observed, int stale, int targets, int readable, Map<String, Integer> unreadable,
                        int replaced) {
        CheckSummary(boolean observed, int stale, int targets, int readable, Map<String, Integer> unreadable) {
            this(observed, stale, targets, readable, unreadable, 0);
        }
    }

    CheckSummary check(LocalDateTime now) {
        Observation observation = observe(false);
        if (!observation.anyAvailable()) {
            // No account's group list is readable now (gateway down or not ready): keep every recorded state.
            return new CheckSummary(false, store.stale().size(), 0, 0, Map.of());
        }
        Map<Long, WhatsAppInviteLinkCheckStore.Row> rows = store.all();
        Set<Long> eligible = new HashSet<>();
        Map<String, Integer> unreadable = new TreeMap<>();
        int stale = 0;
        int readable = 0;
        int replaced = 0;
        for (WhatsAppGroupCompanyLinker.InviteTarget target : groupCompanyLinker.inviteTargets()) {
            eligible.add(target.companyId());
            WhatsAppInviteLinkCheckStore.Row row = rows.get(target.companyId());
            if (row != null && !Objects.equals(row.storedInviteCode(), target.inviteCode())) {
                // The manager saved another link: its own check starts from scratch.
                store.delete(target.companyId());
                row = null;
            }
            String current = observation.inviteCodes().get(target.groupId());
            if (current == null) {
                unreadable.merge(observation.unreadableReason(target.groupId()), 1, Integer::sum);
                if (row != null && WhatsAppInviteLinkCheckStore.STALE.equals(row.status())) stale++;
                continue;
            }
            readable++;
            if (current.equals(target.inviteCode())) {
                if (row != null) store.delete(target.companyId());
                continue;
            }
            String currentLink = inviteLink(current);
            // The previous observation already saw this same current invite: it is the group's link.
            boolean settled = row != null && currentLink.equals(row.currentInviteLink());
            if (row == null) {
                store.suspect(target.companyId(), target.groupId(), target.inviteCode(), currentLink, observation.snapshotAt(), now);
            } else if (WhatsAppInviteLinkCheckStore.STALE.equals(row.status())) {
                if (settled && replaceStaleLink(target, current)) {
                    replaced++;
                    continue;
                }
                store.observeAgain(target.companyId(), currentLink, now);
                stale++;
            } else if (!Objects.equals(row.firstSnapshotAt(), observation.snapshotAt())
                    && row.firstSeenAt() != null && !row.firstSeenAt().plus(confirmAfter).isAfter(now)) {
                if (settled && replaceStaleLink(target, current)) {
                    replaced++;
                    continue;
                }
                store.confirm(target.companyId(), target.groupId(), target.inviteCode(), currentLink, now);
                stale++;
                log.info("WhatsApp invite link is stale: companyId={}, groupId={}", target.companyId(), target.groupId());
            } else {
                store.observeAgain(target.companyId(), currentLink, now);
            }
        }
        // Banned, inactive, unbound or relinked companies no longer need the check.
        rows.keySet().stream().filter(companyId -> !eligible.contains(companyId)).forEach(store::delete);
        return new CheckSummary(true, stale, eligible.size(), readable, java.util.Collections.unmodifiableMap(unreadable), replaced);
    }

    /**
     * Writes the group's current invite into the company in place of its stale link and drops the
     * record. False keeps the stale record (and the manager's card): replacement switched off, the
     * company changed since it was read, or the write failed.
     */
    private boolean replaceStaleLink(WhatsAppGroupCompanyLinker.InviteTarget target, String currentCode) {
        if (!replaceStale) {
            return false;
        }
        try {
            if (!groupCompanyLinker.replaceStaleInviteLink(target.companyId(), target.groupId(), target.inviteCode(), currentCode)) {
                return false;
            }
        } catch (RuntimeException failure) {
            log.warn("WhatsApp stale invite link was not replaced: companyId={}, groupId={}, error={}",
                    target.companyId(), target.groupId(), failure.getMessage());
            return false;
        }
        store.delete(target.companyId());
        return true;
    }

    @Override
    public List<StaleInvite> staleInvites() {
        return store.stale().stream()
                .map(row -> new StaleInvite(row.companyId(), row.storedInviteCode(), row.currentInviteLink(),
                        row.confirmedAt() == null ? row.firstSeenAt() : row.confirmedAt()))
                .toList();
    }

    @Override
    public Recheck recheck(Long companyId) {
        Optional<WhatsAppGroupCompanyLinker.InviteTarget> found = groupCompanyLinker.inviteTarget(companyId);
        if (found.isEmpty()) {
            if (companyId != null) store.delete(companyId);
            return new Recheck(RecheckResult.NOT_APPLICABLE, null);
        }
        WhatsAppGroupCompanyLinker.InviteTarget target = found.get();
        LocalDateTime now = LocalDateTime.now();
        // A manual check concludes at once, so it trusts only a cache within its TTL.
        Observation observation = observe(true);
        String current = observation.inviteCodes().get(target.groupId());
        if (current != null) {
            if (current.equals(target.inviteCode())) {
                store.delete(target.companyId());
                return new Recheck(RecheckResult.VALID, null);
            }
            // The fresh list shows the group's current invite: put it in the company, as the manager would.
            if (replaceStaleLink(target, current)) {
                return new Recheck(RecheckResult.REFRESHED, inviteLink(current));
            }
            store.confirm(target.companyId(), target.groupId(), target.inviteCode(), inviteLink(current), now);
            return new Recheck(RecheckResult.STALE, inviteLink(current));
        }
        // The account cannot read this group's invite: ask WhatsApp where the stored link leads.
        for (String clientId : clientIds()) {
            Optional<WhatsAppGroupInfo> resolved = whatsAppService.resolveGroupByInvite(clientId, target.inviteLink());
            if (resolved.isPresent() && hasText(resolved.get().groupId())) {
                if (target.groupId().equals(resolved.get().groupId().trim())) {
                    store.delete(target.companyId());
                    return new Recheck(RecheckResult.VALID, null);
                }
                store.confirm(target.companyId(), target.groupId(), target.inviteCode(), null, now);
                return new Recheck(RecheckResult.STALE, null);
            }
        }
        String known = store.all().values().stream()
                .filter(row -> target.companyId().equals(row.companyId()))
                .map(WhatsAppInviteLinkCheckStore.Row::currentInviteLink)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        String reason = observation.anyAvailable() ? observation.unreadableReason(target.groupId()) : null;
        return new Recheck(RecheckResult.UNKNOWN, known, reason);
    }

    /**
     * Current invite codes of administered groups from every configured account's cached group list.
     * A cache older than its TTL still counts: reading it starts a background refresh, and a stale
     * mark is confirmed only by a later, different snapshot (a stuck cache never confirms anything).
     */
    private Observation observe(boolean freshOnly) {
        Map<String, String> codes = new HashMap<>();
        Map<String, String> unreadable = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        List<String> snapshotTimes = new ArrayList<>();
        for (String clientId : clientIds()) {
            WhatsAppService.GroupsSnapshot snapshot = whatsAppService.groupsSnapshot(clientId);
            if (snapshot == null || !snapshot.available() || snapshot.groups().isEmpty()
                    || !hasText(snapshot.cachedAt()) || (freshOnly && snapshot.stale())) {
                continue;
            }
            snapshotTimes.add(clientId + "@" + (snapshot.cachedAt() == null ? "" : snapshot.cachedAt()));
            for (WhatsAppGroupInfo group : snapshot.groups()) {
                if (group == null || !hasText(group.groupId())) continue;
                String groupId = group.groupId().trim();
                Optional<String> code = WhatsAppGroupCompanyLinker.whatsAppInviteCode(group.inviteLink());
                if (code.isEmpty()) {
                    unreadable.merge(groupId, unreadableStatus(group), WhatsAppInviteLinkHealthService::closerReason);
                    continue;
                }
                String previous = codes.putIfAbsent(groupId, code.get());
                if (previous != null && !previous.equals(code.get())) conflicting.add(groupId);
            }
        }
        // Two accounts reporting different invites for one group means one cache is outdated.
        conflicting.forEach(groupId -> {
            codes.remove(groupId);
            unreadable.put(groupId, CONFLICTING);
        });
        String snapshotAt = snapshotTimes.isEmpty() ? null : String.join(",", snapshotTimes);
        if (snapshotAt != null && snapshotAt.length() > 40) {
            snapshotAt = Integer.toHexString(snapshotAt.hashCode()) + ":" + snapshotAt.substring(snapshotAt.length() - 30);
        }
        return new Observation(!snapshotTimes.isEmpty(), Map.copyOf(codes), Map.copyOf(unreadable), snapshotAt);
    }

    private static String unreadableStatus(WhatsAppGroupInfo group) {
        String status = group.inviteStatus();
        // "ok" without a parsable link is a gateway/backend disagreement, not a reason of the account.
        if (!hasText(status) || "ok".equalsIgnoreCase(status.trim())) return UNREPORTED;
        return status.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Two accounts list the same group without its invite: the account still in the group explains it
     * better than the one that left, and any reported reason beats an older gateway's silence.
     */
    private static String closerReason(String previous, String next) {
        if (UNREPORTED.equals(previous) || UNREADABLE_NOT_MEMBER.equals(previous)) return next;
        return previous;
    }

    private List<String> clientIds() {
        if (properties.getClients() == null) {
            return List.of();
        }
        return properties.getClients().stream()
                .filter(Objects::nonNull)
                .filter(client -> hasText(client.getId()) && hasText(client.getUrl()))
                .map(client -> client.getId().trim())
                .distinct()
                .toList();
    }

    private static String inviteLink(String code) {
        return "https://chat.whatsapp.com/" + code;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record Observation(boolean anyAvailable, Map<String, String> inviteCodes,
                               Map<String, String> unreadable, String snapshotAt) {
        /** Why no account could read this group's invite; null codes are never silently "nothing". */
        String unreadableReason(String groupId) {
            String reason = unreadable.get(groupId);
            return reason == null ? NOT_LISTED : reason;
        }
    }
}
