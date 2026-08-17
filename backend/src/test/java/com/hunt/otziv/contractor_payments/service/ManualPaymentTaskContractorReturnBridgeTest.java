package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReturnCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.ManualPaymentTaskArchivedSourceRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ManualPaymentTaskContractorReturnBridgeTest {

    private static final long TASK_ID = 16L;
    private static final long TASK_GENERATION = 3L;
    private static final long PROFILE_ID = 77L;
    private static final long ATTRIBUTION_ID = 501L;
    private static final long ALLOCATION_ID = 91L;
    private static final long AMOUNT = 100_000L;

    private ContractorActualPaymentAttributionRepository attributionRepository;
    private PaymentLinkRepository paymentLinkRepository;
    private CommonInvoiceRepository commonInvoiceRepository;
    private ManualPaymentTaskLedgerService taskLedgerService;
    private ManualPaymentTaskArchivedSourceRepository archivedSourceRepository;
    private ManualPaymentTaskContractorReturnBridge service;

    @BeforeEach
    void setUp() {
        attributionRepository = mock(ContractorActualPaymentAttributionRepository.class);
        paymentLinkRepository = mock(PaymentLinkRepository.class);
        commonInvoiceRepository = mock(CommonInvoiceRepository.class);
        taskLedgerService = mock(ManualPaymentTaskLedgerService.class);
        archivedSourceRepository = mock(ManualPaymentTaskArchivedSourceRepository.class);
        service = new ManualPaymentTaskContractorReturnBridge(
                attributionRepository,
                paymentLinkRepository,
                commonInvoiceRepository,
                taskLedgerService,
                archivedSourceRepository
        );
    }

    @Test
    void paymentLinkPartialReturnRecordsExactDelta() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(0L);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockPaymentLinkBinding(scenario.allocation(), scenario.link());
        scenario.allocation().setReturnedKopecks(40_000L);
        service.recordReturn(binding, scenario.allocation());

        assertReturn(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 40_000L, 40_000L);
    }

    @Test
    void paymentLinkActualAllocationFullReturnRecordsOnlyRemainder() {
        Scenario scenario = actualScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(40_000L);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockActualPaymentBinding(scenario.allocation());
        scenario.allocation().setReturnedKopecks(AMOUNT);
        service.recordReturn(binding, scenario.allocation());

        assertReturn(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 60_000L, AMOUNT);
    }

    @Test
    void paymentLinkReplayIsNoOpWhenLedgerAlreadyHasCumulativeReturn() {
        Scenario scenario = actualScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        scenario.allocation().setReturnedKopecks(AMOUNT);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(AMOUNT);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockActualPaymentBinding(scenario.allocation());
        service.recordReturn(binding, scenario.allocation());

        verify(taskLedgerService, never()).recordReturn(any());
    }

    @Test
    void commonInvoicePartialReturnRecordsExactDelta() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(0L);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockCommonInvoiceBinding(scenario.allocation(), scenario.invoice());
        scenario.allocation().setReturnedKopecks(25_000L);
        service.recordReturn(binding, scenario.allocation());

        assertReturn(ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 25_000L, 25_000L);
    }

    @Test
    void commonInvoiceActualAllocationFullReturnRecordsOnlyRemainder() {
        Scenario scenario = actualScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(25_000L);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockActualPaymentBinding(scenario.allocation());
        scenario.allocation().setReturnedKopecks(AMOUNT);
        service.recordReturn(binding, scenario.allocation());

        assertReturn(ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 75_000L, AMOUNT);
    }

    @Test
    void commonInvoiceReplayIsNoOpWhenLedgerAlreadyHasCumulativeReturn() {
        Scenario scenario = actualScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        scenario.allocation().setReturnedKopecks(AMOUNT);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(AMOUNT);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockActualPaymentBinding(scenario.allocation());
        service.recordReturn(binding, scenario.allocation());

        verify(taskLedgerService, never()).recordReturn(any());
    }

    @Test
    void commonSplitToTaskAndPlainProfileOnSameAllocationFailsClosed() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        ContractorActualPaymentAttribution plainProfile = attribution(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                scenario.source().sourceId(),
                false,
                ALLOCATION_ID
        );
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                scenario.source().sourceId()
        )).thenReturn(List.of(scenario.attribution(), plainProfile));

        assertThrows(
                RuntimeException.class,
                () -> service.lockCommonInvoiceBinding(scenario.allocation(), scenario.invoice())
        );
        verify(taskLedgerService, never()).lockReturnSource(any(), any());
    }

    @Test
    void ownerPaymentLinkFullRefundRecordsSourceBoundReturnWithoutAllocation() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(scenario.attribution().getActualManualPaymentTaskTargetKind())
                .thenReturn(ManualPaymentTaskAccountingTargetKind.OWNER);
        scenario.link().setAmountKopecks(AMOUNT);
        scenario.link().setStatus(PaymentLinkStatus.REFUNDED);
        when(taskLedgerService.lockReturnSource(TASK_ID, scenario.source())).thenReturn(25_000L);

        service.recordAuthoritativePaymentLinkReturn(scenario.link());

        assertReturn(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 75_000L, AMOUNT);
    }

    @Test
    void externalPaymentLinkPartialRefundReopensTaskWithoutGuessingAmount() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(scenario.attribution().getActualManualPaymentTaskTargetKind())
                .thenReturn(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        scenario.link().setAmountKopecks(AMOUNT);
        scenario.link().setStatus(PaymentLinkStatus.PARTIAL_REFUNDED);

        service.recordAuthoritativePaymentLinkReturn(scenario.link());

        verify(taskLedgerService).markReturnNeedsAttention(TASK_ID, scenario.source());
        verify(taskLedgerService, never()).recordReturn(any());
        verify(taskLedgerService, never()).lockReturnSource(any(), any());
    }

    @Test
    void redirectedProfilePaymentLinkFullRefundDoesNotDebitTaskLedger() {
        PaymentLink link = paymentLink(129L);
        link.setAmountKopecks(AMOUNT);
        link.setStatus(PaymentLinkStatus.REFUNDED);
        ContractorActualPaymentAttribution profile = attribution(
                ContractorActualPaymentSourceKind.PAYMENT_LINK, 129L, false, null);
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.PAYMENT_LINK, 129L))
                .thenReturn(List.of(profile));

        service.recordAuthoritativePaymentLinkReturn(link);
        service.recordAuthoritativePaymentLinkReturn(link);

        verifyNoInteractions(taskLedgerService);
    }

    @Test
    void redirectedOwnerPaymentLinkPartialRefundDoesNotReopenOrDebitTask() {
        PaymentLink link = paymentLink(130L);
        link.setAmountKopecks(AMOUNT);
        link.setStatus(PaymentLinkStatus.PARTIAL_REFUNDED);
        ContractorActualPaymentAttribution owner = mock(ContractorActualPaymentAttribution.class);
        when(owner.getId()).thenReturn(502L);
        when(owner.getSourceKind()).thenReturn(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(owner.getSourceId()).thenReturn(130L);
        when(owner.getAmountKopecks()).thenReturn(AMOUNT);
        when(owner.getActualCashDestinationKind()).thenReturn(ContractorCashDestinationKind.OWNER);
        when(owner.getActualRecipientType()).thenReturn(ContractorRecipientType.OWNER);
        when(owner.getActualRecipientProfileId()).thenReturn(null);
        when(owner.getActualManualPaymentTaskId()).thenReturn(null);
        when(owner.getActualManualPaymentTaskGeneration()).thenReturn(null);
        when(owner.getActualManualPaymentTaskTargetKind()).thenReturn(null);
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.PAYMENT_LINK, 130L))
                .thenReturn(List.of(owner));

        service.recordAuthoritativePaymentLinkReturn(link);

        verifyNoInteractions(taskLedgerService);
    }

    @Test
    void legacyPaymentLinkFullRefundUsesExactUnverifiedSourceBaseline() {
        PaymentLink link = paymentLink(128L);
        link.setManualTaskGeneration(null);
        link.setManualTaskSourceGeneration(null);
        link.setAmountKopecks(AMOUNT);
        link.setStatus(PaymentLinkStatus.REFUNDED);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L, "LEGACY-128");
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.PAYMENT_LINK, 128L)).thenReturn(List.of());
        when(taskLedgerService.lockLegacyConfirmedReturnSource(
                TASK_ID, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L))
                .thenReturn(new ManualPaymentTaskLedgerService.LockedLegacyReturnSource(
                        source, TASK_ID, TASK_GENERATION, AMOUNT, 25_000L,
                        "V251:BASELINE:PAYMENT_LINK:128"));

        service.recordAuthoritativePaymentLinkReturn(link);

        ArgumentCaptor<ManualPaymentTaskReturnCommand> captor =
                ArgumentCaptor.forClass(ManualPaymentTaskReturnCommand.class);
        verify(taskLedgerService).recordReturn(captor.capture());
        assertEquals(75_000L, captor.getValue().amountKopecks());
        assertEquals(source, captor.getValue().source());
        assertEquals(
                "TASK:RETURN:PAYMENT_LINK:128:LEGACY:TOTAL:" + AMOUNT,
                captor.getValue().operationKey());
        verify(taskLedgerService, never()).lockReturnSource(any(), any());
    }

    @Test
    void restoredLegacyPaymentLinkPartialRefundOnlyReopensExactTask() {
        PaymentLink link = paymentLink(128L);
        link.setManualTaskGeneration(null);
        link.setManualTaskSourceGeneration(null);
        link.setAmountKopecks(AMOUNT);
        link.setStatus(PaymentLinkStatus.PARTIAL_REFUNDED);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L, "LEGACY-128");
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.PAYMENT_LINK, 128L)).thenReturn(List.of());
        when(taskLedgerService.lockLegacyConfirmedReturnSource(
                TASK_ID, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L))
                .thenReturn(new ManualPaymentTaskLedgerService.LockedLegacyReturnSource(
                        source, TASK_ID, TASK_GENERATION, AMOUNT, 0L,
                        "V251:BASELINE:PAYMENT_LINK:128"));

        service.recordAuthoritativePaymentLinkReturn(link);

        verify(taskLedgerService).markReturnNeedsAttention(TASK_ID, source);
        verify(taskLedgerService, never()).recordReturn(any());
        verify(taskLedgerService, never()).lockReturnSource(any(), any());
    }

    @Test
    void legacyPaymentLinkFullRefundReplayIsNoOpAtCumulativeTotal() {
        PaymentLink link = paymentLink(128L);
        link.setManualTaskGeneration(null);
        link.setManualTaskSourceGeneration(null);
        link.setAmountKopecks(AMOUNT);
        link.setStatus(PaymentLinkStatus.REVERSED);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L, "LEGACY-128");
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.PAYMENT_LINK, 128L)).thenReturn(List.of());
        when(taskLedgerService.lockLegacyConfirmedReturnSource(
                TASK_ID, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L))
                .thenReturn(new ManualPaymentTaskLedgerService.LockedLegacyReturnSource(
                        source, TASK_ID, TASK_GENERATION, AMOUNT, AMOUNT,
                        "V251:BASELINE:PAYMENT_LINK:128"));

        service.recordAuthoritativePaymentLinkReturn(link);

        verify(taskLedgerService, never()).recordReturn(any());
    }

    @Test
    void restoredTypedPaymentLinkRefundUsesAttributionAndDurableLedgerBinding() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        scenario.link().setManualTaskGeneration(null);
        scenario.link().setManualTaskSourceGeneration(null);
        scenario.link().setAmountKopecks(AMOUNT);
        scenario.link().setStatus(PaymentLinkStatus.REFUNDED);
        when(taskLedgerService.lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                scenario.link().getId()
        )).thenReturn(new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(
                scenario.source(), 20_000L));

        service.recordAuthoritativePaymentLinkReturn(scenario.link());

        assertReturn(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 80_000L, AMOUNT);
        verify(taskLedgerService).lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                scenario.link().getId()
        );
        verify(taskLedgerService, never()).lockReturnSource(any(), any());
    }

    @Test
    void commonOwnerReturnWithoutAllocationIsNotSynthesized() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(scenario.attribution().getActualManualPaymentTaskTargetKind())
                .thenReturn(ManualPaymentTaskAccountingTargetKind.OWNER);

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockCommonInvoiceBinding(scenario.allocation(), scenario.invoice());
        scenario.allocation().setReturnedKopecks(AMOUNT);
        service.recordReturn(binding, scenario.allocation());

        verify(taskLedgerService, never()).recordReturn(any());
        verify(taskLedgerService, never()).lockReturnSource(any(), any());
    }

    @Test
    void archivedPaymentLinkReusedAllocationUsesImmutableLedgerGeneration() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(archivedSourceRepository.lockPaymentLink(scenario.source().sourceId())).thenReturn(true);
        when(attributionRepository.findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                scenario.source().sourceId()
        )).thenReturn(List.of(scenario.attribution()));
        when(taskLedgerService.lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                scenario.source().sourceId()
        )).thenReturn(new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(
                scenario.source(), 20_000L));

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockArchivedSourceBinding(scenario.allocation());
        InOrder locks = inOrder(archivedSourceRepository, attributionRepository, taskLedgerService);
        locks.verify(archivedSourceRepository).lockPaymentLink(scenario.source().sourceId());
        locks.verify(attributionRepository).findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                scenario.source().sourceId()
        );
        locks.verify(taskLedgerService).lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                scenario.source().sourceId()
        );
        scenario.allocation().setReturnedKopecks(55_000L);
        service.recordReturn(binding, scenario.allocation());

        assertReturn(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 35_000L, 55_000L);
    }

    @Test
    void archivedCommonActualAllocationUsesAttributionAndLedgerWithoutLiveInvoice() {
        Scenario scenario = actualScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(commonInvoiceRepository.findByIdForUpdate(scenario.source().sourceId()))
                .thenReturn(Optional.empty());
        when(archivedSourceRepository.lockCommonInvoice(scenario.source().sourceId())).thenReturn(true);
        when(attributionRepository.findByIdForUpdate(ATTRIBUTION_ID))
                .thenReturn(Optional.of(scenario.attribution()));
        when(taskLedgerService.lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                scenario.source().sourceId()
        )).thenReturn(new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(
                scenario.source(), 25_000L));

        ManualPaymentTaskContractorReturnBridge.Binding binding =
                service.lockActualPaymentBinding(scenario.allocation());
        scenario.allocation().setReturnedKopecks(AMOUNT);
        service.recordReturn(binding, scenario.allocation());

        assertReturn(ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 75_000L, AMOUNT);
    }

    @Test
    void restoredCommonSourceUsesLedgerForPartialFullAndReplayWithoutV252Fields() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        scenario.invoice().setPaymentRouteManualTaskGeneration(null);
        scenario.invoice().setPaymentRouteManualTaskSourceGeneration(null);
        when(taskLedgerService.lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                scenario.source().sourceId()
        )).thenReturn(
                new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(
                        scenario.source(), 0L),
                new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(
                        scenario.source(), 25_000L),
                new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(
                        scenario.source(), AMOUNT)
        );

        ManualPaymentTaskContractorReturnBridge.Binding partial =
                service.lockCommonInvoiceBinding(scenario.allocation(), scenario.invoice());
        scenario.allocation().setReturnedKopecks(25_000L);
        service.recordReturn(partial, scenario.allocation());
        ManualPaymentTaskContractorReturnBridge.Binding full =
                service.lockCommonInvoiceBinding(scenario.allocation(), scenario.invoice());
        scenario.allocation().setReturnedKopecks(AMOUNT);
        service.recordReturn(full, scenario.allocation());
        ManualPaymentTaskContractorReturnBridge.Binding replay =
                service.lockCommonInvoiceBinding(scenario.allocation(), scenario.invoice());
        service.recordReturn(replay, scenario.allocation());

        ArgumentCaptor<ManualPaymentTaskReturnCommand> commands =
                ArgumentCaptor.forClass(ManualPaymentTaskReturnCommand.class);
        verify(taskLedgerService, times(2)).recordReturn(commands.capture());
        assertEquals(List.of(25_000L, 75_000L), commands.getAllValues().stream()
                .map(ManualPaymentTaskReturnCommand::amountKopecks)
                .toList());
        assertEquals(List.of(25_000L, AMOUNT), commands.getAllValues().stream()
                .map(command -> Long.parseLong(command.operationKey()
                        .substring(command.operationKey().lastIndexOf(':') + 1)))
                .toList());
        verify(taskLedgerService, times(3)).lockArchivedReturnSource(
                TASK_ID,
                TASK_GENERATION,
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                scenario.source().sourceId()
        );
        verify(archivedSourceRepository, never()).lockCommonInvoice(any());
    }

    @Test
    void missingArchiveRowFailsClosedBeforeTaskOrAllocationLocks() {
        Scenario scenario = sourceScenario(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(archivedSourceRepository.lockPaymentLink(scenario.source().sourceId())).thenReturn(false);

        assertThrows(RuntimeException.class,
                () -> service.lockArchivedSourceBinding(scenario.allocation()));

        verify(attributionRepository, never()).findAllBySourceForUpdate(any(), any());
        verify(taskLedgerService, never()).lockArchivedReturnSource(
                any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    private Scenario sourceScenario(ContractorActualPaymentSourceKind kind) {
        long sourceId = kind == ContractorActualPaymentSourceKind.PAYMENT_LINK ? 128L : 66L;
        ContractorPaymentAllocation allocation = allocation(
                kind == ContractorActualPaymentSourceKind.PAYMENT_LINK
                        ? ContractorAllocationSourceType.PAYMENT_LINK
                        : ContractorAllocationSourceType.COMMON_INVOICE,
                sourceId
        );
        ContractorActualPaymentAttribution attribution = attribution(kind, sourceId, true, ALLOCATION_ID);
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                kind, sourceId
        )).thenReturn(List.of(attribution));
        return scenario(kind, allocation, attribution, sourceId);
    }

    private Scenario actualScenario(ContractorActualPaymentSourceKind kind) {
        long sourceId = kind == ContractorActualPaymentSourceKind.PAYMENT_LINK ? 128L : 66L;
        ContractorPaymentAllocation allocation = allocation(
                ContractorAllocationSourceType.ACTUAL_PAYMENT,
                ATTRIBUTION_ID
        );
        ContractorActualPaymentAttribution attribution = attribution(kind, sourceId, true, null);
        when(attributionRepository.findById(ATTRIBUTION_ID)).thenReturn(Optional.of(attribution));
        return scenario(kind, allocation, attribution, sourceId);
    }

    private Scenario scenario(
            ContractorActualPaymentSourceKind kind,
            ContractorPaymentAllocation allocation,
            ContractorActualPaymentAttribution attribution,
            long sourceId
    ) {
        if (kind == ContractorActualPaymentSourceKind.PAYMENT_LINK) {
            PaymentLink link = paymentLink(sourceId);
            when(paymentLinkRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(link));
            return new Scenario(
                    allocation,
                    attribution,
                    link,
                    null,
                    new ManualPaymentTaskSourceRef(
                            ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                            sourceId,
                            "source-generation"
                    )
            );
        }
        CommonInvoice invoice = commonInvoice(sourceId);
        when(commonInvoiceRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(invoice));
        return new Scenario(
                allocation,
                attribution,
                null,
                invoice,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                        sourceId,
                        "source-generation"
                )
        );
    }

    private ContractorActualPaymentAttribution attribution(
            ContractorActualPaymentSourceKind sourceKind,
            long sourceId,
            boolean taskDestination,
            Long originalAllocationId
    ) {
        ContractorActualPaymentAttribution row = mock(ContractorActualPaymentAttribution.class);
        when(row.getId()).thenReturn(ATTRIBUTION_ID);
        when(row.getSourceKind()).thenReturn(sourceKind);
        when(row.getSourceId()).thenReturn(sourceId);
        when(row.getOriginalAllocationId()).thenReturn(originalAllocationId);
        when(row.getAccountingMode()).thenReturn(ContractorAllocationMode.LIVE);
        when(row.getActualRecipientType()).thenReturn(ContractorRecipientType.SPECIALIST);
        when(row.getActualRecipientProfileId()).thenReturn(PROFILE_ID);
        when(row.getAmountKopecks()).thenReturn(AMOUNT);
        when(row.getActualCashDestinationKind()).thenReturn(taskDestination
                ? ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                : ContractorCashDestinationKind.CONTRACTOR_PROFILE);
        when(row.getActualManualPaymentTaskId()).thenReturn(taskDestination ? TASK_ID : null);
        when(row.getActualManualPaymentTaskGeneration()).thenReturn(
                taskDestination ? TASK_GENERATION : null
        );
        when(row.getActualManualPaymentTaskTargetKind()).thenReturn(taskDestination
                ? ManualPaymentTaskAccountingTargetKind.SPECIALIST
                : null);
        return row;
    }

    private ContractorPaymentAllocation allocation(
            ContractorAllocationSourceType sourceType,
            long sourceId
    ) {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(PROFILE_ID);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(ALLOCATION_ID);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(sourceType);
        allocation.setSourceId(sourceId);
        allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
        allocation.setRecipientProfile(profile);
        allocation.setAmountKopecks(AMOUNT);
        allocation.setConfirmedKopecks(AMOUNT);
        return allocation;
    }

    private PaymentLink paymentLink(long id) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(TASK_ID);
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setManualTaskGeneration(TASK_GENERATION);
        link.setManualTaskSourceGeneration("source-generation");
        return link;
    }

    private CommonInvoice commonInvoice(long id) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(id);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(TASK_ID);
        invoice.setPaymentRouteManualTaskGeneration(TASK_GENERATION);
        invoice.setPaymentRouteManualTaskSourceGeneration("source-generation");
        return invoice;
    }

    private void assertReturn(
            ManualPaymentTaskLedgerSourceKind sourceKind,
            long expectedDelta,
            long expectedTotal
    ) {
        ArgumentCaptor<ManualPaymentTaskReturnCommand> captor =
                ArgumentCaptor.forClass(ManualPaymentTaskReturnCommand.class);
        verify(taskLedgerService).recordReturn(captor.capture());
        ManualPaymentTaskReturnCommand command = captor.getValue();
        assertEquals(TASK_ID, command.taskId());
        assertEquals(sourceKind, command.source().sourceKind());
        assertEquals(expectedDelta, command.amountKopecks());
        assertEquals(
                "TASK:RETURN:" + sourceKind + ":" + command.source().sourceId()
                        + ":ATTR:" + ATTRIBUTION_ID + ":TOTAL:" + expectedTotal,
                command.operationKey()
        );
        assertEquals("system:contractor-return", command.actor());
    }

    private record Scenario(
            ContractorPaymentAllocation allocation,
            ContractorActualPaymentAttribution attribution,
            PaymentLink link,
            CommonInvoice invoice,
            ManualPaymentTaskSourceRef source
    ) {
    }
}

