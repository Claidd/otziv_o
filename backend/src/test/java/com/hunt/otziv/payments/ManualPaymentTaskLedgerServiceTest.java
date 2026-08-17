package com.hunt.otziv.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.payments.dto.ManualPaymentTaskReserveCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReleaseCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskCorrectionCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReturnCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSettlementCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerEntry;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerEventType;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.repository.ManualPaymentTaskLedgerRepository;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService;
import com.hunt.otziv.config.api.CodedResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ManualPaymentTaskLedgerServiceTest {

    @Mock
    private ManualPaymentTaskLedgerRepository ledgerRepository;
    @Mock
    private ManualPaymentTaskRepository taskRepository;
    @Mock
    private ManualPaymentTaskContractorCapacityService capacityService;

    private ManualPaymentTaskLedgerService service;

    @BeforeEach
    void setUp() {
        service = new ManualPaymentTaskLedgerService(
                ledgerRepository, taskRepository, capacityService);
        org.mockito.Mockito.lenient().when(capacityService.snapshot(any(), any()))
                .thenReturn(ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE);
    }

    @Test
    void activeTaskWithCapacityAndUnresolvedTargetFailsClosed() {
        ManualPaymentTask task = task(11L, ManualPaymentTaskAccountingTargetKind.UNRESOLVED);
        when(taskRepository.findActiveForRouting(4L, 8L, ManualPaymentTaskStatus.ACTIVE))
                .thenReturn(List.of(task));
        when(ledgerRepository.findReservation("PAYMENT_LINK:99:g-1")).thenReturn(Optional.empty());

        Throwable failure = catchThrowable(() -> service.reserveFirst(new ManualPaymentTaskReserveCommand(
                4L,
                8L,
                new ManualPaymentTaskSourceRef(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 99L, "g-1"),
                25_000,
                "reserve:99:g-1",
                "test"
        )));

        assertThat(failure).isInstanceOf(CodedResponseStatusException.class);
        assertThat(((CodedResponseStatusException) failure).code()).isEqualTo("TASK_TARGET_UNRESOLVED");
    }

    @Test
    void externalTaskReservationFreezesTypedTargetAndNeverInfersItFromBankName() {
        ManualPaymentTask task = task(12L, ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        task.setManualRecipientName("Наталья в банке");
        when(taskRepository.findActiveForRouting(4L, 8L, ManualPaymentTaskStatus.ACTIVE))
                .thenReturn(List.of(task));
        when(ledgerRepository.findReservation("COMMON_INVOICE:128:g-2")).thenReturn(Optional.empty());
        when(ledgerRepository.findAllByTaskIdOrderById(12L)).thenReturn(List.of());
        when(taskRepository.findById(12L)).thenReturn(Optional.of(task));
        when(ledgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reserveFirst(new ManualPaymentTaskReserveCommand(
                4L,
                8L,
                new ManualPaymentTaskSourceRef(ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 128L, "g-2"),
                250_000,
                "reserve:128:g-2",
                "test"
        ));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().accountingTargetKind())
                .isEqualTo(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        assertThat(result.orElseThrow().accountingTargetProfileId()).isNull();
        assertThat(result.orElseThrow().bankRecipientName()).isEqualTo("Наталья в банке");

        ArgumentCaptor<ManualPaymentTaskLedgerEntry> captor =
                ArgumentCaptor.forClass(ManualPaymentTaskLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository).save(captor.capture());
        assertThat(captor.getValue().getReservationKey()).isEqualTo("COMMON_INVOICE:128:g-2");
        assertThat(captor.getValue().getSelectedRecipientKey()).isEqualTo("TASK:12:1");
    }

    @Test
    void balanceKeepsUnverifiedBaselineSeparateAndUsesExactSignedDeltas() {
        ManualPaymentTask task = task(15L, ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        task.setNeedsReconciliation(true);
        ManualPaymentTaskLedgerEntry baseline = entry(
                task, ManualPaymentTaskLedgerEventType.LEGACY_BASELINE, 0, 100_000, false, 0);
        baseline.setSourceKind(ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE);
        ManualPaymentTaskLedgerEntry reserve = entry(
                task, ManualPaymentTaskLedgerEventType.RESERVED, 40_000, 0, false, 0);
        ManualPaymentTaskLedgerEntry confirmed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK, -15_000, 15_000, true, 0);
        when(ledgerRepository.findAllByTaskIdOrderById(15L))
                .thenReturn(List.of(baseline, reserve, confirmed));
        when(taskRepository.findById(15L)).thenReturn(Optional.of(task));

        var balance = service.balance(15L);

        assertThat(balance.pendingAmountKopecks()).isEqualTo(25_000);
        assertThat(balance.netConfirmedAmountKopecks()).isEqualTo(115_000);
        assertThat(balance.occupiedAmountKopecks()).isEqualTo(140_000);
        assertThat(balance.unverifiedConfirmedAmountKopecks()).isEqualTo(100_000);
        assertThat(balance.needsReconciliation()).isTrue();
    }

    @Test
    void balanceReportsNetUnbackedExposurePerExactSourceAcrossPartialAndFullReturn() {
        ManualPaymentTask task = task(151L, ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        ManualPaymentTaskLedgerEntry baseline = entry(
                task, ManualPaymentTaskLedgerEventType.LEGACY_BASELINE,
                0L, 100_000L, false, 0L);
        baseline.setSourceId(83L);
        baseline.setSourceGeneration("LEGACY-83");
        ManualPaymentTaskLedgerEntry partialReturn = entry(
                task, ManualPaymentTaskLedgerEventType.RETURNED,
                0L, -25_000L, true, 0L);
        partialReturn.setSourceId(83L);
        partialReturn.setSourceGeneration("LEGACY-83");
        ManualPaymentTaskLedgerEntry positiveCorrection = entry(
                task, ManualPaymentTaskLedgerEventType.CORRECTION,
                0L, 10_000L, true, 0L);
        positiveCorrection.setSourceId(83L);
        positiveCorrection.setSourceGeneration("LEGACY-83");
        ManualPaymentTaskLedgerEntry typed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                0L, 40_000L, true, 0L);
        typed.setSourceId(84L);
        typed.setSourceGeneration("route-84");
        ManualPaymentTaskLedgerEntry fullReturn = entry(
                task, ManualPaymentTaskLedgerEventType.RETURNED,
                0L, -85_000L, true, 0L);
        fullReturn.setSourceId(83L);
        fullReturn.setSourceGeneration("LEGACY-83");
        when(ledgerRepository.findAllByTaskIdOrderById(151L)).thenReturn(
                List.of(baseline, partialReturn, positiveCorrection, typed),
                List.of(baseline, partialReturn, positiveCorrection, fullReturn, typed));
        when(taskRepository.findById(151L)).thenReturn(Optional.of(task));

        ManualPaymentTaskBalance partial = service.balance(151L);
        ManualPaymentTaskBalance full = service.balance(151L);

        assertThat(partial.netConfirmedAmountKopecks()).isEqualTo(125_000L);
        assertThat(partial.unverifiedConfirmedAmountKopecks()).isEqualTo(85_000L);
        assertThat(full.netConfirmedAmountKopecks()).isEqualTo(40_000L);
        assertThat(full.unverifiedConfirmedAmountKopecks()).isZero();
        assertThat(full.needsReconciliation()).isTrue();
    }

    @Test
    void balanceDoesNotLetReturnEraseUnknownCorrectionBeforeBackedCash() {
        ManualPaymentTask task = task(152L, ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        ManualPaymentTaskLedgerEntry typed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                0L, 40_000L, true, 0L);
        ManualPaymentTaskLedgerEntry correction = entry(
                task, ManualPaymentTaskLedgerEventType.CORRECTION,
                0L, 30_000L, true, 0L);
        ManualPaymentTaskLedgerEntry returned = entry(
                task, ManualPaymentTaskLedgerEventType.RETURNED,
                0L, -20_000L, true, 0L);
        when(ledgerRepository.findAllByTaskIdOrderById(152L))
                .thenReturn(List.of(typed, correction, returned));
        when(taskRepository.findById(152L)).thenReturn(Optional.of(task));

        ManualPaymentTaskBalance balance = service.balance(152L);

        assertThat(balance.netConfirmedAmountKopecks()).isEqualTo(50_000L);
        assertThat(balance.unverifiedConfirmedAmountKopecks()).isEqualTo(30_000L);
        assertThat(balance.needsReconciliation()).isTrue();
    }

    @Test
    void migratedBaselineCountsTowardCompletionAfterVerifiedSettlement() {
        ManualPaymentTask task = task(16L, ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        task.setTargetAmountKopecks(100_000L);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 78L, "route-78");
        ManualPaymentTaskLedgerEntry baseline = entry(
                task, ManualPaymentTaskLedgerEventType.LEGACY_BASELINE, 0, 90_000, false, 0);
        baseline.setSourceKind(ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE);
        ManualPaymentTaskLedgerEntry reserve = entry(
                task, ManualPaymentTaskLedgerEventType.RESERVED, 10_000, 0, true, 0);
        reserve.setSourceId(78L);
        reserve.setSourceGeneration("route-78");
        reserve.setSelectedRecipientKey("TASK:16:1");
        ManualPaymentTaskLedgerEntry confirmed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK, -10_000, 10_000, true, 0);
        confirmed.setSourceId(78L);
        confirmed.setSourceGeneration("route-78");
        when(taskRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(task));
        when(taskRepository.findById(16L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findAllByOperationKeyOrderByOperationSequence("settle-78"))
                .thenReturn(List.of());
        when(ledgerRepository.findSourceHistoryNewestFirst(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 78L, "route-78"))
                .thenReturn(List.of(reserve));
        when(ledgerRepository.findAllByTaskIdOrderById(16L))
                .thenReturn(List.of(baseline, reserve, confirmed));
        when(ledgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.settle(new ManualPaymentTaskSettlementCommand(
                16L, 1L, source, "TASK:16:1", 10_000L, 10_000L,
                "settle-78", "manager", "Оплачено"));

        assertThat(task.getStatus()).isEqualTo(ManualPaymentTaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isNotNull();
        verify(taskRepository).save(task);
    }

    @Test
    void fullReturnReopensCompletedTaskAndClearsCompletionTimestamp() {
        ManualPaymentTask task = task(17L, ManualPaymentTaskAccountingTargetKind.OWNER);
        task.setStatus(ManualPaymentTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 79L, "route-79");
        ManualPaymentTaskLedgerEntry confirmed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK, 0, 100_000, true, 0);
        confirmed.setSourceId(79L);
        confirmed.setSourceGeneration("route-79");
        when(taskRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(task));
        when(taskRepository.findById(17L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findAllByOperationKeyOrderByOperationSequence("return-79"))
                .thenReturn(List.of());
        when(ledgerRepository.findSourceHistoryNewestFirst(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 79L, "route-79"))
                .thenReturn(List.of(confirmed));
        when(ledgerRepository.findAllByTaskIdOrderById(17L)).thenReturn(List.of(confirmed));
        when(ledgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordReturn(new ManualPaymentTaskReturnCommand(
                17L, source, 25_000L, "return-79", "test", "Возврат"));

        assertThat(task.getStatus()).isEqualTo(ManualPaymentTaskStatus.NEEDS_ATTENTION);
        assertThat(task.getCompletedAt()).isNull();
        verify(taskRepository).save(task);
    }

    @Test
    void unknownPartialReturnReopensCompletedTaskWithoutLedgerDelta() {
        ManualPaymentTask task = task(18L, ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        task.setStatus(ManualPaymentTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 80L, "route-80");
        ManualPaymentTaskLedgerEntry confirmed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK, 0, 100_000, true, 0);
        confirmed.setSourceId(80L);
        confirmed.setSourceGeneration("route-80");
        when(taskRepository.findByIdForUpdate(18L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findSourceHistoryNewestFirst(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 80L, "route-80"))
                .thenReturn(List.of(confirmed));

        service.markReturnNeedsAttention(18L, source);

        assertThat(task.getStatus()).isEqualTo(ManualPaymentTaskStatus.NEEDS_ATTENTION);
        assertThat(task.getCompletedAt()).isNull();
        verify(taskRepository).save(task);
        verify(ledgerRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void archivedReturnSourceReconstructsGenerationAndCumulativeReturnedAmount() {
        ManualPaymentTask task = task(19L, ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        task.setGeneration(4L);
        ManualPaymentTaskLedgerEntry confirmed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                0L, 100_000L, true, 0L);
        confirmed.setTaskGeneration(4L);
        confirmed.setSourceKind(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK);
        confirmed.setSourceId(81L);
        confirmed.setSourceGeneration("archived-route-81");
        ManualPaymentTaskLedgerEntry returned = entry(
                task, ManualPaymentTaskLedgerEventType.RETURNED,
                0L, -25_000L, true, 0L);
        returned.setTaskGeneration(4L);
        returned.setSourceKind(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK);
        returned.setSourceId(81L);
        returned.setSourceGeneration("archived-route-81");
        when(taskRepository.findByIdForUpdate(19L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findArchivedSourceHistoryNewestFirst(
                19L, 4L, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 81L
        )).thenReturn(List.of(returned, confirmed));

        ManualPaymentTaskLedgerService.LockedArchivedReturnSource locked =
                service.lockArchivedReturnSource(
                        19L, 4L, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 81L);

        assertThat(locked.source()).isEqualTo(new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                81L,
                "archived-route-81"
        ));
        assertThat(locked.returnedKopecks()).isEqualTo(25_000L);
    }

    @Test
    void archivedReturnSourceWithTwoLedgerGenerationsFailsClosed() {
        ManualPaymentTask task = task(20L, ManualPaymentTaskAccountingTargetKind.MANAGER);
        task.setGeneration(5L);
        ManualPaymentTaskLedgerEntry first = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                0L, 50_000L, true, 0L);
        first.setTaskGeneration(5L);
        first.setSourceKind(ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE);
        first.setSourceId(82L);
        first.setSourceGeneration("route-a");
        ManualPaymentTaskLedgerEntry second = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                0L, 50_000L, true, 0L);
        second.setTaskGeneration(5L);
        second.setSourceKind(ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE);
        second.setSourceId(82L);
        second.setSourceGeneration("route-b");
        when(taskRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findArchivedSourceHistoryNewestFirst(
                20L, 5L, ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 82L
        )).thenReturn(List.of(second, first));

        Throwable error = catchThrowable(() -> service.lockArchivedReturnSource(
                20L, 5L, ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 82L));

        assertThat(error)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("неоднозначен");
    }

    @Test
    void legacyConfirmedReturnLocksOneExactSourceAndItsCumulativeReturns() {
        ManualPaymentTask task = task(21L, ManualPaymentTaskAccountingTargetKind.UNRESOLVED);
        task.setGeneration(6L);
        ManualPaymentTaskLedgerEntry baseline = entry(
                task, ManualPaymentTaskLedgerEventType.LEGACY_BASELINE,
                0L, 100_000L, false, 0L);
        baseline.setTaskGeneration(6L);
        baseline.setSourceKind(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK);
        baseline.setSourceId(83L);
        baseline.setSourceGeneration("LEGACY-83");
        baseline.setOperationKey("V251:BASELINE:PAYMENT_LINK:83");
        baseline.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.UNRESOLVED);
        baseline.setAccountingTargetProfile(null);
        ManualPaymentTaskLedgerEntry returned = entry(
                task, ManualPaymentTaskLedgerEventType.RETURNED,
                0L, -25_000L, true, 0L);
        returned.setTaskGeneration(6L);
        returned.setSourceKind(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK);
        returned.setSourceId(83L);
        returned.setSourceGeneration("LEGACY-83");
        when(taskRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findTaskSourceHistoryNewestFirst(
                21L, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 83L
        )).thenReturn(List.of(returned, baseline));

        ManualPaymentTaskLedgerService.LockedLegacyReturnSource locked =
                service.lockLegacyConfirmedReturnSource(
                        21L, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 83L);

        assertThat(locked.source()).isEqualTo(new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                83L,
                "LEGACY-83"
        ));
        assertThat(locked.taskId()).isEqualTo(21L);
        assertThat(locked.taskGeneration()).isEqualTo(6L);
        assertThat(locked.confirmedKopecks()).isEqualTo(100_000L);
        assertThat(locked.returnedKopecks()).isEqualTo(25_000L);
        assertThat(locked.evidenceReference()).isEqualTo("V251:BASELINE:PAYMENT_LINK:83");
    }

    @Test
    void legacyConfirmedReturnWithDuplicateBaselineFailsClosed() {
        ManualPaymentTask task = task(22L, ManualPaymentTaskAccountingTargetKind.UNRESOLVED);
        task.setGeneration(7L);
        ManualPaymentTaskLedgerEntry first = entry(
                task, ManualPaymentTaskLedgerEventType.LEGACY_BASELINE,
                0L, 40_000L, false, 0L);
        ManualPaymentTaskLedgerEntry second = entry(
                task, ManualPaymentTaskLedgerEventType.LEGACY_BASELINE,
                0L, 60_000L, false, 0L);
        for (ManualPaymentTaskLedgerEntry row : List.of(first, second)) {
            row.setTaskGeneration(7L);
            row.setSourceKind(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK);
            row.setSourceId(84L);
            row.setSourceGeneration("LEGACY-84");
            row.setOperationKey("V251:BASELINE:PAYMENT_LINK:84");
            row.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.UNRESOLVED);
            row.setAccountingTargetProfile(null);
        }
        when(taskRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(task));
        when(ledgerRepository.findTaskSourceHistoryNewestFirst(
                22L, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 84L
        )).thenReturn(List.of(second, first));

        Throwable error = catchThrowable(() -> service.lockLegacyConfirmedReturnSource(
                22L, ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 84L));

        assertThat(error)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("неоднозначна");
    }

    @Test
    void reserveReplayIgnoresAuditActorButKeepsExactFinancialIdentity() {
        ManualPaymentTask task = task(30L, ManualPaymentTaskAccountingTargetKind.OWNER);
        ManualPaymentTaskSourceRef source = source();
        ManualPaymentTaskLedgerEntry replay = entry(
                task, ManualPaymentTaskLedgerEventType.RESERVED,
                25_000L, 0L, true, 0L);
        replay.setOperationKey("reserve-replay");
        replay.setReservationKey("PAYMENT_LINK:77:g");
        replay.setActor("migration-actor");
        when(ledgerRepository.findReservation("PAYMENT_LINK:77:g"))
                .thenReturn(Optional.of(replay));

        assertThat(service.reserveFirst(new ManualPaymentTaskReserveCommand(
                4L, 8L, source, 25_000L, "reserve-replay", "runtime-actor")))
                .isPresent();
        assertThatThrownBy(() -> service.reserveFirst(new ManualPaymentTaskReserveCommand(
                4L, 8L, source, 25_001L, "reserve-replay", "runtime-actor")))
                .isInstanceOf(ResponseStatusException.class);
        verify(ledgerRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void settlementReplayRequiresExactRowsAndSequencesButNotActorOrReason() {
        ManualPaymentTask task = task(31L, ManualPaymentTaskAccountingTargetKind.OWNER);
        ManualPaymentTaskSourceRef source = source();
        ManualPaymentTaskLedgerEntry confirmed = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                -60_000L, 60_000L, true, 0L);
        confirmed.setOperationKey("settle-replay");
        confirmed.setOperationSequence(0);
        confirmed.setSelectedRecipientKey("TASK:31:1");
        confirmed.setActor("migration");
        confirmed.setReason("first audit wording");
        ManualPaymentTaskLedgerEntry redirected = entry(
                task, ManualPaymentTaskLedgerEventType.REDIRECTED,
                -40_000L, 0L, true, 40_000L);
        redirected.setOperationKey("settle-replay");
        redirected.setOperationSequence(1);
        redirected.setSelectedRecipientKey("OWNER");
        redirected.setActor("migration");
        redirected.setReason("first audit wording");
        stubReplay(task, "settle-replay", List.of(confirmed, redirected));
        ManualPaymentTaskSettlementCommand command = new ManualPaymentTaskSettlementCommand(
                31L, 1L, source, "OWNER", 100_000L, 60_000L,
                "settle-replay", "runtime", "different wording");

        service.settle(command);

        redirected.setOperationSequence(0);
        assertThatThrownBy(() -> service.settle(command))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("структур");
        verify(ledgerRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void releaseReturnAndCorrectionReplayKeepFinancialFieldsStrictButAuditMetadataIsFirstWriteWins() {
        ManualPaymentTask task = task(32L, ManualPaymentTaskAccountingTargetKind.OWNER);
        ManualPaymentTaskSourceRef source = source();

        ManualPaymentTaskLedgerEntry released = entry(
                task, ManualPaymentTaskLedgerEventType.RELEASED,
                -10_000L, 0L, true, 0L);
        released.setActor("flyway-v251");
        released.setReason("migration reason");
        stubReplay(task, "release-replay", List.of(released));
        service.release(new ManualPaymentTaskReleaseCommand(
                32L, source, 10_000L, "release-replay", "scheduler", "expiry wording"));
        assertThatThrownBy(() -> service.release(new ManualPaymentTaskReleaseCommand(
                32L, source, 10_001L, "release-replay", "scheduler", "expiry wording")))
                .isInstanceOf(ResponseStatusException.class);

        ManualPaymentTaskLedgerEntry returned = entry(
                task, ManualPaymentTaskLedgerEventType.RETURNED,
                0L, -7_000L, true, 0L);
        returned.setActor("provider");
        returned.setReason("first return reason");
        stubReplay(task, "return-replay", List.of(returned));
        service.recordReturn(new ManualPaymentTaskReturnCommand(
                32L, source, 7_000L, "return-replay", "admin", "new wording"));
        assertThatThrownBy(() -> service.recordReturn(new ManualPaymentTaskReturnCommand(
                32L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 77L, "g"),
                7_000L, "return-replay", "admin", "new wording")))
                .isInstanceOf(ResponseStatusException.class);

        ManualPaymentTaskLedgerEntry correctionOf = entry(
                task, ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                0L, 5_000L, true, 0L);
        correctionOf.setId(500L);
        ManualPaymentTaskLedgerEntry correction = entry(
                task, ManualPaymentTaskLedgerEventType.CORRECTION,
                1_000L, -2_000L, true, 0L);
        correction.setCorrectionOf(correctionOf);
        correction.setActor("first-admin");
        correction.setReason("first correction reason");
        stubReplay(task, "correction-replay", List.of(correction));
        service.correct(new ManualPaymentTaskCorrectionCommand(
                32L, source, 1_000L, -2_000L, 500L,
                "correction-replay", "second-admin", "different wording"));
        assertThatThrownBy(() -> service.correct(new ManualPaymentTaskCorrectionCommand(
                32L, source, 1_000L, -2_000L, 501L,
                "correction-replay", "second-admin", "different wording")))
                .isInstanceOf(ResponseStatusException.class);
        verify(ledgerRepository, org.mockito.Mockito.never()).save(any());
    }

    private void stubReplay(
            ManualPaymentTask task,
            String operationKey,
            List<ManualPaymentTaskLedgerEntry> rows
    ) {
        when(taskRepository.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(ledgerRepository.findAllByOperationKeyOrderByOperationSequence(operationKey))
                .thenReturn(rows);
        when(ledgerRepository.findAllByTaskIdOrderById(task.getId())).thenReturn(List.of());
    }

    private ManualPaymentTaskSourceRef source() {
        return new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 77L, "g");
    }

    private ManualPaymentTask task(Long id, ManualPaymentTaskAccountingTargetKind kind) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(id);
        task.setGeneration(1);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setAccountingTargetKind(kind);
        task.setTargetAmountKopecks(500_000);
        task.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        task.setManualPhone("79990000000");
        task.setManualRecipientName("Банковский получатель");
        return task;
    }

    private ManualPaymentTaskLedgerEntry entry(
            ManualPaymentTask task,
            ManualPaymentTaskLedgerEventType type,
            long reserved,
            long confirmed,
            boolean verified,
            long redirected
    ) {
        ManualPaymentTaskLedgerEntry row = new ManualPaymentTaskLedgerEntry();
        row.setTask(task);
        row.setTaskGeneration(1);
        row.setSourceKind(ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK);
        row.setSourceId(77);
        row.setSourceGeneration("g");
        row.setEventType(type);
        row.setReservedDeltaKopecks(reserved);
        row.setConfirmedDeltaKopecks(confirmed);
        row.setRedirectedAmountKopecks(redirected);
        row.setAccountingTargetKind(task.getAccountingTargetKind());
        row.setVerified(verified);
        return row;
    }
}
