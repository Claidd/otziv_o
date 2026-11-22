package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveCandidateCounts;
import com.hunt.otziv.archive.dto.ArchiveRecoveryWorkerOption;
import com.hunt.otziv.archive.dto.ArchiveRestoreResult;
import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.archive.exception.ArchiveRestoreSpecialistRequiredException;
import com.hunt.otziv.archive.repository.OrderArchiveRestoreRepository;
import com.hunt.otziv.common_billing.api.RestoredOrderInvoiceMembership;
import com.hunt.otziv.payments.api.BankPaperPaymentBindings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderArchiveRestoreService {

    private static final String DEFAULT_TARGET_STATUS = "Новый";
    private static final String PAID_STATUS = "Оплачено";
    /** Statuses a person may choose when returning an archived order to work. */
    public static final Set<String> MANUAL_WORK_TARGETS = Set.of("Новый", "В проверку", "На проверке", "Коррекция");
    /** Restored into these, the order waits for its specialist, so that specialist must still work. */
    private static final Set<String> SPECIALIST_WORK_TARGETS = Set.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация"
    );
    static final String SPECIALIST_DEACTIVATED =
            "Специалист заказа деактивирован — выберите действующего специалиста";
    static final String SPECIALIST_MISSING =
            "У заказа нет специалиста — выберите действующего специалиста";
    static final String SPECIALIST_UNAVAILABLE =
            "Выбранный специалист недоступен — выберите действующего специалиста";
    private static final Set<String> ALLOWED_TARGET_STATUSES = Set.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Архив",
            "Оплачено",
            "Бан"
    );

    /** How a restore returning an order to work treats an archived specialist who no longer works. */
    private enum SpecialistRouting {
        /** A person is present (the site's archive window): refuse with the list and a suggestion. */
        ASK,
        /** Nobody to ask (review-check link, archived common invoice, older app): pick by {@link #suggest}. */
        AUTO
    }

    static final String RULE_ACTIVE = "ACTIVE";
    static final String RULE_FIXED_RECIPIENT = "FIXED_RECIPIENT";
    static final String RULE_TRANSFER_SUCCESSOR = "TRANSFER_SUCCESSOR";
    static final String RULE_COMPANY_SPECIALIST = "COMPANY_SPECIALIST";
    static final String RULE_ONLY_MANAGER_SPECIALIST = "ONLY_MANAGER_SPECIALIST";
    static final String RULE_NO_UNAMBIGUOUS_CANDIDATE = "NO_UNAMBIGUOUS_CANDIDATE";
    /** Bounded walk through the transfer history, as for the next order; guards against cycles too. */
    private static final int MAX_TRANSFER_STEPS = 5;

    /** A specialist chosen without a person and the rule that chose it. */
    public record Suggestion(Long workerId, String rule) {
    }

    private record AutoDecision(Long newWorkerId, Long previousWorkerId, String rule) {
    }

    private final OrderArchiveRestoreRepository repository;
    private final RestoredOrderInvoiceMembership membership;
    private final BankPaperPaymentBindings paperDocuments;

    /**
     * Automatic restore (old review-check link, older mobile app): an order returned to work whose archived
     * specialist no longer works goes to the specialist {@link #suggest} picks — the successor from this company's
     * transfer history, else the only specialist of the company, else the only active specialist of the company's
     * manager. Without an unambiguous pick, or when the payment recipient is already fixed, it keeps the archived
     * specialist as before; the restore is audited and «Контроль менеджера» shows the order.
     */
    @Transactional
    public ArchiveRestoreResult restoreIndividualOrder(Long orderId, String targetStatus, String restoredBy, boolean confirm) {
        ArchiveRestoreResult result = restoreOrder(orderId, targetStatus, restoredBy, confirm);
        attachToInvoiceIfWorking(orderId, result);
        return result;
    }

    /**
     * Restore from the manager's archive window. A person is present to choose, so an order returned
     * to work never lands on a deactivated specialist: without a choice the restore is refused with the
     * list of specialists and the server's suggestion, and with a choice the order and its unfinished
     * work move to that person.
     */
    @Transactional
    public ArchiveRestoreResult restoreIndividualOrderWithActiveSpecialist(
            Long orderId,
            String targetStatus,
            String restoredBy,
            boolean confirm,
            Long targetWorkerId
    ) {
        ArchiveRestoreResult result = restore(orderId, targetStatus, restoredBy, confirm, SpecialistRouting.ASK, targetWorkerId);
        attachToInvoiceIfWorking(orderId, result);
        return result;
    }

    /** Automatic restore of one order of an archived common invoice chain; the specialist rule of {@link #restoreIndividualOrder}. */
    @Transactional
    public ArchiveRestoreResult restoreOrder(
            Long orderId,
            String targetStatus,
            String restoredBy,
            boolean confirm
    ) {
        return restore(orderId, targetStatus, restoredBy, confirm, SpecialistRouting.AUTO, null);
    }

    private ArchiveRestoreResult restore(
            Long orderId,
            String targetStatus,
            String restoredBy,
            boolean confirm,
            SpecialistRouting routing,
            Long targetWorkerId
    ) {
        if (!confirm) {
            throw new IllegalArgumentException("Archive restore requires confirm=true");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("Order id is required");
        }

        String resolvedStatus = normalizeStatus(targetStatus);
        if (!ALLOWED_TARGET_STATUSES.contains(resolvedStatus)) {
            throw new IllegalArgumentException("Unsupported archive restore target status: " + resolvedStatus);
        }
        boolean workTarget = SPECIALIST_WORK_TARGETS.contains(resolvedStatus);
        if (targetWorkerId != null && !workTarget) {
            // A paid or closed order keeps the specialist its salary and history belong to.
            throw new IllegalArgumentException("Специалиста можно выбрать только при возврате заказа в работу");
        }

        Long targetStatusId = repository.findStatusId(resolvedStatus);
        if (targetStatusId == null) {
            throw new IllegalArgumentException("Target order status not found: " + resolvedStatus);
        }

        ArchiveCandidateCounts selected = repository.countArchiveRows(orderId);
        if (selected.orders() != 1) {
            throw new IllegalArgumentException("Archive order not found: " + orderId);
        }
        if (repository.isAlreadyRestored(orderId)) {
            throw new ArchiveRestoreConflictException("Archive order has already been restored: " + orderId);
        }

        ArchiveCandidateCounts conflicts = repository.countLiveConflicts(orderId);
        if (!conflicts.isEmpty()) {
            throw new ArchiveRestoreConflictException("Live rows already exist for archive order " + orderId + ": " + conflicts);
        }

        // Money follows the evidence, never the requested status: an order with an active payment
        // check comes back only as paid, and "paid" is never granted without one. Otherwise a
        // restore would either reactivate salary for an unpaid order or silently undo a payment.
        OrderArchiveRestoreRepository.ArchivePaymentState payment = repository.findPaymentState(orderId);
        boolean activePayment = payment != null && payment.activePaymentCheck();
        boolean targetPaid = PAID_STATUS.equals(resolvedStatus);
        if (targetPaid && !activePayment) {
            throw new ArchiveRestoreConflictException(
                    "В статус «Оплачено» можно восстановить только заказ с действующей оплатой");
        }
        if (!targetPaid && activePayment) {
            throw new ArchiveRestoreConflictException(
                    "Оплаченный архивный заказ восстанавливается только в статус «Оплачено». "
                            + "Чтобы вернуть его в работу, сначала отмените оплату.");
        }

        // Decided before any row is written: a refusal leaves the archive untouched.
        Long newWorkerId = null;
        AutoDecision automatic = null;
        if (workTarget && routing == SpecialistRouting.ASK) {
            newWorkerId = resolveNewSpecialist(orderId, targetWorkerId);
        } else if (workTarget) {
            automatic = resolveAutomaticSpecialist(orderId);
            newWorkerId = automatic.newWorkerId();
        }
        boolean automaticDecision = automatic != null && !RULE_ACTIVE.equals(automatic.rule());

        ArchiveCandidateCounts restored = repository.restoreOrder(orderId, targetStatusId);
        if (!selected.equals(restored)) {
            throw new IllegalStateException("Archive restore verification failed: selected=" + selected + ", restored=" + restored);
        }
        if (!targetPaid) {
            repository.clearCompletionMarkers(orderId);
        }
        if (newWorkerId != null) {
            repository.reassignRestoredOrderWorker(orderId, newWorkerId);
        }

        LocalDateTime restoredAt = LocalDateTime.now();
        String actor = restoredBy == null || restoredBy.isBlank() ? "unknown" : restoredBy.trim();
        String message = "restore completed; targetStatus=" + resolvedStatus + "; selected=" + selected
                + (newWorkerId == null ? "" : "; newWorkerId=" + newWorkerId)
                + (automaticDecision ? "; workerRule=" + automatic.rule() + "; previousWorkerId=" + automatic.previousWorkerId() : "");
        Long batchId = repository.insertRestoreBatch(
                orderId,
                restoredAt,
                actor,
                resolvedStatus,
                restored,
                message
        );
        repository.markArchiveOrderRestored(orderId, batchId, restoredAt, actor);
        if (automaticDecision) {
            repository.auditRestoreWorkerDecision(orderId, automatic.previousWorkerId(), newWorkerId, automatic.rule(), actor);
            if (newWorkerId == null) {
                log.warn("Archive order {} returned to work with its inactive specialist {}: {}",
                        orderId, automatic.previousWorkerId(), automatic.rule());
            }
        }

        ArchiveRestoreResult result = new ArchiveRestoreResult(
                batchId,
                orderId,
                restoredAt,
                actor,
                resolvedStatus,
                selected,
                restored,
                message
        );
        log.info("Archive order restored: {}", result);
        return result;
    }

    /**
     * Returns the specialist the restored order must move to, or null to keep the archived one.
     * The archived specialist stays only while still active; otherwise the person must pick one
     * of the company manager's active specialists.
     */
    private Long resolveNewSpecialist(Long orderId, Long targetWorkerId) {
        OrderArchiveRestoreRepository.ArchiveOrderWorker current = repository.findArchiveOrderWorker(orderId);
        Long currentWorkerId = current == null ? null : current.workerId();
        boolean currentActive = current != null && current.active() && currentWorkerId != null;
        // Issued bank documents or a payment recipient already fixed for the client decide who is paid: the specialist
        // cannot change, so the order keeps its archived one (as before) instead of being stuck between both refusals.
        if (targetWorkerId == null && !currentActive && currentWorkerId != null
                && (paperDocuments.hasDocument(orderId) || repository.hasFrozenLivePaymentRoute(orderId))) {
            log.info("Archive order {} keeps its specialist {}: the payment recipient is fixed", orderId, currentWorkerId);
            return null;
        }
        if (targetWorkerId == null || (currentActive && Objects.equals(targetWorkerId, currentWorkerId))) {
            if (currentActive) {
                return null;
            }
            List<ArchiveRecoveryWorkerOption> options = repository.findRestoreWorkerOptions(orderId);
            throw new ArchiveRestoreSpecialistRequiredException(
                    currentWorkerId == null ? SPECIALIST_MISSING : SPECIALIST_DEACTIVATED,
                    options,
                    suggestedWorkerId(orderId, currentWorkerId, options)
            );
        }

        List<ArchiveRecoveryWorkerOption> options = repository.findRestoreWorkerOptions(orderId);
        boolean eligible = options.stream().anyMatch(option -> Objects.equals(option.id(), targetWorkerId));
        if (!eligible) {
            // Deactivated or moved to another team since the list was shown: offer the fresh list.
            throw new ArchiveRestoreSpecialistRequiredException(SPECIALIST_UNAVAILABLE, options,
                    suggestedWorkerId(orderId, currentWorkerId, options));
        }
        // Same guard as changing the specialist in the order editor: issued bank documents or
        // requisites already given to the client fix who is paid, so the specialist stays.
        if (paperDocuments.hasDocument(orderId)) {
            throw new ArchiveRestoreConflictException(
                    "По заказу выпущены банковские документы. Специалиста нельзя изменить до сверки счёта и акта");
        }
        if (repository.hasFrozenLivePaymentRoute(orderId)) {
            throw new ArchiveRestoreConflictException(
                    "Специалиста нельзя изменить: для заказа уже зафиксирован получатель платежа");
        }
        log.info("Archive order {} returns to work with specialist {} instead of {}", orderId, targetWorkerId, currentWorkerId);
        return targetWorkerId;
    }

    /**
     * The automatic counterpart of {@link #resolveNewSpecialist}: never refuses, because nobody can choose. A working
     * archived specialist stays; a fixed payment recipient (bank documents, requisites given to the client) keeps the
     * archived one; otherwise {@link #suggest} picks, and without an unambiguous pick the archived one stays.
     */
    private AutoDecision resolveAutomaticSpecialist(Long orderId) {
        OrderArchiveRestoreRepository.ArchiveOrderWorker current = repository.findArchiveOrderWorker(orderId);
        Long currentWorkerId = current == null ? null : current.workerId();
        if (current != null && current.active() && currentWorkerId != null) {
            return new AutoDecision(null, currentWorkerId, RULE_ACTIVE);
        }
        if (paperDocuments.hasDocument(orderId) || repository.hasFrozenLivePaymentRoute(orderId)) {
            return new AutoDecision(null, currentWorkerId, RULE_FIXED_RECIPIENT);
        }
        List<ArchiveRecoveryWorkerOption> options = repository.findRestoreWorkerOptions(orderId);
        Suggestion suggestion = suggest(options, transferSuccessor(orderId, currentWorkerId, options));
        return suggestion == null
                ? new AutoDecision(null, currentWorkerId, RULE_NO_UNAMBIGUOUS_CANDIDATE)
                : new AutoDecision(suggestion.workerId(), currentWorkerId, suggestion.rule());
    }

    private Long suggestedWorkerId(Long orderId, Long currentWorkerId, List<ArchiveRecoveryWorkerOption> options) {
        Suggestion suggestion = suggest(options, transferSuccessor(orderId, currentWorkerId, options));
        return suggestion == null ? null : suggestion.workerId();
    }

    /**
     * A live order leaving status «Архив» for work (the client approves or corrects the texts by the review-check link)
     * while its specialist no longer works: the same automatic rule as an archive restore, over the live tables.
     * Null when the pick is not unambiguous.
     */
    @Transactional(readOnly = true)
    public Suggestion suggestForLiveOrder(Long orderId, Long companyId, Long currentWorkerId) {
        List<ArchiveRecoveryWorkerOption> options = repository.findLiveWorkerOptions(orderId);
        return suggest(options, companyTransferSuccessor(companyId, currentWorkerId, options));
    }

    private Long transferSuccessor(Long orderId, Long fromWorkerId, List<ArchiveRecoveryWorkerOption> options) {
        if (fromWorkerId == null || options.isEmpty()) {
            return null;
        }
        return companyTransferSuccessor(repository.findArchiveOrderCompany(orderId), fromWorkerId, options);
    }

    /**
     * Who took over the specialist's work for this company: the latest transfer of this company away from that
     * specialist, followed through further transfers while the receiver is not one of the eligible options.
     */
    private Long companyTransferSuccessor(Long companyId, Long fromWorkerId, List<ArchiveRecoveryWorkerOption> options) {
        if (companyId == null || fromWorkerId == null || options.isEmpty()) {
            return null;
        }
        Set<Long> eligible = options.stream().map(ArchiveRecoveryWorkerOption::id).collect(java.util.stream.Collectors.toSet());
        Set<Long> visited = new java.util.HashSet<>();
        Long worker = fromWorkerId;
        for (int step = 0; step < MAX_TRANSFER_STEPS && worker != null && visited.add(worker); step++) {
            Long next = repository.findTransferTarget(companyId, worker);
            if (next == null || eligible.contains(next)) {
                return next;
            }
            worker = next;
        }
        return null;
    }

    /**
     * The one rule both the automatic restore and the site's suggestion use: the transfer successor when eligible, else
     * the only specialist already attached to the company, else the only active specialist of the company's manager.
     */
    static Suggestion suggest(List<ArchiveRecoveryWorkerOption> options, Long transferSuccessor) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (transferSuccessor != null && options.stream().anyMatch(option -> Objects.equals(option.id(), transferSuccessor))) {
            return new Suggestion(transferSuccessor, RULE_TRANSFER_SUCCESSOR);
        }
        List<ArchiveRecoveryWorkerOption> companyWorkers = options.stream().filter(ArchiveRecoveryWorkerOption::companyWorker).toList();
        if (companyWorkers.size() == 1) {
            return new Suggestion(companyWorkers.getFirst().id(), RULE_COMPANY_SPECIALIST);
        }
        if (options.size() == 1) {
            return new Suggestion(options.getFirst().id(), RULE_ONLY_MANAGER_SPECIALIST);
        }
        return null;
    }

    private void attachToInvoiceIfWorking(Long orderId, ArchiveRestoreResult result) {
        if (MANUAL_WORK_TARGETS.contains(result.targetStatus()) || "Публикация".equals(result.targetStatus())) {
            membership.attachOrderByIdIfNeeded(orderId);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_TARGET_STATUS;
        }
        String trimmed = status.trim();
        return trimmed.length() <= 100 ? trimmed : trimmed.substring(0, 100);
    }
}
