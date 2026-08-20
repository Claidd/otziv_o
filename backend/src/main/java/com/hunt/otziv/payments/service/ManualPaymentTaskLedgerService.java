package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.dto.ManualPaymentTaskCorrectionCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReleaseCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReserveCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReturnCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transactional append-only accounting for manual payment tasks. Callers must
 * persist the returned task/source generations on their own source row in the
 * same transaction. No method infers an accounting target from bank details.
 */
@Service
@RequiredArgsConstructor
public class ManualPaymentTaskLedgerService {

    private static final int MAX_OPERATION_KEY = 160;
    private static final int MAX_ACTOR = 160;
    private static final int MAX_REASON = 500;

    private final ManualPaymentTaskLedgerRepository ledgerRepository;
    private final ManualPaymentTaskRepository taskRepository;
    private final ManualPaymentTaskContractorCapacityService capacityService;

    @Transactional
    public ManualPaymentTaskRouteSnapshot lockSourceTask(
            Long taskId,
            ManualPaymentTaskSourceRef sourceRef
    ) {
        requireSource(sourceRef);
        ManualPaymentTask task = lockTask(taskId);
        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(sourceRef);
        requireSourceTask(history, task.getId());
        long pending = pendingForSource(history);
        return snapshot(history.getFirst(), Math.max(0, pending));
    }

