package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ManualPaymentTaskAccountingTargetPolicyTest {

    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private WorkerRepository workerRepository;
    @Mock private ManualPaymentTaskRepository taskRepository;
    @Mock private ManualPaymentTaskLedgerService ledgerService;
    @Mock private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock private ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    @Mock private ManualPaymentTaskContractorCapacityService capacityService;

    private ManualPaymentTaskAccountingTargetPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ManualPaymentTaskAccountingTargetPolicy(
                profileRepository,
                workerRepository,
                taskRepository,
                ledgerService,
                accountingPhaseService,
                targetAccessPolicy,
                capacityService
        );
    }

    @Test
    void existingTaskComparesOnlyRemainingTargetAndAddsBackItsOwnPendingExposure() {
        ContractorPaymentProfile profile = profile(true);
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(16L);
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        task.setAccountingTargetProfile(profile);

        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        when(taskRepository.findByIdWithDetails(16L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(16L)).thenReturn(new ManualPaymentTaskBalance(
                1_115_000L,
                3_870_000L,
                4_985_000L,
                0, 0, 0, 0, 1, 0, false
        ));
        when(capacityService.snapshot(task, ledgerService.balance(16L))).thenReturn(
                new ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot(
                        16L, 77L, 15_000L, 0L));
        when(capacityService.evaluateTarget(
                any(), any(), any(), anyLong(), anyLong(), anyLong(), anyBoolean()
        )).thenReturn(new ManualPaymentTaskContractorCapacityService.TargetCapacity(
                15_000L, 0L, 0L, 1_130_000L, 15_000L, 0L));

        var result = policy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST.name(),
                77L,
                5_000_000L,
                false,
                16L
        );

        assertEquals(1_130_000L, result.currentAvailableKopecks());
        assertEquals(0L, result.projectedOverrunKopecks());
        assertFalse(result.acknowledgementUsed());
    }

    @Test
    void liveModeMarksGloballyEnabledButLiveDisabledProfileIneligible() {
        ContractorPaymentProfile profile = profile(false);
        when(profileRepository.findAllEnabledWithUser()).thenReturn(List.of(profile));
        when(targetAccessPolicy.canManageUser(42L)).thenReturn(true);
        when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.LIVE);
        when(capacityService.evaluateTargetSnapshot(
                any(), any(), any(), anyLong(), anyLong(), anyLong(), anyBoolean()
        )).thenReturn(new ManualPaymentTaskContractorCapacityService.TargetCapacity(
                500_000L, 0L, 0L, 500_000L, 100_000L, 0L));

        var option = policy.managementOptions(null, 100_000L, null).stream()
                .filter(value -> Long.valueOf(77L).equals(value.profileId()))
                .findFirst()
                .orElseThrow();

        assertFalse(option.enabled());

        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        assertThrows(ResponseStatusException.class, () -> policy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST.name(),
                77L,
                100_000L,
                false,
                null
        ));
    }

    @Test
    void exactLegacyRemediationOffersHistoricalProfileButNewTaskStillRejectsIt() {
        ContractorPaymentProfile profile = profile(false);
        profile.setEnabled(false);
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(21L);
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.UNRESOLVED);
        task.setTargetAmountKopecks(100_000L);
        ManualPaymentTaskBalance balance = new ManualPaymentTaskBalance(
                100_000L, 0L, 100_000L,
                0L, 0L, 0L, 0L, 1L, 0L, false);
        when(taskRepository.findByIdWithDetails(21L)).thenReturn(Optional.of(task));
        when(ledgerService.pendingUnresolvedSources(21L)).thenReturn(List.of(
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        44L,
                        "LEGACY-44")));
        when(ledgerService.balance(21L)).thenReturn(balance);
        when(capacityService.snapshot(task, balance)).thenReturn(
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE);
        when(profileRepository.findAllWithUser()).thenReturn(List.of(profile));
        when(targetAccessPolicy.canManageUser(42L)).thenReturn(true);
        when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.LIVE);
        when(capacityService.evaluateTargetSnapshot(
                any(), any(), any(), anyLong(), anyLong(), anyLong(), anyBoolean()
        )).thenReturn(new ManualPaymentTaskContractorCapacityService.TargetCapacity(
                0L, 0L, 0L, 0L, 100_000L, 100_000L));
        when(capacityService.evaluateTarget(
                any(), any(), any(), anyLong(), anyLong(), anyLong(), anyBoolean()
        )).thenReturn(new ManualPaymentTaskContractorCapacityService.TargetCapacity(
                0L, 0L, 0L, 0L, 100_000L, 100_000L));

        var historical = policy.managementOptions(null, 100_000L, 21L).stream()
                .filter(option -> Long.valueOf(77L).equals(option.profileId()))
                .findFirst()
                .orElseThrow();
        assertEquals(true, historical.enabled());
        assertEquals(true, historical.needsAcknowledgement());
        assertEquals(true, historical.label().contains("исторический/отключённый"));

        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        assertThrows(ResponseStatusException.class, () -> policy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST.name(),
                77L, 100_000L, true, null));
        assertThrows(ResponseStatusException.class, () -> policy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST.name(),
                77L, 100_000L, false, 21L, true));

        var resolution = policy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST.name(),
                77L, 100_000L, true, 21L, true);
        assertEquals(true, resolution.label().contains("исторический/отключённый"));
        assertEquals(100_000L, resolution.projectedOverrunKopecks());
        assertEquals(true, resolution.acknowledgementRefreshed());
    }

    private ContractorPaymentProfile profile(boolean liveEnabled) {
        User user = new User();
        user.setId(42L);
        user.setFio("Анна");
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(77L);
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(liveEnabled);
        return profile;
    }
}
