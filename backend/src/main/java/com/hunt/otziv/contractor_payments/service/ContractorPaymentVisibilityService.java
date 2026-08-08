package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
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

    private ContractorPaymentSummaryResponse summary(ContractorPaymentProfile profile) {
        LocalDate monthStart = LocalDate.now(businessZone()).withDayOfMonth(1);
        LocalDate nextMonth = monthStart.plusMonths(1);
        LocalDateTime monthStartTime = monthStart.atStartOfDay();
        LocalDateTime nextMonthTime = nextMonth.atStartOfDay();
        LocalDateTime trackingStartedAt = profile.getTrackingStartedAt();
        boolean currentMonthCoverageComplete = trackingStartedAt != null
                && !trackingStartedAt.isAfter(monthStartTime);
        boolean shadowMode = appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        );
        boolean liveRouting = runtimeSwitch.status().liveRoutingEnabled();
        ContractorAllocationMode balanceMode = accountingPhaseService.current();

        long accruedTotal = ledgerService.totalAccrued(profile);
        long reserved = allocationRepository.sumOutstandingExposure(profile.getId(), balanceMode, RESERVED);
        long clientReported = allocationRepository.sumOutstandingExposure(
                profile.getId(), balanceMode, CLIENT_REPORTED
        );
        long partiallyConfirmedOutstanding = allocationRepository.sumOutstandingExposure(
                profile.getId(), balanceMode, PARTIALLY_CONFIRMED
        );
        long grossConfirmedMonth = accountingService.confirmedGrossInPeriod(
                profile, balanceMode, monthStartTime, nextMonthTime
        );
        long grossConfirmedTotal = accountingService.confirmedGross(profile, balanceMode);
        long returnedMonth = accountingService.returnedInPeriod(
                profile, balanceMode, monthStartTime, nextMonthTime
        );
        long returnedTotal = accountingService.returned(profile, balanceMode);
        long closedWithoutPaymentMonth = accountingService.closedWithoutPaymentInPeriod(
                profile, balanceMode, monthStartTime, nextMonthTime
        );
        long closedWithoutPaymentTotal = accountingService.closedWithoutPayment(profile, balanceMode);
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
                ledgerService.accruedInPeriod(profile, monthStart, nextMonth),
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