    public Optional<ManualPaymentTaskRouteSnapshot> reserveFirst(ManualPaymentTaskReserveCommand command) {
        requireReserveCommand(command);
        String reservationKey = reservationKey(command.source());
        Optional<ManualPaymentTaskLedgerEntry> replay = ledgerRepository.findReservation(reservationKey);
        if (replay.isPresent()) {
            return Optional.of(assertReserveReplay(replay.get(), command));
        }

        List<ManualPaymentTask> candidates = taskRepository.findActiveForRouting(
                command.managerId(),
                command.paymentProfileId(),
                ManualPaymentTaskStatus.ACTIVE
        );

        // The task locks serialize competing attempts. Recheck the source key
        // only after those locks are held to make retries exact.
        replay = ledgerRepository.findReservation(reservationKey);
        if (replay.isPresent()) {
            return Optional.of(assertReserveReplay(replay.get(), command));
        }

        for (ManualPaymentTask task : candidates) {
            ManualPaymentTaskBalance balance = balance(task.getId());
            long projected = addExact(balance.occupiedAmountKopecks(), command.amountKopecks());
            if (projected > task.getTargetAmountKopecks()) {
                continue;
            }
            if (!isResolvedAndUsable(task)) {
                continue;
            }
            ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                    capacityService.snapshot(task, balance);
            if (beforeCommitment.profileId() != null) {
                capacityService.validateReservationIntent(
                        task, balance, command.amountKopecks());
            }
            ManualPaymentTaskLedgerEntry entry = newEntry(
                    task,
                    task.getGeneration(),
                    command.source(),
                    ManualPaymentTaskLedgerEventType.RESERVED,
                    command.operationKey(),
                    0,
                    command.amountKopecks(),
                    0,
                    0,
                    command.actor(),
                    "Резерв платёжного задания"
            );
            entry.setReservationKey(reservationKey);
            entry.setSelectedRecipientKey(candidateKey(task.getId(), task.getGeneration()));
            ledgerRepository.save(entry);
            capacityService.synchronize(beforeCommitment, task, balance(task.getId()));
            return Optional.of(snapshot(entry, command.amountKopecks()));
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<ManualPaymentTaskRouteSnapshot> candidateForSource(ManualPaymentTaskSourceRef source) {
        requireSource(source);
        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(source);
        if (history.isEmpty() || pendingForSource(history) <= 0) {
            return Optional.empty();
        }
        ManualPaymentTaskLedgerEntry latest = history.getFirst();
        return Optional.of(snapshot(latest, pendingForSource(history)));
    }

    /** Non-locking discovery used before source -> phase -> task locking. */
    @Transactional(readOnly = true)
    public List<ManualPaymentTaskSourceRef> pendingUnresolvedSources(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw badRequest("Укажите платёжное задание");
        }
        return ledgerRepository.findPendingUnresolvedReservations(taskId).stream()
                .map(this::source)
                .distinct()
                .sorted(Comparator
                        .comparing(ManualPaymentTaskSourceRef::sourceKind)
                        .thenComparing(ManualPaymentTaskSourceRef::sourceId)
                        .thenComparing(ManualPaymentTaskSourceRef::sourceGeneration))
                .toList();
    }

    @Transactional
    public ManualPaymentTaskBalance settle(ManualPaymentTaskSettlementCommand command) {
        requireSettlementCommand(command);
        ManualPaymentTask task = lockTask(command.taskId());
        List<ManualPaymentTaskLedgerEntry> existing = operation(command.operationKey());
        if (!existing.isEmpty()) {
            assertSettlementReplay(existing, command);
            return balance(task.getId());
        }
        ManualPaymentTaskBalance beforeBalance = balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                capacityService.snapshot(task, beforeBalance);

        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(command.source());
        ManualPaymentTaskLedgerEntry target = requireSourceTask(history, task.getId());
        long pending = pendingForSource(history);
        if (pending != command.totalReservedAmountKopecks()) {
            throw conflict("Резерв платёжного задания изменился; обновите данные");
        }
        if (target.getTaskGeneration() != command.taskGeneration()) {
            throw conflict("Поколение получателя платёжного задания устарело");
        }
        String expectedKey = candidateKey(task.getId(), command.taskGeneration());
        if (command.taskAttributedAmountKopecks() > 0
                && !expectedKey.equals(normalize(command.selectedRecipientKey()))) {
            throw conflict("Часть оплаты задания можно зачесть только выбранному получателю задания");
        }
        if (command.taskAttributedAmountKopecks() > 0
                && target.getAccountingTargetKind() == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            throw conflict("Получатель платёжного задания не привязан");
        }

        int sequence = 0;
        if (command.taskAttributedAmountKopecks() > 0) {
            ManualPaymentTaskLedgerEntry confirmed = copyEntry(
                    target,
                    ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                    command.operationKey(),
                    sequence++,
                    -command.taskAttributedAmountKopecks(),
                    command.taskAttributedAmountKopecks(),
                    0,
                    command.actor(),
                    command.reason()
            );
            confirmed.setSelectedRecipientKey(expectedKey);
            ledgerRepository.save(confirmed);
        }
        long redirected = command.totalReservedAmountKopecks() - command.taskAttributedAmountKopecks();
        if (redirected > 0) {
            ManualPaymentTaskLedgerEntry row = copyEntry(
                    target,
                    ManualPaymentTaskLedgerEventType.REDIRECTED,
                    command.operationKey(),
                    sequence,
                    -redirected,
                    0,
                    redirected,
                    command.actor(),
                    command.reason()
            );
            row.setSelectedRecipientKey(limit(command.selectedRecipientKey(), 160));
            ledgerRepository.save(row);
        }
        completeAfterVerifiedSettlement(task);
        ManualPaymentTaskBalance afterBalance = balance(task.getId());
        capacityService.synchronize(beforeCommitment, task, afterBalance);
        return afterBalance;
    }

    @Transactional
    public ManualPaymentTaskBalance release(ManualPaymentTaskReleaseCommand command) {
        requireReleaseCommand(command);
        ManualPaymentTask task = lockTask(command.taskId());
        List<ManualPaymentTaskLedgerEntry> replay = operation(command.operationKey());
        if (!replay.isEmpty()) {
            assertSingleReplay(replay, command.source(), ManualPaymentTaskLedgerEventType.RELEASED,
                    -command.amountKopecks(), 0, command.taskId(), null);
            return balance(task.getId());
        }
        ManualPaymentTaskBalance beforeBalance = balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                capacityService.snapshot(task, beforeBalance);
        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(command.source());
        ManualPaymentTaskLedgerEntry source = requireSourceTask(history, task.getId());
        if (pendingForSource(history) < command.amountKopecks()) {
            throw conflict("Освобождаемая сумма превышает остаток резерва задания");
        }
        ledgerRepository.save(copyEntry(
                source,
                ManualPaymentTaskLedgerEventType.RELEASED,
                command.operationKey(),
                0,
                -command.amountKopecks(),
                0,
                0,
                command.actor(),
                command.reason()
        ));
        ManualPaymentTaskBalance afterBalance = balance(task.getId());
        capacityService.synchronize(beforeCommitment, task, afterBalance);
        return afterBalance;
    }

    @Transactional
    public ManualPaymentTaskBalance recordReturn(ManualPaymentTaskReturnCommand command) {
        requireReturnCommand(command);
        ManualPaymentTask task = lockTask(command.taskId());
        List<ManualPaymentTaskLedgerEntry> replay = operation(command.operationKey());
        if (!replay.isEmpty()) {
            assertSingleReplay(replay, command.source(), ManualPaymentTaskLedgerEventType.RETURNED,
                    0, -command.amountKopecks(), command.taskId(), null);
            return balance(task.getId());
        }
        ManualPaymentTaskBalance beforeBalance = balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                capacityService.snapshot(task, beforeBalance);
        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(command.source());
        ManualPaymentTaskLedgerEntry source = requireSourceTask(history, task.getId());
        if (confirmedForSource(history) < command.amountKopecks()) {
            throw conflict("Возврат превышает подтверждённую сумму задания");
        }
        ledgerRepository.save(copyEntry(
                source,
                ManualPaymentTaskLedgerEventType.RETURNED,
                command.operationKey(),
                0,
                0,
                -command.amountKopecks(),
                0,
                command.actor(),
                command.reason()
        ));
        markNeedsAttention(task);
        ManualPaymentTaskBalance afterBalance = balance(task.getId());
        capacityService.synchronize(beforeCommitment, task, afterBalance);
        return afterBalance;
    }

    /**
     * Locks the task in the global financial order and returns the amount
     * already returned for the exact source generation. The caller keeps this
     * transaction open while locking the contractor profile/allocation, then
     * invokes {@link #recordReturn(ManualPaymentTaskReturnCommand)}.
     */
    @Transactional
    public long lockReturnSource(Long taskId, ManualPaymentTaskSourceRef sourceRef) {
        requireSource(sourceRef);
        ManualPaymentTask task = lockTask(taskId);
        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(sourceRef);
        requireSourceTask(history, task.getId());
        long returned = 0L;
        for (ManualPaymentTaskLedgerEntry entry : history) {
            if (entry.getEventType() == ManualPaymentTaskLedgerEventType.RETURNED) {
                returned = addExact(returned, Math.negateExact(entry.getConfirmedDeltaKopecks()));
            }
        }
        return returned;
    }

    /**
     * Reconstructs an archived source binding only from immutable ledger
     * evidence. Exactly one source generation must exist for the attributed
     * task generation; missing or ambiguous archive history fails closed.
     */
    @Transactional
    public LockedArchivedReturnSource lockArchivedReturnSource(
            Long taskId,
            long taskGeneration,
            ManualPaymentTaskLedgerSourceKind sourceKind,
            Long sourceId
    ) {
        if (taskId == null || taskGeneration <= 0 || sourceKind == null
                || sourceKind == ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE
                || sourceId == null || sourceId <= 0) {
            throw conflict("Архивный источник задания задан некорректно");
        }
        ManualPaymentTask task = lockTask(taskId);
        List<ManualPaymentTaskLedgerEntry> history = ledgerRepository
                .findArchivedSourceHistoryNewestFirst(
                        taskId, taskGeneration, sourceKind, sourceId);
        if (history.isEmpty()) {
            throw conflict("Архивный источник не найден в журнале задания");
        }
        String sourceGeneration = normalize(history.getFirst().getSourceGeneration());
        if (sourceGeneration.isBlank()) {
            throw conflict("Архивный источник задания не имеет поколения");
        }
        long returned = 0L;
        for (ManualPaymentTaskLedgerEntry entry : history) {
            if (entry.getTask() == null
                    || !Objects.equals(entry.getTask().getId(), task.getId())
                    || entry.getTaskGeneration() != taskGeneration
                    || entry.getSourceKind() != sourceKind
                    || entry.getSourceId() != sourceId
                    || !sourceGeneration.equals(normalize(entry.getSourceGeneration()))) {
                throw conflict("Архивный источник задания неоднозначен");
            }
            if (entry.getEventType() == ManualPaymentTaskLedgerEventType.RETURNED) {
                returned = addExact(returned, Math.negateExact(entry.getConfirmedDeltaKopecks()));
            }
        }
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                sourceKind, sourceId, sourceGeneration);
        requireSourceTask(history, task.getId());
        return new LockedArchivedReturnSource(source, returned);
    }

