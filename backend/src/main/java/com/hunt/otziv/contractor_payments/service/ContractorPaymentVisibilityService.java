package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAdminSummaryResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAllocationEventResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAllocationJournalItemResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSummaryResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ContractorPaymentVisibilityService {

    private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final Set<ContractorAllocationStatus> RESERVED = EnumSet.of(
            ContractorAllocationStatus.RESERVED
    );
    private static final Set<ContractorAllocationStatus> CLIENT_REPORTED = EnumSet.of(
            ContractorAllocationStatus.CLIENT_REPORTED
    );
    private static final Set<ContractorAllocationStatus> PARTIALLY_CONFIRMED = EnumSet.of(
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );

    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentAllocationEventRepository eventRepository;
    private final ContractorActualPaymentAttributionRepository attributionRepository;
    private final ContractorRewardLedgerService ledgerService;
    private final ContractorPaymentAccountingService accountingService;
    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ContractorPaymentProfileService profileService;
    private final UserRepository userRepository;
    private final AppSettingService appSettingService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;

    @Value("${otziv.contractor-payments.business-zone:Asia/Irkutsk}")
    private String businessZoneId;

    /**
     * The caller cannot pass a user id. Identity is always resolved from the
     * authenticated session, which prevents reading another contractor's data.
     */
    @Transactional
    public List<ContractorPaymentSummaryResponse> ownSummary(Authentication authentication) {
        User user = resolveCurrentUser(authentication);
        // Roles may be granted outside the normal provisioning path. Keep the
        // permanent, disabled-by-default profile invariant true before the
        // user reads their accounting summary.
        profileService.ensureForUser(user.getId());
        return profileRepository.findAllByUserIdForUpdate(user.getId()).stream()
                .map(this::summary)
                .toList();
    }

    /**
     * Read-only finance overview for administrators and owners. The endpoint
     * layer must still enforce role access; this method deliberately returns no
     * payment requisites. Monthly movements use {@code selectedDate}; balance
     * and open-exposure values intentionally remain the current ledger snapshot.
     */
    @Transactional(readOnly = true)
    public List<ContractorPaymentAdminSummaryResponse> adminSummary(LocalDate selectedDate) {
        LocalDate monthStart = monthStart(selectedDate);
        List<ContractorPaymentProfile> profiles = profileRepository.findAllWithUser();
        if (profiles.isEmpty()) return List.of();
        SummaryPolicy policy = summaryPolicy();
        var ids = profiles.stream().map(ContractorPaymentProfile::getId).toList();
        var accruals = ledgerService.totalsForProfiles(profiles, monthStart, monthStart.plusMonths(1));
        var events = accountingService.totalsForProfiles(ids, policy.mode(), monthStart.atStartOfDay(), monthStart.plusMonths(1).atStartOfDay());
        var exposures = allocationRepository.sumOutstandingForProfiles(ids, policy.mode(),
                EnumSet.of(ContractorAllocationStatus.RESERVED, ContractorAllocationStatus.CLIENT_REPORTED, ContractorAllocationStatus.PARTIALLY_CONFIRMED))
                .stream().collect(Collectors.groupingBy(row -> row.getProfileId(),
                        Collectors.toMap(row -> row.getStatus(), row -> row.getOutstanding())));
        Map<Long, ActualTransferStats> actualTransfers = actualTransfersByProfile(profiles, monthStart);
        return profiles.stream()
                .map(profile -> {
                    var accrued = accruals.getOrDefault(profile.getId(), new ContractorRewardLedgerService.AccrualTotals(profile.getOpeningBalanceKopecks(), 0));
                    var event = events.getOrDefault(profile.getId(), ContractorPaymentAccountingService.PeriodTotals.empty());
                    var exposure = exposures.getOrDefault(profile.getId(), Map.of());
                    var amounts = new SummaryAmounts(accrued.total(), accrued.month(),
                            exposure.getOrDefault(ContractorAllocationStatus.RESERVED, 0L),
                            exposure.getOrDefault(ContractorAllocationStatus.CLIENT_REPORTED, 0L),
                            exposure.getOrDefault(ContractorAllocationStatus.PARTIALLY_CONFIRMED, 0L),
                            event.confirmedMonth(), event.confirmedTotal(), event.returnedMonth(), event.returnedTotal(),
                            event.closedMonth(), event.closedTotal());
                    ContractorPaymentSummaryResponse summary = summary(profile, monthStart, policy, amounts);
                    ActualTransferStats transferStats = actualTransfers.getOrDefault(
                            profile.getId(),
                            ActualTransferStats.empty()
                    );
                    long pending = Math.addExact(
                            summary.clientReportedKopecks(),
                            summary.partiallyConfirmedOutstandingKopecks()
                    );
                    long outstandingReserved = Math.addExact(summary.reservedKopecks(), pending);
                    long netPaid = Math.max(0L, summary.netReceivedTotalKopecks());
                    long outstandingDebt = Math.max(
                            0L,
                            Math.subtractExact(summary.accruedTotalKopecks(), netPaid)
                    );
                    return new ContractorPaymentAdminSummaryResponse(
                            profile.getId(),
                            profile.getUser().getId(),
                            profile.getUser().getFio(),
                            profile.getRole(),
                            profile.isEnabled(),
                            profile.isLiveEnabled(),
                            summary.accruedMonthKopecks(),
                            summary.accruedTotalKopecks(),
                            summary.reservedKopecks(),
                            pending,
                            summary.netReceivedMonthKopecks(),
                            summary.netReceivedTotalKopecks(),
                            transferStats.count(),
                            transferStats.amountKopecks(),
                            outstandingDebt,
                            outstandingReserved,
                            summary.availableKopecks(),
                            summary.reportingLive(),
                            summary.currentMonthCoverageComplete()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<ContractorPaymentAllocationJournalItemResponse> journal(
            Long userId,
            ContractorAllocationStatus status,
            ContractorAllocationMode mode,
            ContractorAllocationSourceType sourceType,
            Long sourceId,
            int page,
            int size
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Page<ContractorPaymentAllocation> allocations = allocationRepository.findJournal(
                userId,
                status,
                mode,
                sourceType,
                sourceId,
                targetAccessPolicy.excludePrivilegedTargetsFromJournal(),
                PageRequest.of(safePage, safeSize)
        );
        List<Long> allocationIds = allocations.getContent().stream()
                .map(ContractorPaymentAllocation::getId)
                .toList();
        Map<Long, List<ContractorPaymentAllocationEvent>> eventsByAllocation = allocationIds.isEmpty()
                ? Map.of()
                : eventRepository.findAllByAllocationIdInOrderByEffectiveAtAscIdAsc(allocationIds).stream()
                        .collect(Collectors.groupingBy(event -> event.getAllocation().getId()));

        return allocations.map(allocation -> journalItem(
                allocation,
                eventsByAllocation.getOrDefault(allocation.getId(), List.of())
        ));
    }

    private Map<Long, ActualTransferStats> actualTransfersByProfile(
            List<ContractorPaymentProfile> profiles,
            LocalDate monthStart
    ) {
        if (profiles == null || profiles.isEmpty()) {
            return Map.of();
        }
        Set<Long> profileIds = profiles.stream()
                .map(ContractorPaymentProfile::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (profileIds.isEmpty()) {
            return Map.of();
        }

        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime to = monthStart.plusMonths(1).atStartOfDay();
        ContractorAllocationMode mode = accountingPhaseService.current();

        return attributionRepository
                .summarizeProfileActualTransfersInPeriod(profileIds, mode, from, to)
                .stream()
                .filter(row -> row.getProfileId() != null)
                .collect(Collectors.toMap(
                        ContractorActualPaymentAttributionRepository.ProfileActualTransferSummary::getProfileId,
                        row -> new ActualTransferStats(
                                safeLong(row.getTransferCount()),
                                safeLong(row.getTransferAmountKopecks())
                        ),
                        ActualTransferStats::merge
                ));
    }

    private record SummaryPolicy(boolean shadow, boolean liveRouting, ContractorAllocationMode mode) {}
    private record SummaryAmounts(long accruedTotal, long accruedMonth, long reserved, long clientReported,
                                  long partiallyConfirmedOutstanding, long grossConfirmedMonth, long grossConfirmedTotal,
                                  long returnedMonth, long returnedTotal, long closedWithoutPaymentMonth, long closedWithoutPaymentTotal) {}

    private SummaryPolicy summaryPolicy() {
        return new SummaryPolicy(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true),
                runtimeSwitch.status().liveRoutingEnabled(), accountingPhaseService.current());
    }

    private ContractorPaymentSummaryResponse summary(ContractorPaymentProfile profile) {
        LocalDate from = monthStart(null), to = from.plusMonths(1);
        SummaryPolicy policy = summaryPolicy();
        var mode = policy.mode();
        var amounts = new SummaryAmounts(ledgerService.totalAccrued(profile), ledgerService.accruedInPeriod(profile, from, to),
                allocationRepository.sumOutstandingExposure(profile.getId(), mode, RESERVED),
                allocationRepository.sumOutstandingExposure(profile.getId(), mode, CLIENT_REPORTED),
                allocationRepository.sumOutstandingExposure(profile.getId(), mode, PARTIALLY_CONFIRMED),
                accountingService.confirmedGrossInPeriod(profile, mode, from.atStartOfDay(), to.atStartOfDay()),
                accountingService.confirmedGross(profile, mode),
                accountingService.returnedInPeriod(profile, mode, from.atStartOfDay(), to.atStartOfDay()),
                accountingService.returned(profile, mode),
                accountingService.closedWithoutPaymentInPeriod(profile, mode, from.atStartOfDay(), to.atStartOfDay()),
                accountingService.closedWithoutPayment(profile, mode));
        return summary(profile, from, policy, amounts);
    }

    /** The same financial arithmetic is used by batch finance reads and the own-profile path. */
    private ContractorPaymentSummaryResponse summary(ContractorPaymentProfile profile, LocalDate monthStart,
            SummaryPolicy policy, SummaryAmounts amounts) {
        LocalDateTime trackingStartedAt = profile.getTrackingStartedAt();
        boolean currentMonthCoverageComplete = trackingStartedAt != null && !trackingStartedAt.isAfter(monthStart.atStartOfDay());
        boolean shadowMode = policy.shadow(), liveRouting = policy.liveRouting();
        ContractorAllocationMode balanceMode = policy.mode();
        long accruedTotal = amounts.accruedTotal(), reserved = amounts.reserved(), clientReported = amounts.clientReported();
        long partiallyConfirmedOutstanding = amounts.partiallyConfirmedOutstanding();
        long grossConfirmedMonth = amounts.grossConfirmedMonth(), grossConfirmedTotal = amounts.grossConfirmedTotal();
        long returnedMonth = amounts.returnedMonth(), returnedTotal = amounts.returnedTotal();
        long closedWithoutPaymentMonth = amounts.closedWithoutPaymentMonth(), closedWithoutPaymentTotal = amounts.closedWithoutPaymentTotal();
        long netReceivedMonth = Math.subtractExact(grossConfirmedMonth, returnedMonth);
        long netReceivedTotal = Math.subtractExact(grossConfirmedTotal, returnedTotal);
        long netPaid = Math.max(0L, netReceivedTotal);
        long outstanding = Math.addExact(
                Math.addExact(reserved, clientReported),
                partiallyConfirmedOutstanding
        );
        long available = Math.max(0L, accruedTotal - netPaid - outstanding);
        long credit = Math.max(0L, netPaid - accruedTotal);
        long exposureOverrun = Math.max(
                0L,
                Math.addExact(netPaid, outstanding) - accruedTotal
        );

        return new ContractorPaymentSummaryResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getRole(),
                profile.isEnabled(),
                profile.isLiveEnabled(),
                profile.getRecipientName(),
                profile.getPaymentPhone(),
                profile.getBankName(),
                profile.getPaymentComment(),
                amounts.accruedMonth(),
                accruedTotal,
                reserved,
                clientReported,
                partiallyConfirmedOutstanding,
                grossConfirmedMonth,
                grossConfirmedTotal,
                returnedMonth,
                returnedTotal,
                closedWithoutPaymentMonth,
                closedWithoutPaymentTotal,
                netReceivedMonth,
                netReceivedTotal,
                available,
                credit,
                exposureOverrun,
                balanceMode == ContractorAllocationMode.LIVE,
                shadowMode,
                liveRouting,
                trackingStartedAt,
                currentMonthCoverageComplete
        );
    }

    private LocalDate monthStart(LocalDate selectedDate) {
        LocalDate effectiveDate = selectedDate == null
                ? LocalDate.now(businessZone())
                : selectedDate;
        return effectiveDate.withDayOfMonth(1);
    }

    private ContractorPaymentAllocationJournalItemResponse journalItem(
            ContractorPaymentAllocation allocation,
            List<ContractorPaymentAllocationEvent> events
    ) {
        return new ContractorPaymentAllocationJournalItemResponse(
                allocation.getId(),
                allocation.getAttemptNo(),
                allocation.getMode(),
                allocation.getSourceType(),
                allocation.getSourceId(),
                allocation.getOrderId(),
                allocation.getCommonInvoiceId(),
                allocation.getRecipientType(),
                allocation.getRecipientProfile() == null ? null : allocation.getRecipientProfile().getId(),
                allocation.getRecipientUserId(),
                allocation.getRecipientNameSnapshot(),
                allocation.getCurrentWorkerId(),
                allocation.getCurrentManagerId(),
                allocation.getAmountKopecks(),
                allocation.getConfirmedKopecks(),
                allocation.getReturnedKopecks(),
                allocation.getStatus(),
                allocation.getRoutingDecisionReason(),
                allocation.getSpecialistRejectionReason(),
                allocation.getManagerRejectionReason(),
                allocation.getAvailableBeforeKopecks(),
                allocation.getReservedAt(),
                allocation.getClientReportedAt(),
                allocation.getConfirmedAt(),
                allocation.getReleasedAt(),
                allocation.getCreatedAt(),
                allocation.getUpdatedAt(),
                allocation.getReleaseReason(),
                allocation.getReconcileAttempts(),
                allocation.getReconcileNextRetryAt(),
                allocation.getReconcileLastErrorCode(),
                events.stream().map(this::event).toList()
        );
    }

    private ContractorPaymentAllocationEventResponse event(ContractorPaymentAllocationEvent event) {
        return new ContractorPaymentAllocationEventResponse(
                event.getId(),
                event.getEventType(),
                event.getAmountKopecks(),
                event.getStatusBefore(),
                event.getStatusAfter(),
                event.getRoutingDecisionReason(),
                event.getSpecialistRejectionReason(),
                event.getManagerRejectionReason(),
                event.getEffectiveAt(),
                event.getReason(),
                event.getActor(),
                event.getObservedAt()
        );
    }

    private User resolveCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется авторизация");
        }
        Jwt jwt = jwt(authentication);
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            User bySubject = userRepository.findByKeycloakId(jwt.getSubject()).orElse(null);
            if (bySubject != null) {
                return bySubject;
            }
        }
        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Не удалось определить пользователя");
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private record ActualTransferStats(long count, long amountKopecks) {
        private static ActualTransferStats empty() {
            return new ActualTransferStats(0L, 0L);
        }

        private static ActualTransferStats merge(ActualTransferStats left, ActualTransferStats right) {
            return new ActualTransferStats(
                    Math.addExact(left.count(), right.count()),
                    Math.addExact(left.amountKopecks(), right.amountKopecks())
            );
        }
    }

    private Jwt jwt(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken();
        }
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    private ZoneId businessZone() {
        if (businessZoneId == null || businessZoneId.isBlank()) {
            return DEFAULT_BUSINESS_ZONE;
        }
        try {
            return ZoneId.of(businessZoneId.trim());
        } catch (RuntimeException ignored) {
            return DEFAULT_BUSINESS_ZONE;
        }
    }
}
