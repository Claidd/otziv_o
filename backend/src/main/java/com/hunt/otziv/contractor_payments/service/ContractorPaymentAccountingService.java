package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Append-only financial facts for contractor payment routing. The allocation
 * itself is only a current snapshot; statistics must use these facts so a
 * later return or cancellation cannot erase an earlier confirmation.
 */
@Service
@RequiredArgsConstructor
public class ContractorPaymentAccountingService {

    private static final Set<ContractorAllocationEventType> LIVE_CONFIRMED =
            EnumSet.of(ContractorAllocationEventType.CONFIRMED);
    private static final Set<ContractorAllocationEventType> SHADOW_CONFIRMED =
            EnumSet.of(ContractorAllocationEventType.SIMULATED_CONFIRMED);
    private static final Set<ContractorAllocationEventType> RETURNED =
            EnumSet.of(ContractorAllocationEventType.RETURNED);
    private static final Set<ContractorAllocationEventType> CLOSED_WITHOUT_PAYMENT = EnumSet.of(
            ContractorAllocationEventType.RELEASED,
            ContractorAllocationEventType.EXPIRED,
            ContractorAllocationEventType.CANCELED
    );
    private static final Set<ContractorAllocationStatus> RELEASABLE_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED,
            ContractorAllocationStatus.OWNER_FALLBACK
    );
    private static final Set<ContractorAllocationStatus> RELEASE_TARGET_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RELEASED_UNPAID,
            ContractorAllocationStatus.EXPIRED,
            ContractorAllocationStatus.CANCELED
    );
    private static final Set<ContractorAllocationStatus> RETURN_PENDING_SOURCES = EnumSet.of(
            ContractorAllocationStatus.CONFIRMED,
            ContractorAllocationStatus.SIMULATED_PAID,
            ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED,
            ContractorAllocationStatus.PARTIALLY_RETURNED
    );

    private final ContractorPaymentAllocationEventRepository eventRepository;

    public void recordReservation(ContractorPaymentAllocation allocation) {
        String decisionSummary = routingDecisionSummary(allocation);
        append(
                allocation,
                allocation.getStatus() == ContractorAllocationStatus.OWNER_FALLBACK
                        ? ContractorAllocationEventType.OWNER_FALLBACK
                        : ContractorAllocationEventType.RESERVED,
                allocation.getStatus() == ContractorAllocationStatus.OWNER_FALLBACK
                        ? 0L
                        : allocation.getAmountKopecks(),
                allocation.getReservedAt(),
                decisionSummary,
                "RESERVATION:ATTEMPT:" + allocation.getAttemptNo(),
                null,
                allocation.getStatus()
        );
    }

    public boolean recordClientReported(
            ContractorPaymentAllocation allocation,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef
    ) {
        if (eventExists(allocation, externalRef)) {
            return false;
        }
        if (allocation.getStatus() == ContractorAllocationStatus.CLIENT_REPORTED) {
            return false;
        }
        if (allocation.getStatus() != ContractorAllocationStatus.RESERVED) {
            return false;
        }
        ContractorAllocationStatus before = allocation.getStatus();
        allocation.setStatus(ContractorAllocationStatus.CLIENT_REPORTED);
        allocation.setClientReportedAt(actualTime(effectiveAt));
        append(allocation, ContractorAllocationEventType.CLIENT_REPORTED, allocation.getAmountKopecks(),
                effectiveAt, reason, externalRef, before, allocation.getStatus());
        return true;
    }

    public boolean recordConfirmation(
            ContractorPaymentAllocation allocation,
            long confirmedTotalKopecks,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef,
            boolean simulated,
            boolean late
    ) {
        if (eventExists(allocation, externalRef)) {
            return false;
        }
        long normalizedTotal = Math.max(0L, confirmedTotalKopecks);
        if (normalizedTotal <= allocation.getConfirmedKopecks()) {
            return false;
        }
        long delta = Math.max(0L, normalizedTotal - allocation.getConfirmedKopecks());
        ContractorAllocationStatus before = allocation.getStatus();
        boolean unresolvedReturn = allocation.isNeedsReturnAmount()
                || before == ContractorAllocationStatus.RETURN_AMOUNT_PENDING;
        allocation.setConfirmedKopecks(Math.max(allocation.getConfirmedKopecks(), normalizedTotal));
        allocation.setConfirmedAt(actualTime(effectiveAt));
        long netConfirmed = Math.max(0L, allocation.getConfirmedKopecks() - allocation.getReturnedKopecks());
        allocation.setStatus(unresolvedReturn
                ? ContractorAllocationStatus.RETURN_AMOUNT_PENDING
                : late
                    ? ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE
                    : netConfirmed < allocation.getAmountKopecks()
                        ? ContractorAllocationStatus.PARTIALLY_CONFIRMED
                        : simulated
                            ? ContractorAllocationStatus.SIMULATED_PAID
                            : ContractorAllocationStatus.CONFIRMED);
        if (late && !unresolvedReturn) {
            allocation.setReleaseReason(limit(reason));
        }
        append(
                allocation,
                simulated
                        ? ContractorAllocationEventType.SIMULATED_CONFIRMED
                        : ContractorAllocationEventType.CONFIRMED,
                delta,
                effectiveAt,
                reason,
                externalRef,
                before,
                allocation.getStatus()
        );
        return true;
    }

    public boolean recordReturnTotal(
            ContractorPaymentAllocation allocation,
            long returnedTotalKopecks,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef
    ) {
        if (eventExists(allocation, externalRef)) {
            return false;
        }
        long ceiling = Math.max(0L, allocation.getConfirmedKopecks());
        long normalizedTotal = Math.max(0L, Math.min(returnedTotalKopecks, ceiling));
        if (normalizedTotal < allocation.getReturnedKopecks()) {
            return false;
        }
        boolean resolvingPending = allocation.isNeedsReturnAmount()
                || allocation.getStatus() == ContractorAllocationStatus.RETURN_AMOUNT_PENDING;
        if (normalizedTotal == allocation.getReturnedKopecks() && !resolvingPending) {
            return false;
        }
        long delta = Math.max(0L, normalizedTotal - allocation.getReturnedKopecks());
        ContractorAllocationStatus before = allocation.getStatus();
        allocation.setReturnedKopecks(Math.max(allocation.getReturnedKopecks(), normalizedTotal));
        allocation.setNeedsReturnAmount(false);
        long net = Math.max(0L, ceiling - normalizedTotal);
        if (normalizedTotal == 0L) {
            allocation.setReleasedAt(null);
            allocation.setReleaseReason(null);
            allocation.setStatus(net < allocation.getAmountKopecks()
                    ? ContractorAllocationStatus.PARTIALLY_CONFIRMED
                    : allocation.getMode() == ContractorAllocationMode.SHADOW
                        ? ContractorAllocationStatus.SIMULATED_PAID
                        : ContractorAllocationStatus.CONFIRMED);
        } else {
            allocation.setReleasedAt(actualTime(effectiveAt));
            allocation.setReleaseReason(limit(reason));
            allocation.setStatus(normalizedTotal >= ceiling && ceiling > 0
                    ? ContractorAllocationStatus.RETURNED
                    : ContractorAllocationStatus.PARTIALLY_RETURNED);
        }
        append(allocation, ContractorAllocationEventType.RETURNED, delta, effectiveAt, reason,
                externalRef, before, allocation.getStatus());
        return true;
    }

    public boolean recordReturnAmountPending(
            ContractorPaymentAllocation allocation,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef
    ) {
        if (eventExists(allocation, externalRef)) {
            return false;
        }
        if (allocation.getStatus() == ContractorAllocationStatus.RETURN_AMOUNT_PENDING) {
            return false;
        }
        if (!RETURN_PENDING_SOURCES.contains(allocation.getStatus())
                || allocation.getConfirmedKopecks() <= allocation.getReturnedKopecks()) {
            return false;
        }
        ContractorAllocationStatus before = allocation.getStatus();
        allocation.setNeedsReturnAmount(true);
        allocation.setReleasedAt(actualTime(effectiveAt));
        allocation.setReleaseReason(limit(reason));
        allocation.setStatus(ContractorAllocationStatus.RETURN_AMOUNT_PENDING);
        append(allocation, ContractorAllocationEventType.RETURN_AMOUNT_PENDING, 0L, effectiveAt, reason,
                externalRef, before, allocation.getStatus());
        return true;
    }

    public boolean recordRelease(
            ContractorPaymentAllocation allocation,
            ContractorAllocationStatus status,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef
    ) {
        if (eventExists(allocation, externalRef)) {
            return false;
        }
        if (!RELEASE_TARGET_STATUSES.contains(status)
                || !RELEASABLE_STATUSES.contains(allocation.getStatus())) {
            return false;
        }
        ContractorAllocationStatus before = allocation.getStatus();
        allocation.setStatus(status);
        allocation.setReleasedAt(actualTime(effectiveAt));
        allocation.setReleaseReason(limit(reason));
        ContractorAllocationEventType type = switch (status) {
            case EXPIRED -> ContractorAllocationEventType.EXPIRED;
            case CANCELED -> ContractorAllocationEventType.CANCELED;
            default -> ContractorAllocationEventType.RELEASED;
        };
        append(allocation, type,
                before == ContractorAllocationStatus.OWNER_FALLBACK
                        ? 0L
                        : Math.max(0L, allocation.getAmountKopecks() - allocation.getConfirmedKopecks()),
                effectiveAt, reason, externalRef,
                before, allocation.getStatus());
        return true;
    }

    public long confirmedGross(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        return eventRepository.sumByProfileAndModeAndTypeIn(
                profile.getId(), mode,
                mode == ContractorAllocationMode.SHADOW ? SHADOW_CONFIRMED : LIVE_CONFIRMED
        );
    }

    public long confirmedGrossInPeriod(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return eventRepository.sumByProfileAndModeAndTypeInAndPeriod(
                profile.getId(), mode,
                mode == ContractorAllocationMode.SHADOW ? SHADOW_CONFIRMED : LIVE_CONFIRMED,
                from, to
        );
    }

    public long returned(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        return eventRepository.sumByProfileAndModeAndTypeIn(profile.getId(), mode, RETURNED);
    }

    public long returnedInPeriod(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return eventRepository.sumByProfileAndModeAndTypeInAndPeriod(
                profile.getId(), mode, RETURNED, from, to
        );
    }

    public long closedWithoutPayment(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        return eventRepository.sumByProfileAndModeAndTypeIn(profile.getId(), mode, CLOSED_WITHOUT_PAYMENT);
    }

    public long closedWithoutPaymentInPeriod(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return eventRepository.sumByProfileAndModeAndTypeInAndPeriod(
                profile.getId(), mode, CLOSED_WITHOUT_PAYMENT, from, to
        );
    }

    public long netReceived(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        return Math.max(0L, confirmedGross(profile, mode) - returned(profile, mode));
    }

    public List<ContractorPaymentAllocationEvent> history(Long allocationId) {
        return eventRepository.findAllByAllocationIdOrderByEffectiveAtAscIdAsc(allocationId);
    }

    private void append(
            ContractorPaymentAllocation allocation,
            ContractorAllocationEventType type,
            long amount,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef,
            ContractorAllocationStatus before,
            ContractorAllocationStatus after
    ) {
        ContractorPaymentAllocationEvent event = new ContractorPaymentAllocationEvent();
        event.setAllocation(allocation);
        event.setEventType(type);
        event.setAmountKopecks(Math.max(0L, amount));
        event.setStatusBefore(before);
        event.setStatusAfter(after);
        event.setRoutingDecisionReason(allocation.getRoutingDecisionReason());
        event.setSpecialistRejectionReason(allocation.getSpecialistRejectionReason());
        event.setManagerRejectionReason(allocation.getManagerRejectionReason());
        event.setEffectiveAt(actualTime(effectiveAt));
        event.setReason(limit(reason));
        event.setExternalRef(limitRef(externalRef));
        event.setActor(currentActor());
        eventRepository.save(event);
    }

    private String routingDecisionSummary(ContractorPaymentAllocation allocation) {
        if (allocation == null || allocation.getRoutingDecisionReason() == null) {
            return null;
        }
        StringBuilder summary = new StringBuilder("routingDecision=")
                .append(allocation.getRoutingDecisionReason().name());
        if (allocation.getSpecialistRejectionReason() != null) {
            summary.append("; specialistRejection=")
                    .append(allocation.getSpecialistRejectionReason().name());
        }
        if (allocation.getManagerRejectionReason() != null) {
            summary.append("; managerRejection=")
                    .append(allocation.getManagerRejectionReason().name());
        }
        return limit(summary.toString());
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "system";
        }
        String actor = authentication.getName().trim();
        return actor.length() <= 150 ? actor : actor.substring(0, 150);
    }

    private boolean eventExists(ContractorPaymentAllocation allocation, String externalRef) {
        return allocation != null
                && allocation.getId() != null
                && eventRepository.existsByAllocationIdAndExternalRef(allocation.getId(), limitRef(externalRef));
    }

    private LocalDateTime actualTime(LocalDateTime value) {
        return value == null ? LocalDateTime.now() : value;
    }

    private String limit(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private String limitRef(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("externalRef must not be blank");
        }
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }
}