    /**
     * Locks the exact pre-V251 confirmed source whose recipient was never
     * typed. Only one unverified LEGACY_BASELINE row and its own RETURNED
     * events are accepted. This deliberately cannot infer a contractor: the
     * caller may debit the task ledger only after an authoritative full source
     * reversal.
     */
    @Transactional
    public LockedLegacyReturnSource lockLegacyConfirmedReturnSource(
            ManualPaymentTaskLedgerSourceKind sourceKind,
            Long sourceId
    ) {
        if (sourceKind == null
                || sourceKind == ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE
                || sourceId == null || sourceId <= 0) {
            throw conflict("Исторический источник возврата задан некорректно");
        }
        List<Long> taskIds = ledgerRepository.findTaskIdsBySource(sourceKind, sourceId);
        if (taskIds.size() != 1 || taskIds.getFirst() == null) {
            throw conflict("Для исторического возврата нет однозначной привязки задания; требуется сверка");
        }
        return lockLegacyConfirmedReturnSource(taskIds.getFirst(), sourceKind, sourceId);
    }

    @Transactional
    public LockedLegacyReturnSource lockLegacyConfirmedReturnSource(
            Long taskId,
            ManualPaymentTaskLedgerSourceKind sourceKind,
            Long sourceId
    ) {
        if (taskId == null || sourceKind == null
                || sourceKind == ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE
                || sourceId == null || sourceId <= 0) {
            throw conflict("Исторический источник возврата задан некорректно");
        }
        ManualPaymentTask task = lockTask(taskId);
        List<ManualPaymentTaskLedgerEntry> history = ledgerRepository
                .findTaskSourceHistoryNewestFirst(taskId, sourceKind, sourceId);
        if (history.isEmpty()) {
            throw conflict("Для исторического возврата нет точной привязки источника; требуется сверка");
        }
        ManualPaymentTaskLedgerEntry newest = history.getFirst();
        long taskGeneration = newest.getTaskGeneration();
        String sourceGeneration = normalize(newest.getSourceGeneration());
        String expectedGeneration = "LEGACY-" + sourceId;
        if (taskGeneration <= 0 || !expectedGeneration.equals(sourceGeneration)) {
            throw conflict("Исторический источник возврата неоднозначен; требуется сверка");
        }
        long confirmed = 0L;
        long returned = 0L;
        int baselines = 0;
        String evidenceReference = null;
        String expectedEvidence = "V251:BASELINE:" + sourceKind.name() + ":" + sourceId;
        for (ManualPaymentTaskLedgerEntry entry : history) {
            if (entry.getTask() == null
                    || !Objects.equals(entry.getTask().getId(), task.getId())
                    || entry.getTaskGeneration() != taskGeneration
                    || entry.getSourceKind() != sourceKind
                    || !Objects.equals(entry.getSourceId(), sourceId)
                    || !sourceGeneration.equals(normalize(entry.getSourceGeneration()))) {
                throw conflict("Исторический источник возврата неоднозначен; требуется сверка");
            }
            if (entry.getEventType() == ManualPaymentTaskLedgerEventType.LEGACY_BASELINE) {
                baselines++;
                if (entry.isVerified()
                        || entry.getReservedDeltaKopecks() != 0L
                        || entry.getConfirmedDeltaKopecks() <= 0L
                        || entry.getRedirectedAmountKopecks() != 0L
                        || entry.getAccountingTargetKind()
                                != ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                        || entry.getAccountingTargetProfile() != null
                        || !expectedEvidence.equals(normalize(entry.getOperationKey()))) {
                    throw conflict("Историческая сумма источника повреждена; требуется сверка");
                }
                evidenceReference = normalize(entry.getOperationKey());
                confirmed = addExact(confirmed, entry.getConfirmedDeltaKopecks());
                continue;
            }
            if (entry.getEventType() != ManualPaymentTaskLedgerEventType.RETURNED
                    || !entry.isVerified()
                    || entry.getReservedDeltaKopecks() != 0L
                    || entry.getConfirmedDeltaKopecks() >= 0L
                    || entry.getRedirectedAmountKopecks() != 0L) {
                throw conflict("Исторический источник уже изменён и требует сверки");
            }
            returned = addExact(returned, Math.negateExact(entry.getConfirmedDeltaKopecks()));
        }
        if (baselines != 1 || confirmed <= 0L || returned > confirmed) {
            throw conflict("Историческая сумма источника неоднозначна; требуется сверка");
        }
        return new LockedLegacyReturnSource(
                new ManualPaymentTaskSourceRef(sourceKind, sourceId, sourceGeneration),
                task.getId(),
                taskGeneration,
                confirmed,
                returned,
                evidenceReference
        );
    }

    /**
     * Reopens a completed task when the provider reports a partial return but
     * does not expose its amount. The exact source binding is still verified;
     * no financial delta is guessed.
     */
    @Transactional
    public void markReturnNeedsAttention(Long taskId, ManualPaymentTaskSourceRef sourceRef) {
        requireSource(sourceRef);
        ManualPaymentTask task = lockTask(taskId);
        requireSourceTask(sourceHistory(sourceRef), task.getId());
        ManualPaymentTaskBalance balance = balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                capacityService.snapshot(task, balance);
        markNeedsAttention(task);
        capacityService.synchronize(beforeCommitment, task, balance);
    }

