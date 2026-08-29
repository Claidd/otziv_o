package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRewardLedgerEntry;
import com.hunt.otziv.contractor_payments.model.ContractorRewardSyncMarker;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardLedgerRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardSyncMarkerRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorRewardLedgerService {

    private static final int INTERACTIVE_BATCH_SIZE = 250;

    private final ZpRepository zpRepository;
    private final ContractorRewardLedgerRepository ledgerRepository;
    private final ContractorRewardSyncMarkerRepository syncMarkerRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorRewardAttributionService attributionService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;
    private final ContractorPaymentBusinessClock businessClock;

    /**
     * Drains a bounded global queue. A source marker is global rather than
     * profile-scoped, preventing profile-count × reward-count table growth.
     * Missing/out-of-order commits and same-id corrections remain discoverable
     * by the durable marker and source updated timestamp.
     */
    @Transactional
    public void synchronize(ContractorPaymentProfile ignoredProfile) {
        processGlobalBatch(INTERACTIVE_BATCH_SIZE);
    }

    @Transactional
    public void synchronizeSources(Iterable<Zp> rewards) {
        if (rewards == null) {
            return;
        }
        List<Zp> ordered = new ArrayList<>();
        rewards.forEach(ordered::add);
        ordered.stream()
                .filter(Objects::nonNull)
                .filter(reward -> reward.getId() != null)
                .sorted(Comparator.comparing(Zp::getId))
                .forEach(this::synchronizeReward);
    }

    /**
     * Rebuilds direct legacy rows after an initial profile boundary is moved.
     * The caller holds source locks followed by the target profile lock. This
     * intentionally ignores a marker produced while the profile boundary
     * still excluded the source.
     */
    @Transactional
    public void forceSynchronizeDirectSourcesForLockedProfile(
            Iterable<Zp> rewards,
            ContractorPaymentProfile lockedProfile
    ) {
        if (rewards == null || lockedProfile == null || lockedProfile.getId() == null
                || lockedProfile.getUser() == null || lockedProfile.getUser().getId() == null
                || lockedProfile.getRole() == null) {
            throw new IllegalArgumentException("A locked contractor profile is required for forced synchronization");
        }
        List<Zp> ordered = new ArrayList<>();
        rewards.forEach(ordered::add);
        ordered = ordered.stream()
                .filter(Objects::nonNull)
                .filter(reward -> reward.getId() != null)
                .sorted(Comparator.comparing(Zp::getId))
                .toList();
        for (Zp reward : ordered) {
            if (!Objects.equals(reward.getUserId(), lockedProfile.getUser().getId())
                    || reward.getContractorRole() != lockedProfile.getRole()
                    || !reward.isAttributionFinal()
                    || !ContractorRewardSourceCodes.isLedgerSourceCompatible(
                            reward.getSource(), reward.getContractorRole())) {
                throw new IllegalStateException(
                        "Forced contractor reward synchronization requires an exact direct source: sourceId="
                                + reward.getId()
                );
            }
            boolean foreignLedgerRow = ledgerRepository.findAllBySourceZpId(reward.getId()).stream()
                    .map(ContractorRewardLedgerEntry::getProfile)
                    .filter(Objects::nonNull)
                    .map(ContractorPaymentProfile::getId)
                    .anyMatch(profileId -> !Objects.equals(profileId, lockedProfile.getId()));
            if (foreignLedgerRow) {
                throw new IllegalStateException(
                        "Forced contractor reward synchronization found a foreign ledger attribution: sourceId="
                                + reward.getId()
                );
            }
            synchronizeLockedReward(reward, true, true);
        }
    }

    /**
     * Completion transactions can touch several people. Lock every source and
     * every direct profile in one deterministic order before applying any
     * ledger row, preventing reversed manager/specialist pairs from deadlocking
     * across application nodes.
     */
    @Transactional
    public void synchronizeCompletionSourcesCanonical(Iterable<Zp> rewards) {
        if (rewards == null) {
            return;
        }
        List<Long> sourceIds = new ArrayList<>();
        rewards.forEach(reward -> {
            if (reward != null && reward.getId() != null) {
                sourceIds.add(reward.getId());
            }
        });
        List<Zp> lockedRewards = sourceIds.stream()
                .distinct()
                .sorted()
                .map(zpRepository::findByIdForContractorLedgerUpdate)
                .flatMap(java.util.Optional::stream)
                .toList();
        if (lockedRewards.isEmpty()) {
            return;
        }
        if (lockedRewards.stream().anyMatch(reward ->
                reward.getContractorRole() != ContractorRole.MANAGER && !reward.isAttributionFinal())) {
            throw new IllegalStateException("Completion ledger batch requires immutable direct attribution");
        }

        List<UserRoleKey> profileKeys = lockedRewards.stream()
                .filter(reward -> reward.getUserId() != null && reward.getContractorRole() != null)
                .map(reward -> new UserRoleKey(reward.getUserId(), reward.getContractorRole()))
                .distinct()
                .sorted(Comparator.comparing(UserRoleKey::userId)
                        .thenComparing(key -> key.role().name()))
                .toList();
        Set<Long> profileIds = new TreeSet<>();
        for (UserRoleKey key : profileKeys) {
            Long profileId = profileRepository.findIdByUserIdAndRole(key.userId(), key.role()).orElse(null);
            if (profileId == null) {
                // Permanent profiles are normally provisioned with the user.
                // If repair must create one, missing keys are handled in the
                // same user/role order on every node. Existing profiles are
                // not locked until their ids can be acquired canonically.
                ContractorPaymentProfile created = profileFor(
                        key.userId(), key.role(), lockedRewards.get(0).getId(), true
                );
                profileId = created == null ? null : created.getId();
            }
            if (profileId != null) {
                profileIds.add(profileId);
            }
        }
        for (Zp reward : lockedRewards) {
            ledgerRepository.findAllBySourceZpId(reward.getId()).stream()
                    .map(ContractorRewardLedgerEntry::getProfile)
                    .filter(Objects::nonNull)
                    .map(ContractorPaymentProfile::getId)
                    .filter(Objects::nonNull)
                    .forEach(profileIds::add);
        }
        if (!profileIds.isEmpty()) {
            profileRepository.findAllByIdForUpdate(profileIds);
        }
        lockedRewards.forEach(reward -> synchronizeLockedReward(reward, true));
    }

    public void synchronizeSourcesSafely(Iterable<Zp> rewards) {
        if (rewards == null) {
            return;
        }
        List<Long> sourceIds = new ArrayList<>();
        rewards.forEach(reward -> {
            if (reward != null && reward.getId() != null) {
                sourceIds.add(reward.getId());
            }
        });
        if (sourceIds.isEmpty()) {
            return;
        }
        List<Long> immutableIds = sourceIds.stream().distinct().sorted().toList();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // The ZP source is not visible to REQUIRES_NEW until its creating
            // transaction commits. Run synchronously in afterCommit instead;
            // failures are caught outside TransactionTemplate.execute, which
            // also catches commit-time failures.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronizeSourceIdsInIndependentTransactions(immutableIds);
                }
            });
        } else {
            synchronizeSourceIdsInIndependentTransactions(immutableIds);
        }
    }

    private void synchronizeSourceIdsInIndependentTransactions(List<Long> sourceIds) {
        for (Long sourceId : sourceIds) {
            try {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.executeWithoutResult(status -> {
                    Zp source = zpRepository.findById(sourceId).orElse(null);
                    synchronizeReward(source);
                });
            } catch (RuntimeException exception) {
                log.error(
                        "Contractor reward ledger immediate synchronization failed; "
                                + "scheduled repair remains active: sourceId={}, code={}",
                        sourceId,
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    public long totalAccrued(ContractorPaymentProfile profile) {
        return Math.addExact(profile.getOpeningBalanceKopecks(), ledgerRepository.sumActiveByProfileId(profile.getId()));
    }

    public long accruedInPeriod(ContractorPaymentProfile profile, LocalDate from, LocalDate to) {
        return ledgerRepository.sumActiveByProfileIdAndPeriod(profile.getId(), from, to);
    }

    /**
     * Last-resort reconciliation for a derivative ledger row whose source ZP
     * is already inactive or missing from the active set. Normal mutations
     * synchronize the source rows; this closes historical/manual drift too.
     */
    @Transactional
    public int deactivateActiveOrderEntries(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        return ledgerRepository.deactivateActiveByOrderId(orderId);
    }

    /**
     * A source at or before a profile cutover is represented only by the
     * manually entered opening balance, not by a reversible ledger row. Never
     * silently deactivate it: an administrator must first make an audited
     * opening-balance correction.
     */
    @Transactional
    public void requireCancellationRepresentable(Iterable<Zp> rewards) {
        if (rewards == null) {
            return;
        }
        for (Zp reward : rewards) {
            if (reward == null || reward.getId() == null || reward.getUserId() == null) {
                continue;
            }
            List<ContractorPaymentProfile> profiles =
                    profileRepository.findAllByUserIdForUpdate(reward.getUserId());
            boolean preCutover = profiles.stream().anyMatch(profile ->
                    reward.getId() <= profile.getTrackingStartZpId()
                            && (reward.getContractorRole() == null
                            || reward.getContractorRole() == profile.getRole())
            );
            if (preCutover) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Начисление входит в переходящий остаток платёжного профиля. "
                                + "Сначала оформите корректировку остатка с причиной, затем повторите отмену"
                );
            }
        }
    }

    private int processGlobalBatch(int size) {
        List<Zp> rewards = zpRepository.findContractorRewardsNeedingGlobalRepair(
                java.time.LocalDateTime.now(),
                PageRequest.of(0, Math.max(1, size))
        );
        rewards.forEach(this::synchronizeReward);
        return rewards.size();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void synchronizeSourceId(Long sourceZpId) {
        if (sourceZpId == null) {
            return;
        }
        Zp source = zpRepository.findById(sourceZpId).orElse(null);
        synchronizeReward(source);
    }

    private void synchronizeReward(Zp reward) {
        if (reward == null || reward.getId() == null) {
            return;
        }
        // Every path, including the scheduled repair, locks the durable source
        // before examining the marker. Two application nodes can select the
        // same stale row, but the second one re-reads the marker after waiting
        // and becomes a no-op instead of racing the ledger UNIQUE constraint.
        Zp lockedReward = zpRepository.findByIdForContractorLedgerUpdate(reward.getId()).orElse(null);
        if (lockedReward == null) {
            return;
        }
        synchronizeLockedReward(lockedReward, false);
    }

    private void synchronizeLockedReward(Zp lockedReward, boolean profilesAlreadyLocked) {
        synchronizeLockedReward(lockedReward, profilesAlreadyLocked, false);
    }

    private void synchronizeLockedReward(
            Zp lockedReward,
            boolean profilesAlreadyLocked,
            boolean force
    ) {
        if (!ContractorRewardSourceCodes.isLedgerSourceCompatible(
                lockedReward.getSource(), lockedReward.getContractorRole())) {
            // Never turn an unknown source-role pair into a successful marker:
            // readiness must stay blocked and any old ledger attribution must
            // remain untouched until an operator fixes the source.
            throw new IllegalStateException(
                    "Contractor reward source-role pair is incompatible: sourceId="
                            + lockedReward.getId() + ", source=" + lockedReward.getSource()
                            + ", role=" + lockedReward.getContractorRole()
            );
        }
        ContractorRewardSyncMarker existingMarker = syncMarkerRepository
                .findBySourceZpId(lockedReward.getId())
                .orElse(null);
        if (!force && !needsSynchronization(lockedReward, existingMarker)) {
            return;
        }

        List<ContractorRewardLedgerEntry> existingRows = ledgerRepository.findAllBySourceZpId(
                lockedReward.getId()
        );
        // Resolve profile ids without taking profile locks first. Acquiring
        // individual profile locks while walking attribution shares would let
        // two different ZP sources lock the same pair in opposite order.
        if (!profilesAlreadyLocked) {
            List<DesiredEntry> preliminaryDesired = desiredEntries(lockedReward, false);
            Set<Long> affectedProfileIds = new TreeSet<>();
            existingRows.stream()
                    .map(ContractorRewardLedgerEntry::getProfile)
                    .filter(Objects::nonNull)
                    .map(ContractorPaymentProfile::getId)
                    .filter(Objects::nonNull)
                    .forEach(affectedProfileIds::add);
            preliminaryDesired.stream()
                    .map(DesiredEntry::profile)
                    .filter(Objects::nonNull)
                    .map(ContractorPaymentProfile::getId)
                    .filter(Objects::nonNull)
                    .forEach(affectedProfileIds::add);
            if (!affectedProfileIds.isEmpty()) {
                // Shared capacity mutex with routing. ZP is locked first; routing
                // never locks ZP, so this deterministic profile order cannot form
                // a reverse profile→ZP deadlock.
                profileRepository.findAllByIdForUpdate(affectedProfileIds);
            }
        }
        List<DesiredEntry> desired = desiredEntries(lockedReward, !profilesAlreadyLocked);
        Map<EntryKey, ContractorRewardLedgerEntry> existing = new LinkedHashMap<>();
        existingRows.forEach(entry -> existing.put(
                new EntryKey(entry.getProfile().getId(), entry.getAttributionKey()),
                entry
        ));

        for (DesiredEntry value : desired) {
            EntryKey key = new EntryKey(value.profile().getId(), value.attributionKey());
            ContractorRewardLedgerEntry entry = existing.remove(key);
            if (entry == null) {
                entry = ledgerRepository
                        .findBySourceZpIdAndProfileIdAndAttributionKey(
                                lockedReward.getId(), value.profile().getId(), value.attributionKey()
                        )
                        .orElseGet(ContractorRewardLedgerEntry::new);
            }
            apply(entry, lockedReward, value);
            ledgerRepository.save(entry);
        }
        existing.values().forEach(entry -> {
            if (entry.isActive()) {
                entry.setActive(false);
                ledgerRepository.save(entry);
            }
        });
        markProcessed(lockedReward, existingMarker);
    }

    private boolean needsSynchronization(Zp source, ContractorRewardSyncMarker marker) {
        if (marker == null || marker.isSourceActive() != source.isActive()) {
            return true;
        }
        if (source.getUpdatedAt() == null) {
            // A just-persisted IDENTITY entity may not expose its DB-generated
            // timestamp until the next transaction. The active-state marker is
            // sufficient now; the bounded repair will refresh it once later.
            return false;
        }
        return marker.getSourceUpdatedAt() == null
                || marker.getSourceUpdatedAt().isBefore(source.getUpdatedAt());
    }

    private List<DesiredEntry> desiredEntries(Zp reward, boolean lockProfiles) {
        if (reward.getContractorRole() == ContractorRole.MANAGER || reward.isAttributionFinal()) {
            return directEntry(reward, lockProfiles);
        }
        if (ContractorRewardSourceCodes.isPerformerProductAttributionSource(reward.getSource())
                && reward.getAttributionSnapshot() != null
                && !reward.getAttributionSnapshot().isBlank()) {
            return attributedSnapshotEntries(
                    reward,
                    ContractorRewardAttributionSnapshotCodec.decode(reward.getAttributionSnapshot()),
                    lockProfiles
            );
        }
        if (ContractorRewardSourceCodes.isOrderSpecialistAttributionSource(reward.getSource())
                && reward.getAttributionSnapshot() != null
                && !reward.getAttributionSnapshot().isBlank()) {
            return coefficientAdjustedSnapshotEntries(
                    reward,
                    ContractorRewardAttributionSnapshotCodec.decode(reward.getAttributionSnapshot()),
                    lockProfiles
            );
        }
        if (reward.getOrderId() == null) {
            return directEntry(reward, lockProfiles);
        }
        Order order = orderRepository.findByIdForOrderDto(reward.getOrderId()).orElse(null);
        if (order == null) {
            return directEntry(reward, lockProfiles);
        }
        if (ContractorRewardSourceCodes.isPerformerProductAttributionSource(reward.getSource())) {
            return attributedEntries(
                    reward,
                    attributionService.attribute(order, order.getSum()),
                    lockProfiles
            );
        }
        if (ContractorRewardSourceCodes.isOrderSpecialistAttributionSource(reward.getSource())) {
            List<ContractorRewardAttributionService.SpecialistShare> shares =
                    attributionService.attributeRecordedWork(order);
            return reward.getRewardBasis() == null
                    ? attributedEntries(reward, shares, lockProfiles)
                    : coefficientAdjustedEntries(reward, shares, lockProfiles);
        }
        return directEntry(reward, lockProfiles);
    }

    private List<DesiredEntry> directEntry(Zp reward, boolean lockProfiles) {
        ContractorPaymentProfile profile = profileFor(
                reward.getUserId(), reward.getContractorRole(), reward.getId(), lockProfiles
        );
        if (!eligible(profile, reward)) {
            return List.of();
        }
        long attributionKey = reward.getContractorRole() == ContractorRole.SPECIALIST
                && reward.getProfessionId() != null
                    ? reward.getProfessionId()
                    : 0L;
        return List.of(new DesiredEntry(
                profile,
                attributionKey,
                reward.getContractorRole() == ContractorRole.SPECIALIST ? reward.getProfessionId() : null,
                toKopecks(reward.getSum()),
                Math.max(0, reward.getAmount())
        ));
    }

    private List<DesiredEntry> attributedEntries(
            Zp reward,
            List<ContractorRewardAttributionService.SpecialistShare> shares,
            boolean lockProfiles
    ) {
        if (shares.isEmpty()) {
            return directEntry(reward, lockProfiles);
        }
        Map<Long, Long> amounts = allocateProportionally(toKopecks(reward.getSum()), shares);
        List<DesiredEntry> desired = new ArrayList<>();
        for (ContractorRewardAttributionService.SpecialistShare share : shares) {
            ContractorPaymentProfile profile = profileFor(
                    share.user().getId(), ContractorRole.SPECIALIST, reward.getId(), lockProfiles
            );
            long amount = amounts.getOrDefault(share.workerId(), 0L);
            if (!eligible(profile, reward) || amount <= 0) {
                continue;
            }
            desired.add(new DesiredEntry(
                    profile,
                    share.workerId(),
                    share.workerId(),
                    amount,
                    share.workUnits()
            ));
        }
        return desired;
    }

    private List<DesiredEntry> attributedSnapshotEntries(
            Zp reward,
            List<ContractorRewardAttributionSnapshotCodec.SnapshotShare> shares,
            boolean lockProfiles
    ) {
        if (shares.isEmpty()) {
            return directEntry(reward, lockProfiles);
        }
        Map<Long, Long> amounts = allocateSnapshotProportionally(toKopecks(reward.getSum()), shares);
        if (amounts.isEmpty()) {
            return directEntry(reward, lockProfiles);
        }
        List<DesiredEntry> desired = new ArrayList<>();
        for (ContractorRewardAttributionSnapshotCodec.SnapshotShare share : shares) {
            ContractorPaymentProfile profile = profileFor(
                    share.userId(), ContractorRole.SPECIALIST, reward.getId(), lockProfiles
            );
            long amount = amounts.getOrDefault(share.workerId(), 0L);
            if (!eligible(profile, reward) || amount <= 0L) {
                continue;
            }
            desired.add(new DesiredEntry(
                    profile,
                    share.workerId(),
                    share.workerId(),
                    amount,
                    share.workUnits()
            ));
        }
        return desired;
    }

    private List<DesiredEntry> coefficientAdjustedEntries(
            Zp reward,
            List<ContractorRewardAttributionService.SpecialistShare> shares,
            boolean lockProfiles
    ) {
        if (shares.isEmpty()) {
            return directEntry(reward, lockProfiles);
        }
        Map<Long, Long> grossKopecks = allocateProportionally(toKopecks(reward.getRewardBasis()), shares);
        List<DesiredEntry> desired = new ArrayList<>();
        for (ContractorRewardAttributionService.SpecialistShare share : shares) {
            ContractorPaymentProfile profile = profileFor(
                    share.user().getId(), ContractorRole.SPECIALIST, reward.getId(), lockProfiles
            );
            long gross = grossKopecks.getOrDefault(share.workerId(), 0L);
            BigDecimal coefficient = share.user().getCoefficient() == null
                    ? BigDecimal.ZERO
                    : share.user().getCoefficient();
            long amount = BigDecimal.valueOf(gross)
                    .multiply(coefficient)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            if (!eligible(profile, reward) || amount <= 0) {
                continue;
            }
            desired.add(new DesiredEntry(
                    profile,
                    share.workerId(),
                    share.workerId(),
                    amount,
                    share.workUnits()
            ));
        }
        return desired;
    }

    private List<DesiredEntry> coefficientAdjustedSnapshotEntries(
            Zp reward,
            List<ContractorRewardAttributionSnapshotCodec.SnapshotShare> shares,
            boolean lockProfiles
    ) {
        if (shares.isEmpty()) {
            return directEntry(reward, lockProfiles);
        }
        Map<Long, Long> grossByWorker = allocateSnapshotProportionally(
                toKopecks(reward.getRewardBasis()),
                shares
        );
        if (grossByWorker.isEmpty()) {
            return List.of();
        }
        List<DesiredEntry> desired = new ArrayList<>();
        for (ContractorRewardAttributionSnapshotCodec.SnapshotShare share : shares) {
            ContractorPaymentProfile profile = profileFor(
                    share.userId(), ContractorRole.SPECIALIST, reward.getId(), lockProfiles
            );
            long gross = grossByWorker.getOrDefault(share.workerId(), 0L);
            long amount = BigDecimal.valueOf(gross)
                    .multiply(share.coefficient())
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            if (!eligible(profile, reward) || amount <= 0) {
                continue;
            }
            desired.add(new DesiredEntry(
                    profile,
                    share.workerId(),
                    share.workerId(),
                    amount,
                    share.workUnits()
            ));
        }
        return desired;
    }

    private Map<Long, Long> allocateSnapshotProportionally(
            long totalKopecks,
            List<ContractorRewardAttributionSnapshotCodec.SnapshotShare> shares
    ) {
        BigDecimal grossTotal = shares.stream()
                .map(ContractorRewardAttributionSnapshotCodec.SnapshotShare::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalKopecks <= 0 || grossTotal.signum() <= 0) {
            return Map.of();
        }
        List<SnapshotPart> parts = new ArrayList<>();
        long floors = 0L;
        for (ContractorRewardAttributionSnapshotCodec.SnapshotShare share : shares) {
            BigDecimal exact = BigDecimal.valueOf(totalKopecks)
                    .multiply(share.grossAmount())
                    .divide(grossTotal, 12, RoundingMode.DOWN);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            floors = Math.addExact(floors, floor);
            parts.add(new SnapshotPart(share, floor, exact.subtract(BigDecimal.valueOf(floor))));
        }
        parts.sort(Comparator.comparing(SnapshotPart::remainder).reversed()
                .thenComparing(part -> part.share().workerId()));
        Map<Long, Long> grossByWorker = new LinkedHashMap<>();
        parts.forEach(part -> grossByWorker.put(part.share().workerId(), part.floor()));
        long missing = Math.subtractExact(totalKopecks, floors);
        for (int index = 0; index < missing; index++) {
            long workerId = parts.get(index % parts.size()).share().workerId();
            grossByWorker.compute(workerId, (ignored, amount) -> Math.addExact(amount, 1L));
        }

        return grossByWorker;
    }

    private ContractorPaymentProfile profileFor(
            Long userId,
            ContractorRole role,
            Long sourceZpId,
            boolean lockProfile
    ) {
        if (userId == null || role == null) {
            return null;
        }
        ContractorPaymentProfile existing = (lockProfile
                ? profileRepository.findByUserIdAndRoleForUpdate(userId, role)
                : profileRepository.findByUserIdAndRole(userId, role))
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        if (!lockProfile) {
            // Missing profiles are created only in the locked pass. A
            // concurrent UNIQUE-key winner is harmless: the durable repair
            // queue retries and then resolves that permanent profile.
            return null;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new IllegalStateException(
                    "Contractor reward source " + sourceZpId + " references missing user " + userId
            );
        }
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setUser(user);
        profile.setRole(role);
        profile.setEnabled(false);
        profile.setLiveEnabled(false);
        profile.setOpeningBalanceKopecks(0L);
        profile.setTrackingStartedAt(java.time.LocalDateTime.now());
        // This recovery path is only reached when migration/provisioning did
        // not create any profile or opening balance. Start from the beginning;
        // using sourceId-1 would permanently skip an older out-of-order source
        // repaired later.
        profile.setTrackingStartZpId(0L);
        profile.setLedgerSyncZpId(profile.getTrackingStartZpId());
        profile.setLedgerSyncAt(profile.getTrackingStartedAt());
        return profileRepository.save(profile);
    }

    private boolean eligible(ContractorPaymentProfile profile, Zp reward) {
        return profile != null
                && reward.getId() != null
                && reward.getId() > profile.getTrackingStartZpId();
    }

    private Map<Long, Long> allocateProportionally(
            long totalKopecks,
            List<ContractorRewardAttributionService.SpecialistShare> shares
    ) {
        BigDecimal grossTotal = shares.stream()
                .map(ContractorRewardAttributionService.SpecialistShare::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalKopecks <= 0 || grossTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of();
        }
        List<ProportionalPart> parts = new ArrayList<>();
        long allocated = 0L;
        for (ContractorRewardAttributionService.SpecialistShare share : shares) {
            BigDecimal exact = BigDecimal.valueOf(totalKopecks)
                    .multiply(share.grossAmount())
                    .divide(grossTotal, 12, RoundingMode.DOWN);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            allocated += floor;
            parts.add(new ProportionalPart(
                    share.workerId(), floor, exact.subtract(BigDecimal.valueOf(floor))
            ));
        }
        parts.sort(Comparator.comparing(ProportionalPart::remainder).reversed()
                .thenComparing(ProportionalPart::workerId));
        long missing = totalKopecks - allocated;
        Map<Long, Long> result = new LinkedHashMap<>();
        parts.forEach(part -> result.put(part.workerId(), part.floor()));
        for (int index = 0; index < missing; index++) {
            Long workerId = parts.get(index % parts.size()).workerId();
            result.compute(workerId, (ignored, amount) -> Math.addExact(amount, 1L));
        }
        return result;
    }

    private void apply(ContractorRewardLedgerEntry entry, Zp reward, DesiredEntry desired) {
        entry.setProfile(desired.profile());
        entry.setSourceZpId(reward.getId());
        entry.setAttributedWorkerId(desired.workerId());
        entry.setAttributionKey(desired.attributionKey());
        entry.setOrderId(reward.getOrderId());
        entry.setPaymentStatusGuardId(reward.getPaymentStatusGuardId());
        entry.setAmountKopecks(desired.amountKopecks());
        entry.setWorkUnits(desired.workUnits());
        entry.setOccurredOn(reward.getCreated() == null ? businessClock.today() : reward.getCreated());
        entry.setActive(reward.isActive());
        entry.setSourceCode(reward.getSource());
    }

    private void markProcessed(Zp reward, ContractorRewardSyncMarker existingMarker) {
        ContractorRewardSyncMarker marker = existingMarker == null
                ? new ContractorRewardSyncMarker()
                : existingMarker;
        marker.setSourceZpId(reward.getId());
        marker.setSourceActive(reward.isActive());
        marker.setSourceUpdatedAt(reward.getUpdatedAt());
        syncMarkerRepository.save(marker);
    }

    private long toKopecks(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private record EntryKey(Long profileId, long attributionKey) {
    }

    private record UserRoleKey(Long userId, ContractorRole role) {
    }

    private record DesiredEntry(
            ContractorPaymentProfile profile,
            long attributionKey,
            Long workerId,
            long amountKopecks,
            int workUnits
    ) {
    }

    private record ProportionalPart(Long workerId, long floor, BigDecimal remainder) {
    }

    private record SnapshotPart(
            ContractorRewardAttributionSnapshotCodec.SnapshotShare share,
            long floor,
            BigDecimal remainder
    ) {
    }
}
