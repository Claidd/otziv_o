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
 * link the manager has just replaced is never reported. Nothing changes a company's link or bound
 * group, nothing fires the link-changed flow and nothing is sent.
 */
@Service
@Slf4j
public class WhatsAppInviteLinkHealthService implements WhatsAppInviteLinkHealth {

    private final WhatsAppProperties properties;
    private final WhatsAppService whatsAppService;
    private final WhatsAppGroupCompanyLinker groupCompanyLinker;
    private final WhatsAppInviteLinkCheckStore store;
    private final boolean enabled;
    private final Duration confirmAfter;

    public WhatsAppInviteLinkHealthService(
            WhatsAppProperties properties,
            WhatsAppService whatsAppService,
            WhatsAppGroupCompanyLinker groupCompanyLinker,
            WhatsAppInviteLinkCheckStore store,
            @Value("${whatsapp.invite-check.enabled:true}") boolean enabled,
            @Value("${whatsapp.invite-check.confirm-after-minutes:20}") long confirmAfterMinutes
    ) {
        this.properties = properties;
        this.whatsAppService = whatsAppService;
        this.groupCompanyLinker = groupCompanyLinker;
        this.store = store;
        this.enabled = enabled;
        this.confirmAfter = Duration.ofMinutes(Math.max(1, confirmAfterMinutes));
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
            int stale = runCheck(LocalDateTime.now());
            log.info("WhatsApp invite link check finished: stale={}", stale);
        } catch (RuntimeException failure) {
            log.warn("WhatsApp invite link check failed: {}", failure.getMessage(), failure);
        }
    }

    /** One observation of all bound companies; returns the number of confirmed stale links. */
    int runCheck(LocalDateTime now) {
        Observation observation = observe(false);
        if (!observation.anyAvailable()) {
            // No account's group list is readable now (gateway down or not ready): keep every recorded state.
            return store.stale().size();
        }
        Map<Long, WhatsAppInviteLinkCheckStore.Row> rows = store.all();
        Set<Long> eligible = new HashSet<>();
        int stale = 0;
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
                if (row != null && WhatsAppInviteLinkCheckStore.STALE.equals(row.status())) stale++;
                continue;
            }
            if (current.equals(target.inviteCode())) {
                if (row != null) store.delete(target.companyId());
                continue;
            }
            String currentLink = inviteLink(current);
            if (row == null) {
                store.suspect(target.companyId(), target.groupId(), target.inviteCode(), currentLink, observation.snapshotAt(), now);
            } else if (WhatsAppInviteLinkCheckStore.STALE.equals(row.status())) {
                store.observeAgain(target.companyId(), currentLink, now);
                stale++;
            } else if (!Objects.equals(row.firstSnapshotAt(), observation.snapshotAt())
                    && row.firstSeenAt() != null && !row.firstSeenAt().plus(confirmAfter).isAfter(now)) {
                store.confirm(target.companyId(), target.groupId(), target.inviteCode(), currentLink, now);
                stale++;
                log.info("WhatsApp invite link is stale: companyId={}, groupId={}", target.companyId(), target.groupId());
            } else {
                store.observeAgain(target.companyId(), currentLink, now);
            }
        }
        // Banned, inactive, unbound or relinked companies no longer need the check.
        rows.keySet().stream().filter(companyId -> !eligible.contains(companyId)).forEach(store::delete);
        return stale;
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
        return new Recheck(RecheckResult.UNKNOWN, known);
    }

    /**
     * Current invite codes of administered groups from every configured account's cached group list.
     * A cache older than its TTL still counts: reading it starts a background refresh, and a stale
     * mark is confirmed only by a later, different snapshot (a stuck cache never confirms anything).
     */
    private Observation observe(boolean freshOnly) {
        Map<String, String> codes = new HashMap<>();
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
                Optional<String> code = WhatsAppGroupCompanyLinker.whatsAppInviteCode(group.inviteLink());
                if (code.isEmpty()) continue;
                String groupId = group.groupId().trim();
                String previous = codes.putIfAbsent(groupId, code.get());
                if (previous != null && !previous.equals(code.get())) conflicting.add(groupId);
            }
        }
        // Two accounts reporting different invites for one group means one cache is outdated.
        conflicting.forEach(codes::remove);
        String snapshotAt = snapshotTimes.isEmpty() ? null : String.join(",", snapshotTimes);
        if (snapshotAt != null && snapshotAt.length() > 40) {
            snapshotAt = Integer.toHexString(snapshotAt.hashCode()) + ":" + snapshotAt.substring(snapshotAt.length() - 30);
        }
        return new Observation(!snapshotTimes.isEmpty(), Map.copyOf(codes), snapshotAt);
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

    private record Observation(boolean anyAvailable, Map<String, String> inviteCodes, String snapshotAt) {
    }
}