    @Transactional
    public ManualPaymentTaskBalance correct(ManualPaymentTaskCorrectionCommand command) {
        requireCorrectionCommand(command);
        ManualPaymentTask task = lockTask(command.taskId());
        List<ManualPaymentTaskLedgerEntry> replay = operation(command.operationKey());
        if (!replay.isEmpty()) {
            assertSingleReplay(replay, command.source(), ManualPaymentTaskLedgerEventType.CORRECTION,
                    command.reservedDeltaKopecks(), command.confirmedDeltaKopecks(),
                    command.taskId(), command.correctionOfEntryId());
            return balance(task.getId());
        }
        ManualPaymentTaskBalance beforeBalance = balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                capacityService.snapshot(task, beforeBalance);
        List<ManualPaymentTaskLedgerEntry> history = sourceHistory(command.source());
        ManualPaymentTaskLedgerEntry source = requireSourceTask(history, task.getId());
        if (addExact(pendingForSource(history), command.reservedDeltaKopecks()) < 0
                || addExact(confirmedForSource(history), command.confirmedDeltaKopecks()) < 0) {
            throw conflict("Исправление создаёт отрицательный остаток задания");
        }
        ManualPaymentTaskLedgerEntry correctionOf = ledgerRepository.findById(command.correctionOfEntryId())
                .filter(row -> task.getId().equals(row.getTask().getId()))
                .orElseThrow(() -> conflict("Исправляемая операция задания не найдена"));
        ManualPaymentTaskLedgerEntry row = copyEntry(
                source,
                ManualPaymentTaskLedgerEventType.CORRECTION,
                command.operationKey(),
                0,
                command.reservedDeltaKopecks(),
                command.confirmedDeltaKopecks(),
                0,
                command.actor(),
                command.reason()
        );
        row.setCorrectionOf(correctionOf);
        ledgerRepository.save(row);
        markNeedsAttention(task);
        ManualPaymentTaskBalance afterBalance = balance(task.getId());
        capacityService.synchronize(beforeCommitment, task, afterBalance);
        return afterBalance;
    }

    @Transactional(readOnly = true)
    public ManualPaymentTaskBalance balance(Long taskId) {
        if (taskId == null) {
            return ManualPaymentTaskBalance.empty(false);
        }
        List<ManualPaymentTaskLedgerEntry> entries = ledgerRepository.findAllByTaskIdOrderById(taskId);
        long pending = 0;
        long confirmed = 0;
        long redirected = 0;
        long released = 0;
        long returned = 0;
        long baselineReconciliationCount = 0;
        Map<String, SourceState> sources = new HashMap<>();
        Map<String, ConfirmedExposureState> confirmedSources = new HashMap<>();
        for (ManualPaymentTaskLedgerEntry entry : entries) {
            pending = addExact(pending, entry.getReservedDeltaKopecks());
            confirmed = addExact(confirmed, entry.getConfirmedDeltaKopecks());
            ConfirmedExposureState confirmedSource = confirmedSources.computeIfAbsent(
                    sourceKey(entry), ignored -> new ConfirmedExposureState());
            long confirmedDelta = entry.getConfirmedDeltaKopecks();
            if (entry.isVerified()
                    && entry.getEventType() == ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK
                    && confirmedDelta > 0L) {
                confirmedSource.backedPositive = addExact(
                        confirmedSource.backedPositive, confirmedDelta);
            } else if (confirmedDelta > 0L) {
                confirmedSource.unbackedPositive = addExact(
                        confirmedSource.unbackedPositive, confirmedDelta);
            } else if (confirmedDelta < 0L) {
                confirmedSource.negative = addExact(
                        confirmedSource.negative, Math.negateExact(confirmedDelta));
            }
            redirected = addExact(redirected, entry.getRedirectedAmountKopecks());
            if (entry.getEventType() == ManualPaymentTaskLedgerEventType.RELEASED) {
                released = addExact(released, Math.negateExact(entry.getReservedDeltaKopecks()));
            }
            if (entry.getEventType() == ManualPaymentTaskLedgerEventType.RETURNED) {
                returned = addExact(returned, Math.negateExact(entry.getConfirmedDeltaKopecks()));
            }
            if (!entry.isVerified() && entry.getConfirmedDeltaKopecks() != 0) {
                confirmedSource.hasUnverifiedEntry = true;
                baselineReconciliationCount = addExact(baselineReconciliationCount, 1);
            }
            if (entry.getSourceKind() != ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE) {
                SourceState state = sources.computeIfAbsent(sourceKey(entry), ignored -> new SourceState());
                state.pending = addExact(state.pending, entry.getReservedDeltaKopecks());
                state.latestTargetKind = entry.getAccountingTargetKind();
            }
        }
        if (pending < 0 || confirmed < 0) {
            throw new IllegalStateException("Отрицательный остаток в журнале платёжного задания " + taskId);
        }
        long unverifiedConfirmed = 0L;
        long unexplainedSourceCount = 0L;
        for (ConfirmedExposureState source : confirmedSources.values()) {
            // A negative delta may belong to the contractor-backed receipt.
            // Allocate it there first; otherwise a later RETURNED row could
            // incorrectly erase an unknown positive correction from capacity.
            long totalPositive = addExact(
                    source.backedPositive, source.unbackedPositive);
            long sourceNet = addExact(totalPositive, Math.negateExact(source.negative));
            long backedNet = Math.max(0L, addExact(
                    source.backedPositive, Math.negateExact(source.negative)));
            long netUnbacked = Math.max(0L,
                    addExact(sourceNet, Math.negateExact(backedNet)));
            unverifiedConfirmed = addExact(unverifiedConfirmed, netUnbacked);
            if (netUnbacked > 0L && !source.hasUnverifiedEntry) {
                unexplainedSourceCount = addExact(unexplainedSourceCount, 1L);
            }
        }
        long pendingCount = sources.values().stream().filter(state -> state.pending > 0).count();
        long unresolvedCount = sources.values().stream()
                .filter(state -> state.pending > 0
                        && state.latestTargetKind == ManualPaymentTaskAccountingTargetKind.UNRESOLVED)
                .count();
        long reconciliationCount = addExact(
                addExact(baselineReconciliationCount, unresolvedCount),
                unexplainedSourceCount);
        boolean persistedFlag = taskRepository.findById(taskId)
                .map(ManualPaymentTask::isNeedsReconciliation)
                .orElse(false);
        long occupied = addExact(pending, confirmed);
        return new ManualPaymentTaskBalance(
                pending,
                confirmed,
                occupied,
                redirected,
                released,
                returned,
                unverifiedConfirmed,
                pendingCount,
                reconciliationCount,
                persistedFlag || reconciliationCount > 0
        );
    }

