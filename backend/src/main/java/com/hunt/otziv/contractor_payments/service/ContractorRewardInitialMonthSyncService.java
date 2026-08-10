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
import java.util.Comparator;
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
        List<Long> sourceIds = legacySourceIds(userId, role, monthStart, monthStart.plusMonths(1));
        List<Zp> lockedSources = sourceIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(zpRepository::findByIdForContractorLedgerUpdate)
                .flatMap(java.util.Optional::stream)
                .toList();

        // Canonical lock order is ZP source(s) -> profile. Reward repair uses
        // the same order, so enabling a profile cannot deadlock an accrual.
        ContractorPaymentProfile profile = profileRepository.findByIdForUpdate(profileId).orElse(null);
        if (!requiresSync(profile, coverageStart)) {
            return false;
        }

        List<Zp> eligibleSources = lockedSources.stream()
                .filter(source -> belongsToPeriodAndUser(source, userId, monthStart, monthStart.plusMonths(1)))
                .filter(source -> source.getContractorRole() == null || source.getContractorRole() == role)
                .sorted(Comparator.comparing(Zp::getId))
                .toList();
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

        List<Zp> imported = new ArrayList<>();
        for (Zp source : eligibleSources) {
            if (source.getContractorRole() != null) {
                continue;
            }
            source.setContractorRole(role);
            if (source.getSource() == null || source.getSource().isBlank()) {
                source.setSource(role == ContractorRole.SPECIALIST
                        ? ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST
                        : ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER);
            }
            // Historical rows already contain the exact recipient and amount
            // shown in the personal cabinet. Do not redistribute them using a
            // later order assignment after a workload transfer.
            source.setAttributionFinal(true);
            zpRepository.save(source);
            imported.add(source);
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

        if (!imported.isEmpty()) {
            ledgerService.synchronizeSources(imported);
        }

        long importedKopecks = imported.stream()
                .map(Zp::getSum)
                .map(this::toKopecks)
                .reduce(0L, Math::addExact);
        Map<String, Object> oldState = new LinkedHashMap<>();
        oldState.put("trackingStartedAt", previousTrackingStartedAt);
        oldState.put("trackingStartZpId", previousBoundary);
        Map<String, Object> newState = new LinkedHashMap<>();
        newState.put("trackingStartedAt", coverageStart);
        newState.put("trackingStartZpId", newBoundary);
        newState.put("importedSources", imported.size());
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
                profile.getId(), role, monthStart, imported.size(), importedKopecks
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

    private long toKopecks(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
