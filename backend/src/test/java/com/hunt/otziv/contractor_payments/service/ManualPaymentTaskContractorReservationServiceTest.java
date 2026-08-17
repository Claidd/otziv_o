package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManualPaymentTaskContractorReservationServiceTest {

    @Mock
    private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;
    @Mock
    private ContractorPaymentProfileRepository profileRepository;
    @Mock
    private ContractorPaymentProfileService profileService;
    @Mock
    private ContractorPaymentAccountingService accountingService;
    @Mock
    private ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    @Mock
    private ManualPaymentTaskRepository taskRepository;
    @Mock
    private ManualPaymentTaskContractorCapacityService capacityService;

    @InjectMocks
    private ManualPaymentTaskContractorReservationService service;

    @BeforeEach
    void setUpCapacity() {
        org.mockito.Mockito.lenient().when(capacityService.validateProjectedReservation(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(new ManualPaymentTaskContractorCapacityService.ReservationCapacity(
                10_000L, 0L, 0L, 0L, null, "", 0L));
    }

    @Test
    void releasesPersistedShadowAttemptAfterGlobalPhaseMovedToLive() {
        ContractorPaymentProfile profile = profile(77L);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(91L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(128L);
        allocation.setSourceGenerationSnapshot("source-generation");
        allocation.setRecipientProfile(profile);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);

        when(allocationRepository.findRecipientProfileIdById(91L)).thenReturn(Optional.of(77L));
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        when(allocationRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(allocation));
        when(accountingService.recordRelease(
                eq(allocation),
                eq(ContractorAllocationStatus.RELEASED_UNPAID),
                any(LocalDateTime.class),
                eq("Перевод не поступил"),
                eq("TASK_RELEASE:PAYMENT_LINK:128:source-generation")
        )).thenReturn(true);

        service.releaseLocked(
                91L,
                ContractorAllocationSourceType.PAYMENT_LINK,
                128L,
                "source-generation",
                ContractorAllocationStatus.RELEASED_UNPAID,
                "Перевод не поступил"
        );

        verify(allocationRepository).save(allocation);
        verifyNoInteractions(accountingPhaseService);
    }

    @Test
    void contractorJournalUsesTypedAccountingLabelInsteadOfBankCardHolder() {
        ContractorPaymentProfile profile = profile(77L);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        when(taskRepository.findByIdWithDetails(16L)).thenReturn(Optional.of(
                persistedTask(profile, 3L)));
        when(targetAccessPolicy.canManageUser(42L)).thenReturn(true);
        when(allocationRepository
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        ContractorAllocationMode.SHADOW,
                        ContractorAllocationSourceType.PAYMENT_LINK,
                        128L
                )).thenReturn(Optional.empty());
        when(allocationRepository.save(any(ContractorPaymentAllocation.class))).thenAnswer(invocation -> {
            ContractorPaymentAllocation value = invocation.getArgument(0);
            value.setId(92L);
            return value;
        });

        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(10_000L);
        ManualPaymentTaskRouteSnapshot task = new ManualPaymentTaskRouteSnapshot(
                16L,
                3L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        128L,
                        "source-generation"
                ),
                "TASK:16:3",
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L,
                "Специалист · Анна Щ.",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья (держатель карты)",
                null,
                null,
                10_000L,
                LocalDateTime.now(),
                "admin"
        );

        service.reserve(link, task, ContractorAllocationMode.SHADOW);

        ArgumentCaptor<ContractorPaymentAllocation> captor =
                ArgumentCaptor.forClass(ContractorPaymentAllocation.class);
        verify(allocationRepository).save(captor.capture());
        assertEquals("Специалист · Анна Щ.", captor.getValue().getRecipientNameSnapshot());
        verify(accountingPhaseService, never()).lockCurrent();
    }

    @Test
    void legacyBindingCreatesExactReservedAllocationForHistoricallyDisabledSpecialist() {
        ContractorPaymentProfile profile = profile(77L);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(false);
        when(allocationRepository.findLatestIdsBySourceAcrossModes("PAYMENT_LINK", 128L))
                .thenReturn(List.of());
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        when(taskRepository.findByIdWithDetails(16L)).thenReturn(Optional.of(
                persistedTask(profile, 9L)));
        when(targetAccessPolicy.canManageUser(42L)).thenReturn(true);
        when(allocationRepository
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        ContractorAllocationMode.SHADOW,
                        ContractorAllocationSourceType.PAYMENT_LINK,
                        128L
                )).thenReturn(Optional.empty());
        when(allocationRepository.save(any(ContractorPaymentAllocation.class))).thenAnswer(invocation -> {
            ContractorPaymentAllocation value = invocation.getArgument(0);
            value.setId(92L);
            return value;
        });
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(10_000L);

        Long allocationId = service.remediateLegacy(
                link,
                legacyTask(ManualPaymentTaskAccountingTargetKind.SPECIALIST, 77L),
                ContractorAllocationMode.SHADOW
        );

        assertEquals(92L, allocationId);
        ArgumentCaptor<ContractorPaymentAllocation> captor =
                ArgumentCaptor.forClass(ContractorPaymentAllocation.class);
        verify(allocationRepository).save(captor.capture());
        assertEquals("LEGACY-128", captor.getValue().getSourceGenerationSnapshot());
        assertEquals(
                com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason
                        .MANUAL_PAYMENT_TASK_SELECTED,
                captor.getValue().getRoutingDecisionReason()
        );
    }

    @Test
    void legacyLiveBindingCreatesExactReservationForHistoricallyLiveDisabledSpecialist() {
        ContractorPaymentProfile profile = profile(77L);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(false);
        when(allocationRepository.findLatestIdsBySourceAcrossModes("PAYMENT_LINK", 128L))
                .thenReturn(List.of());
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        when(taskRepository.findByIdWithDetails(16L)).thenReturn(Optional.of(
                persistedTask(profile, 9L)));
        when(targetAccessPolicy.canManageUser(42L)).thenReturn(true);
        when(allocationRepository
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        ContractorAllocationMode.LIVE,
                        ContractorAllocationSourceType.PAYMENT_LINK,
                        128L
                )).thenReturn(Optional.empty());
        when(allocationRepository.save(any(ContractorPaymentAllocation.class))).thenAnswer(invocation -> {
            ContractorPaymentAllocation value = invocation.getArgument(0);
            value.setId(93L);
            return value;
        });
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(10_000L);

        Long allocationId = service.remediateLegacy(
                link,
                legacyTask(ManualPaymentTaskAccountingTargetKind.SPECIALIST, 77L),
                ContractorAllocationMode.LIVE
        );

        assertEquals(93L, allocationId);
        ArgumentCaptor<ContractorPaymentAllocation> captor =
                ArgumentCaptor.forClass(ContractorPaymentAllocation.class);
        verify(allocationRepository).save(captor.capture());
        assertEquals(ContractorAllocationMode.LIVE, captor.getValue().getMode());
        assertEquals("LEGACY-128", captor.getValue().getSourceGenerationSnapshot());
    }

    @Test
    void ordinaryReservationStillRejectsDisabledProfile() {
        ContractorPaymentProfile profile = profile(77L);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(false);
        when(profileRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(profile));
        when(targetAccessPolicy.canManageUser(42L)).thenReturn(true);
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(10_000L);
        ManualPaymentTaskRouteSnapshot ordinary = new ManualPaymentTaskRouteSnapshot(
                16L, 9L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        128L,
                        "ordinary-generation"),
                "TASK:16:9",
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L,
                "Анна",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья",
                null,
                null,
                10_000L,
                null,
                ""
        );

        assertThrows(RuntimeException.class, () ->
                service.reserve(link, ordinary, ContractorAllocationMode.SHADOW));

        verify(allocationRepository, never()).save(any());
        verify(taskRepository, never()).findByIdWithDetails(any());
    }

    @Test
    void legacyRemediationRejectsSourceKindMismatchBeforeAnyProfileWrite() {
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(10_000L);
        ManualPaymentTaskRouteSnapshot wrongSource = new ManualPaymentTaskRouteSnapshot(
                16L, 9L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                        128L,
                        "LEGACY-128"),
                "TASK:16:9",
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L,
                "Анна",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья",
                null,
                null,
                10_000L,
                null,
                ""
        );

        assertThrows(RuntimeException.class, () -> service.remediateLegacy(
                link, wrongSource, ContractorAllocationMode.SHADOW));

        verifyNoInteractions(profileRepository, accountingService);
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void legacyOwnerBindingReleasesOldWorkerReservationAndReturnsNoAllocation() {
        ContractorPaymentProfile oldProfile = profile(66L);
        ContractorPaymentAllocation old = new ContractorPaymentAllocation();
        old.setId(91L);
        old.setMode(ContractorAllocationMode.SHADOW);
        old.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        old.setSourceId(128L);
        old.setSourceGenerationSnapshot("old-generation");
        old.setRecipientProfile(oldProfile);
        old.setRecipientType(com.hunt.otziv.contractor_payments.model.ContractorRecipientType.SPECIALIST);
        old.setStatus(ContractorAllocationStatus.RESERVED);
        old.setAmountKopecks(10_000L);
        when(allocationRepository.findLatestIdsBySourceAcrossModes("PAYMENT_LINK", 128L))
                .thenReturn(List.of(91L));
        when(allocationRepository.findRecipientProfileIdById(91L)).thenReturn(Optional.of(66L));
        when(profileRepository.findByIdForUpdate(66L)).thenReturn(Optional.of(oldProfile));
        when(allocationRepository.findAllByIdForUpdate(List.of(91L))).thenReturn(List.of(old));
        when(accountingService.recordRelease(
                org.mockito.ArgumentMatchers.eq(old),
                org.mockito.ArgumentMatchers.eq(ContractorAllocationStatus.CANCELED),
                any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("TASK_LEGACY_REBIND:PAYMENT_LINK:128")
        )).thenReturn(true);
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(10_000L);
        link.setContractorAllocationId(91L);

        Long allocationId = service.remediateLegacy(
                link,
                legacyTask(ManualPaymentTaskAccountingTargetKind.OWNER, null),
                ContractorAllocationMode.SHADOW
        );

        assertNull(allocationId);
        verify(allocationRepository).saveAndFlush(old);
    }

    private ManualPaymentTaskRouteSnapshot legacyTask(
            ManualPaymentTaskAccountingTargetKind kind,
            Long profileId
    ) {
        return new ManualPaymentTaskRouteSnapshot(
                16L,
                9L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        128L,
                        "LEGACY-128"
                ),
                "TASK:16:9",
                kind,
                profileId,
                kind == ManualPaymentTaskAccountingTargetKind.OWNER ? "Владелец" : "Анна",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья",
                null,
                null,
                10_000L,
                null,
                ""
        );
    }

    private ContractorPaymentProfile profile(Long id) {
        User user = new User();
        user.setId(42L);
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(id);
        profile.setUser(user);
        return profile;
    }

    private ManualPaymentTask persistedTask(ContractorPaymentProfile profile, long generation) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(16L);
        task.setGeneration(generation);
        task.setStatus(com.hunt.otziv.payments.model.ManualPaymentTaskStatus.ACTIVE);
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        task.setAccountingTargetProfile(profile);
        return task;
    }
}