    @Transactional
    public List<ManualPaymentTaskRouteSnapshot> bindPendingLegacyReservations(
            ManualPaymentTask task,
            String actor
    ) {
        if (task == null || task.getId() == null || !isResolvedAndUsable(task)) {
            return List.of();
        }
        List<ManualPaymentTaskLedgerEntry> reservations =
                ledgerRepository.findPendingUnresolvedReservations(task.getId());
        List<ManualPaymentTaskRouteSnapshot> boundSources = new ArrayList<>();
        for (ManualPaymentTaskLedgerEntry reservation : reservations) {
            ManualPaymentTaskSourceRef source = source(reservation);
            List<ManualPaymentTaskLedgerEntry> history = sourceHistory(source);
            long pending = pendingForSource(history);
            if (history.isEmpty()
                    || reservation.getAccountingTargetKind()
                    != ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                    || pending <= 0) {
                continue;
            }
            ManualPaymentTaskLedgerEntry latest = history.getFirst();
            ManualPaymentTaskLedgerEntry bound;
            if (latest.getAccountingTargetKind() != ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
                if (!sameBoundTarget(latest, task)) {
                    throw conflict("Старый резерв уже привязан к другому получателю");
                }
                bound = latest;
            } else {
                String operationKey = "TASK_BIND:" + task.getId() + ":" + task.getGeneration()
                        + ":" + reservation.getId();
                List<ManualPaymentTaskLedgerEntry> replay = operation(operationKey);
                if (!replay.isEmpty()) {
                    if (replay.size() != 1 || !sameSource(replay.getFirst(), source)
                            || replay.getFirst().getEventType()
                            != ManualPaymentTaskLedgerEventType.TARGET_BOUND
                            || !sameBoundTarget(replay.getFirst(), task)) {
                        throw conflict("Ключ привязки старого резерва уже использован");
                    }
                    bound = replay.getFirst();
                } else {
                    bound = copyEntry(
                            reservation,
                            ManualPaymentTaskLedgerEventType.TARGET_BOUND,
                            operationKey,
                            0,
                            0,
                            0,
                            0,
                            actor,
                            "Явная привязка получателя старого задания"
                    );
                    // The one-time remediation freezes only the typed accounting
                    // destination. Bank requisites remain the immutable source
                    // snapshots and are never replaced with the task's current data.
                    bound.setTaskGeneration(task.getGeneration());
                    bound.setAccountingTargetKind(targetKind(task));
                    bound.setAccountingTargetProfile(task.getAccountingTargetProfile());
                    bound.setAccountingTargetLabelSnapshot(targetLabel(task));
                    bound.setTargetOverrunAcknowledgedAt(task.getTargetOverrunAcknowledgedAt());
                    bound.setTargetOverrunAcknowledgedBy(task.getTargetOverrunAcknowledgedBy());
                    bound.setSelectedRecipientKey(candidateKey(task.getId(), task.getGeneration()));
                    bound = ledgerRepository.save(bound);
                }
            }
            boundSources.add(snapshot(bound, pending));
        }
        ManualPaymentTaskBalance current = balance(task.getId());
        task.setNeedsReconciliation(current.unverifiedConfirmedAmountKopecks() > 0);
        taskRepository.save(task);
        return List.copyOf(boundSources);
    }

    private boolean sameBoundTarget(ManualPaymentTaskLedgerEntry entry, ManualPaymentTask task) {
        Long entryProfileId = entry.getAccountingTargetProfile() == null
                ? null : entry.getAccountingTargetProfile().getId();
        Long taskProfileId = task.getAccountingTargetProfile() == null
                ? null : task.getAccountingTargetProfile().getId();
        return entry.getTaskGeneration() == task.getGeneration()
                && entry.getAccountingTargetKind() == targetKind(task)
                && java.util.Objects.equals(entryProfileId, taskProfileId)
                && normalize(entry.getSelectedRecipientKey()).equals(
                        candidateKey(task.getId(), task.getGeneration()));
    }

    public static String candidateKey(Long taskId, long taskGeneration) {
        if (taskId == null || taskId <= 0 || taskGeneration <= 0) {
            throw new IllegalArgumentException("Task id and generation must be positive");
        }
        return "TASK:" + taskId + ":" + taskGeneration;
    }

    private ManualPaymentTaskLedgerEntry newEntry(
            ManualPaymentTask task,
            long taskGeneration,
            ManualPaymentTaskSourceRef source,
            ManualPaymentTaskLedgerEventType eventType,
            String operationKey,
            int operationSequence,
            long reservedDelta,
            long confirmedDelta,
            long redirected,
            String actor,
            String reason
    ) {
        ManualPaymentTaskLedgerEntry entry = new ManualPaymentTaskLedgerEntry();
        entry.setTask(task);
        entry.setTaskGeneration(taskGeneration);
        entry.setSourceKind(source.sourceKind());
        entry.setSourceId(source.sourceId());
        entry.setSourceGeneration(source.sourceGeneration());
        entry.setEventType(eventType);
        entry.setOperationKey(requireText(operationKey, MAX_OPERATION_KEY, "Укажите ключ операции задания"));
        entry.setOperationSequence(operationSequence);
        entry.setReservedDeltaKopecks(reservedDelta);
        entry.setConfirmedDeltaKopecks(confirmedDelta);
        entry.setRedirectedAmountKopecks(redirected);
        copyTaskSnapshot(entry, task);
        entry.setVerified(true);
        entry.setActor(defaultActor(actor));
        entry.setReason(limit(reason, MAX_REASON));
        return entry;
    }

