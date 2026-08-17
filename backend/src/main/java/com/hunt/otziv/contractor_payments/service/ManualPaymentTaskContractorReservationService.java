package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.payments.service.ManualPaymentTaskRouteErrors;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exact contractor exposure belonging to a frozen manual-task route. */
@Service
@RequiredArgsConstructor
public class ManualPaymentTaskContractorReservationService {

    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentProfileService profileService;
    private final ContractorPaymentAccountingService accountingService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final ManualPaymentTaskRepository taskRepository;
    private final ManualPaymentTaskContractorCapacityService capacityService;

    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorAllocationMode lockAccountingMode() {
        return accountingPhaseService.lockCurrent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long reserve(PaymentLink link, ManualPaymentTaskRouteSnapshot task, ContractorAllocationMode mode) {
        return reserve(link, task, mode, false);
    }

    private Long reserve(
            PaymentLink link, ManualPaymentTaskRouteSnapshot task,
            ContractorAllocationMode mode, boolean legacyRemediation
    ) {
        if (link == null || link.getId() == null) throw ManualPaymentTaskRouteErrors.stale();
        return reserve(
                mode, ContractorAllocationSourceType.PAYMENT_LINK, link.getId(),
                task.source().sourceGeneration(), link.getOrder() == null ? null : link.getOrder().getId(), null,
                link.getOrder() == null || link.getOrder().getWorker() == null
                        ? null : link.getOrder().getWorker().getId(),
                link.getOrder() == null || link.getOrder().getManager() == null
                        ? null : link.getOrder().getManager().getId(),
                0L, task, legacyRemediation
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long reserve(CommonInvoice invoice, ManualPaymentTaskRouteSnapshot task, ContractorAllocationMode mode) {
        return reserve(invoice, task, mode, false);
    }

    private Long reserve(
            CommonInvoice invoice, ManualPaymentTaskRouteSnapshot task,
            ContractorAllocationMode mode, boolean legacyRemediation
    ) {
        if (invoice == null || invoice.getId() == null) throw ManualPaymentTaskRouteErrors.stale();
        long baseline = Math.max(0L, invoice.getAmountKopecks() - task.reservedAmountKopecks());
        return reserve(
                mode, ContractorAllocationSourceType.COMMON_INVOICE, invoice.getId(),
                task.source().sourceGeneration(), null, invoice.getId(),
                invoice.getShadowRouteWorkerId(), invoice.getShadowRouteManagerId(), baseline, task
                , legacyRemediation
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long remediateLegacy(
            PaymentLink link,
            ManualPaymentTaskRouteSnapshot task,
            ContractorAllocationMode mode
    ) {
        if (link == null || link.getId() == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long reusable = releaseConflictingLegacyReservations(
                mode,
                ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId(),
                link.getContractorAllocationId(),
                task
        );
        if (nonContractorTarget(task)) {
            return null;
        }
        return reusable != null ? reusable : reserve(link, task, mode, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long remediateLegacy(
            CommonInvoice invoice,
            ManualPaymentTaskRouteSnapshot task,
            ContractorAllocationMode mode
    ) {
        if (invoice == null || invoice.getId() == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long reusable = releaseConflictingLegacyReservations(
                mode,
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId(),
                invoice.getContractorAllocationId(),
                task
        );
        if (nonContractorTarget(task)) {
            return null;
        }
        return reusable != null ? reusable : reserve(invoice, task, mode, true);
    }

    private Long releaseConflictingLegacyReservations(
            ContractorAllocationMode mode,
            ContractorAllocationSourceType sourceType,
            Long sourceId,
            Long explicitAllocationId,
            ManualPaymentTaskRouteSnapshot task
    ) {
        if (mode == null || !exactLegacySource(sourceType, sourceId, task)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Set<Long> allocationIds = new LinkedHashSet<>(
                allocationRepository.findLatestIdsBySourceAcrossModes(
                        sourceType.name(), sourceId));
        if (explicitAllocationId != null) {
            allocationIds.add(explicitAllocationId);
        }
        List<Long> sortedAllocationIds = allocationIds.stream().sorted().toList();
        Set<Long> profileIds = new LinkedHashSet<>();
        if (task.accountingTargetProfileId() != null) {
            profileIds.add(task.accountingTargetProfileId());
        }
        for (Long allocationId : sortedAllocationIds) {
            allocationRepository.findRecipientProfileIdById(allocationId).ifPresent(profileIds::add);
        }
        profileIds.stream().sorted().forEach(profileId ->
                profileRepository.findByIdForUpdate(profileId)
                        .orElseThrow(ManualPaymentTaskRouteErrors::stale));

        List<ContractorPaymentAllocation> allocations = sortedAllocationIds.isEmpty()
                ? List.of()
                : allocationRepository.findAllByIdForUpdate(sortedAllocationIds);
        if (allocations.size() != sortedAllocationIds.size()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long reusable = null;
        for (ContractorPaymentAllocation allocation : allocations) {
            if (allocation.getSourceType() != sourceType
                    || !Objects.equals(allocation.getSourceId(), sourceId)) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            if (allocation.getStatus() == ContractorAllocationStatus.CLIENT_REPORTED
                    || allocation.getStatus() == ContractorAllocationStatus.PARTIALLY_CONFIRMED
                    || allocation.getConfirmedKopecks() > allocation.getReturnedKopecks()) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            if (allocation.getStatus() != ContractorAllocationStatus.RESERVED) {
                continue;
            }
            if (reusable == null && exactLegacyReservation(allocation, mode, task)) {
                reusable = allocation.getId();
                continue;
            }
            String externalRef = "TASK_LEGACY_REBIND:" + sourceType + ":" + sourceId
                    + ":" + task.source().sourceGeneration() + ":" + allocation.getId();
            boolean released = accountingService.recordRelease(
                    allocation,
                    ContractorAllocationStatus.CANCELED,
                    LocalDateTime.now(),
                    "Старый резерв перенесён к явно выбранному получателю задания",
                    externalRef
            );
            if (!released) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            allocationRepository.saveAndFlush(allocation);
        }
        return reusable;
    }

    private boolean exactLegacyReservation(
            ContractorPaymentAllocation allocation,
            ContractorAllocationMode mode,
            ManualPaymentTaskRouteSnapshot task
    ) {
        Long profileId = allocation.getRecipientProfile() == null
                ? null : allocation.getRecipientProfile().getId();
        ContractorRecipientType expectedType = task.accountingTargetKind()
                == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                ? ContractorRecipientType.SPECIALIST : ContractorRecipientType.MANAGER;
        return !nonContractorTarget(task)
                && allocation.getMode() == mode
                && allocation.getRecipientType() == expectedType
                && Objects.equals(profileId, task.accountingTargetProfileId())
                && Objects.equals(allocation.getSourceGenerationSnapshot(),
                        task.source().sourceGeneration())
                && allocation.getAmountKopecks() == task.reservedAmountKopecks();
    }

    private boolean nonContractorTarget(ManualPaymentTaskRouteSnapshot task) {
        return task.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.OWNER
                || task.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK;
    }

    private boolean exactLegacySource(
            ContractorAllocationSourceType sourceType,
            Long sourceId,
            ManualPaymentTaskRouteSnapshot task
    ) {
        if (sourceType == null || sourceId == null || task == null || task.source() == null) {
            return false;
        }
        com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind expectedKind =
                sourceType == ContractorAllocationSourceType.PAYMENT_LINK
                        ? com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK
                        : sourceType == ContractorAllocationSourceType.COMMON_INVOICE
                                ? com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE
                                : null;
        return expectedKind != null
                && task.source().sourceKind() == expectedKind
                && Objects.equals(task.source().sourceId(), sourceId)
                && Objects.equals(task.source().sourceGeneration(), "LEGACY-" + sourceId);
    }

    private Long reserve(
            ContractorAllocationMode mode, ContractorAllocationSourceType sourceType, Long sourceId,
            String sourceGeneration, Long orderId, Long commonInvoiceId, Long workerId, Long managerId,
            long paidBaseline, ManualPaymentTaskRouteSnapshot task, boolean legacyRemediation
    ) {
        if (legacyRemediation && !exactLegacySource(sourceType, sourceId, task)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (task == null || task.accountingTargetKind() == null
                || task.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        if (task.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK
                || task.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.OWNER) {
            if (task.accountingTargetProfileId() != null) throw ManualPaymentTaskRouteErrors.stale();
            return null;
        }
        ContractorRole role = task.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                ? ContractorRole.SPECIALIST : ContractorRole.MANAGER;
        ContractorRecipientType recipientType = role == ContractorRole.SPECIALIST
                ? ContractorRecipientType.SPECIALIST : ContractorRecipientType.MANAGER;
        Long profileId = task.accountingTargetProfileId();
        if (profileId == null || profileId <= 0) throw ManualPaymentTaskRouteErrors.unresolved();

        ContractorPaymentProfile profile = profileRepository.findByIdForUpdate(profileId)
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        if (profile.getRole() != role || profile.getUser() == null || profile.getUser().getId() == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (!targetAccessPolicy.canManageUser(profile.getUser().getId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (!legacyRemediation
                && (!profile.isEnabled()
                || (mode == ContractorAllocationMode.LIVE && !profile.isLiveEnabled()))) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }

        ContractorPaymentAllocation latest = allocationRepository
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(mode, sourceType, sourceId)
                .orElse(null);
        if (latest != null && latest.getStatus() == ContractorAllocationStatus.RESERVED) {
            if (Objects.equals(latest.getSourceGenerationSnapshot(), sourceGeneration)
                    && latest.getRecipientProfile() != null
                    && Objects.equals(latest.getRecipientProfile().getId(), profileId)
                    && latest.getAmountKopecks() == task.reservedAmountKopecks()) {
                return latest.getId();
            }
            throw ManualPaymentTaskRouteErrors.stale();
        }

        ManualPaymentTask persistedTask = taskRepository.findByIdWithDetails(task.taskId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        if (persistedTask.getGeneration() != task.taskGeneration()
                || persistedTask.getAccountingTargetProfile() == null
                || !Objects.equals(persistedTask.getAccountingTargetProfile().getId(), profileId)
                || persistedTask.getAccountingTargetKind() != task.accountingTargetKind()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ManualPaymentTaskContractorCapacityService.ReservationCapacity capacity =
                capacityService.validateProjectedReservation(
                        profile, mode, persistedTask, task.reservedAmountKopecks());
        long available = Math.max(0L, capacity.capacityPositionBeforeKopecks());
        ContractorPaymentAllocation row = new ContractorPaymentAllocation();
        row.setMode(mode);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setSourceGenerationSnapshot(sourceGeneration);
        row.setAttemptNo(latest == null ? 1 : latest.getAttemptNo() + 1);
        row.setOrderId(orderId);
        row.setCommonInvoiceId(commonInvoiceId);
        row.setManualPaymentTaskId(task.taskId());
        row.setRecipientType(recipientType);
        row.setRecipientProfile(profile);
        row.setRecipientUserId(profile.getUser().getId());
        row.setCurrentWorkerId(workerId);
        row.setCurrentManagerId(managerId);
        row.setAmountKopecks(task.reservedAmountKopecks());
        row.setSourcePaidBaselineKopecks(paidBaseline);
        row.setRoutingDecisionReason(ContractorRoutingDecisionReason.MANUAL_PAYMENT_TASK_SELECTED);
        // Contractor journals describe the accounting recipient, not the
        // holder of the bank card that happened to be shown to the client.
        row.setRecipientNameSnapshot(task.accountingTargetLabel());
        row.setPaymentPhoneSnapshot(task.manualPhone());
        row.setBankNameSnapshot("");
        row.setPaymentCommentSnapshot("Платёжное задание #" + task.taskId());
        row.setAvailableBeforeKopecks(available);
        row.setTaskCapacityPositionBeforeKopecks(capacity.capacityPositionBeforeKopecks());
        row.setTaskCapacityCommitmentBeforeKopecks(capacity.taskCommitmentBeforeKopecks());
        row.setTaskCapacityProjectedOverrunKopecks(capacity.projectedOverrunKopecks());
        row.setTaskCapacityAcknowledgedKopecks(capacity.profileAcknowledgedOverrunKopecks());
        row.setTaskCapacityAcknowledgedAt(capacity.taskAcknowledgedAt());
        row.setTaskCapacityAcknowledgedBy(capacity.taskAcknowledgedBy());
        row.setStatus(ContractorAllocationStatus.RESERVED);
        row.setReservedAt(LocalDateTime.now());
        ContractorPaymentAllocation saved = allocationRepository.save(row);
        accountingService.recordReservation(saved);
        return saved.getId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void preflightRelease(
            Long allocationId, ContractorAllocationSourceType sourceType,
            Long sourceId, String sourceGeneration, long expectedAmountKopecks
    ) {
        if (allocationId == null) return;
        ContractorPaymentAllocation row = lockReleaseAttempt(
                allocationId, sourceType, sourceId, sourceGeneration);
        if (expectedAmountKopecks <= 0L
                || row.getAmountKopecks() != expectedAmountKopecks
                || !releasableTaskStatus(row.getStatus())
                || row.getConfirmedKopecks() > row.getReturnedKopecks()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseLocked(
            Long allocationId, ContractorAllocationSourceType sourceType,
            Long sourceId, String sourceGeneration, ContractorAllocationStatus terminalStatus, String reason
    ) {
        if (allocationId == null) return;
        ContractorPaymentAllocation row = lockReleaseAttempt(
                allocationId, sourceType, sourceId, sourceGeneration);
        String externalRef = "TASK_RELEASE:" + sourceType + ":" + sourceId + ":" + sourceGeneration;
        if (accountingService.recordRelease(row, terminalStatus, LocalDateTime.now(), reason, externalRef)) {
            allocationRepository.save(row);
        }
    }

    private ContractorPaymentAllocation lockReleaseAttempt(
            Long allocationId, ContractorAllocationSourceType sourceType,
            Long sourceId, String sourceGeneration
    ) {
        allocationRepository.findRecipientProfileIdById(allocationId).ifPresent(profileId ->
                profileRepository.findByIdForUpdate(profileId)
                        .orElseThrow(ManualPaymentTaskRouteErrors::stale));
        ContractorPaymentAllocation row = allocationRepository.findByIdForUpdate(allocationId)
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        // Release belongs to the immutable allocation attempt. The global
        // phase may have changed from SHADOW to LIVE since the route was
        // created and must never be used to reinterpret that attempt.
        ContractorAllocationMode persistedMode = row.getMode();
        if (persistedMode == null || row.getSourceType() != sourceType
                || !Objects.equals(row.getSourceId(), sourceId)
                || !Objects.equals(row.getSourceGenerationSnapshot(), sourceGeneration)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return row;
    }

    private boolean releasableTaskStatus(ContractorAllocationStatus status) {
        return status == ContractorAllocationStatus.RESERVED
                || status == ContractorAllocationStatus.CANCELED
                || status == ContractorAllocationStatus.EXPIRED
                || status == ContractorAllocationStatus.RELEASED_UNPAID;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
