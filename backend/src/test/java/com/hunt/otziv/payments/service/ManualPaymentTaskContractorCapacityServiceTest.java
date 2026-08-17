package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ManualPaymentTaskContractorCapacityServiceTest {

    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ContractorPaymentAllocationRepository allocationRepository;
    @Mock private ContractorPaymentProfileService profileService;
    @Mock private ContractorPaymentAccountingPhaseService accountingPhaseService;

    private ManualPaymentTaskContractorCapacityService service;
    private ContractorPaymentProfile profile;

    @BeforeEach
    void setUp() {
        service = new ManualPaymentTaskContractorCapacityService(
                profileRepository, allocationRepository, profileService, accountingPhaseService);
        profile = profile(7L);
        org.mockito.Mockito.lenient().when(profileRepository.findAllByIdForUpdate(List.of(7L)))
                .thenReturn(List.of(profile));
        org.mockito.Mockito.lenient().when(profileRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(profile));
        org.mockito.Mockito.lenient().when(accountingPhaseService.lockCurrent())
                .thenReturn(ContractorAllocationMode.LIVE);
    }

    @Test
    void sequentialTasksShareOneProfileCommitmentAndOrdinaryRouteSeesOnlyRemainder() {
        when(profileService.capacityPosition(profile, ContractorAllocationMode.LIVE))
                .thenReturn(100_000L);
        ManualPaymentTask first = task(1L, profile, 80_000L, ManualPaymentTaskStatus.ACTIVE);
        var firstCapacity = service.evaluateTarget(
                profile, ContractorAllocationMode.LIVE,
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE,
                80_000L, 0L, 0L, false);
        assertThat(firstCapacity.projectedOverrunKopecks()).isZero();
        service.synchronize(
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE,
                first, balance(0L, 0L));

        var secondCapacity = service.evaluateTarget(
                profile, ContractorAllocationMode.LIVE,
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE,
                50_000L, 0L, 0L, false);

        assertThat(profile.getManualTaskCommitmentKopecks()).isEqualTo(80_000L);
        assertThat(secondCapacity.currentAvailableKopecks()).isEqualTo(20_000L);
        assertThat(secondCapacity.projectedOverrunKopecks()).isEqualTo(30_000L);
        assertThat(service.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .isEqualTo(20_000L);
    }

    @Test
    void pendingAllocationReplacesCommitmentAndReleaseOrReturnReopensIt() {
        profile.setManualTaskCommitmentKopecks(100_000L);
        ManualPaymentTask task = task(1L, profile, 100_000L, ManualPaymentTaskStatus.ACTIVE);
        var initial = service.snapshot(task, balance(0L, 0L));

        service.synchronize(initial, task, balance(40_000L, 0L));
        assertThat(profile.getManualTaskCommitmentKopecks()).isEqualTo(60_000L);

        var reserved = service.snapshot(task, balance(40_000L, 0L));
        service.synchronize(reserved, task, balance(0L, 0L));
        assertThat(profile.getManualTaskCommitmentKopecks()).isEqualTo(100_000L);

        profile.setManualTaskCommitmentKopecks(0L);
        task.setStatus(ManualPaymentTaskStatus.COMPLETED);
        var completed = service.snapshot(task, balance(0L, 100_000L));
        task.setStatus(ManualPaymentTaskStatus.NEEDS_ATTENTION);
        service.synchronize(completed, task, balance(0L, 60_000L));
        assertThat(profile.getManualTaskCommitmentKopecks()).isEqualTo(40_000L);
    }

    @Test
    void pauseKeepsCommitmentAndActivationDoesNotApplySecondDeltaButRevalidates() {
        profile.setManualTaskCommitmentKopecks(100_000L);
        ManualPaymentTask task = task(1L, profile, 100_000L, ManualPaymentTaskStatus.PAUSED);
        var paused = service.snapshot(task, balance(0L, 0L));
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        service.synchronize(paused, task, balance(0L, 0L));
        assertThat(profile.getManualTaskCommitmentKopecks()).isEqualTo(100_000L);

        when(profileService.capacityPosition(profile, ContractorAllocationMode.LIVE))
                .thenReturn(50_000L);
        assertThatThrownBy(() -> service.requireActivationCovered(
                task, balance(0L, 0L), ContractorAllocationMode.LIVE))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void signedPositionUsesExistingAckWithoutLosingDeficitCoverage() {
        profile.setManualTaskCommitmentKopecks(50_000L);
        profile.setManualTaskOverrunAcknowledgedKopecks(100_000L);
        when(profileService.capacityPosition(profile, ContractorAllocationMode.LIVE))
                .thenReturn(-50_000L);

        var capacity = service.evaluateTarget(
                profile, ContractorAllocationMode.LIVE,
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE,
                50_000L, 0L, 0L, false);

        assertThat(capacity.projectedOverrunKopecks()).isEqualTo(50_000L);
    }

    @Test
    void projectedReservationConsumesOnlyPersistedExactAcknowledgement() {
        profile.setManualTaskCommitmentKopecks(100_000L);
        profile.setManualTaskOverrunAcknowledgedKopecks(50_000L);
        ManualPaymentTask task = task(1L, profile, 150_000L, ManualPaymentTaskStatus.ACTIVE);
        task.setTargetOverrunAcknowledgedAt(LocalDateTime.now());
        task.setTargetOverrunAcknowledgedBy("admin");
        task.setTargetOverrunAcknowledgedKopecks(50_000L);
        when(profileService.capacityPosition(profile, ContractorAllocationMode.LIVE))
                .thenReturn(100_000L);

        var accepted = service.validateProjectedReservation(
                profile, ContractorAllocationMode.LIVE, task, 50_000L);
        assertThat(accepted.projectedOverrunKopecks()).isEqualTo(50_000L);

        assertThatThrownBy(() -> service.validateProjectedReservation(
                profile, ContractorAllocationMode.LIVE, task, 60_000L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shadowTaskExposureRemainsReservedAfterPromotionAndAcrossSettleOrRelease() {
        profile.setManualTaskCommitmentKopecks(60_000L);
        when(profileService.capacityPosition(profile, ContractorAllocationMode.LIVE))
                .thenReturn(100_000L);
        when(allocationRepository.taskCapacityExposureOutsideModeForUpdate(7L, "LIVE"))
                .thenReturn(40_000L, 40_000L, 0L);

        assertThat(service.ordinaryAvailable(profile, ContractorAllocationMode.LIVE)).isZero();

        // Settling the old SHADOW source converts foreign outstanding to
        // foreign net-paid exposure, so the effective amount remains 40k.
        assertThat(service.ordinaryAvailable(profile, ContractorAllocationMode.LIVE)).isZero();

        // Releasing it reopens the same amount in the durable commitment.
        profile.setManualTaskCommitmentKopecks(100_000L);
        assertThat(service.ordinaryAvailable(profile, ContractorAllocationMode.LIVE)).isZero();
    }

    @Test
    void netUnbackedLegacyConfirmationKeepsCapacityBlockedUntilExactReturn() {
        when(profileService.capacityPosition(profile, ContractorAllocationMode.LIVE))
                .thenReturn(100_000L);
        ManualPaymentTask task = task(1L, profile, 80_000L, ManualPaymentTaskStatus.ACTIVE);

        var legacyOnly = service.snapshot(task, balance(0L, 80_000L, 80_000L));
        assertThat(legacyOnly.commitmentKopecks()).isEqualTo(80_000L);
        service.synchronize(
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE,
                task,
                balance(0L, 80_000L, 80_000L));
        assertThat(service.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .isEqualTo(20_000L);

        var partialReturn = service.snapshot(task, balance(0L, 30_000L, 30_000L));
        assertThat(partialReturn.commitmentKopecks()).isEqualTo(80_000L);
        var fullReturn = service.snapshot(task, balance(0L, 0L, 0L));
        assertThat(fullReturn.commitmentKopecks()).isEqualTo(80_000L);
    }

    @Test
    void terminalProfileTaskKeepsNetUnbackedExposureUntilExactLegacyReturn() {
        ManualPaymentTask task = task(1L, profile, 100_000L, ManualPaymentTaskStatus.ACTIVE);

        // 40k typed confirmation is already in profile position; 30k legacy
        // confirmation is not, so the durable unfunded commitment is 60k.
        assertThat(service.snapshot(task, balance(0L, 70_000L, 30_000L))
                .commitmentKopecks()).isEqualTo(60_000L);

        task.setStatus(ManualPaymentTaskStatus.COMPLETED);
        assertThat(service.snapshot(task, balance(0L, 70_000L, 30_000L))
                .commitmentKopecks()).isEqualTo(30_000L);
        assertThat(service.snapshot(task, balance(0L, 40_000L, 0L))
                .commitmentKopecks()).isZero();

        task.setStatus(ManualPaymentTaskStatus.CANCELED);
        assertThat(service.snapshot(task, balance(0L, 30_000L, 30_000L))
                .commitmentKopecks()).isEqualTo(30_000L);
        assertThat(service.snapshot(task, balance(0L, 0L, 0L))
                .commitmentKopecks()).isZero();
    }

    private ManualPaymentTask task(
            Long id,
            ContractorPaymentProfile profile,
            long target,
            ManualPaymentTaskStatus status
    ) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(id);
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        task.setAccountingTargetProfile(profile);
        task.setTargetAmountKopecks(target);
        task.setStatus(status);
        return task;
    }

    private ManualPaymentTaskBalance balance(long pending, long confirmed) {
        return balance(pending, confirmed, 0L);
    }

    private ManualPaymentTaskBalance balance(
            long pending,
            long confirmed,
            long unbackedConfirmed
    ) {
        return new ManualPaymentTaskBalance(
                pending, confirmed, pending + confirmed,
                0L, 0L, 0L, unbackedConfirmed,
                pending > 0L ? 1L : 0L, 0L, unbackedConfirmed > 0L);
    }

    private ContractorPaymentProfile profile(Long id) {
        User user = new User();
        user.setId(70L);
        ContractorPaymentProfile value = new ContractorPaymentProfile();
        value.setId(id);
        value.setUser(user);
        value.setRole(ContractorRole.SPECIALIST);
        value.setEnabled(true);
        value.setLiveEnabled(true);
        return value;
    }
}