    private ManualPaymentTaskLedgerEntry copyEntry(
            ManualPaymentTaskLedgerEntry source,
            ManualPaymentTaskLedgerEventType type,
            String operationKey,
            int sequence,
            long reservedDelta,
            long confirmedDelta,
            long redirected,
            String actor,
            String reason
    ) {
        ManualPaymentTaskLedgerEntry entry = new ManualPaymentTaskLedgerEntry();
        entry.setTask(source.getTask());
        entry.setTaskGeneration(source.getTaskGeneration());
        entry.setSourceKind(source.getSourceKind());
        entry.setSourceId(source.getSourceId());
        entry.setSourceGeneration(source.getSourceGeneration());
        entry.setEventType(type);
        entry.setOperationKey(requireText(operationKey, MAX_OPERATION_KEY, "Укажите ключ операции задания"));
        entry.setOperationSequence(sequence);
        entry.setReservedDeltaKopecks(reservedDelta);
        entry.setConfirmedDeltaKopecks(confirmedDelta);
        entry.setRedirectedAmountKopecks(redirected);
        entry.setAccountingTargetKind(source.getAccountingTargetKind());
        entry.setAccountingTargetProfile(source.getAccountingTargetProfile());
        entry.setAccountingTargetLabelSnapshot(source.getAccountingTargetLabelSnapshot());
        entry.setManualPaymentType(source.getManualPaymentType());
        entry.setManualPhoneSnapshot(source.getManualPhoneSnapshot());
        entry.setBankRecipientNameSnapshot(source.getBankRecipientNameSnapshot());
        entry.setManualPaymentUrlSnapshot(source.getManualPaymentUrlSnapshot());
        entry.setManualPaymentButtonSnapshot(source.getManualPaymentButtonSnapshot());
        entry.setTargetOverrunAcknowledgedAt(source.getTargetOverrunAcknowledgedAt());
        entry.setTargetOverrunAcknowledgedBy(source.getTargetOverrunAcknowledgedBy());
        entry.setVerified(true);
        entry.setActor(defaultActor(actor));
        entry.setReason(limit(reason, MAX_REASON));
        return entry;
    }

    private void copyTaskSnapshot(ManualPaymentTaskLedgerEntry entry, ManualPaymentTask task) {
        entry.setAccountingTargetKind(targetKind(task));
        entry.setAccountingTargetProfile(task.getAccountingTargetProfile());
        entry.setAccountingTargetLabelSnapshot(targetLabel(task));
        entry.setManualPaymentType(task.getManualPaymentType() == null
                ? ManualPaymentType.MOBILE_BANK : task.getManualPaymentType());
        entry.setManualPhoneSnapshot(normalize(task.getManualPhone()));
        entry.setBankRecipientNameSnapshot(normalize(task.getManualRecipientName()));
        entry.setManualPaymentUrlSnapshot(PaymentUrlPolicy.safe(
                task.getManualPaymentUrl(), PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL));
        entry.setManualPaymentButtonSnapshot(limit(task.getManualPaymentButtonLabel(), 80));
        entry.setTargetOverrunAcknowledgedAt(task.getTargetOverrunAcknowledgedAt());
        entry.setTargetOverrunAcknowledgedBy(task.getTargetOverrunAcknowledgedBy());
    }

    private ManualPaymentTaskRouteSnapshot snapshot(ManualPaymentTaskLedgerEntry entry, long reserved) {
        return new ManualPaymentTaskRouteSnapshot(
                entry.getTask().getId(),
                entry.getTaskGeneration(),
                source(entry),
                candidateKey(entry.getTask().getId(), entry.getTaskGeneration()),
                entry.getAccountingTargetKind(),
                entry.getAccountingTargetProfile() == null ? null : entry.getAccountingTargetProfile().getId(),
                normalize(entry.getAccountingTargetLabelSnapshot()),
                entry.getManualPaymentType(),
                normalize(entry.getManualPhoneSnapshot()),
                normalize(entry.getBankRecipientNameSnapshot()),
                PaymentUrlPolicy.safe(entry.getManualPaymentUrlSnapshot(), PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL),
                normalize(entry.getManualPaymentButtonSnapshot()),
                reserved,
                entry.getTargetOverrunAcknowledgedAt(),
                normalize(entry.getTargetOverrunAcknowledgedBy())
        );
    }

    private ManualPaymentTaskRouteSnapshot assertReserveReplay(
            ManualPaymentTaskLedgerEntry entry,
            ManualPaymentTaskReserveCommand command
    ) {
        if (entry.getEventType() != ManualPaymentTaskLedgerEventType.RESERVED
                || entry.getReservedDeltaKopecks() != command.amountKopecks()
                || !entry.getOperationKey().equals(normalize(command.operationKey()))
                || !sameSource(entry, command.source())) {
            throw conflict("Ключ резерва задания уже использован с другими данными");
        }
        return snapshot(entry, entry.getReservedDeltaKopecks());
    }

