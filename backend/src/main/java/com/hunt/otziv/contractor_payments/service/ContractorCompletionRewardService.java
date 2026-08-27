package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardMarkerRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.performers.service.PerformerProductRewardZpService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates immutable contractor rewards when work is completed. The order row
 * is the cross-node mutex; V1_10_227 is the final database guard against a
 * duplicate source/profession row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorCompletionRewardService {

    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final OrderRepository orderRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewRecoveryGateService recoveryGateService;
    private final ZpRepository zpRepository;
    private final ContractorCompletionRewardMarkerRepository completionMarkerRepository;
    private final ContractorRewardAttributionService attributionService;
    private final ContractorRewardLedgerService rewardLedgerService;
    private final PerformerProductRewardZpService performerProductRewardZpService;
    private final ContractorPaymentBusinessClock businessClock;
    private final ContractorOrderManagerResolver orderManagerResolver;
    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ContractorLegacyRewardGuard legacyRewardGuard;
    private final ContractorLegacyRewardReconciliationService legacyRewardReconciliationService;
    private final ContractorPaymentProfileService profileService;

    /**
     * Ensures every completion source for an order. This method is used by the
     * synchronous publication/payment paths and by bounded repair jobs.
     */
    @Transactional
    public int ensureOrderCompletionAccrual(Long orderId) {
        return ensureOrderCompletionAccrual(orderId, null, false);
    }

    /** Used by synchronous transitions that are completing the work now. */
    @Transactional
    public int ensureOrderCompletionAccrualNow(Long orderId) {
        return ensureOrderCompletionAccrual(orderId, businessClock.today(), false);
    }

    /** Payment-time bridge for work completed before the one-way cutover. */
    @Transactional
    public int ensureOrderPaymentAccrual(Long orderId) {
        return ensureOrderCompletionAccrual(orderId, null, true);
    }

    private int ensureOrderCompletionAccrual(
            Long orderId,
            LocalDate forcedOccurredOn,
            boolean paymentTrigger
    ) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return 0;
        }
        // Every order mutation path already owns this row before it reads the
        // rollout singleton. Keep that canonical order here as well so repair
        // cannot deadlock a concurrent payment transition. The rollout lock
        // still serializes the decision with one-way activation.
        if (rolloutStateService.lockAccountingAuthority()
                != com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority.COMPLETION) {
            return 0;
        }
        Order order = orderRepository.findByIdForOrderDto(orderId).orElse(null);
        if (order == null) {
            return 0;
        }
        List<BadReviewTask> completedTasks = badReviewTaskRepository.findAllByOrderIdAndStatus(
                orderId,
                BadReviewTaskStatus.DONE
        );
        boolean allMarkersPresent = allCompletionMarkersPresent(orderId, completedTasks);
        if (!paymentTrigger && allMarkersPresent) {
            // Immutable markers are the authoritative idempotency boundary.
            // A retry must never re-read today's manager/review composition
            // after every logical source was already frozen.
            synchronizeCompletionSources(orderId);
            return 0;
        }
        requireActuallyCompleted(order);
        LocalDate attributionStart = runtimeSwitch.completionAttributionStartDate().orElse(null);
        if (attributionStart == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не задана дата начала учета выполненных работ");
        }
        if (paymentTrigger && allMarkersPresent
                && hasPostCutoverCompletionEvidence(orderId, attributionStart)) {
            // A post-cutover completion transition already froze the obligation date and recipients.
            // A later payment must not reinterpret historical review dates through the legacy bridge
            // and create a second manager/specialist accrual for the same completed work.
            synchronizeCompletionSources(orderId);
            return 0;
        }

        LocalDate occurredOn = forcedOccurredOn != null
                ? forcedOccurredOn
                : resolveOrderCompletionDate(order, attributionStart);
        if (occurredOn == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Дата выполнения заказа не подтверждена. Нужен датированный перенос начислений"
            );
        }

        requireDatedCompletedTasks(completedTasks);
        List<BadReviewTask> postCutoffTasks = completedTasks.stream()
                .filter(task -> !task.getCompletedDate().isBefore(attributionStart))
                .toList();
        requireValidPostCutoffCompletedTasks(postCutoffTasks);

        int changed = 0;
        if (occurredOn.isBefore(attributionStart)) {
            if (paymentTrigger) {
                return ensurePreCutoffPaymentAccrual(
                        order,
                        completedTasks,
                        postCutoffTasks,
                        occurredOn,
                        attributionStart
                );
            }
            if (!postCutoffTasks.isEmpty()) {
                if (!hasProvablyPreCutoffPublishedBase(order, attributionStart)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Дата выполнения исторического заказа подтверждена не полностью; нужна ручная сверка"
                    );
                }
                legacyRewardGuard.requireOnlyDatedPreCutoffLegacyAggregate(orderId, attributionStart);
            }
            Manager postCutoffTaskManager = null;
            if (!postCutoffTasks.isEmpty()) {
                // The order itself belongs to the signed opening balance, but
                // a later task creates a new obligation. Historical repair
                // may use only an order-frozen manager, never today's company
                // fallback, and must resolve it before any marker is written.
                postCutoffTaskManager = orderManagerResolver.resolve(order, false);
                requireResolvedManager(postCutoffTaskManager);
            }
            // Everything before the accounting boundary is represented by
            // the signed opening balance. Freeze every logical source even
            // when only a subset of legacy earned rows exists; never infer a
            // missing historical recipient from today's order card.
            freezePreCutoffBaseSources(orderId, occurredOn);
            for (BadReviewTask task : completedTasks) {
                changed += ensureCompletedBadReviewTaskLocked(order, postCutoffTaskManager, task, false);
            }
            saveMarker(orderId, ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION, occurredOn);
            synchronizeCompletionSources(orderId);
            return changed;
        }
        try {
            legacyRewardGuard.requireNoActiveLegacyAggregate(orderId);
        } catch (ResponseStatusException conflict) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "После даты начала учета найден ранее созданный или неопознанный источник; "
                            + "нужна датированная ручная корректировка",
                    conflict
            );
        }
        // Resolve only after the opening-balance/legacy branches. Work that
        // creates no recipient rows must not read today's company manager.
        // This one identity is then frozen across base/task/product sources.
        Manager effectiveManager = orderManagerResolver.resolve(order, forcedOccurredOn != null);
        requireResolvedManager(effectiveManager);
        validateStableOrderRewardBasis(order);
        List<ContractorRewardAttributionService.SpecialistShare> specialistShares =
                attributionService.attributeCompletedBaseWork(order);
        performerProductRewardZpService.validateCompletedOrderEvidence(order);
        changed += ensureBaseOrderRewards(order, effectiveManager, occurredOn, false, specialistShares);
        for (BadReviewTask task : completedTasks) {
            changed += ensureCompletedBadReviewTaskLocked(order, effectiveManager, task, false);
        }
        changed += performerProductRewardZpService.accrueForCompletedOrderLocked(
                order,
                effectiveManager,
                occurredOn,
                false
        );
        synchronizeCompletionSources(orderId);
        return changed;
    }

    /** Called immediately after a task is persisted as DONE and before a new invoice is prepared. */
    @Transactional
    public int ensureCompletedBadReviewTask(BadReviewTask task) {
        if (task == null
                || task.getId() == null
                || task.getStatus() != BadReviewTaskStatus.DONE
                || task.getOrder() == null
                || task.getOrder().getId() == null) {
            return 0;
        }
        Long orderId = task.getOrder().getId();
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return 0;
        }
        if (rolloutStateService.lockAccountingAuthority()
                != com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority.COMPLETION) {
            return 0;
        }
        Order order = orderRepository.findByIdForOrderDto(orderId).orElse(task.getOrder());
        LocalDate attributionStart = runtimeSwitch.completionAttributionStartDate().orElse(null);
        boolean postCutoffTask = attributionStart != null
                && task.getCompletedDate() != null
                && !task.getCompletedDate().isBefore(attributionStart);
        if (postCutoffTask) {
            if (hasProvablyPreCutoffPublishedBase(order, attributionStart)) {
                legacyRewardGuard.requireOnlyDatedPreCutoffLegacyAggregate(orderId, attributionStart);
            } else {
                legacyRewardGuard.requireNoActiveLegacyAggregate(orderId);
            }
        }
        Manager effectiveManager = attributionStart != null
                && task.getCompletedDate() != null
                && !task.getCompletedDate().isBefore(attributionStart)
                ? orderManagerResolver.resolve(order, true)
                : null;
        if (attributionStart != null
                && task.getCompletedDate() != null
                && !task.getCompletedDate().isBefore(attributionStart)) {
            requireResolvedManager(effectiveManager);
        }
        int changed = ensureCompletedBadReviewTaskLocked(order, effectiveManager, task, false);
        synchronizeCompletionSources(orderId);
        return changed;
    }

    /**
     * Preserves the original completion-month rows and appends a cancellation-
     * date negative adjustment for the same immutable recipients.
     */
    @Transactional
    public int adjustCanceledBadReviewTaskAccrual(Long orderId, Long taskId) {
        if (orderId == null
                || orderId <= 0
                || taskId == null
                || taskId <= 0) {
            return 0;
        }
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return 0;
        }
        if (rolloutStateService.lockAccountingAuthority()
                != com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority.COMPLETION) {
            return 0;
        }
        String markerSource = ContractorRewardSourceCodes.badReviewCancelMarker(taskId);
        if (marker(orderId, markerSource) != null) {
            synchronizeActiveSource(orderId, ContractorRewardSourceCodes.badReviewCancelManager(taskId));
            synchronizeActiveSource(orderId, ContractorRewardSourceCodes.badReviewCancelSpecialist(taskId));
            synchronizeCompletionSources(orderId);
            return 0;
        }
        List<Zp> originals = new ArrayList<>();
        originals.addAll(zpRepository.findByOrderIdAndSourceAndActiveTrue(
                orderId,
                ContractorRewardSourceCodes.badReviewManager(taskId)
        ));
        ContractorCompletionRewardMarker doneMarker = marker(
                orderId,
                ContractorRewardSourceCodes.badReviewDoneMarker(taskId)
        );
        originals.addAll(zpRepository.findByOrderIdAndSourceAndActiveTrue(
                orderId,
                ContractorRewardSourceCodes.badReviewSpecialist(taskId)
        ));
        LocalDate attributionStart = runtimeSwitch.completionAttributionStartDate().orElse(null);
        if (originals.isEmpty() && doneMarker != null) {
            if (attributionStart != null && doneMarker.getOccurredOn().isBefore(attributionStart)) {
                int changed = adjustPreCutoffCanceledBadReviewOpeningBalance(orderId, taskId, doneMarker);
                saveMarker(orderId, markerSource, businessClock.today());
                synchronizeCompletionSources(orderId);
                return changed;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для выполненной дополнительной работы есть маркер без исходного начисления; "
                            + "нужна ручная сверка"
            );
        }
        int changed = 0;
        LocalDate occurredOn = businessClock.today();
        for (Zp original : originals) {
            String adjustmentSource = original.getContractorRole() == ContractorRole.MANAGER
                    ? ContractorRewardSourceCodes.badReviewCancelManager(taskId)
                    : ContractorRewardSourceCodes.badReviewCancelSpecialist(taskId);
            changed += ensureAdjustment(original, adjustmentSource, occurredOn);
        }
        saveMarker(orderId, markerSource, occurredOn);
        synchronizeCompletionSources(orderId);
        return changed;
    }

    /**
     * A payment made before the completion cutover may only have legacy,
     * payment-dependent rows. Before canceling that payment, materialize the
     * equivalent immutable work sources in the same transaction so the earned
     * obligation is not lost when legacy rows are deactivated.
     */
    @Transactional
    public int migrateLegacyRewardsBeforePaymentCancellation(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return 0;
        }
        if (rolloutStateService.lockAccountingAuthority()
                != com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority.COMPLETION) {
            return 0;
        }
        Order order = orderRepository.findByIdForOrderDto(orderId).orElse(null);
        if (order == null) {
            return 0;
        }
        legacyRewardGuard.requireCancellationClassifiable(orderId);
        if (!hasLegacyAggregateReward(orderId)) {
            return 0;
        }
        requireActuallyCompleted(order);
        LocalDate attributionStart = runtimeSwitch.completionAttributionStartDate().orElse(null);
        if (attributionStart == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не задана дата начала учета выполненных работ");
        }
        LocalDate occurredOn = resolveOrderCompletionDate(order, attributionStart);
        if (occurredOn == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Дата выполнения исторического заказа не подтверждена. "
                            + "Перед отменой оплаты нужен датированный перенос начислений"
            );
        }
        if (!occurredOn.isBefore(attributionStart)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Legacy-источник после даты начала учета нельзя переносить автоматически; "
                            + "нужна датированная ручная корректировка"
            );
        }
        List<BadReviewTask> completedTasks = badReviewTaskRepository.findAllByOrderIdAndStatus(
                orderId,
                BadReviewTaskStatus.DONE
        );
        requireDatedCompletedTasks(completedTasks);
        if (completedTasks.stream().anyMatch(task -> !task.getCompletedDate().isBefore(attributionStart))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "После даты начала учета есть выполненная дополнительная работа, "
                            + "которая могла войти в ранее созданное начисление; нужна ручная сверка"
            );
        }
        // Legacy order/product sources represent an earned obligation. They
        // may already be included in the imported opening balance; replacing
        // them with new post-watermark ids would double-count the debt. Freeze
        // logical completion markers only and keep the original rows active.
        freezePreCutoffBaseSources(orderId, occurredOn);
        for (BadReviewTask task : completedTasks) {
            saveMarker(
                    orderId,
                    ContractorRewardSourceCodes.badReviewDoneMarker(task.getId()),
                    task.getCompletedDate()
            );
        }
        saveMarker(orderId, ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION, occurredOn);
        synchronizeCompletionSources(orderId);
        return 0;
    }

    public static boolean isCompletionBasedSource(String source) {
        return ContractorRewardSourceCodes.isCompletionBased(source);
    }

    private int ensurePreCutoffPaymentAccrual(
            Order order,
            List<BadReviewTask> completedTasks,
            List<BadReviewTask> postCutoffTasks,
            LocalDate completedOn,
            LocalDate attributionStart
    ) {
        // An incomplete order cannot legitimately have an old paid-only row.
        // Treat any such row (including null/unknown sources) as ambiguous
        // instead of filling gaps around it and risking a double obligation.
        legacyRewardGuard.requireNoUnclassifiedActiveRows(order.getId());
        validateStableOrderRewardBasis(order);

        Manager frozenManager = orderManagerResolver.resolve(order, false);
        requireResolvedManager(frozenManager);
        List<ContractorRewardAttributionService.SpecialistShare> baseShares =
                attributionService.attributeCompletedBaseWork(order);
        List<BadReviewTask> preCutoffTasks = completedTasks.stream()
                .filter(task -> task.getCompletedDate().isBefore(attributionStart))
                .toList();
        requireValidPostCutoffCompletedTasks(preCutoffTasks);

        BigDecimal legacyGross = money(order.getSum()).add(preCutoffTasks.stream()
                .map(BadReviewTask::getPrice)
                .map(this::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        LocalDate paymentDate = businessClock.today();
        int changed = ensureReward(
                order,
                frozenManager.getUser(),
                frozenManager.getId(),
                legacyGross.multiply(coefficient(frozenManager.getUser())),
                Math.max(0, order.getAmount()) + preCutoffTasks.size(),
                ContractorRole.MANAGER,
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                legacyGross,
                paymentDate
        );

        Map<Long, LegacySpecialistShare> shares = new LinkedHashMap<>();
        for (ContractorRewardAttributionService.SpecialistShare share : baseShares) {
            mergeLegacyShare(shares, share.user(), share.workerId(), share.grossAmount(), share.workUnits());
        }
        for (BadReviewTask task : preCutoffTasks) {
            Worker worker = task.getWorker();
            mergeLegacyShare(shares, worker.getUser(), worker.getId(), task.getPrice(), 1);
        }
        for (LegacySpecialistShare share : shares.values()) {
            changed += ensureReward(
                    order,
                    share.user(),
                    share.workerId(),
                    money(share.gross()).multiply(coefficient(share.user())),
                    share.workUnits(),
                    ContractorRole.SPECIALIST,
                    ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST,
                    share.gross(),
                    paymentDate
            );
        }

        changed += performerProductRewardZpService.accrueForPreCutoffPaymentLocked(
                order,
                frozenManager,
                paymentDate
        );
        freezePreCutoffBaseSources(order.getId(), completedOn);
        for (BadReviewTask task : preCutoffTasks) {
            saveMarker(
                    order.getId(),
                    ContractorRewardSourceCodes.badReviewDoneMarker(task.getId()),
                    task.getCompletedDate()
            );
        }
        for (BadReviewTask task : postCutoffTasks) {
            changed += ensureCompletedBadReviewTaskLocked(order, frozenManager, task, false);
        }
        saveMarker(order.getId(), ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION, completedOn);
        synchronizePaymentBridgeSources(order.getId());
        return changed;
    }

    private void mergeLegacyShare(
            Map<Long, LegacySpecialistShare> shares,
            User user,
            Long workerId,
            BigDecimal gross,
            int workUnits
    ) {
        if (user == null || user.getId() == null || workerId == null || money(gross).signum() <= 0) {
            throw unverifiableCompletedTaskWorker();
        }
        LegacySpecialistShare existing = shares.get(workerId);
        if (existing != null && !existing.user().getId().equals(user.getId())) {
            throw unverifiableCompletedTaskWorker();
        }
        shares.put(workerId, existing == null
                ? new LegacySpecialistShare(user, workerId, money(gross), Math.max(0, workUnits))
                : new LegacySpecialistShare(
                        existing.user(),
                        workerId,
                        money(existing.gross()).add(money(gross)),
                        existing.workUnits() + Math.max(0, workUnits)
                ));
    }

    private int ensureBaseOrderRewards(
            Order order,
            Manager effectiveManager,
            LocalDate occurredOn,
            boolean overrideEmptyMarker,
            List<ContractorRewardAttributionService.SpecialistShare> specialistShares
    ) {
        int changed = 0;
        if (marker(order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER) != null
                && !(overrideEmptyMarker && !hasActiveSource(
                        order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER))) {
            synchronizeActiveSource(order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER);
        } else {
            if (effectiveManager != null) {
                BigDecimal gross = money(order.getSum());
                changed += ensureReward(
                        order,
                        effectiveManager.getUser(),
                        effectiveManager.getId(),
                        gross.multiply(coefficient(effectiveManager.getUser())),
                        Math.max(0, order.getAmount()),
                        ContractorRole.MANAGER,
                        ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER,
                        gross,
                        occurredOn
                );
            }
            saveMarker(order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER, occurredOn);
        }

        if (marker(order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST) != null
                && !(overrideEmptyMarker && !hasActiveSource(
                        order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST))) {
            synchronizeActiveSource(order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST);
            return changed;
        }
        if (specialistShares == null || specialistShares.isEmpty()) {
            throw unverifiableCompletedTaskWorker();
        }
        for (ContractorRewardAttributionService.SpecialistShare share : specialistShares) {
            if (share == null
                    || share.user() == null
                    || share.user().getId() == null
                    || share.workerId() == null) {
                throw unverifiableCompletedTaskWorker();
            }
            changed += ensureReward(
                    order,
                    share.user(),
                    share.workerId(),
                    money(share.grossAmount()).multiply(coefficient(share.user())),
                    Math.max(0, share.workUnits()),
                    ContractorRole.SPECIALIST,
                    ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST,
                    money(share.grossAmount()),
                    occurredOn
            );
        }
        saveMarker(order.getId(), ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST, occurredOn);
        return changed;
    }

    private int ensureCompletedBadReviewTaskLocked(
            Order order,
            Manager effectiveManager,
            BadReviewTask task,
            boolean bypassCutoff
    ) {
        if (task == null
                || task.getId() == null
                || task.getStatus() != BadReviewTaskStatus.DONE
                || order == null
                || order.getId() == null) {
            return 0;
        }
        LocalDate occurredOn = task.getCompletedDate();
        if (occurredOn == null) {
            throw undatedCompletedTask();
        }
        LocalDate attributionStart = runtimeSwitch.completionAttributionStartDate().orElse(null);
        if (attributionStart == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не задана дата начала учета выполненных работ");
        }
        String markerSource = ContractorRewardSourceCodes.badReviewDoneMarker(task.getId());
        if (!bypassCutoff && occurredOn.isBefore(attributionStart)) {
            if (marker(order.getId(), markerSource) == null) {
                saveMarker(order.getId(), markerSource, occurredOn);
            }
            return 0;
        }
        requireValidPostCutoffCompletedTasks(List.of(task));
        if (marker(order.getId(), markerSource) != null) {
            synchronizeActiveSource(order.getId(), ContractorRewardSourceCodes.badReviewManager(task.getId()));
            synchronizeActiveSource(order.getId(), ContractorRewardSourceCodes.badReviewSpecialist(task.getId()));
            return 0;
        }
        BigDecimal gross = money(task.getPrice());
        int changed = 0;
        if (effectiveManager != null) {
            changed += ensureReward(
                    order,
                    effectiveManager.getUser(),
                    effectiveManager.getId(),
                    gross.multiply(coefficient(effectiveManager.getUser())),
                    1,
                    ContractorRole.MANAGER,
                    ContractorRewardSourceCodes.badReviewManager(task.getId()),
                    gross,
                    occurredOn
            );
        }
        Worker taskWorker = task.getWorker();
        if (taskWorker != null && taskWorker.getId() != null && taskWorker.getUser() != null) {
            changed += ensureReward(
                    order,
                    taskWorker.getUser(),
                    taskWorker.getId(),
                    gross.multiply(coefficient(taskWorker.getUser())),
                    1,
                    ContractorRole.SPECIALIST,
                    ContractorRewardSourceCodes.badReviewSpecialist(task.getId()),
                    gross,
                    occurredOn
            );
        }
        saveMarker(order.getId(), markerSource, occurredOn);
        return changed;
    }

    private void requireDatedCompletedTasks(List<BadReviewTask> completedTasks) {
        if (completedTasks == null) {
            return;
        }
        for (BadReviewTask task : completedTasks) {
            if (task == null || task.getCompletedDate() == null) {
                throw undatedCompletedTask();
            }
        }
    }

    private void requireValidPostCutoffCompletedTasks(List<BadReviewTask> completedTasks) {
        if (completedTasks == null) {
            return;
        }
        for (BadReviewTask task : completedTasks) {
            Worker worker = task == null ? null : task.getWorker();
            if (task == null
                    || task.getId() == null
                    || task.getStatus() != BadReviewTaskStatus.DONE
                    || task.getPrice() == null
                    || task.getPrice().signum() <= 0
                    || worker == null
                    || worker.getId() == null
                    || worker.getUser() == null
                    || worker.getUser().getId() == null) {
                throw unverifiableCompletedTaskWorker();
            }
        }
    }

    private ResponseStatusException unverifiableCompletedTaskWorker() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Исполнитель выполненной дополнительной работы не подтвержден; нужна ручная сверка"
        );
    }

    private void requireResolvedManager(Manager manager) {
        if (manager == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для выполненной работы не зафиксирован менеджер; нужна ручная сверка"
            );
        }
    }

    private ResponseStatusException undatedCompletedTask() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Дата выполнения дополнительной работы не подтверждена; нужна датированная ручная корректировка"
        );
    }

    private int adjustPreCutoffCanceledBadReviewOpeningBalance(
            Long orderId,
            Long taskId,
            ContractorCompletionRewardMarker doneMarker
    ) {
        BadReviewTask task = badReviewTaskRepository.findByIdForMutation(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Выполненная дополнительная работа не найдена; нужна ручная сверка"
                ));
        Long actualOrderId = task.getOrder() == null ? null : task.getOrder().getId();
        if (!Objects.equals(actualOrderId, orderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Дополнительная работа относится к другому заказу; нужна ручная сверка"
            );
        }
        if (task.getCompletedDate() == null
                || doneMarker.getOccurredOn() == null
                || !doneMarker.getOccurredOn().equals(task.getCompletedDate())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Дата выполненной дополнительной работы изменилась; нужна ручная сверка"
            );
        }
        BigDecimal gross = money(task.getPrice());
        if (gross.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сумма выполненной дополнительной работы не подтверждена; нужна ручная сверка"
            );
        }
        Order order = task.getOrder();
        Manager manager = orderManagerResolver.resolve(order, false);
        Worker worker = task.getWorker();
        if (worker == null || worker.getId() == null || worker.getUser() == null || worker.getUser().getId() == null) {
            throw unverifiableCompletedTaskWorker();
        }
        int changed = 0;
        changed += applyOpeningBalanceBadReviewCancellation(
                manager.getUser(),
                ContractorRole.MANAGER,
                gross.multiply(coefficient(manager.getUser())),
                orderId,
                taskId
        );
        changed += applyOpeningBalanceBadReviewCancellation(
                worker.getUser(),
                ContractorRole.SPECIALIST,
                gross.multiply(coefficient(worker.getUser())),
                orderId,
                taskId
        );
        return changed;
    }

    private int applyOpeningBalanceBadReviewCancellation(
            User user,
            ContractorRole role,
            BigDecimal amount,
            Long orderId,
            Long taskId
    ) {
        long amountKopecks = kopecks(amount);
        if (amountKopecks <= 0L) {
            return 0;
        }
        long appliedDeltaKopecks = profileService.applySystemOpeningBalanceDelta(
                user.getId(),
                role,
                -amountKopecks,
                "Автокорректировка переходящего остатка: плохая задача #" + taskId
                        + " удалена из счета заказа #" + orderId
        );
        return appliedDeltaKopecks == 0L ? 0 : 1;
    }

    private int ensureAdjustment(Zp original, String source, LocalDate occurredOn) {
        if (original == null
                || original.getOrderId() == null
                || original.getProfessionId() == null
                || original.getContractorRole() == null
                || original.getSum() == null
                || original.getSum().signum() == 0) {
            return 0;
        }
        Zp existing = zpRepository
                .findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
                        original.getOrderId(),
                        source,
                        original.getContractorRole(),
                        original.getProfessionId()
                )
                .orElse(null);
        if (existing != null) {
            return 0;
        }
        Zp adjustment = new Zp();
        adjustment.setFio(original.getFio());
        adjustment.setSum(money(original.getSum()).negate());
        adjustment.setOrderId(original.getOrderId());
        adjustment.setUserId(original.getUserId());
        adjustment.setProfessionId(original.getProfessionId());
        adjustment.setAmount(-Math.abs(original.getAmount()));
        adjustment.setActive(true);
        adjustment.setSource(source);
        adjustment.setContractorRole(original.getContractorRole());
        adjustment.setAttributionFinal(true);
        adjustment.setRewardBasis(original.getRewardBasis() == null
                ? null
                : money(original.getRewardBasis()).negate());
        adjustment.setCreated(occurredOn);
        zpRepository.save(adjustment);
        return 1;
    }

    private int ensureReward(
            Order order,
            User user,
            Long professionId,
            BigDecimal amount,
            int workUnits,
            ContractorRole role,
            String source,
            BigDecimal grossBasis,
            LocalDate occurredOn
    ) {
        if (order == null
                || order.getId() == null
                || user == null
                || user.getId() == null
                || professionId == null
                || amount == null
                || money(amount).signum() <= 0) {
            return 0;
        }
        Zp existing = zpRepository
                .findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
                        order.getId(), source, role, professionId
                )
                .orElse(null);
        if (existing != null) {
            if (!existing.isActive()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Историческое начисление было скорректировано и не может быть восстановлено автоматически"
                );
            }
            return 0;
        }

        Zp reward = new Zp();
        reward.setFio(user.getFio());
        reward.setSum(money(amount));
        reward.setOrderId(order.getId());
        reward.setUserId(user.getId());
        reward.setProfessionId(professionId);
        reward.setAmount(Math.max(0, workUnits));
        reward.setActive(true);
        reward.setSource(source);
        reward.setContractorRole(role);
        reward.setAttributionFinal(true);
        reward.setRewardBasis(money(grossBasis));
        reward.setCreated(occurredOn);
        zpRepository.save(reward);

        // LIVE routing may execute later in the same transaction. Therefore
        // the ledger must be materialized synchronously, not in afterCommit.
        log.info(
                "Создано начисление за выполненную работу: orderId={}, source={}, role={}, professionId={}",
                order.getId(), source, role, professionId
        );
        return 1;
    }

    private boolean hasLegacyAggregateReward(Long orderId) {
        return zpRepository.existsByOrderIdAndSourceAndActiveTrue(
                orderId,
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER
        ) || zpRepository.existsByOrderIdAndSourceAndActiveTrue(
                orderId,
                ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST
        ) || zpRepository.existsByOrderIdAndSourceAndActiveTrue(
                orderId,
                ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT
        );
    }

    private boolean hasActiveSource(Long orderId, String source) {
        return !zpRepository.findByOrderIdAndSourceAndActiveTrue(orderId, source).isEmpty();
    }

    private boolean allCompletionMarkersPresent(Long orderId, List<BadReviewTask> completedTasks) {
        if (marker(orderId, ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER) == null
                || marker(orderId, ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST) == null
                || marker(orderId, ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION) == null) {
            return false;
        }
        if (completedTasks == null) {
            return true;
        }
        for (BadReviewTask task : completedTasks) {
            if (task == null
                    || task.getId() == null
                    || marker(orderId, ContractorRewardSourceCodes.badReviewDoneMarker(task.getId())) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPostCutoverCompletionEvidence(Long orderId, LocalDate attributionStart) {
        ContractorCompletionRewardMarker managerMarker = marker(
                orderId,
                ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER
        );
        ContractorCompletionRewardMarker specialistMarker = marker(
                orderId,
                ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST
        );
        boolean postCutoverMarkers = managerMarker != null
                && specialistMarker != null
                && managerMarker.getOccurredOn() != null
                && specialistMarker.getOccurredOn() != null
                && !managerMarker.getOccurredOn().isBefore(attributionStart)
                && !specialistMarker.getOccurredOn().isBefore(attributionStart);
        return postCutoverMarkers
                || hasActiveSource(orderId, ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER)
                || hasActiveSource(orderId, ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST)
                || hasActiveSource(orderId, ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION);
    }

    private void freezePreCutoffBaseSources(Long orderId, LocalDate occurredOn) {
        saveMarker(orderId, ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER, occurredOn);
        saveMarker(orderId, ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST, occurredOn);
    }

    private void validateStableOrderRewardBasis(Order order) {
        BigDecimal detailTotal = order.getDetails() == null
                ? BigDecimal.ZERO
                : order.getDetails().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(detail -> detail.getPrice() == null ? BigDecimal.ZERO : detail.getPrice())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal canonical = order.getSum();
        if (canonical == null
                || canonical.signum() <= 0
                || (detailTotal.signum() > 0 && canonical.compareTo(detailTotal) != 0)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сумма заказа не совпадает с составом работ; вознаграждение и платежный маршрут не зафиксированы"
            );
        }
    }

    private ContractorCompletionRewardMarker marker(Long orderId, String logicalSource) {
        return completionMarkerRepository.findByOrderIdAndLogicalSource(orderId, logicalSource).orElse(null);
    }

    private void saveMarker(Long orderId, String logicalSource, LocalDate occurredOn) {
        if (marker(orderId, logicalSource) != null) {
            return;
        }
        ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
        marker.setOrderId(orderId);
        marker.setLogicalSource(logicalSource);
        marker.setOccurredOn(occurredOn);
        completionMarkerRepository.save(marker);
    }

    private void synchronizeActiveSource(Long orderId, String source) {
        // Logical markers short-circuit mutable attribution. The caller runs
        // one canonical batch after every source has been collected.
    }

    private void synchronizeCompletionSources(Long orderId) {
        List<Zp> rewards = zpRepository.findByOrderIdAndActiveTrue(orderId).stream()
                .filter(reward -> ContractorRewardSourceCodes.isCompletionBased(reward.getSource()))
                .toList();
        if (!rewards.isEmpty()) {
            rewardLedgerService.synchronizeCompletionSourcesCanonical(rewards);
        }
    }

    private void synchronizePaymentBridgeSources(Long orderId) {
        List<Zp> rewards = zpRepository.findByOrderIdAndActiveTrue(orderId).stream()
                .filter(reward -> ContractorRewardSourceCodes.isCompletionBased(reward.getSource())
                        || ContractorRewardSourceCodes.isLegacyEarnedReward(reward.getSource()))
                .toList();
        if (!rewards.isEmpty()) {
            rewardLedgerService.synchronizeCompletionSourcesCanonical(rewards);
        }
    }

    private LocalDate resolveOrderCompletionDate(Order order, LocalDate attributionStart) {
        Optional<LocalDate> attested = legacyRewardReconciliationService.authoritativeCompletedOn(
                order.getId(), attributionStart
        );
        if (attested.isPresent()) {
            // Exact signed evidence is an override, not merely a fallback:
            // planned/future review dates (e.g. typed legacy order 25820)
            // must not turn an already-paid pre-cutoff base into new debt.
            return attested.get();
        }
        LocalDate lastPublished = reviewRepository.getAllByOrderId(order.getId()).stream()
                .filter(Review::isPublish)
                .map(Review::getPublishedDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (lastPublished != null) {
            return lastPublished;
        }
        String status = order.getStatus() == null ? "" : order.getStatus().getTitle();
        if (("Опубликовано".equals(status)
                || "Выставлен счет".equals(status)
                || "Ожидает общего счета".equals(status))
                && order.getStatusChangedAt() != null) {
            return order.getStatusChangedAt().toLocalDate();
        }
        return null;
    }

    private boolean hasProvablyPreCutoffPublishedBase(Order order, LocalDate attributionStart) {
        if (order == null || order.getId() == null || attributionStart == null || order.getAmount() <= 0) {
            return false;
        }
        List<Review> published = reviewRepository.getAllByOrderId(order.getId()).stream()
                .filter(Review::isPublish)
                .toList();
        return published.size() == order.getAmount()
                && published.stream().allMatch(review -> review.getPublishedDate() != null
                        && review.getPublishedDate().isBefore(attributionStart));
    }

    private void requireActuallyCompleted(Order order) {
        if (order == null || order.getId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ не найден для начисления");
        }
        int required = order.getAmount();
        int published = reviewRepository.countPublishedByOrderId(order.getId());
        if (required <= 0
                || published != required
                || recoveryGateService.hasActiveRecoveryTasks(order.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Начисление возможно только после фактического завершения всех работ"
            );
        }
    }

    private record LegacySpecialistShare(
            User user,
            Long workerId,
            BigDecimal gross,
            int workUnits
    ) {
    }

    private BigDecimal coefficient(User user) {
        return user == null || user.getCoefficient() == null ? BigDecimal.ZERO : user.getCoefficient();
    }

    private long kopecks(BigDecimal value) {
        return money(value).movePointRight(2).longValueExact();
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
