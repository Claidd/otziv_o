package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Profile-scoped mutex and durable commitment accounting for typed manual
 * payment tasks. The profile aggregate avoids stale REPEATABLE READ snapshots:
 * every capacity writer changes the same locked profile row.
 */
@Service
@RequiredArgsConstructor
public class ManualPaymentTaskContractorCapacityService {

    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentProfileService profileService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;

    public TaskCommitmentSnapshot snapshot(
            ManualPaymentTask task,
            ManualPaymentTaskBalance balance
    ) {
        if (!isProfileTarget(task)) {
            return task == null || task.getId() == null
                    ? TaskCommitmentSnapshot.NONE
                    : new TaskCommitmentSnapshot(task.getId(), null, 0L, 0L);
        }
        Long profileId = task.getAccountingTargetProfile().getId();
        long pending = nonNegative(balance == null ? 0L : balance.pendingAmountKopecks());
        long confirmed = nonNegative(balance == null ? 0L : balance.netConfirmedAmountKopecks());
        long unbackedConfirmed = nonNegative(
                balance == null ? 0L : balance.unverifiedConfirmedAmountKopecks());
        boolean terminal = task.getStatus() == ManualPaymentTaskStatus.COMPLETED
                || task.getStatus() == ManualPaymentTaskStatus.CANCELED;
        long commitment = terminal
                ? unbackedConfirmed
                : addSaturated(
                        commitment(task.getTargetAmountKopecks(), confirmed, pending),
                        unbackedConfirmed);
        long acknowledged = !terminal && hasAuditableAcknowledgement(task)
                ? nonNegative(task.getTargetOverrunAcknowledgedKopecks()) : 0L;
        return new TaskCommitmentSnapshot(task.getId(), profileId, commitment, acknowledged);
    }

    public TargetCapacity evaluateTarget(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            TaskCommitmentSnapshot current,
            long targetAmountKopecks,
            long confirmedAmountKopecks,
            long pendingAmountKopecks,
            boolean pendingAlreadyBackedByThisProfile
    ) {
        return evaluateTarget(profile, mode, current, targetAmountKopecks,
                confirmedAmountKopecks, pendingAmountKopecks,
                pendingAlreadyBackedByThisProfile, false);
    }

    /** Non-locking preview for read-only task/list/options endpoints. */
    public TargetCapacity evaluateTargetSnapshot(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            TaskCommitmentSnapshot current,
            long targetAmountKopecks,
            long confirmedAmountKopecks,
            long pendingAmountKopecks,
            boolean pendingAlreadyBackedByThisProfile
    ) {
        return evaluateTarget(profile, mode, current, targetAmountKopecks,
                confirmedAmountKopecks, pendingAmountKopecks,
                pendingAlreadyBackedByThisProfile, true);
    }