    private void assertSettlementReplay(
            List<ManualPaymentTaskLedgerEntry> rows,
            ManualPaymentTaskSettlementCommand command
    ) {
        long redirectedAmount = command.totalReservedAmountKopecks()
                - command.taskAttributedAmountKopecks();
        int expectedRows = (command.taskAttributedAmountKopecks() > 0 ? 1 : 0)
                + (redirectedAmount > 0 ? 1 : 0);
        if (rows.size() != expectedRows) {
            throw conflict("Ключ закрытия задания уже использован с другой структурой операции");
        }
        long reservedDelta = 0;
        long confirmedDelta = 0;
        long redirected = 0;
        int index = 0;
        if (command.taskAttributedAmountKopecks() > 0) {
            ManualPaymentTaskLedgerEntry row = rows.get(index++);
            assertSettlementReplayRow(
                    row,
                    command,
                    ManualPaymentTaskLedgerEventType.CONFIRMED_TO_TASK,
                    0,
                    candidateKey(command.taskId(), command.taskGeneration())
            );
        }
        if (redirectedAmount > 0) {
            ManualPaymentTaskLedgerEntry row = rows.get(index);
            assertSettlementReplayRow(
                    row,
                    command,
                    ManualPaymentTaskLedgerEventType.REDIRECTED,
                    command.taskAttributedAmountKopecks() > 0 ? 1 : 0,
                    normalize(command.selectedRecipientKey())
            );
        }
        for (ManualPaymentTaskLedgerEntry row : rows) {
            if (!sameSource(row, command.source())
                    || !row.getTask().getId().equals(command.taskId())
                    || row.getTaskGeneration() != command.taskGeneration()) {
                throw conflict("Ключ закрытия задания уже использован с другими данными");
            }
            reservedDelta = addExact(reservedDelta, row.getReservedDeltaKopecks());
            confirmedDelta = addExact(confirmedDelta, row.getConfirmedDeltaKopecks());
            redirected = addExact(redirected, row.getRedirectedAmountKopecks());
        }
        if (reservedDelta != -command.totalReservedAmountKopecks()
                || confirmedDelta != command.taskAttributedAmountKopecks()
                || redirected != redirectedAmount) {
            throw conflict("Ключ закрытия задания уже использован с другими суммами");
        }
    }

    private void assertSettlementReplayRow(
            ManualPaymentTaskLedgerEntry row,
            ManualPaymentTaskSettlementCommand command,
            ManualPaymentTaskLedgerEventType expectedType,
            int expectedSequence,
            String expectedRecipient
    ) {
        if (row.getEventType() != expectedType
                || row.getOperationSequence() != expectedSequence
                || !sameSource(row, command.source())
                || row.getTask() == null
                || !Objects.equals(row.getTask().getId(), command.taskId())
                || row.getTaskGeneration() != command.taskGeneration()
                || !normalize(row.getSelectedRecipientKey()).equals(expectedRecipient)) {
            throw conflict("Ключ закрытия задания уже использован с другой структурой операции");
        }
    }

    private void assertSingleReplay(
            List<ManualPaymentTaskLedgerEntry> rows,
            ManualPaymentTaskSourceRef source,
            ManualPaymentTaskLedgerEventType type,
            long reservedDelta,
            long confirmedDelta,
            Long taskId,
            Long correctionOfEntryId
    ) {
        if (rows.size() != 1) {
            throw conflict("Ключ операции задания уже использован");
        }
        ManualPaymentTaskLedgerEntry row = rows.getFirst();
        if (!sameSource(row, source)
                || row.getTask() == null
                || !Objects.equals(row.getTask().getId(), taskId)
                || row.getEventType() != type
                || row.getReservedDeltaKopecks() != reservedDelta
                || row.getConfirmedDeltaKopecks() != confirmedDelta
                || !java.util.Objects.equals(
                        row.getCorrectionOf() == null ? null : row.getCorrectionOf().getId(),
                        correctionOfEntryId)) {
            throw conflict("Ключ операции задания уже использован с другими данными");
        }
    }

    private void completeAfterVerifiedSettlement(ManualPaymentTask task) {
        ManualPaymentTaskBalance current = balance(task.getId());
        long netConfirmed = Math.max(0, current.netConfirmedAmountKopecks());
        if (netConfirmed >= task.getTargetAmountKopecks()
                && (task.getStatus() == ManualPaymentTaskStatus.ACTIVE
                    || task.getStatus() == ManualPaymentTaskStatus.NEEDS_ATTENTION)) {
            task.setStatus(ManualPaymentTaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    private void markNeedsAttention(ManualPaymentTask task) {
        if (task.getStatus() != ManualPaymentTaskStatus.CANCELED) {
            task.setStatus(ManualPaymentTaskStatus.NEEDS_ATTENTION);
            task.setCompletedAt(null);
            taskRepository.save(task);
        }
    }

    private ManualPaymentTaskLedgerEntry requireSourceTask(
            List<ManualPaymentTaskLedgerEntry> history,
            Long taskId
    ) {
        if (history.isEmpty() || !history.getFirst().getTask().getId().equals(taskId)) {
            throw conflict("Источник не зарезервирован в этом платёжном задании");
        }
        return history.getFirst();
    }

    private List<ManualPaymentTaskLedgerEntry> sourceHistory(ManualPaymentTaskSourceRef source) {
        return ledgerRepository.findSourceHistoryNewestFirst(
                source.sourceKind(), source.sourceId(), source.sourceGeneration());
    }

    private List<ManualPaymentTaskLedgerEntry> operation(String operationKey) {
        return ledgerRepository.findAllByOperationKeyOrderByOperationSequence(
                requireText(operationKey, MAX_OPERATION_KEY, "Укажите ключ операции задания"));
    }

    private ManualPaymentTask lockTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw badRequest("Укажите платёжное задание");
        }
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> conflict("Платёжное задание не найдено"));
    }

    private long pendingForSource(List<ManualPaymentTaskLedgerEntry> history) {
        long result = 0;
        for (ManualPaymentTaskLedgerEntry row : history) {
            result = addExact(result, row.getReservedDeltaKopecks());
        }
        return result;
    }

    private long confirmedForSource(List<ManualPaymentTaskLedgerEntry> history) {
        long result = 0;
        for (ManualPaymentTaskLedgerEntry row : history) {
            result = addExact(result, row.getConfirmedDeltaKopecks());
        }
        return result;
    }

    private boolean isResolvedAndUsable(ManualPaymentTask task) {
        ManualPaymentTaskAccountingTargetKind kind = targetKind(task);
        if (kind == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            return false;
        }
        boolean requisitesReady = task.getManualPaymentType() == ManualPaymentType.EXTERNAL_LINK
                ? !PaymentUrlPolicy.safe(task.getManualPaymentUrl(), PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL).isBlank()
                : !normalize(task.getManualPhone()).isBlank() && !normalize(task.getManualRecipientName()).isBlank();
        if (!requisitesReady) return false;
        if (kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                || kind == ManualPaymentTaskAccountingTargetKind.MANAGER) {
            return task.getAccountingTargetProfile() != null
                    && task.getAccountingTargetProfile().isEnabled();
        }
        return task.getAccountingTargetProfile() == null;
    }

