package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time bridge between the legacy personal-cabinet reward rows and the
 * contractor accounting ledger. It deliberately imports source rows rather
 * than one synthetic balance, preserving order linkage, cancellation and
 * duplicate protection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorRewardInitialMonthSyncService {

    private final ContractorPaymentProfileRepository profileRepository;
    private final ZpRepository zpRepository;
    private final ContractorRewardLedgerService ledgerService;
    private final ContractorPaymentBusinessClock businessClock;
    private final BusinessAuditService businessAuditService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean synchronizeProfile(Long profileId) {
        if (profileId == null) {
            return false;
        }
        ContractorPaymentProfile discovered = profileRepository.findById(profileId).orElse(null);
        LocalDate monthStart = businessClock.today().withDayOfMonth(1);
        LocalDateTime coverageStart = monthStart.atStartOfDay();
        if (!requiresSync(discovered, coverageStart)) {
            return false;
        }

        Long userId = discovered.getUser().getId();
        ContractorRole role = discovered.getRole();
        LocalDateTime previousTrackingStartedAt = discovered.getTrackingStartedAt();
        long initialBoundary = Math.max(0L, discovered.getTrackingStartZpId());
        List<Long> sourceIds = legacySourceIds(userId, role, monthStart, monthStart.plusMonths(1)).stream()
                .filter(Objects::nonNull)
                .filter(sourceId -> sourceId <= initialBoundary)
                .distinct()
                .sorted()
                .toList();
        List<Zp> lockedSources = sourceIds.stream()
                .map(sourceId -> zpRepository.findByIdForContractorLedgerUpdate(sourceId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Legacy contractor reward source disappeared before lock: sourceId=" + sourceId
                        )))
                .toList();

        // Canonical lock order is ZP source(s) -> profile. Reward repair uses
        // the same order, so enabling a profile cannot deadlock an accrual.
        ContractorPaymentProfile profile = profileRepository.findByIdForUpdate(profileId).orElse(null);
        if (!requiresSync(profile, coverageStart)) {
            return false;
        }
        if (profile.getUser() == null
                || !Objects.equals(profile.getUser().getId(), userId)
                || profile.getRole() != role) {
            throw new IllegalStateException(
                    "Contractor payment profile identity changed during initial-month sync: profileId=" + profileId
            );
        }

        for (Zp source : lockedSources) {
            if (!belongsToPeriodAndUser(source, userId, monthStart, monthStart.plusMonths(1))
                    || !eligibleAfterLock(source, role)) {
                throw new IllegalStateException(
                        "Legacy contractor reward source changed or is not classifiable after lock: sourceId="
                                + source.getId() + ", role=" + role
                );
            }
        }
        List<Zp> eligibleSources = lockedSources;
        long previousBoundary = profile.getTrackingStartZpId();
        long newBoundary = eligibleSources.stream()
                .map(Zp::getId)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .min()
                .stream()
                .map(firstId -> Math.max(0L, firstId - 1L))
                .map(candidate -> Math.min(previousBoundary, candidate))
                .findFirst()
                .orElse(previousBoundary);

        List<Zp> classified = new ArrayList<>();
        for (Zp source : eligibleSources) {
            boolean changed = false;
            if (source.getContractorRole() == null) {
                source.setContractorRole(role);
                changed = true;
            }
            if (source.getSource() == null || source.getSource().isBlank()) {
                source.setSource(role == ContractorRole.SPECIALIST
                        ? ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST
                        : ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER);
                changed = true;
            }
            // Historical rows already contain the exact recipient and amount
            // shown in the personal cabinet. Do not redistribute them using a
            // later order assignment after a workload transfer.
            if (!source.isAttributionFinal()) {
                source.setAttributionFinal(true);
                changed = true;
            }
            if (changed) {
                zpRepository.save(source);
                classified.add(source);
            }
        }

        profile.setTrackingStartZpId(newBoundary);
        profile.setTrackingStartedAt(coverageStart);
        profile.setLedgerSyncZpId(Math.max(
                profile.getLedgerSyncZpId(),
                eligibleSources.stream()
                        .map(Zp::getId)
                        .filter(Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .max()
                        .orElse(profile.getLedgerSyncZpId())
        ));
        profile.setLedgerSyncAt(businessClock.now());
        profileRepository.saveAndFlush(profile);

        // A global repair may already have written an up-to-date marker while
        // this profile still had the MAX tracking boundary. Lowering the
        // boundary therefore requires a forced, marker-independent rebuild
        // for every eligible source, including rows typed by reconciliation
        // before the first profile sync.
        ledgerService.forceSynchronizeDirectSourcesForLockedProfile(eligibleSources, profile);

        long importedKopecks = eligibleSources.stream()
                .map(Zp::getSum)
                .map(this::toKopecks)
                .reduce(0L, Math::addExact);
        Map<String, Object> oldState = new LinkedHashMap<>();
        oldState.put("trackingStartedAt", previousTrackingStartedAt);
        oldState.put("trackingStartZpId", previousBoundary);
        Map<String, Object> newState = new LinkedHashMap<>();
        newState.put("trackingStartedAt", coverageStart);
        newState.put("trackingStartZpId", newBoundary);
        newState.put("classifiedSources", classified.size());
        newState.put("importedSources", eligibleSources.size());
        newState.put("importedKopecks", importedKopecks);
        businessAuditService.recordRequiredInCurrentTransaction(
                "INITIAL_CONTRACTOR_REWARD_MONTH_SYNC",
                "CONTRACTOR_PAYMENT_PROFILE",
                profile.getId(),
                null,
                null,
                oldState,
                newState,
                "userId=" + userId + ", role=" + role + ", month=" + monthStart
        );
        log.info(
                "Initial contractor reward month synchronized: profileId={}, role={}, month={}, sources={}, amountKopecks={}",
                profile.getId(), role, monthStart, eligibleSources.size(), importedKopecks
        );
        return true;
    }

    private List<Long> legacySourceIds(
            Long userId,
            ContractorRole role,
            LocalDate from,
            LocalDate to
    ) {
        if (role == ContractorRole.SPECIALIST) {
            return zpRepository.findLegacySpecialistRewardIdsInPeriod(userId, from, to);
        }
        if (role == ContractorRole.MANAGER) {
            return zpRepository.findLegacyManagerRewardIdsInPeriod(userId, from, to);
        }
        return List.of();
    }

    private boolean requiresSync(ContractorPaymentProfile profile, LocalDateTime coverageStart) {
        return profile != null
                && profile.isEnabled()
                && profile.getTrackingStartedAt() != null
                && profile.getTrackingStartedAt().isAfter(coverageStart);
    }

    private boolean belongsToPeriodAndUser(
            Zp source,
            Long userId,
            LocalDate from,
            LocalDate to
    ) {
        return source != null
                && source.getId() != null
                && Objects.equals(source.getUserId(), userId)
                && source.getOrderId() != null
                && source.getOrderId() > 0L
                && source.getCreated() != null
                && !source.getCreated().isBefore(from)
                && source.getCreated().isBefore(to);
    }

    private boolean eligibleAfterLock(Zp source, ContractorRole role) {
        if (source == null
                || source.getId() == null
                || !source.isActive()
                || (source.getContractorRole() != null && source.getContractorRole() != role)
                || !compatibleLegacySource(source.getSource(), role)) {
            return false;
        }
        if (role == ContractorRole.SPECIALIST) {
            return zpRepository.countEligibleLegacySpecialistRewardForSync(source.getId()) == 1L;
        }
        if (role == ContractorRole.MANAGER) {
            return zpRepository.countEligibleLegacyManagerRewardForSync(source.getId()) == 1L;
        }
        return false;
    }

    private boolean compatibleLegacySource(String source, ContractorRole role) {
        if (source == null || source.isBlank()) {
            return role == ContractorRole.SPECIALIST || role == ContractorRole.MANAGER;
        }
        if (ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT.equals(source)) {
            return role == ContractorRole.SPECIALIST || role == ContractorRole.MANAGER;
        }
        return role == ContractorRole.SPECIALIST
                ? ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST.equals(source)
                : role == ContractorRole.MANAGER
                        && ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER.equals(source);
    }

    private long toKopecks(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