    private TargetCapacity evaluateTarget(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            TaskCommitmentSnapshot current,
            long targetAmountKopecks,
            long confirmedAmountKopecks,
            long pendingAmountKopecks,
            boolean pendingAlreadyBackedByThisProfile,
            boolean snapshot
    ) {
        if (profile == null || profile.getId() == null || mode == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        TaskCommitmentSnapshot safeCurrent = current == null
                ? TaskCommitmentSnapshot.NONE : current;
        boolean sameProfile = Objects.equals(profile.getId(), safeCurrent.profileId());
        long ownCommitment = sameProfile ? safeCurrent.commitmentKopecks() : 0L;
        long ownAcknowledged = sameProfile ? safeCurrent.acknowledgedOverrunKopecks() : 0L;
        long otherCommitment = subtractNonNegative(
                profile.getManualTaskCommitmentKopecks(), ownCommitment,
                "Нарушен агрегат обязательств платёжных заданий"
        );
        long otherAcknowledged = subtractNonNegative(
                profile.getManualTaskOverrunAcknowledgedKopecks(), ownAcknowledged,
                "Нарушен агрегат подтверждённых превышений платёжных заданий"
        );
        long foreignProfileExposure = nonNegative(snapshot
                ? allocationRepository.taskCapacityExposureOutsideModeSnapshot(
                        profile.getId(), mode.name())
                : allocationRepository.taskCapacityExposureOutsideModeForUpdate(
                        profile.getId(), mode.name()));
        long ownForeignExposure = safeCurrent.taskId() == null ? 0L : nonNegative(snapshot
                ? allocationRepository.taskCapacityExposureOutsideModeSnapshot(
                        profile.getId(), safeCurrent.taskId(), mode.name())
                : allocationRepository.taskCapacityExposureOutsideModeForUpdate(
                        profile.getId(), safeCurrent.taskId(), mode.name()));
        long ownAllModeExposure = safeCurrent.taskId() == null ? 0L : nonNegative(snapshot
                ? allocationRepository.taskCapacityExposureAllModesSnapshot(
                        profile.getId(), safeCurrent.taskId())
                : allocationRepository.taskCapacityExposureAllModesForUpdate(
                        profile.getId(), safeCurrent.taskId()));
        long effectiveOtherCommitment = addSaturated(
                otherCommitment,
                subtractNonNegative(
                        foreignProfileExposure,
                        ownForeignExposure,
                        "Нарушен агрегат межрежимного покрытия платёжных заданий"
                )
        );
        long position = snapshot
                ? profileService.capacityPositionSnapshot(profile, mode)
                : profileService.capacityPosition(profile, mode);
        // Acknowledgement is signed deficit coverage, not merely a discount
        // bounded by the remaining commitment. It must therefore also cover a
        // negative raw position created by the task's already persisted
        // allocations/confirmations.
        long capacityForTask = addSaturated(
                subtractSaturated(position, effectiveOtherCommitment),
                otherAcknowledged
        );
        long pending = nonNegative(pendingAmountKopecks);
        long confirmed = nonNegative(confirmedAmountKopecks);
        long proposedCommitment = commitment(targetAmountKopecks, confirmed, pending);
        long ledgerExposure = addSaturated(pending, confirmed);
        long unbackedLedgerExposure = Math.max(0L,
                subtractSaturated(ledgerExposure, ownAllModeExposure));
        long capacityDemand = addSaturated(
                addSaturated(proposedCommitment, ownForeignExposure),
                unbackedLedgerExposure
        );
        long overrun = Math.max(0L, subtractSaturated(capacityDemand, capacityForTask));
        long displayAvailable = Math.max(0L, capacityForTask);
        long ownCurrentModeExposure = Math.max(0L,
                subtractSaturated(ownAllModeExposure, ownForeignExposure));
        if (pendingAlreadyBackedByThisProfile || ownCurrentModeExposure > 0L) {
            displayAvailable = addSaturated(displayAvailable, ownCurrentModeExposure);
        }
        return new TargetCapacity(
                position,
                otherCommitment,
                otherAcknowledged,
                displayAvailable,
                proposedCommitment,
                overrun
        );
    }

    public long ordinaryAvailable(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode
    ) {
        if (profile == null || profile.getId() == null || mode == null) {
            return 0L;
        }
        long position = profileService.capacityPosition(profile, mode);
        long foreignTaskExposure = nonNegative(
                allocationRepository.taskCapacityExposureOutsideModeForUpdate(
                        profile.getId(), mode.name()));
        long effectiveCommitment = addSaturated(
                nonNegative(profile.getManualTaskCommitmentKopecks()),
                foreignTaskExposure
        );
        return Math.max(0L, subtractSaturated(position, effectiveCommitment));
    }

    /** Locks both sides of a destination change in stable profile-id order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProfilesForChange(Long oldProfileId, Long newProfileId) {
        List<Long> ids = java.util.stream.Stream.of(oldProfileId, newProfileId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .sorted()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        if (profileRepository.findAllByIdForUpdate(ids).size() != ids.size()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationCapacity validateReservationIntent(
            ManualPaymentTask task,
            ManualPaymentTaskBalance balance,
            long amountKopecks
    ) {
        TaskCommitmentSnapshot current = snapshot(task, balance);
        if (current.profileId() == null || current.commitmentKopecks() < amountKopecks) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ContractorAllocationMode mode = accountingPhaseService.lockCurrent();
        ContractorPaymentProfile profile = profileRepository.findByIdForUpdate(current.profileId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        return requireCovered(profile, mode, 0L, task);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActivationCovered(
            ManualPaymentTask task,
            ManualPaymentTaskBalance balance,
            ContractorAllocationMode mode
    ) {
        TaskCommitmentSnapshot current = snapshot(task, balance);
        if (current.profileId() == null) {
            return;
        }
        ContractorPaymentProfile profile = profileRepository.findByIdForUpdate(current.profileId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        TargetCapacity capacity = evaluateTarget(
                profile,
                mode,
                current,
                task.getTargetAmountKopecks(),
                balance == null ? 0L : balance.netConfirmedAmountKopecks(),
                balance == null ? 0L : balance.pendingAmountKopecks(),
                true
        );
        if (capacity.projectedOverrunKopecks() > current.acknowledgedOverrunKopecks()) {
            throw conflict("Доступный лимит получателя изменился; подтвердите точную сумму превышения");
        }
    }

    /** Rechecks the invariant including the allocation that is about to be persisted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationCapacity validateProjectedReservation(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            ManualPaymentTask task,
            long allocationAmountKopecks
    ) {
        if (allocationAmountKopecks <= 0L) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return requireCovered(profile, mode, allocationAmountKopecks, task);
    }

    private ReservationCapacity requireCovered(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            long newAllocationAmountKopecks,
            ManualPaymentTask task
    ) {
        if (profile == null || profile.getId() == null || mode == null || !counts(task)
                || !Objects.equals(profile.getId(), task.getAccountingTargetProfile().getId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        long positionBefore = profileService.capacityPosition(profile, mode);
        long projectedPosition = subtractSaturated(positionBefore, newAllocationAmountKopecks);
        long commitment = nonNegative(profile.getManualTaskCommitmentKopecks());
        long foreignTaskExposure = nonNegative(
                allocationRepository.taskCapacityExposureOutsideModeForUpdate(
                        profile.getId(), mode.name()));
        long effectiveCommitment = addSaturated(commitment, foreignTaskExposure);
        long acknowledged = nonNegative(profile.getManualTaskOverrunAcknowledgedKopecks());
        long requiredAcknowledgement = Math.max(0L,
                subtractSaturated(effectiveCommitment, projectedPosition));
        if (requiredAcknowledgement > acknowledged) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return new ReservationCapacity(
                positionBefore,
                effectiveCommitment,
                requiredAcknowledgement,
                acknowledged,
                task.getTargetOverrunAcknowledgedAt(),
                normalize(task.getTargetOverrunAcknowledgedBy()),
                nonNegative(task.getTargetOverrunAcknowledgedKopecks())
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronize(
            TaskCommitmentSnapshot before,
            ManualPaymentTask task,
            ManualPaymentTaskBalance afterBalance
    ) {
        TaskCommitmentSnapshot oldValue = before == null
                ? TaskCommitmentSnapshot.NONE : before;
        TaskCommitmentSnapshot newValue = snapshot(task, afterBalance);
        Map<Long, Delta> deltas = new LinkedHashMap<>();
        addDelta(deltas, oldValue.profileId(), -oldValue.commitmentKopecks(),
                -oldValue.acknowledgedOverrunKopecks());
        addDelta(deltas, newValue.profileId(), newValue.commitmentKopecks(),
                newValue.acknowledgedOverrunKopecks());
        List<Long> ids = deltas.keySet().stream().sorted().toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, ContractorPaymentProfile> locked = profileRepository.findAllByIdForUpdate(ids)
                .stream().collect(java.util.stream.Collectors.toMap(
                        ContractorPaymentProfile::getId, value -> value));
        if (locked.size() != ids.size()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        for (Long id : ids) {
            ContractorPaymentProfile profile = locked.get(id);
            Delta delta = deltas.get(id);
            long commitment = addExact(profile.getManualTaskCommitmentKopecks(),
                    delta.commitmentKopecks());
            long acknowledged = addExact(profile.getManualTaskOverrunAcknowledgedKopecks(),
                    delta.acknowledgedKopecks());
            if (commitment < 0L || acknowledged < 0L) {
                throw conflict("Нарушен агрегат лимита платёжных заданий; нужна сверка");
            }
            profile.setManualTaskCommitmentKopecks(commitment);
            profile.setManualTaskOverrunAcknowledgedKopecks(acknowledged);
            profileRepository.save(profile);
        }
    }

    private boolean counts(ManualPaymentTask task) {
        if (!isProfileTarget(task)) {
            return false;
        }
        ManualPaymentTaskStatus status = task.getStatus();
        return status != ManualPaymentTaskStatus.COMPLETED
                && status != ManualPaymentTaskStatus.CANCELED;
    }

    private boolean isProfileTarget(ManualPaymentTask task) {
        if (task == null || task.getAccountingTargetProfile() == null
                || task.getAccountingTargetProfile().getId() == null) {
            return false;
        }
        ManualPaymentTaskAccountingTargetKind kind = task.getAccountingTargetKind();
        return kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                || kind == ManualPaymentTaskAccountingTargetKind.MANAGER;
    }

    private boolean hasAuditableAcknowledgement(ManualPaymentTask task) {
        Long amount = task == null ? null : task.getTargetOverrunAcknowledgedKopecks();
        LocalDateTime at = task == null ? null : task.getTargetOverrunAcknowledgedAt();
        return amount != null && amount > 0L && at != null
                && !normalize(task.getTargetOverrunAcknowledgedBy()).isBlank();
    }

    private long commitment(long target, long confirmed, long pending) {
        long remaining = subtractSaturated(nonNegative(target), nonNegative(confirmed));
        remaining = subtractSaturated(remaining, nonNegative(pending));
        return Math.max(0L, remaining);
    }

    private void addDelta(Map<Long, Delta> target, Long profileId, long commitment, long ack) {
        if (profileId == null || profileId <= 0L || (commitment == 0L && ack == 0L)) {
            return;
        }
        target.merge(profileId, new Delta(commitment, ack), (left, right) -> new Delta(
                addExact(left.commitmentKopecks(), right.commitmentKopecks()),
                addExact(left.acknowledgedKopecks(), right.acknowledgedKopecks())
        ));
    }

    private long subtractNonNegative(long value, long subtracted, String message) {
        long result = addExact(value, Math.negateExact(subtracted));
        if (result < 0L) {
            throw conflict(message);
        }
        return result;
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw conflict("Переполнение агрегата лимита платёжных заданий");
        }
    }

    private long addSaturated(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private long subtractSaturated(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return left >= 0L && right < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record TaskCommitmentSnapshot(
            Long taskId,
            Long profileId,
            long commitmentKopecks,
            long acknowledgedOverrunKopecks
    ) {
        public static final TaskCommitmentSnapshot NONE = new TaskCommitmentSnapshot(
                null, null, 0L, 0L);
    }

    public record TargetCapacity(
            long profileCapacityPositionKopecks,
            long otherTaskCommitmentKopecks,
            long otherTaskAcknowledgedOverrunKopecks,
            long currentAvailableKopecks,
            long proposedCommitmentKopecks,
            long projectedOverrunKopecks
    ) {
    }

    public record ReservationCapacity(
            long capacityPositionBeforeKopecks,
            long taskCommitmentBeforeKopecks,
            long projectedOverrunKopecks,
            long profileAcknowledgedOverrunKopecks,
            LocalDateTime taskAcknowledgedAt,
            String taskAcknowledgedBy,
            long taskAcknowledgedOverrunKopecks
    ) {
    }

    private record Delta(long commitmentKopecks, long acknowledgedKopecks) {
    }
}
