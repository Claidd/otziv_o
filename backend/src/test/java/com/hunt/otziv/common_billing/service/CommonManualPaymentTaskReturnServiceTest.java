package com.hunt.otziv.common_billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnRequest;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReturnCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.repository.ManualPaymentTaskArchivedSourceRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommonManualPaymentTaskReturnServiceTest {

    @Mock CommonInvoiceRepository invoiceRepository;
    @Mock ContractorActualPaymentAttributionRepository attributionRepository;
    @Mock ManualPaymentTaskArchivedSourceRepository archivedSourceRepository;
    @Mock ManualPaymentTaskLedgerService taskLedgerService;
    @InjectMocks CommonManualPaymentTaskReturnService service;

    @Test
    void liveOwnerReturnSupportsPartialFullAndReplayWithCumulativeIdempotency() {
        CommonInvoice invoice = liveInvoice(77L, 9L, 3L, "source-77");
        ContractorActualPaymentAttribution row = attribution(
                801L, 77L, 9L, 3L, 100_000L,
                ManualPaymentTaskAccountingTargetKind.OWNER, "evidence-77");
        when(invoiceRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(invoice));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 77L))
                .thenReturn(List.of(row));
        when(attributionRepository.findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 77L))
                .thenReturn(List.of(row));
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 77L, "source-77");
        when(taskLedgerService.lockReturnSource(9L, source)).thenReturn(0L, 40_000L, 100_000L);

        var partial = service.record(77L, request(801L, "evidence-77", 40_000L), "owner-user");
        var full = service.record(77L, request(801L, "evidence-77", 100_000L), "owner-user");
        var replay = service.record(77L, request(801L, "evidence-77", 100_000L), "owner-user");

        assertEquals(40_000L, partial.appliedDeltaKopecks());
        assertFalse(partial.replay());
        assertEquals(60_000L, full.appliedDeltaKopecks());
        assertFalse(full.replay());
        assertEquals(0L, replay.appliedDeltaKopecks());
        assertTrue(replay.replay());
        ArgumentCaptor<ManualPaymentTaskReturnCommand> commands =
                ArgumentCaptor.forClass(ManualPaymentTaskReturnCommand.class);
        verify(taskLedgerService, org.mockito.Mockito.times(2)).recordReturn(commands.capture());
        assertEquals(List.of(40_000L, 60_000L), commands.getAllValues().stream()
                .map(ManualPaymentTaskReturnCommand::amountKopecks).toList());
        assertEquals(
                "TASK:RETURN:COMMON_INVOICE:77:ATTR:801:TOTAL:40000",
                commands.getAllValues().getFirst().operationKey());
        assertEquals(
                "TASK:RETURN:COMMON_INVOICE:77:ATTR:801:TOTAL:100000",
                commands.getAllValues().getLast().operationKey());
        assertTrue(commands.getAllValues().stream()
                .allMatch(command -> "owner-user".equals(command.actor())));
    }

    @Test
    void archivedExternalTaskReturnUsesArchiveThenExactLedgerBinding() {
        ContractorActualPaymentAttribution row = attribution(
                802L, 78L, 10L, 5L, 90_000L,
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK, "archive-evidence");
        when(invoiceRepository.findByIdForUpdate(78L)).thenReturn(Optional.empty());
        when(archivedSourceRepository.lockCommonTaskInvoice(78L)).thenReturn(
                new ManualPaymentTaskArchivedSourceRepository.ArchivedCommonTaskSource(10L, 90_000L));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 78L))
                .thenReturn(List.of(row));
        when(attributionRepository.findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 78L))
                .thenReturn(List.of(row));
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 78L, "archived-source");
        when(taskLedgerService.lockArchivedReturnSource(
                10L, 5L, ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 78L))
                .thenReturn(new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(source, 20_000L));

        var result = service.record(
                78L,
                request(802L, "archive-evidence", 50_000L),
                "admin-user"
        );

        assertEquals(30_000L, result.appliedDeltaKopecks());
        InOrder order = inOrder(
                invoiceRepository, archivedSourceRepository,
                attributionRepository, taskLedgerService);
        order.verify(invoiceRepository).findByIdForUpdate(78L);
        order.verify(archivedSourceRepository).lockCommonTaskInvoice(78L);
        order.verify(attributionRepository)
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.COMMON_INVOICE, 78L);
        order.verify(taskLedgerService).lockArchivedReturnSource(
                10L, 5L, ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 78L);
        order.verify(attributionRepository).findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 78L);
        order.verify(taskLedgerService).recordReturn(any(ManualPaymentTaskReturnCommand.class));
    }

    @Test
    void restoredLiveInvoiceWithoutV252ColumnsUsesDurableLedgerBinding() {
        CommonInvoice restored = liveInvoice(79L, 11L, null, null);
        ContractorActualPaymentAttribution row = attribution(
                803L, 79L, 11L, 6L, 70_000L,
                ManualPaymentTaskAccountingTargetKind.OWNER, "restored-evidence");
        when(invoiceRepository.findByIdForUpdate(79L)).thenReturn(Optional.of(restored));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 79L))
                .thenReturn(List.of(row));
        when(attributionRepository.findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 79L))
                .thenReturn(List.of(row));
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 79L, "durable-source");
        when(taskLedgerService.lockArchivedReturnSource(
                11L, 6L, ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 79L))
                .thenReturn(new ManualPaymentTaskLedgerService.LockedArchivedReturnSource(source, 0L));

        var result = service.record(
                79L,
                request(803L, "restored-evidence", 10_000L),
                "owner-user"
        );

        assertEquals(10_000L, result.appliedDeltaKopecks());
        verify(archivedSourceRepository, never()).lockCommonInvoice(any());
        verify(taskLedgerService).recordReturn(any(ManualPaymentTaskReturnCommand.class));
    }

    @Test
    void splitWithOneTaskAndProfileReturnsOnlyExactTaskAttribution() {
        CommonInvoice invoice = liveInvoice(80L, 12L, 7L, "source-80");
        ContractorActualPaymentAttribution taskRow = attribution(
                804L, 80L, 12L, 7L, 50_000L,
                ManualPaymentTaskAccountingTargetKind.OWNER, "evidence-80");
        ContractorActualPaymentAttribution profileRow = profileAttribution(805L, 80L, 50_000L);
        when(invoiceRepository.findByIdForUpdate(80L)).thenReturn(Optional.of(invoice));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 80L))
                .thenReturn(List.of(taskRow, profileRow));
        when(attributionRepository.findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 80L))
                .thenReturn(List.of(taskRow, profileRow));
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 80L, "source-80");
        when(taskLedgerService.lockReturnSource(12L, source))
                .thenReturn(0L, 10_000L, 50_000L);

        var partial = service.record(
                80L, request(804L, "evidence-80", 10_000L), "owner-user");
        var full = service.record(
                80L, request(804L, "evidence-80", 50_000L), "owner-user");
        var replay = service.record(
                80L, request(804L, "evidence-80", 50_000L), "owner-user");

        assertEquals(10_000L, partial.appliedDeltaKopecks());
        assertEquals(40_000L, full.appliedDeltaKopecks());
        assertTrue(replay.replay());
        ArgumentCaptor<ManualPaymentTaskReturnCommand> commands =
                ArgumentCaptor.forClass(ManualPaymentTaskReturnCommand.class);
        verify(taskLedgerService, org.mockito.Mockito.times(2)).recordReturn(commands.capture());
        assertEquals(List.of(10_000L, 40_000L), commands.getAllValues().stream()
                .map(ManualPaymentTaskReturnCommand::amountKopecks).toList());
        assertTrue(commands.getAllValues().stream()
                .allMatch(command -> command.operationKey().contains(":ATTR:804:")));
    }

    @Test
    void multipleTaskRowsOrMismatchedEvidenceFailClosed() {
        CommonInvoice invoice = liveInvoice(82L, 14L, 9L, "source-82");
        ContractorActualPaymentAttribution owner = attribution(
                807L, 82L, 14L, 9L, 30_000L,
                ManualPaymentTaskAccountingTargetKind.OWNER, "evidence-82");
        ContractorActualPaymentAttribution external = attribution(
                808L, 82L, 14L, 9L, 20_000L,
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK, "evidence-82");
        when(invoiceRepository.findByIdForUpdate(82L)).thenReturn(Optional.of(invoice));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 82L))
                .thenReturn(List.of(owner, external), List.of(owner));

        assertThrows(ResponseStatusException.class, () -> service.record(
                82L, request(807L, "evidence-82", 10_000L), "owner-user"));
        assertThrows(ResponseStatusException.class, () -> service.record(
                82L, request(807L, "wrong-evidence", 10_000L), "owner-user"));

        verifyNoInteractions(taskLedgerService);
    }

    @Test
    void cumulativeAmountCannotMoveBackwardsOrExceedAttribution() {
        CommonInvoice invoice = liveInvoice(81L, 13L, 8L, "source-81");
        ContractorActualPaymentAttribution row = attribution(
                806L, 81L, 13L, 8L, 60_000L,
                ManualPaymentTaskAccountingTargetKind.OWNER, "evidence-81");
        when(invoiceRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(invoice));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 81L))
                .thenReturn(List.of(row));
        when(attributionRepository.findAllBySourceForUpdate(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 81L))
                .thenReturn(List.of(row));
        when(taskLedgerService.lockReturnSource(13L, new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 81L, "source-81")))
                .thenReturn(30_000L);

        assertThrows(ResponseStatusException.class, () -> service.record(
                81L, request(806L, "evidence-81", 20_000L), "owner-user"));
        assertThrows(ResponseStatusException.class, () -> service.record(
                81L, request(806L, "evidence-81", 70_000L), "owner-user"));

        verify(taskLedgerService, never()).recordReturn(any());
    }

    @Test
    void legacyLiveReturnSupportsPartialFullAndReplayFromExactBaselineOnly() {
        CommonInvoice invoice = liveInvoice(83L, 15L, 10L, "LEGACY-83");
        invoice.setPaymentRouteAmountKopecks(100_000L);
        when(invoiceRepository.findByIdForUpdate(83L)).thenReturn(Optional.of(invoice));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 83L)).thenReturn(List.of());
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 83L, "LEGACY-83");
        when(taskLedgerService.lockLegacyConfirmedReturnSource(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 83L))
                .thenReturn(
                        new ManualPaymentTaskLedgerService.LockedLegacyReturnSource(
                                source, 15L, 10L, 100_000L, 0L,
                                "V251:BASELINE:COMMON_INVOICE:83"),
                        new ManualPaymentTaskLedgerService.LockedLegacyReturnSource(
                                source, 15L, 10L, 100_000L, 40_000L,
                                "V251:BASELINE:COMMON_INVOICE:83"),
                        new ManualPaymentTaskLedgerService.LockedLegacyReturnSource(
                                source, 15L, 10L, 100_000L, 100_000L,
                                "V251:BASELINE:COMMON_INVOICE:83"));

        var partial = service.record(83L,
                request(null, "V251:BASELINE:COMMON_INVOICE:83", 40_000L), "owner-user");
        var full = service.record(83L,
                request(null, "V251:BASELINE:COMMON_INVOICE:83", 100_000L), "owner-user");
        var replay = service.record(83L,
                request(null, "V251:BASELINE:COMMON_INVOICE:83", 100_000L), "owner-user");

        assertEquals(40_000L, partial.appliedDeltaKopecks());
        assertEquals(60_000L, full.appliedDeltaKopecks());
        assertTrue(replay.replay());
        assertEquals(null, replay.attributionId());
        ArgumentCaptor<ManualPaymentTaskReturnCommand> commands =
                ArgumentCaptor.forClass(ManualPaymentTaskReturnCommand.class);
        verify(taskLedgerService, org.mockito.Mockito.times(2)).recordReturn(commands.capture());
        assertEquals(List.of(40_000L, 60_000L), commands.getAllValues().stream()
                .map(ManualPaymentTaskReturnCommand::amountKopecks).toList());
        assertEquals("TASK:RETURN:COMMON_INVOICE:83:LEGACY:TOTAL:40000",
                commands.getAllValues().getFirst().operationKey());
    }

    @Test
    void legacyArchivedReturnUsesArchiveTaskIdentityAndRejectsAmbiguousLedger() {
        when(invoiceRepository.findByIdForUpdate(84L)).thenReturn(Optional.empty());
        when(archivedSourceRepository.lockCommonTaskInvoice(84L)).thenReturn(
                new ManualPaymentTaskArchivedSourceRepository.ArchivedCommonTaskSource(16L, 75_000L));
        when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                ContractorActualPaymentSourceKind.COMMON_INVOICE, 84L)).thenReturn(List.of());
        when(taskLedgerService.lockLegacyConfirmedReturnSource(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 84L))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Для исторического возврата нет однозначной привязки задания; требуется сверка"));

        assertThrows(ResponseStatusException.class, () -> service.record(
                84L, request(null, "V251:BASELINE:COMMON_INVOICE:84", 75_000L), "admin-user"));

        verify(taskLedgerService, never()).recordReturn(any());
        verify(attributionRepository, never()).findAllBySourceForUpdate(any(), any());
    }

    private CommonManualPaymentTaskReturnRequest request(
            Long attributionId,
            String evidence,
            long cumulative
    ) {
        return new CommonManualPaymentTaskReturnRequest(
                attributionId, evidence, cumulative, "Возврат подтвержден выпиской");
    }

    private CommonInvoice liveInvoice(
            Long invoiceId,
            Long taskId,
            Long taskGeneration,
            String sourceGeneration
    ) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(invoiceId);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(taskId);
        invoice.setPaymentRouteManualTaskGeneration(taskGeneration);
        invoice.setPaymentRouteManualTaskSourceGeneration(sourceGeneration);
        return invoice;
    }

    private ContractorActualPaymentAttribution attribution(
            Long id,
            Long invoiceId,
            Long taskId,
            Long taskGeneration,
            long amount,
            ManualPaymentTaskAccountingTargetKind target,
            String evidence
    ) {
        ContractorActualPaymentAttribution row = mock(
                ContractorActualPaymentAttribution.class, withSettings().lenient());
        when(row.getId()).thenReturn(id);
        when(row.getSourceKind()).thenReturn(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(row.getSourceId()).thenReturn(invoiceId);
        when(row.getActualCashDestinationKind())
                .thenReturn(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        when(row.getActualManualPaymentTaskId()).thenReturn(taskId);
        when(row.getActualManualPaymentTaskGeneration()).thenReturn(taskGeneration);
        when(row.getActualManualPaymentTaskTargetKind()).thenReturn(target);
        when(row.getAmountKopecks()).thenReturn(amount);
        when(row.getEvidenceReference()).thenReturn(evidence);
        return row;
    }

    private ContractorActualPaymentAttribution profileAttribution(
            Long id,
            Long invoiceId,
            long amount
    ) {
        ContractorActualPaymentAttribution row = mock(
                ContractorActualPaymentAttribution.class, withSettings().lenient());
        when(row.getId()).thenReturn(id);
        when(row.getSourceKind()).thenReturn(ContractorActualPaymentSourceKind.COMMON_INVOICE);
        when(row.getSourceId()).thenReturn(invoiceId);
        when(row.getActualCashDestinationKind())
                .thenReturn(ContractorCashDestinationKind.CONTRACTOR_PROFILE);
        when(row.getAmountKopecks()).thenReturn(amount);
        when(row.getEvidenceReference()).thenReturn("profile-evidence");
        return row;
    }
}
