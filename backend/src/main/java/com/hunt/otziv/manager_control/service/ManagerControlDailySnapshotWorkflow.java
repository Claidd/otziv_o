package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.service.ManagerControlSlaPolicy.SlaWindow;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlOverdueStatusResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlProblemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlSectionResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlWorkerExplanationStatsResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.model.ManagerDailyControlStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlDailySnapshotWorkflow {

    private final ManagerControlDayLifecycle dayLifecycle;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlSlaPolicy slaPolicy;

    private final ManagerControlWorkerTaskLookup workerTaskLookup;

    private final ManagerControlWorkerExplanationQueries workerExplanationQueries;

    private final ManagerControlOrderAutomationDiagnostics orderAutomationDiagnostics;

    static final List<String> ORDER_ATTENTION_STATUSES = List.of("Новый", "В проверку", "На проверке", "Коррекция", "Публикация", "Ожидает общего счета", "Выставлен счет", "Напоминание", "Требует внимания", "Не оплачено");

    private final OrderService orderService;

    private final ClientChatMessageTrackerService clientChatMessageTrackerService;

    private final BadReviewTaskService badReviewTaskService;

    private final ReviewRecoveryTaskService reviewRecoveryTaskService;

    private final ReviewService reviewService;

    private final ReviewRepository reviewRepository;

    private final OrderRepository orderRepository;

    private final CompanyRepository companyRepository;

    private final ManagerControlInvoiceDiagnostics invoiceDiagnostics;

    private final ManagerAutomationFailureService managerAutomationFailureService;

    private final WorkerRiskIncidentRepository riskIncidentRepository;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlItemRepository dailyControlItemRepository;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    private final ManagerActionBalanceService managerActionBalanceService;

    private final ManagerOperationalMetricsService managerOperationalMetricsService;

    private final LeadsRepository leadsRepository;

    boolean autoCloseControlIfReady(ManagerDailyControl control, LocalDateTime now) {
        if (control == null || control.getId() == null || control.getClosedAt() != null) {
            return false;
        }
        if (now == null || !isAutoCloseWindowForControl(control, now)) {
            return false;
        }
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        if (!closeBlockers(control, items).isEmpty()) {
            return false;
        }
        closeControl(control, items, now, null, "Контроль закрыт автоматически: блокеров нет в вечернем окне");
        return true;
    }

    boolean isAutoCloseWindowForControl(ManagerDailyControl control, LocalDateTime now) {
        LocalDate controlDate = control.getControlDate();
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (controlDate.equals(today) && !time.isBefore(FINAL_STAGE_START)) {
            return true;
        }
        return controlDate.equals(today.minusDays(1)) && time.isBefore(MORNING_STAGE_START);
    }

    ManagerDailyControl transientControl(Manager manager, LocalDate today) {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setControlDate(today);
        control.setManager(manager);
        control.setManagerUserId(manager == null || manager.getUser() == null ? null : manager.getUser().getId());
        control.setStatus(ManagerDailyControlStatus.IN_PROGRESS);
        control.setQualityScore(100);
        control.setQualityGrade("A");
        return control;
    }

    List<String> closeBlockers(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.closeBlockers(control, items);
    }

    void closeControl(ManagerDailyControl control, List<ManagerDailyControlItem> items, LocalDateTime now, Long actorUserId, String comment) {
        dayLifecycle.closeControl(control, items, now, actorUserId, comment);
    }

    boolean reopenClosedControlIfNeeded(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.reopenClosedControlIfNeeded(control, items);
    }

    List<ManagerDailyControlItem> activeControlItems(List<ManagerDailyControlItem> items) {
        return dayLifecycle.activeControlItems(items);
    }

    boolean updateQuality(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.updateQuality(control, items);
    }

    ManagerControlManagerResponse managerControl(Manager manager, LocalDate today, ManagerPerformanceScoreResponse managerPerformance, boolean persist, boolean includeOperationalMetrics) {
        User user = manager.getUser();
        Map<String, Integer> orderCounts = safeMap(orderService.countOrdersByStatusToManager(manager));
        WorkerSectionCounts workerCounts = workerSectionCounts(manager, today);
        List<ManagerControlOverdueStatusResponse> overdueStatuses = overdueStatuses(manager, today);
        long overdueOrders = overdueStatuses.stream().mapToLong(ManagerControlOverdueStatusResponse::count).sum();
        long openRisks = openRiskCount(manager);
        long orderAttention = sum(orderCounts, ORDER_ATTENTION_STATUSES);
        long workerSectionTotal = workerCounts.total();
        long workerActionCount = workerCounts.actionTotal();
        long workerWorkloadCount = workerCounts.workloadTotal();
        long requiresAttention = orderCounts.getOrDefault("Требует внимания", 0);
        List<ManagerAutomationFailureService.AutomationFailureIssue> automationFailures = managerAutomationFailureService.issues(manager, 10_000);
        Set<Long> automationInvoiceIds = automationFailures.stream().map(ManagerAutomationFailureService.AutomationFailureIssue::commonInvoiceId).filter(Objects::nonNull).collect(Collectors.toSet());
        long automationFailureCount = automationFailures.size();
        long commonInvoiceActionCount = invoiceDiagnostics.countActions(manager, automationInvoiceIds);
        long publicationDateIssueCount = reviewRepository.countPublicationDateIssuesByManager(manager);
        long chatBindingIssueCount = companyRepository.countChatBindingIssuesByManager(manager);
        long paymentIntegrityIssueCount = orderRepository.countPaymentIntegrityIssuesByManager(manager, PAYMENT_AUTOMATION_STATUSES);
        long telegramChatIssueCount = telegramChatIssueCompanies(manager, 10_000).size();
        long unansweredClientMessages = clientChatMessageTrackerService.countDue(manager);
        long suspiciousClientClosures = clientChatMessageTrackerService.countAuditRequired(manager);
        long leadActionCount = leadsRepository.countByLidStatusAndManager("Новый", manager) + leadsRepository.countByLidStatusAndManager("В работу", manager) + leadsRepository.countByLidStatusAndManagerAndDateNewTryLessThanEqual("Напоминание", manager, today);
        long leadsInWork = leadsRepository.countByLidStatusAndManager("В работе", manager);
        List<ManagerControlProblemResponse> problems = new ArrayList<>();
        addProblem(problems, "OVERDUE_ORDERS", "Просроченные заказы", overdueOrders, "CRITICAL", "ACTION", "schedule", ordersUrl(manager, null));
        addProblem(problems, "OPEN_RISKS", "Риски", openRisks, "CRITICAL", "ACTION", "warning", "/worker/risk");
        addProblem(problems, "REQUIRES_ATTENTION", "Требует внимания", requiresAttention, "CRITICAL", "ACTION", "error", ordersUrl(manager, "Требует внимания"));
        addProblem(problems, "AUTOMATION_FAILURES", "Ошибки счетов и сообщений", automationFailureCount, "CRITICAL", "ACTION", "sync_problem", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "COMMON_INVOICES", "Общие счета", commonInvoiceActionCount, "CRITICAL", "ACTION", "receipt_long", "/admin/common-billing");
        addProblem(problems, "PAYMENT_INTEGRITY", "Повторная оплата", paymentIntegrityIssueCount, "CRITICAL", "ACTION", "payments", ordersUrl(manager, null));
        addProblem(problems, "PUBLICATION_DATE_ISSUES", "Публикация без даты", publicationDateIssueCount, "CRITICAL", "ACTION", "event_busy", ordersUrl(manager, "Публикация"));
        addProblem(problems, "CHAT_BINDING_ISSUES", "Привязка соцсетей", chatBindingIssueCount, "CRITICAL", "ACTION", "link_off", ordersUrl(manager, null));
        addProblem(problems, "TELEGRAM_CHAT_MIGRATION", "Telegram-группы", telegramChatIssueCount, "CRITICAL", "ACTION", "send", ordersUrl(manager, null));
        addProblem(problems, "UNANSWERED_CLIENT_MESSAGES", "Неотвеченные сообщения", unansweredClientMessages, "CRITICAL", "ACTION", "mark_chat_unread", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "SUSPICIOUS_CLIENT_CLOSURES", "Ответ требует проверки", suspiciousClientClosures, "CRITICAL", "ACTION", "fact_check", "/admin/manager-control/" + manager.getId());
        addProblem(problems, "LEADS", "Лиды требуют действия", leadActionCount, "WARNING", "ACTION", "person_search", "/leads");
        addProblem(problems, "ORDERS_WORKLOAD", "Рабочие заказы", orderAttention, "INFO", "WORKLOAD", "inventory_2", ordersUrl(manager, null));
        addProblem(problems, "LEADS_WORKLOAD", "Лиды в работе", leadsInWork, "INFO", "WORKLOAD", "groups", "/leads");
        addProblem(problems, "WORKER_WORKLOAD", "Нагрузка специалистов", workerWorkloadCount, "INFO", "WORKLOAD", "engineering", firstWorkerSectionUrl(workerCounts.sections(), "WORKLOAD", "new"));
        List<ManagerControlSectionResponse> sections = workerCounts.sections();
        long criticalCount = overdueOrders + openRisks + requiresAttention + automationFailureCount + commonInvoiceActionCount + paymentIntegrityIssueCount + publicationDateIssueCount + chatBindingIssueCount + telegramChatIssueCount + unansweredClientMessages + suspiciousClientClosures + workerActionCount;
        long warningCount = leadActionCount;
        long workloadCount = orderAttention + workerWorkloadCount + leadsInWork;
        DailyControlSyncResult controlSync = persist ? syncDailyControl(manager, today, problems, sections, overdueStatuses) : readDailyControl(manager, today);
        problems = problems.stream().map(problem -> decorate(problem, controlSync.itemsByKey().get(problemKey(problem.code())))).toList();
        sections = sections.stream().map(section -> decorate(section, controlSync.itemsByKey().get(workerSectionKey(section.code())))).toList();
        overdueStatuses = overdueStatuses.stream().map(statusItem -> decorate(statusItem, controlSync.itemsByKey().get(overdueKey(statusItem.status())))).toList();
        long openItemCount = controlSync.items().stream().filter(cardLifecycle::isOpenActionItem).count();
        long handledItemCount = controlSync.items().stream().filter(cardLifecycle::isHandledActionItem).count();
        long openCriticalCount = controlSync.items().stream().filter(cardLifecycle::isOpenCriticalActionItem).count();
        long handledCriticalCount = controlSync.items().stream().filter(cardLifecycle::isHandledCriticalActionItem).count();
        String status = openCriticalCount > 0 || (controlSync.items().isEmpty() && criticalCount > 0) ? "RED" : handledCriticalCount > 0 || warningCount > 0 ? "YELLOW" : "GREEN";
        if (persist && updateQuality(controlSync.control(), controlSync.items())) {
            dailyControlRepository.save(controlSync.control());
        }
        List<String> blockers = controlSync.control().getId() == null ? List.of("Контроль еще не синхронизирован") : closeBlockers(controlSync.control(), controlSync.items());
        List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats = controlSync.control().getId() == null ? List.of() : workerExplanationStats(controlSync.control());
        List<ManagerDailyControlConcreteItem> balanceConcreteItems = controlSync.control().getId() == null ? List.of() : dailyControlConcreteItemRepository.findByControl(controlSync.control());
        var actionBalance = managerActionBalanceService.calculate(controlSync.items(), balanceConcreteItems);
        long actionCompletedCount = actionBalance.handledByManager();
        long actionTotalCount = actionBalance.total();
        long actionFinishedCount = actionCompletedCount + actionBalance.autoClosed();
        int actionProgressPercent = actionTotalCount <= 0 ? 100 : (int) Math.max(0, Math.min(100, Math.round(actionFinishedCount * 100D / actionTotalCount)));
        ManagerOperationalMetricsService.Metrics operational = includeOperationalMetrics ? managerOperationalMetricsService.calculate(manager, today, LocalDateTime.now()) : null;
        if (operational == null) {
            operational = new ManagerOperationalMetricsService.Metrics(0, 0, 0, 0);
        }
        return new ManagerControlManagerResponse(manager.getId(), user == null ? null : user.getId(), safe(user == null ? null : user.getUsername()), managerName(manager), user == null || user.isActive(), controlSync.control().getId(), controlSync.control().getStatus().name(), controlSync.control().getStartedAt(), controlSync.control().getClosedAt(), controlSync.control().getMorningStartedAt(), controlSync.control().getMorningCompletedAt(), controlSync.control().getDayCheckedAt(), controlSync.control().getFinalCheckedAt(), controlSync.control().getQualityScore(), controlSync.control().getQualityGrade(), controlSync.control().getRiskScore(), controlSync.control().isFastClickRisk(), blockers.isEmpty(), openItemCount, handledItemCount, actionTotalCount, actionCompletedCount, actionProgressPercent, actionBalance.autoClosed(), actionBalance.remaining(), actionBalance.resolved(), actionBalance.actionTaken(), actionBalance.deferred(), actionBalance.acknowledged(), actionBalance.overdueRemaining(), actionBalance.riskRemaining(), actionBalance.unansweredRemaining(), actionBalance.otherRemaining(), leadActionCount, status, criticalCount, warningCount, workloadCount, criticalCount + warningCount, overdueOrders, openRisks, orderAttention, workerSectionTotal, problems, sections, overdueStatuses, workerExplanationStats, operational.activeWorkSeconds(), operational.averageDailyWorkSeconds(), operational.averageReactionSeconds(), operational.reactionCount(), managerPerformance);
    }

    List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats(ManagerDailyControl control) {
        return workerExplanationQueries.workerExplanationStats(control);
    }

    DailyControlSyncResult syncDailyControl(Manager manager, LocalDate today, List<ManagerControlProblemResponse> problems, List<ManagerControlSectionResponse> sections, List<ManagerControlOverdueStatusResponse> overdueStatuses) {
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager).orElseGet(() -> {
            ManagerDailyControl created = new ManagerDailyControl();
            LocalDateTime now = LocalDateTime.now();
            created.setControlDate(today);
            created.setManager(manager);
            created.setManagerUserId(manager.getUser() == null ? null : manager.getUser().getId());
            created.setStatus(ManagerDailyControlStatus.IN_PROGRESS);
            created.setStartedAt(now);
            created.setMorningStartedAt(now);
            created.setLastActivityAt(now);
            ManagerDailyControl saved = dailyControlRepository.save(created);
            cardLifecycle.saveEvent(saved, null, null, ManagerDailyControlEventType.CONTROL_CREATED, null, "Контроль дня стартовал автоматически");
            return saved;
        });
        if (control.getId() != null) {
            control = dailyControlRepository.findByIdForUpdate(control.getId()).orElse(control);
        }
        Map<String, ManagerDailyControlItem> existing = dailyControlItemRepository.findByControl(control).stream().collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        Set<String> activeKeys = new HashSet<>();
        List<ManagerDailyControlItem> currentItems = new ArrayList<>();
        for (ControlItemInput input : controlItemInputs(problems, sections)) {
            activeKeys.add(input.itemKey());
            ManagerDailyControlItem item = existing.get(input.itemKey());
            boolean created = false;
            if (item == null) {
                item = new ManagerDailyControlItem();
                item.setControl(control);
                item.setItemKey(input.itemKey());
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                item.setAutomaticResolution(false);
                created = true;
            }
            long previousCount = item.getCount();
            boolean shouldReopen = !created && input.group() == ManagerDailyControlGroup.ACTION && input.count() > previousCount && item.getStatus() != ManagerDailyControlItemStatus.OPEN;
            boolean shouldReopenFollowUp = !created && input.group() == ManagerDailyControlGroup.ACTION && input.count() > 0 && item.getStatus() != ManagerDailyControlItemStatus.OPEN && hasDueConcreteFollowUp(item);
            boolean shouldReopenConcrete = !created && input.group() == ManagerDailyControlGroup.ACTION && input.severity() == ManagerDailyControlSeverity.CRITICAL && input.count() > 0 && item.getStatus() != ManagerDailyControlItemStatus.OPEN && hasUnfinishedConcreteBreakdown(item, input.count());
            boolean changed = applyControlItemSnapshot(item, input);
            if (shouldReopen || shouldReopenFollowUp || shouldReopenConcrete) {
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                item.setAutomaticResolution(false);
                item.setActionType(null);
                item.setComment(null);
                item.setResolvedAt(null);
                changed = true;
            }
            ManagerDailyControlItem saved = created || changed ? dailyControlItemRepository.save(item) : item;
            currentItems.add(saved);
            if (created) {
                cardLifecycle.saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, null);
            } else if (shouldReopen) {
                cardLifecycle.saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, "Пункт снова открыт: счетчик вырос");
            } else if (shouldReopenFollowUp) {
                cardLifecycle.saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, "Пункт снова открыт: наступил повторный контроль");
            } else if (shouldReopenConcrete) {
                cardLifecycle.saveEvent(control, saved, null, ManagerDailyControlEventType.ITEM_CREATED, null, "Пункт снова открыт: есть необработанные карточки внутри");
            }
        }
        for (ManagerDailyControlItem item : existing.values()) {
            if (!activeKeys.contains(item.getItemKey()) && cardLifecycle.isOpenActionItem(item)) {
                cardLifecycle.recordItemEpisode(item, ManagerDailyControlItemStatus.RESOLVED, true);
                item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
                item.setResolvedAt(LocalDateTime.now());
                item.setAutomaticResolution(true);
                ManagerDailyControlItem resolvedItem = dailyControlItemRepository.save(item);
                resolveOpenConcreteItemsForResolvedParent(resolvedItem);
                currentItems.add(resolvedItem);
                cardLifecycle.saveEvent(control, item, null, ManagerDailyControlEventType.ITEM_RESOLVED, ManagerDailyControlActionType.RESOLVED, "Автоматически закрыто: пункт больше не требует внимания");
            } else if (!currentItems.contains(item)) {
                currentItems.add(item);
            }
        }
        boolean reopened = reopenClosedControlIfNeeded(control, currentItems);
        ManagerDailyControlStatus nextStatus = cardLifecycle.recalculateControlStatus(currentItems);
        if (control.getStatus() != nextStatus) {
            control.setStatus(nextStatus);
            cardLifecycle.saveEvent(control, null, null, ManagerDailyControlEventType.CONTROL_STATUS_CHANGED, null, nextStatus.name());
            dailyControlRepository.save(control);
        } else if (reopened) {
            dailyControlRepository.save(control);
        }
        if (autoCloseControlIfReady(control, LocalDateTime.now())) {
            currentItems = activeControlItems(dailyControlItemRepository.findByControl(control));
        }
        Map<String, ManagerDailyControlItem> itemsByKey = currentItems.stream().collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        return new DailyControlSyncResult(control, currentItems, itemsByKey);
    }

    boolean applyControlItemSnapshot(ManagerDailyControlItem item, ControlItemInput input) {
        boolean changed = false;
        if (item.getItemType() != input.itemType()) {
            item.setItemType(input.itemType());
            changed = true;
        }
        if (!Objects.equals(item.getEntityId(), input.entityId())) {
            item.setEntityId(input.entityId());
            changed = true;
        }
        if (!Objects.equals(item.getWorkerId(), input.workerId())) {
            item.setWorkerId(input.workerId());
            changed = true;
        }
        if (!Objects.equals(item.getSectionCode(), input.sectionCode())) {
            item.setSectionCode(input.sectionCode());
            changed = true;
        }
        if (!Objects.equals(item.getReasonCode(), input.reasonCode())) {
            item.setReasonCode(input.reasonCode());
            changed = true;
        }
        if (!Objects.equals(item.getLabel(), input.label())) {
            item.setLabel(input.label());
            changed = true;
        }
        if (!Objects.equals(item.getTargetUrl(), input.targetUrl())) {
            item.setTargetUrl(input.targetUrl());
            changed = true;
        }
        if (item.getCount() != input.count()) {
            item.setCount(input.count());
            changed = true;
        }
        if (item.getSeverity() != input.severity()) {
            item.setSeverity(input.severity());
            changed = true;
        }
        if (item.getGroup() != input.group()) {
            item.setGroup(input.group());
            changed = true;
        }
        return changed;
    }

    DailyControlSyncResult readDailyControl(Manager manager, LocalDate today) {
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager).orElseGet(() -> transientControl(manager, today));
        if (control.getId() == null) {
            return new DailyControlSyncResult(control, List.of(), Map.of());
        }
        List<ManagerDailyControlItem> items = activeControlItems(dailyControlItemRepository.findByControl(control));
        Map<String, ManagerDailyControlItem> itemsByKey = items.stream().collect(Collectors.toMap(ManagerDailyControlItem::getItemKey, Function.identity(), (left, right) -> left));
        return new DailyControlSyncResult(control, items, itemsByKey);
    }

    List<ControlItemInput> controlItemInputs(List<ManagerControlProblemResponse> problems, List<ManagerControlSectionResponse> sections) {
        List<ControlItemInput> inputs = new ArrayList<>();
        for (ManagerControlProblemResponse problem : problems) {
            if (problem.count() <= 0) {
                continue;
            }
            if (parseGroup(problem.group()) == ManagerDailyControlGroup.WORKLOAD) {
                continue;
            }
            inputs.add(new ControlItemInput(problemKey(problem.code()), ManagerDailyControlItemType.PROBLEM, null, null, null, problem.code(), problem.label(), problem.targetUrl(), problem.count(), parseSeverity(problem.severity()), parseGroup(problem.group())));
        }
        for (ManagerControlSectionResponse section : sections) {
            if (section.count() <= 0) {
                continue;
            }
            if (parseGroup(section.group()) == ManagerDailyControlGroup.WORKLOAD) {
                continue;
            }
            inputs.add(new ControlItemInput(workerSectionKey(section.code()), ManagerDailyControlItemType.WORKER_SECTION, null, null, section.code(), section.code(), section.label(), section.targetUrl(), section.count(), parseSeverity(section.severity()), parseGroup(section.group())));
        }
        return inputs;
    }

    boolean hasDueConcreteFollowUp(ManagerDailyControlItem item) {
        if (item == null || item.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return dailyControlConcreteItemRepository.findByParentItem(item).stream().anyMatch(concrete -> concrete.getFollowUpAt() != null && !concrete.getFollowUpAt().isAfter(now) && concrete.getStatus() != ManagerDailyControlItemStatus.OPEN && concrete.getStatus() != ManagerDailyControlItemStatus.RESOLVED);
    }

    boolean hasUnfinishedConcreteBreakdown(ManagerDailyControlItem item, long expectedCount) {
        if (!requiresConcreteCardAction(item) || item.getId() == null) {
            return false;
        }
        List<ManagerDailyControlConcreteItem> concreteItems = dailyControlConcreteItemRepository.findByParentItem(item);
        return concreteItems.size() < expectedCount || concreteItems.stream().anyMatch(concrete -> concrete.getStatus() == ManagerDailyControlItemStatus.OPEN);
    }

    ManagerControlProblemResponse decorate(ManagerControlProblemResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        SlaWindow sla = slaPolicy.slaWindow(response.code(), item.getCreatedAt(), item.getResolvedAt());
        return new ManagerControlProblemResponse(response.code(), response.label(), response.count(), response.severity(), response.group(), response.icon(), response.targetUrl(), item.getId(), item.getStatus().name(), item.getActionType() == null ? null : item.getActionType().name(), item.getComment(), sla.firstObservedAt(), sla.targetDeadlineAt(), sla.hardDeadlineAt(), sla.state());
    }

    ManagerControlSectionResponse decorate(ManagerControlSectionResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        SlaWindow sla = slaPolicy.slaWindow(response.code(), item.getCreatedAt(), item.getResolvedAt());
        return new ManagerControlSectionResponse(response.code(), response.label(), response.count(), response.severity(), response.group(), response.targetUrl(), item.getId(), item.getStatus().name(), item.getActionType() == null ? null : item.getActionType().name(), item.getComment(), sla.firstObservedAt(), sla.targetDeadlineAt(), sla.hardDeadlineAt(), sla.state());
    }

    ManagerControlOverdueStatusResponse decorate(ManagerControlOverdueStatusResponse response, ManagerDailyControlItem item) {
        if (item == null) {
            return response;
        }
        return new ManagerControlOverdueStatusResponse(response.status(), response.count(), response.maxDays(), response.targetUrl(), item.getId(), item.getStatus().name(), item.getActionType() == null ? null : item.getActionType().name(), item.getComment());
    }

    void resolveOpenConcreteItemsForResolvedParent(ManagerDailyControlItem parentItem) {
        if (parentItem == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ManagerDailyControlConcreteItem item : dailyControlConcreteItemRepository.findByParentItem(parentItem)) {
            if (item == null || item.getStatus() != ManagerDailyControlItemStatus.OPEN) {
                continue;
            }
            cardLifecycle.recordConcreteEpisode(item, ManagerDailyControlItemStatus.RESOLVED, true);
            item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            item.setActionType(ManagerDailyControlActionType.RESOLVED);
            item.setComment("Родительский пункт больше не требует внимания");
            item.setResolvedAt(now);
            item.setAutomaticResolution(true);
            item.setFollowUpAt(null);
            item.setLastManualTouchAt(null);
            dailyControlConcreteItemRepository.save(item);
        }
    }

    boolean isSpecialistActionConcrete(ManagerDailyControlConcreteItem item) {
        return workerTaskLookup.isSpecialistActionConcrete(item);
    }

    List<Order> workerStaleOrdersForControl(List<Long> workerIds, String status, LocalDate today) {
        return orderAutomationDiagnostics.workerStaleOrdersForControl(workerIds, status, today);
    }

    List<Company> telegramChatIssueCompanies(Manager manager, int limit) {
        return problemExamples.telegramChatIssueCompanies(manager, limit);
    }

    String problemKey(String code) {
        return "problem:" + safe(code);
    }

    String workerSectionKey(String code) {
        return "worker:" + safe(code);
    }

    String overdueKey(String status) {
        return "overdue:" + safe(status);
    }

    boolean requiresConcreteCardAction(ManagerDailyControlItem item) {
        return dayLifecycle.requiresConcreteCardAction(item);
    }

    ManagerDailyControlSeverity parseSeverity(String value) {
        if (value == null || value.isBlank()) {
            return ManagerDailyControlSeverity.INFO;
        }
        try {
            return ManagerDailyControlSeverity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ManagerDailyControlSeverity.INFO;
        }
    }

    ManagerDailyControlGroup parseGroup(String value) {
        if (value == null || value.isBlank()) {
            return ManagerDailyControlGroup.WORKLOAD;
        }
        try {
            return ManagerDailyControlGroup.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ManagerDailyControlGroup.WORKLOAD;
        }
    }

    WorkerSectionCounts workerSectionCounts(Manager manager, LocalDate today) {
        List<Long> workerIds = workerIds(manager);
        Map<Long, Integer> publishByWorker = workerIds.isEmpty() ? Map.of() : safeMapLong(reviewService.countOrdersByWorkerIdsAndStatusPublish(workerIds, managerControlPublicationOverdueDate(today)));
        Map<Long, Integer> nagulByWorker = workerIds.isEmpty() ? Map.of() : safeMapLong(reviewService.countOrdersByWorkerIdsAndStatusVigul(workerIds, today.plusDays(60)));
        Map<String, Long> staleOrderCounts = workerIds.isEmpty() ? Map.of() : safeStatusCountMap(orderRepository.countManagerControlWorkerStaleOrdersByStatus(workerIds, Set.of("Новый", "Коррекция"), managerControlWorkerOrderOverdueDate(today)));
        long nagulOverdueTotal = workerIds.isEmpty() ? 0L : sumRowCounts(reviewRepository.countManagerControlNagulReviewsByWorkerIds(workerIds, managerControlPublicationOverdueDate(today)));
        long newCount = workerOrderCount(workerIds, "Новый");
        long correctCount = workerOrderCount(workerIds, "Коррекция");
        long nagulCount = sumValues(nagulByWorker);
        Map<String, Long> snoozedWorkerTasks = snoozedWorkerTaskCountsByType(manager, today);
        LocalDate workerTaskOverdueDate = managerControlWorkerTaskOverdueDate(today);
        long newOverdueBaseCount = workerIds.isEmpty() ? 0L : workerStaleOrdersForControl(workerIds, "Новый", today).size();
        long newOverdueCount = Math.max(0L, newOverdueBaseCount - snoozedWorkerTasks.getOrDefault(ENTITY_WORKER_ORDER_NEW, 0L));
        long correctOverdueCount = Math.max(0L, staleOrderCounts.getOrDefault("Коррекция", 0L) - snoozedWorkerTasks.getOrDefault(ENTITY_WORKER_ORDER_CORRECT, 0L));
        long nagulOverdueCount = Math.max(0L, nagulOverdueTotal - snoozedWorkerTasks.getOrDefault(ENTITY_NAGUL_REVIEW, 0L));
        long recoveryCount = Math.max(0L, reviewRecoveryTaskService.countDueTasksToManager(manager, workerTaskOverdueDate) - snoozedWorkerTasks.getOrDefault("RECOVERY_TASK", 0L));
        long publishCount = Math.max(0L, sumValues(publishByWorker) - snoozedWorkerTasks.getOrDefault(ENTITY_PUBLISH_REVIEW, 0L));
        long badCount = Math.max(0L, badReviewTaskService.countDueTasksToManager(manager, workerTaskOverdueDate) - snoozedWorkerTasks.getOrDefault("BAD_REVIEW_TASK", 0L));
        List<ManagerControlSectionResponse> sections = List.of(section("new_overdue", "Новые без изменений", newOverdueCount, "CRITICAL", "ACTION", workerUrl("new")), section("correct_overdue", "Коррекция без изменений", correctOverdueCount, "CRITICAL", "ACTION", workerUrl("correct")), section("nagul_overdue", "Просроченный выгул", nagulOverdueCount, "CRITICAL", "ACTION", workerUrl("nagul")), section("new", "Новые", newCount, "INFO", "WORKLOAD", workerUrl("new")), section("correct", "Коррекция", correctCount, "INFO", "WORKLOAD", workerUrl("correct")), section("nagul", "Выгул", nagulCount, "INFO", "WORKLOAD", workerUrl("nagul")), section("recovery", "Восстановление", recoveryCount, "CRITICAL", "ACTION", workerUrl("recovery")), section("publish", "Публикация", publishCount, "CRITICAL", "ACTION", workerUrl("publish")), section("bad", "Плохие", badCount, "CRITICAL", "ACTION", workerUrl("bad")));
        return new WorkerSectionCounts(sections, sections.stream().mapToLong(ManagerControlSectionResponse::count).sum(), newOverdueCount + correctOverdueCount + nagulOverdueCount + recoveryCount + publishCount + badCount, newCount + correctCount + nagulCount);
    }

    LocalDate managerControlWorkerTaskOverdueDate(LocalDate today) {
        return problemExamples.managerControlWorkerTaskOverdueDate(today);
    }

    LocalDate managerControlWorkerOrderOverdueDate(LocalDate today) {
        return orderAutomationDiagnostics.managerControlWorkerOrderOverdueDate(today);
    }

    LocalDate managerControlPublicationOverdueDate(LocalDate today) {
        return problemExamples.managerControlPublicationOverdueDate(today);
    }

    Map<String, Long> snoozedWorkerTaskCountsByType(Manager manager, LocalDate today) {
        return dailyControlRepository.findByControlDateAndManager(today, manager).map(control -> dailyControlConcreteItemRepository.findByControlAndFollowUpAtAfter(control, LocalDateTime.now()).stream().filter(this::isSpecialistActionConcrete).filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN).collect(Collectors.groupingBy(ManagerDailyControlConcreteItem::getEntityType, Collectors.counting()))).orElse(Map.of());
    }

    List<ManagerControlOverdueStatusResponse> overdueStatuses(Manager manager, LocalDate today) {
        return problemExamples.overdueStatuses(manager, today);
    }

    long openRiskCount(Manager manager) {
        List<Long> userIds = workerUserIds(manager);
        if (userIds.isEmpty()) {
            return 0;
        }
        return riskIncidentRepository.countByWorkerUserIdInAndStatus(userIds, WorkerRiskIncidentStatus.OPEN);
    }

    long workerOrderCount(List<Long> workerIds, String status) {
        if (workerIds.isEmpty()) {
            return 0;
        }
        return sumValues(orderService.countOrdersByWorkerIdsAndStatus(workerIds, status));
    }

    List<Long> workerIds(Manager manager) {
        return problemExamples.workerIds(manager);
    }

    List<Long> workerUserIds(Manager manager) {
        return problemExamples.workerUserIds(manager);
    }

    ManagerControlSectionResponse section(String code, String label, long count, String severity, String group, String targetUrl) {
        return new ManagerControlSectionResponse(code, label, Math.max(0, count), severity, group, targetUrl);
    }

    void addProblem(List<ManagerControlProblemResponse> problems, String code, String label, long count, String severity, String group, String icon, String targetUrl) {
        if (count <= 0) {
            return;
        }
        problems.add(new ManagerControlProblemResponse(code, label, count, severity, group, icon, targetUrl));
    }

    long sum(Map<String, Integer> counts, List<String> statuses) {
        return statuses.stream().mapToLong(status -> counts.getOrDefault(status, 0)).sum();
    }

    long sumValues(Map<?, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        return counts.values().stream().filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
    }

    Map<String, Integer> safeMap(Map<String, Integer> source) {
        return source == null ? Map.of() : source;
    }

    Map<Long, Integer> safeMapLong(Map<Long, Integer> source) {
        return source == null ? Map.of() : source;
    }

    Map<String, Long> safeStatusCountMap(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream().filter(Objects::nonNull).collect(Collectors.toMap(row -> rowString(row, 0, "Без статуса"), row -> rowLong(row, 1), Long::sum));
    }

    long sumRowCounts(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return rows.stream().mapToLong(row -> rowLong(row, 1)).sum();
    }

    long rowLong(Object[] row, int index) {
        return problemExamples.rowLong(row, index);
    }

    String rowString(Object[] row, int index, String fallback) {
        return problemExamples.rowString(row, index, fallback);
    }

    String ordersUrl(Manager manager, String status) {
        return problemExamples.ordersUrl(manager, status);
    }

    String workerUrl(String section) {
        if (section == null || section.isBlank()) {
            return "/worker";
        }
        return "/worker?section=" + encode(section);
    }

    String firstWorkerSectionUrl(List<ManagerControlSectionResponse> sections, String group, String fallbackSection) {
        return sections.stream().filter(section -> group.equals(section.group())).filter(section -> section.count() > 0).filter(section -> !"risk".equals(section.code())).map(ManagerControlSectionResponse::code).findFirst().map(this::workerUrl).orElseGet(() -> workerUrl(fallbackSection));
    }

    String encode(String value) {
        return problemExamples.encode(value);
    }

    String managerName(Manager manager) {
        if (manager == null) {
            return "Менеджер";
        }
        User user = manager.getUser();
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Менеджер #" + manager.getId() : username;
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }

    record WorkerSectionCounts(List<ManagerControlSectionResponse> sections, long total, long actionTotal, long workloadTotal) {
    }

    record DailyControlSyncResult(ManagerDailyControl control, List<ManagerDailyControlItem> items, Map<String, ManagerDailyControlItem> itemsByKey) {
    }

    record ControlItemInput(String itemKey, ManagerDailyControlItemType itemType, Long entityId, Long workerId, String sectionCode, String reasonCode, String label, String targetUrl, long count, ManagerDailyControlSeverity severity, ManagerDailyControlGroup group) {
    }
}