    private ManualPaymentTaskAccountingTargetKind targetKind(ManualPaymentTask task) {
        return task.getAccountingTargetKind() == null
                ? ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                : task.getAccountingTargetKind();
    }

    private String targetLabel(ManualPaymentTask task) {
        return switch (targetKind(task)) {
            case UNRESOLVED -> "Получатель задания не привязан";
            case EXTERNAL_TASK -> normalize(task.getManualRecipientName());
            case OWNER -> "Владелец";
            case SPECIALIST, MANAGER -> profileLabel(task.getAccountingTargetProfile());
        };
    }

    private String profileLabel(ContractorPaymentProfile profile) {
        if (profile == null || profile.getUser() == null) {
            return "";
        }
        String fio = normalize(profile.getUser().getFio());
        return fio.isBlank() ? normalize(profile.getUser().getUsername()) : fio;
    }

    private void requireReserveCommand(ManualPaymentTaskReserveCommand command) {
        if (command == null || command.managerId() == null || command.managerId() <= 0
                || command.paymentProfileId() == null || command.paymentProfileId() <= 0
                || command.amountKopecks() <= 0) {
            throw badRequest("Некорректный резерв платёжного задания");
        }
        requireSource(command.source());
        requireText(command.operationKey(), MAX_OPERATION_KEY, "Укажите ключ резерва задания");
    }

    private void requireSettlementCommand(ManualPaymentTaskSettlementCommand command) {
        if (command == null || command.taskId() == null || command.taskId() <= 0
                || command.taskGeneration() <= 0 || command.totalReservedAmountKopecks() <= 0
                || command.taskAttributedAmountKopecks() < 0
                || command.taskAttributedAmountKopecks() > command.totalReservedAmountKopecks()) {
            throw badRequest("Некорректное закрытие резерва платёжного задания");
        }
        requireSource(command.source());
        requireText(command.operationKey(), MAX_OPERATION_KEY, "Укажите ключ закрытия задания");
    }

    private void requireReleaseCommand(ManualPaymentTaskReleaseCommand command) {
        if (command == null || command.taskId() == null || command.amountKopecks() <= 0) {
            throw badRequest("Некорректное освобождение резерва задания");
        }
        requireSource(command.source());
        requireText(command.operationKey(), MAX_OPERATION_KEY, "Укажите ключ освобождения задания");
    }

    private void requireReturnCommand(ManualPaymentTaskReturnCommand command) {
        if (command == null || command.taskId() == null || command.amountKopecks() <= 0) {
            throw badRequest("Некорректный возврат по заданию");
        }
        requireSource(command.source());
        requireText(command.operationKey(), MAX_OPERATION_KEY, "Укажите ключ возврата задания");
    }

    private void requireCorrectionCommand(ManualPaymentTaskCorrectionCommand command) {
        if (command == null || command.taskId() == null || command.correctionOfEntryId() == null
                || (command.reservedDeltaKopecks() == 0 && command.confirmedDeltaKopecks() == 0)
                || normalize(command.reason()).isBlank()) {
            throw badRequest("Для исправления задания укажите исходную операцию, суммы и причину");
        }
        requireSource(command.source());
        requireText(command.operationKey(), MAX_OPERATION_KEY, "Укажите ключ исправления задания");
    }

    private void requireSource(ManualPaymentTaskSourceRef source) {
        if (source == null || source.sourceKind() == null
                || source.sourceKind() == ManualPaymentTaskLedgerSourceKind.LEGACY_TASK_BASELINE
                || source.sourceId() == null || source.sourceId() <= 0
                || normalize(source.sourceGeneration()).isBlank()
                || normalize(source.sourceGeneration()).length() > 36) {
            throw badRequest("Некорректный источник платёжного задания");
        }
    }

    private ManualPaymentTaskSourceRef source(ManualPaymentTaskLedgerEntry entry) {
        return new ManualPaymentTaskSourceRef(
                entry.getSourceKind(), entry.getSourceId(), entry.getSourceGeneration());
    }

    private boolean sameSource(ManualPaymentTaskLedgerEntry entry, ManualPaymentTaskSourceRef source) {
        return entry.getSourceKind() == source.sourceKind()
                && java.util.Objects.equals(entry.getSourceId(), source.sourceId())
                && entry.getSourceGeneration().equals(source.sourceGeneration());
    }

    private String reservationKey(ManualPaymentTaskSourceRef source) {
        return source.sourceKind().name() + ":" + source.sourceId() + ":" + source.sourceGeneration();
    }

    private String sourceKey(ManualPaymentTaskLedgerEntry entry) {
        return entry.getSourceKind().name() + ":" + entry.getSourceId() + ":" + entry.getSourceGeneration();
    }

    private String defaultActor(String actor) {
        String normalized = limit(actor, MAX_ACTOR);
        return normalized.isBlank() ? "system" : normalized;
    }

    private String requireText(String value, int max, String message) {
        String normalized = normalize(value);
        if (normalized.isBlank() || normalized.length() > max) {
            throw badRequest(message);
        }
        return normalized;
    }

    private String limit(String value, int max) {
        String normalized = normalize(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record LockedArchivedReturnSource(
            ManualPaymentTaskSourceRef source,
            long returnedKopecks
    ) {
    }

    public record LockedLegacyReturnSource(
            ManualPaymentTaskSourceRef source,
            Long taskId,
            long taskGeneration,
            long confirmedKopecks,
            long returnedKopecks,
            String evidenceReference
    ) {
    }

    private long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw conflict("Переполнение суммы платёжного задания");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static final class SourceState {
        private long pending;
        private ManualPaymentTaskAccountingTargetKind latestTargetKind;
    }

    private static final class ConfirmedExposureState {
        private long backedPositive;
        private long unbackedPositive;
        private long negative;
        private boolean hasUnverifiedEntry;
    }
}
