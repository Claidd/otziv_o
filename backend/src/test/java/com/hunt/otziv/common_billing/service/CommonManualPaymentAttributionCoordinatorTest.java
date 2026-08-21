package com.hunt.otziv.common_billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRowRequest;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorOrderManagerResolver;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class CommonManualPaymentAttributionCoordinatorTest {

    private final ContractorPaymentAllocationRepository allocationRepository =
            mock(ContractorPaymentAllocationRepository.class);
    private final ContractorPaymentProfileRepository profileRepository =
            mock(ContractorPaymentProfileRepository.class);
    private final ContractorActualPaymentAttributionRepository attributionRepository =
            mock(ContractorActualPaymentAttributionRepository.class);
    private final ContractorActualPaymentAttributionService attributionService =
            mock(ContractorActualPaymentAttributionService.class);
    private final ContractorPaymentAccountingPhaseService accountingPhaseService =
            mock(ContractorPaymentAccountingPhaseService.class);
    private final ContractorPaymentRuntimeSwitch runtimeSwitch = mock(ContractorPaymentRuntimeSwitch.class);
    private final ManualPaymentTaskContractorCapacityService taskCapacityService =
            mock(ManualPaymentTaskContractorCapacityService.class);
    private final ContractorOrderManagerResolver managerResolver = mock(ContractorOrderManagerResolver.class);
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy =
            mock(ContractorPaymentTargetAccessPolicy.class);
    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService =
            mock(ManualPaymentTaskReceiptIntegrationService.class);
    private final CommonManualPaymentAttributionCoordinator coordinator =
            new CommonManualPaymentAttributionCoordinator(
                    allocationRepository,
                    profileRepository,
                    attributionRepository,
                    attributionService,
                    accountingPhaseService,
                    runtimeSwitch,
                    taskCapacityService,
                    managerResolver,
                    targetAccessPolicy,
                    taskReceiptIntegrationService
            );

    @Test
    void ordinaryCommonCandidateKeepsTaskPriorityAndAddsBackOnlyReusableSourceExposure() {
        ContractorPaymentProfile profile = mock(ContractorPaymentProfile.class);
        when(profile.getId()).thenReturn(77L);
        // Canonical availability already subtracts an 80k task commitment from
        // 100k capacity, including any task exposure carried from SHADOW.
        when(taskCapacityService.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .thenReturn(20_000L);

        Long withoutReusableSource = ReflectionTestUtils.invokeMethod(
                coordinator,
                "availableFor",
                profile,
                ContractorAllocationMode.LIVE,
                null
        );
        assertEquals(20_000L, withoutReusableSource.longValue());

        ContractorPaymentAllocation reusable = mock(ContractorPaymentAllocation.class);
        when(reusable.getRecipientProfile()).thenReturn(profile);
        when(reusable.getStatus()).thenReturn(ContractorAllocationStatus.PARTIALLY_CONFIRMED);
        when(reusable.getAmountKopecks()).thenReturn(30_000L);
        when(reusable.getConfirmedKopecks()).thenReturn(10_000L);
        when(reusable.getReturnedKopecks()).thenReturn(2_000L);

        Long withReusableSource = ReflectionTestUtils.invokeMethod(
                coordinator,
                "availableFor",
                profile,
                ContractorAllocationMode.LIVE,
                reusable
        );
        assertEquals(42_000L, withReusableSource.longValue());
        verify(taskCapacityService, org.mockito.Mockito.times(2))
                .ordinaryAvailable(profile, ContractorAllocationMode.LIVE);
    }

    @Test
    void rejectsNonHttpReceiptBeforeAnyAccountingMutation() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-91",
                true,
                true,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                "Ручная сверка",
                "file:///tmp/receipt.pdf",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "owner",
                        ContractorRecipientType.OWNER,
                        null,
                        10_000L
                ))
        );

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                coordinator.recordFinalReceipt(invoice, List.of(), 10_000L, request, () -> "manager"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(attributionService, never()).recordFinalAttributions(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void rejectsTaskKeyThatClaimsAnotherContractorProfile() {
        when(attributionService.lockEnabledAccountingMode()).thenReturn(ContractorAllocationMode.SHADOW);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        invoice.setPaymentRouteManualSource(com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);

        com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot snapshot =
                mock(com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot.class);
        when(snapshot.candidateKey()).thenReturn("TASK:16:2");
        when(snapshot.taskId()).thenReturn(16L);
        when(snapshot.taskGeneration()).thenReturn(2L);
        when(snapshot.accountingTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        when(snapshot.bankRecipientName()).thenReturn("Наталья П.");
        when(snapshot.accountingTargetLabel()).thenReturn("Специалист · Наталья Ш.");
        when(snapshot.reservedAmountKopecks()).thenReturn(10_000L);
        when(taskReceiptIntegrationService.candidate(invoice)).thenReturn(java.util.Optional.of(snapshot));
        when(taskReceiptIntegrationService.destination(snapshot)).thenReturn(
                new ManualPaymentTaskReceiptIntegrationService.Destination(
                        "TASK:16:2",
                        com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        ContractorRecipientType.SPECIALIST,
                        32L,
                        16L,
                        2L,
                        com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                        "Наталья П.",
                        "Специалист · Наталья Ш."
                ));
        com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile profile =
                mock(com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile.class);
        com.hunt.otziv.u_users.model.User user = mock(com.hunt.otziv.u_users.model.User.class);
        when(profileRepository.findById(32L)).thenReturn(java.util.Optional.of(profile));
        when(profile.getRole()).thenReturn(com.hunt.otziv.contractor_payments.model.ContractorRole.SPECIALIST);
        when(profile.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(13L);
        when(targetAccessPolicy.canManageUser(13L)).thenReturn(true);

        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-91",
                true,
                true,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                "Ручная сверка",
                "",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "task-row",
                        "TASK:16:2",
                        ContractorRecipientType.SPECIALIST,
                        999L,
                        10_000L
                ))
        );

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                coordinator.recordFinalReceipt(invoice, List.of(), 10_000L, request, () -> "manager"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(attributionService, never()).recordFinalAttributions(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void rejectsTaskAndDirectRowsForTheSameEconomicProfile() {
        var direct = new com.hunt.otziv.common_billing.dto.CommonManualPaymentRecipientCandidateResponse(
                "PROFILE:32", ContractorRecipientType.SPECIALIST, 32L, 13L,
                "Специалист · Наталья", false, true, true, 100_000L
        );
        var task = new com.hunt.otziv.common_billing.dto.CommonManualPaymentRecipientCandidateResponse(
                "TASK:16:2",
                ContractorRecipientType.SPECIALIST,
                32L,
                13L,
                "Платёжное задание #16",
                true,
                true,
                true,
                100_000L,
                com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                16L,
                2L,
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                "Наталья П.",
                "Специалист · Наталья",
                "Сумма будет зачтена в задание"
        );
        java.util.Set<String> seen = new java.util.HashSet<>();

        coordinator.requireUniqueEconomicRecipient(seen, direct);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                coordinator.requireUniqueEconomicRecipient(seen, task));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void publicHistoryShapeCannotSerializeEncryptedReceiptUrl() throws Exception {
        CommonManualPaymentAttributionResponse row = new CommonManualPaymentAttributionResponse(
                1L,
                "COMMON_INVOICE:91:batch:owner",
                ContractorAllocationMode.LIVE,
                ContractorRecipientType.OWNER,
                null,
                "Владелец",
                ContractorRecipientType.MANAGER,
                7L,
                "Менеджер · Анна",
                10_000L,
                8_000L,
                2_000L,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                "Ручная сверка",
                "COMMON_INVOICE:91:batch",
                "manager",
                LocalDateTime.of(2026, 8, 15, 10, 1)
        );

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(row);

        assertFalse(json.contains("receiptUrl"));
        assertFalse(json.contains("receipt.pdf"));
    }

    @Test
    void rejectsReplayThatContainsOnlyPartOfOriginallyRecordedBatch() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        ContractorActualPaymentAttribution first = mock(ContractorActualPaymentAttribution.class);
        ContractorActualPaymentAttribution second = mock(ContractorActualPaymentAttribution.class);
        when(first.getEvidenceReference()).thenReturn("COMMON_INVOICE:91:batch-91");
        when(second.getEvidenceReference()).thenReturn("COMMON_INVOICE:91:batch-91");
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                91L
        )).thenReturn(List.of(first, second));
        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-91",
                true,
                true,
                LocalDateTime.of(2025, 8, 15, 10, 0),
                "Ручная сверка",
                "",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "owner",
                        ContractorRecipientType.OWNER,
                        null,
                        10_000L
                ))
        );

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                coordinator.replayIfRecorded(invoice, List.of(), request, () -> "manager"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(attributionService, never()).requireFinalAttributionsAccountingApplied(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                91L
        );
    }

    @Test
    void acceptsExactRecordedBatchAndVerifiesAccountingInvariant() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        LocalDateTime effectiveAt = LocalDateTime.of(2025, 8, 15, 10, 0);
        ContractorActualPaymentAttribution existing = mock(ContractorActualPaymentAttribution.class);
        when(existing.getAttributionKey()).thenReturn("COMMON_INVOICE:91:batch-91:owner");
        when(existing.getEvidenceReference()).thenReturn("COMMON_INVOICE:91:batch-91");
        when(existing.getSourceKind()).thenReturn(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(existing.getSourceId()).thenReturn(91L);
        when(existing.getCommonInvoiceId()).thenReturn(91L);
        when(existing.getActualRecipientType()).thenReturn(ContractorRecipientType.OWNER);
        when(existing.getActualRecipientProfileId()).thenReturn(null);
        when(existing.getAmountKopecks()).thenReturn(10_000L);
        when(existing.getEffectiveAt()).thenReturn(effectiveAt);
        when(existing.getReason()).thenReturn("Ручная сверка");
        when(existing.getReceiptUrl()).thenReturn(null);
        when(existing.getActor()).thenReturn("original-manager");
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                91L
        )).thenReturn(List.of(existing));
        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-91",
                true,
                true,
                effectiveAt,
                "Ручная сверка",
                "",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "owner",
                        ContractorRecipientType.OWNER,
                        null,
                        10_000L
                ))
        );

        boolean replayed = coordinator.replayIfRecorded(
                invoice,
                List.of(),
                request,
                () -> "manager"
        );

        assertTrue(replayed);
        InOrder order = inOrder(attributionService, taskReceiptIntegrationService);
        order.verify(attributionService).lockEnabledAccountingMode();
        order.verify(taskReceiptIntegrationService).settle(
                invoice,
                "SPLIT:batch-91",
                0L,
                "TASK:SETTLE:COMMON_INVOICE:91:batch-91",
                "original-manager",
                "Ручная сверка"
        );
        order.verify(attributionService).requireFinalAttributionsAccountingApplied(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 91L);
    }

    @Test
    void frozenTaskReplayUsesPersistedModeAfterGlobalFeatureDisable() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(193L);
        invoice.setPaymentRouteManualSource(
                com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(118L);
        invoice.setPaymentRouteManualTaskGeneration(3L);
        invoice.setPaymentRouteManualTaskSourceGeneration("source-193");
        invoice.setPaymentRouteManualTaskAccountingMode(ContractorAllocationMode.SHADOW);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.of(2026, 8, 15, 9, 0));
        LocalDateTime effectiveAt = LocalDateTime.of(2026, 8, 15, 10, 0);
        ContractorActualPaymentAttribution existing = mock(ContractorActualPaymentAttribution.class);
        when(existing.getAttributionKey()).thenReturn("COMMON_INVOICE:193:batch-193:task-owner");
        when(existing.getEvidenceReference()).thenReturn("COMMON_INVOICE:193:batch-193");
        when(existing.getSourceKind()).thenReturn(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(existing.getSourceId()).thenReturn(193L);
        when(existing.getCommonInvoiceId()).thenReturn(193L);
        when(existing.getActualRecipientType()).thenReturn(ContractorRecipientType.OWNER);
        when(existing.getActualRecipientProfileId()).thenReturn(null);
        when(existing.getActualCashDestinationKind()).thenReturn(
                com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        when(existing.getActualManualPaymentTaskId()).thenReturn(118L);
        when(existing.getActualManualPaymentTaskGeneration()).thenReturn(3L);
        when(existing.getActualManualPaymentTaskTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER);
        when(existing.getAmountKopecks()).thenReturn(10_000L);
        when(existing.getEffectiveAt()).thenReturn(effectiveAt);
        when(existing.getReason()).thenReturn("Ручная сверка frozen route");
        when(existing.getReceiptUrl()).thenReturn(null);
        when(existing.getActor()).thenReturn("original-owner");
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                193L
        )).thenReturn(List.of(existing));
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-193",
                true,
                true,
                effectiveAt,
                "Ручная сверка frozen route",
                "",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "task-owner",
                        "TASK:118:3",
                        ContractorRecipientType.OWNER,
                        null,
                        10_000L
                ))
        );

        boolean replayed = coordinator.replayIfRecorded(
                invoice,
                List.of(),
                request,
                () -> "retry-owner"
        );

        assertTrue(replayed);
        InOrder order = inOrder(accountingPhaseService, taskReceiptIntegrationService, attributionService);
        order.verify(accountingPhaseService).lockCurrent();
        order.verify(taskReceiptIntegrationService).settle(
                invoice,
                "TASK:118:3",
                10_000L,
                "TASK:SETTLE:COMMON_INVOICE:193:batch-193",
                "original-owner",
                "Ручная сверка frozen route"
        );
        order.verify(attributionService).requireFinalAttributionsAccountingAppliedForFrozenSource(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                193L,
                ContractorAllocationMode.SHADOW
        );
        verify(attributionService, never()).lockEnabledAccountingMode();
        verify(attributionService, never()).requireFinalAttributionsAccountingApplied(
                any(), anyLong());
    }

    @Test
    void taskOwnerSuppressesOrdinaryOwnerAlias() {
        when(attributionService.lockEnabledAccountingMode()).thenReturn(ContractorAllocationMode.SHADOW);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        invoice.setPaymentRouteManualSource(com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        var snapshot = mock(com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot.class);
        when(snapshot.candidateKey()).thenReturn("TASK:16:2");
        when(snapshot.taskId()).thenReturn(16L);
        when(snapshot.taskGeneration()).thenReturn(2L);
        when(snapshot.accountingTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER);
        when(snapshot.bankRecipientName()).thenReturn("Наталья П.");
        when(snapshot.accountingTargetLabel()).thenReturn("Владелец");
        when(taskReceiptIntegrationService.candidate(invoice)).thenReturn(java.util.Optional.of(snapshot));
        when(taskReceiptIntegrationService.destination(snapshot)).thenReturn(
                new ManualPaymentTaskReceiptIntegrationService.Destination(
                        "TASK:16:2",
                        com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        ContractorRecipientType.OWNER,
                        null,
                        16L,
                        2L,
                        com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER,
                        "Наталья П.",
                        "Владелец"
                ));

        var options = coordinator.options(invoice, List.of(), 10_000L);

        assertEquals("TASK:16:2", options.defaultRecipientKey());
        assertTrue(options.candidates().stream().anyMatch(row -> row.key().equals("TASK:16:2")));
        assertFalse(options.candidates().stream().anyMatch(row -> row.key().equals("OWNER")));
    }

    @ParameterizedTest
    @EnumSource(ContractorAllocationMode.class)
    void frozenTaskReceiptUsesPersistedModeAfterGlobalFeatureDisable(
            ContractorAllocationMode persistedMode
    ) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(191L);
        invoice.setPaymentRouteManualSource(
                com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(116L);
        invoice.setPaymentRouteManualTaskGeneration(2L);
        invoice.setPaymentRouteManualTaskSourceGeneration("source-191");
        invoice.setPaymentRouteManualTaskAccountingMode(persistedMode);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.of(2026, 8, 15, 9, 0));
        var snapshot = mock(com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot.class);
        when(snapshot.candidateKey()).thenReturn("TASK:116:2");
        when(snapshot.taskId()).thenReturn(116L);
        when(snapshot.taskGeneration()).thenReturn(2L);
        when(snapshot.source()).thenReturn(new com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef(
                com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                191L,
                "source-191"));
        when(snapshot.accountingTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER);
        when(snapshot.bankRecipientName()).thenReturn("Наталья П.");
        when(snapshot.accountingTargetLabel()).thenReturn("Владелец");
        when(snapshot.reservedAmountKopecks()).thenReturn(10_000L);
        when(taskReceiptIntegrationService.candidate(invoice))
                .thenReturn(java.util.Optional.of(snapshot));
        when(taskReceiptIntegrationService.destination(snapshot)).thenReturn(
                new ManualPaymentTaskReceiptIntegrationService.Destination(
                        "TASK:116:2",
                        com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        ContractorRecipientType.OWNER,
                        null,
                        116L,
                        2L,
                        com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.OWNER,
                        "Наталья П.",
                        "Владелец"
                ));
        when(accountingPhaseService.lockCurrent()).thenReturn(
                persistedMode == ContractorAllocationMode.SHADOW
                        ? ContractorAllocationMode.LIVE : ContractorAllocationMode.LIVE);
        when(attributionService.recordFinalAttributionsForFrozenSource(
                any(), anyList(), org.mockito.ArgumentMatchers.eq(persistedMode)))
                .thenReturn(List.of());
        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-191",
                true,
                true,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                "Ручная сверка frozen route",
                "",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "task-owner",
                        "TASK:116:2",
                        ContractorRecipientType.OWNER,
                        null,
                        10_000L
                ))
        );

        coordinator.recordFinalReceipt(invoice, List.of(), 10_000L, request, () -> "owner");

        verify(accountingPhaseService).lockCurrent();
        verify(attributionService, never()).lockEnabledAccountingMode();
        verify(attributionService).recordFinalAttributionsForFrozenSource(
                any(), anyList(), org.mockito.ArgumentMatchers.eq(persistedMode));
        verify(attributionService, never()).recordFinalAttributions(any(), anyList());
        verify(taskReceiptIntegrationService).settle(
                invoice,
                "TASK:116:2",
                10_000L,
                "TASK:SETTLE:COMMON_INVOICE:191:batch-191",
                "owner",
                "Ручная сверка frozen route"
        );
    }

    @Test
    void preCutoverFrozenTaskWithoutPersistedModeUsesShadowFallback() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(192L);
        invoice.setPaymentRouteManualSource(
                com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(117L);
        invoice.setPaymentRouteManualTaskGeneration(2L);
        invoice.setPaymentRouteManualTaskSourceGeneration("LEGACY-192");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.of(2026, 8, 19, 9, 0));
        when(runtimeSwitch.completionAccountingActivatedAt())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 20, 22, 0)));
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        var snapshot = mock(com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot.class);
        when(snapshot.candidateKey()).thenReturn("TASK:117:2");
        when(snapshot.taskId()).thenReturn(117L);
        when(snapshot.taskGeneration()).thenReturn(2L);
        when(snapshot.source()).thenReturn(new com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef(
                com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                192L,
                "LEGACY-192"));
        when(snapshot.accountingTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        when(snapshot.accountingTargetProfileId()).thenReturn(44L);
        when(snapshot.bankRecipientName()).thenReturn("Анастасия Щ.");
        when(snapshot.accountingTargetLabel()).thenReturn("Специалист · Анастасия Щ.");
        when(snapshot.reservedAmountKopecks()).thenReturn(2_600L);
        when(taskReceiptIntegrationService.candidate(invoice))
                .thenReturn(Optional.of(snapshot));
        when(taskReceiptIntegrationService.destination(snapshot)).thenReturn(
                new ManualPaymentTaskReceiptIntegrationService.Destination(
                        "TASK:117:2",
                        com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        ContractorRecipientType.SPECIALIST,
                        44L,
                        117L,
                        2L,
                        com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                        "Анастасия Щ.",
                        "Специалист · Анастасия Щ."
                ));
        ContractorPaymentProfile profile = mock(ContractorPaymentProfile.class);
        User user = mock(User.class);
        when(profile.getId()).thenReturn(44L);
        when(profile.getRole()).thenReturn(com.hunt.otziv.contractor_payments.model.ContractorRole.SPECIALIST);
        when(profile.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(144L);
        when(user.getFio()).thenReturn("Анастасия Щ.");
        when(profileRepository.findById(44L)).thenReturn(Optional.of(profile));
        when(targetAccessPolicy.canManageUser(144L)).thenReturn(true);
        when(attributionService.recordFinalAttributionsForFrozenSource(
                any(), anyList(), org.mockito.ArgumentMatchers.eq(ContractorAllocationMode.SHADOW)))
                .thenReturn(List.of());
        CommonManualPaymentAttributionRequest request = new CommonManualPaymentAttributionRequest(
                "batch-192",
                true,
                true,
                LocalDateTime.of(2026, 8, 21, 12, 10),
                "Ручная сверка старого маршрута",
                "",
                List.of(new CommonManualPaymentAttributionRowRequest(
                        "task-specialist",
                        "TASK:117:2",
                        ContractorRecipientType.SPECIALIST,
                        44L,
                        2_600L
                ))
        );

        coordinator.recordFinalReceipt(invoice, List.of(), 2_600L, request, () -> "owner");

        verify(accountingPhaseService).lockCurrent();
        verify(attributionService, never()).lockEnabledAccountingMode();
        verify(attributionService).recordFinalAttributionsForFrozenSource(
                any(), anyList(), org.mockito.ArgumentMatchers.eq(ContractorAllocationMode.SHADOW));
        verify(taskReceiptIntegrationService).settle(
                invoice,
                "TASK:117:2",
                2_600L,
                "TASK:SETTLE:COMMON_INVOICE:192:batch-192",
                "owner",
                "Ручная сверка старого маршрута"
        );
    }

    @Test
    void postCutoverFrozenTaskWithoutPersistedModeFailsClosed() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(193L);
        invoice.setPaymentRouteManualSource(
                com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(117L);
        invoice.setPaymentRouteManualTaskGeneration(2L);
        invoice.setPaymentRouteManualTaskSourceGeneration("source-193");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.of(2026, 8, 21, 9, 0));
        when(runtimeSwitch.completionAccountingActivatedAt())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 8, 20, 22, 0)));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> coordinator.options(invoice, List.of(), 10_000L)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(accountingPhaseService).lockCurrent();
        verify(attributionService, never()).lockEnabledAccountingMode();
    }

    @Test
    void taskProfileSuppressesOrdinaryAliasForTheSameProfile() {
        when(attributionService.lockEnabledAccountingMode()).thenReturn(ContractorAllocationMode.SHADOW);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        invoice.setPaymentRouteManualSource(com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        var snapshot = mock(com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot.class);
        when(snapshot.candidateKey()).thenReturn("TASK:16:2");
        when(snapshot.taskId()).thenReturn(16L);
        when(snapshot.taskGeneration()).thenReturn(2L);
        when(snapshot.accountingTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        when(snapshot.bankRecipientName()).thenReturn("Наталья П.");
        when(snapshot.accountingTargetLabel()).thenReturn("Специалист · Наталья Ш.");
        when(taskReceiptIntegrationService.candidate(invoice)).thenReturn(java.util.Optional.of(snapshot));
        when(taskReceiptIntegrationService.destination(snapshot)).thenReturn(
                new ManualPaymentTaskReceiptIntegrationService.Destination(
                        "TASK:16:2",
                        com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        ContractorRecipientType.SPECIALIST,
                        32L,
                        16L,
                        2L,
                        com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                        "Наталья П.",
                        "Специалист · Наталья Ш."
                ));
        var profile = mock(com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile.class);
        var user = mock(com.hunt.otziv.u_users.model.User.class);
        when(profile.getId()).thenReturn(32L);
        when(profile.getRole()).thenReturn(com.hunt.otziv.contractor_payments.model.ContractorRole.SPECIALIST);
        when(profile.getUser()).thenReturn(user);
        when(profile.isEnabled()).thenReturn(true);
        when(user.getId()).thenReturn(13L);
        when(user.getFio()).thenReturn("Наталья Ш.");
        when(profileRepository.findById(32L)).thenReturn(java.util.Optional.of(profile));
        when(profileRepository.findByUserIdAndRole(
                13L,
                com.hunt.otziv.contractor_payments.model.ContractorRole.SPECIALIST
        )).thenReturn(java.util.Optional.of(profile));
        when(targetAccessPolicy.canManageUser(13L)).thenReturn(true);
        var item = mock(com.hunt.otziv.common_billing.model.CommonInvoiceOrder.class);
        var order = mock(com.hunt.otziv.p_products.model.Order.class);
        var worker = mock(com.hunt.otziv.u_users.model.Worker.class);
        when(item.isActiveMembership()).thenReturn(true);
        when(item.getOrder()).thenReturn(order);
        when(order.getWorker()).thenReturn(worker);
        when(worker.getId()).thenReturn(5L);
        when(worker.getUser()).thenReturn(user);

        var options = coordinator.options(invoice, List.of(item), 10_000L);

        assertEquals("TASK:16:2", options.defaultRecipientKey());
        assertTrue(options.candidates().stream().anyMatch(row -> row.key().equals("TASK:16:2")));
        assertFalse(options.candidates().stream().anyMatch(row -> row.key().equals("PROFILE:32")));
    }

    @Test
    void externalTaskHistoryIsNotMislabelledAsSpecialist() {
        when(attributionService.lockEnabledAccountingMode()).thenReturn(ContractorAllocationMode.SHADOW);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        ContractorActualPaymentAttribution row = mock(ContractorActualPaymentAttribution.class);
        when(row.getOriginalRecipientType()).thenReturn(null);
        when(row.getActualRecipientType()).thenReturn(null);
        when(row.getOriginalRecipientUserId()).thenReturn(null);
        when(row.getActualRecipientUserId()).thenReturn(null);
        when(row.getOriginalRecipientNameSnapshot()).thenReturn("Наталья П.");
        when(row.getActualRecipientNameSnapshot()).thenReturn("Наталья П.");
        when(row.getOriginalCashDestinationKind()).thenReturn(
                com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        when(row.getActualCashDestinationKind()).thenReturn(
                com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        when(row.getOriginalManualPaymentTaskTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        when(row.getActualManualPaymentTaskTargetKind()).thenReturn(
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                91L
        )).thenReturn(List.of(row));

        var options = coordinator.options(invoice, List.of(), 10_000L);

        assertEquals(1, options.history().size());
        assertTrue(options.history().getFirst().actualRecipientLabel().contains("Внешний получатель"));
        assertTrue(options.history().getFirst().actualRecipientLabel().contains("Наталья П."));
        assertFalse(options.history().getFirst().actualRecipientLabel().contains("Специалист"));
    }
}
