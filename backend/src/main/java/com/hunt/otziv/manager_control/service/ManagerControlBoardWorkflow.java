package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlEventResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlSummaryResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlWorkerExplanationStatsResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlBoardWorkflow {

    private final ManagerControlDailySnapshotWorkflow dailySnapshot;

    private final ManagerControlDayLifecycle dayLifecycle;

    private final ManagerControlConcreteSnapshotWorkflow concreteSnapshot;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlWorkerExplanationQueries workerExplanationQueries;

    private final ManagerControlQualityQueries qualityQueries;

    private final ManagerRepository managerRepository;

    private final ManagerPermissionService managerPermissionService;

    private final ScheduledClientMessageService scheduledClientMessageService;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlItemRepository dailyControlItemRepository;

    private final ManagerPerformanceService managerPerformanceService;

    //ok
    @Transactional(readOnly = true)
    public ManagerControlSummaryResponse today(Principal principal, Authentication authentication) {
        return today(principal, authentication, false);
    }

    @Transactional
    public ManagerControlSummaryResponse syncToday(Principal principal, Authentication authentication) {
        reconcileClientMessagesForControl();
        cardLifecycle.invalidateManagerPerformance();
        LocalDate today = LocalDate.now();
        for (Manager manager : accessPolicy.visibleManagers(principal, authentication)) {
            managerControl(manager, today, null, true, false);
            syncManagerActionConcreteItems(manager, today);
        }
        return today(principal, authentication, false);
    }

    @Transactional
    public void synchronizeDailySnapshot(LocalDate date) {
        LocalDate snapshotDate = date == null ? LocalDate.now() : date;
        reconcileClientMessagesForControl();
        for (Manager manager : managerRepository.findAllWithUserAndImage()) {
            managerControl(manager, snapshotDate, null, true, false);
            syncManagerActionConcreteItems(manager, snapshotDate);
        }
    }

    ManagerControlSummaryResponse today(Principal principal, Authentication authentication, boolean persist) {
        LocalDate today = LocalDate.now();
        List<ManagerControlManagerResponse> managers = accessPolicy.visibleManagers(principal, authentication).stream().map(manager -> managerControl(manager, today, null, persist, true)).sorted(Comparator.comparingInt((ManagerControlManagerResponse manager) -> statusRank(manager.status())).thenComparing(ManagerControlManagerResponse::totalAttentionCount, Comparator.reverseOrder()).thenComparing(ManagerControlManagerResponse::name, String.CASE_INSENSITIVE_ORDER)).toList();
        if (managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER")) {
            Map<Long, ManagerPerformanceScoreResponse> performanceByManagerId = managerPerformanceService.score(today).stream().filter(score -> score.managerId() != null).collect(Collectors.toMap(ManagerPerformanceScoreResponse::managerId, score -> score, (left, right) -> left));
            managers = managers.stream().map(manager -> withManagerPerformance(manager, performanceByManagerId.get(manager.managerId()))).toList();
        }
        long green = managers.stream().filter(manager -> "GREEN".equals(manager.status())).count();
        long yellow = managers.stream().filter(manager -> "YELLOW".equals(manager.status())).count();
        long red = managers.stream().filter(manager -> "RED".equals(manager.status())).count();
        long critical = managers.stream().mapToLong(ManagerControlManagerResponse::criticalCount).sum();
        long warning = managers.stream().mapToLong(ManagerControlManagerResponse::warningCount).sum();
        long workload = managers.stream().mapToLong(ManagerControlManagerResponse::workloadCount).sum();
        long attention = managers.stream().mapToLong(ManagerControlManagerResponse::totalAttentionCount).sum();
        return new ManagerControlSummaryResponse(today, LocalDateTime.now(), true, managerPermissionService.hasRole(authentication, "MANAGER") && !managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER"), managers.size(), green, yellow, red, critical, warning, workload, attention, managers);
    }

    ManagerControlManagerResponse withManagerPerformance(ManagerControlManagerResponse manager, ManagerPerformanceScoreResponse managerPerformance) {
        return new ManagerControlManagerResponse(manager.managerId(), manager.userId(), manager.username(), manager.name(), manager.active(), manager.dailyControlId(), manager.dailyControlStatus(), manager.startedAt(), manager.closedAt(), manager.morningStartedAt(), manager.morningCompletedAt(), manager.dayCheckedAt(), manager.finalCheckedAt(), manager.qualityScore(), manager.qualityGrade(), manager.riskScore(), manager.fastClickRisk(), manager.canCloseDay(), manager.openItemCount(), manager.handledItemCount(), manager.actionTotalCount(), manager.actionCompletedCount(), manager.actionProgressPercent(), manager.actionAutoClosedCount(), manager.actionRemainingCount(), manager.actionResolvedCount(), manager.actionTakenCount(), manager.actionDeferredCount(), manager.actionAcknowledgedCount(), manager.actionOverdueRemainingCount(), manager.actionRiskRemainingCount(), manager.actionUnansweredRemainingCount(), manager.actionOtherRemainingCount(), manager.leadActionCount(), manager.status(), manager.criticalCount(), manager.warningCount(), manager.workloadCount(), manager.totalAttentionCount(), manager.overdueOrderCount(), manager.openRiskCount(), manager.orderAttentionCount(), manager.workerSectionCount(), manager.problems(), manager.workerSections(), manager.overdueStatuses(), manager.workerExplanationStats(), manager.activeWorkSeconds(), manager.averageDailyWorkSeconds(), manager.averageReactionSeconds(), manager.reactionCount(), managerPerformance);
    }

    void reconcileClientMessagesForControl() {
        if (scheduledClientMessageService == null) {
            return;
        }
        try {
            scheduledClientMessageService.reconcileCandidatesNow();
        } catch (Exception e) {
            log.warn("Не удалось досоздать очередь клиентских сообщений перед контролем менеджеров", e);
        }
    }

    @Transactional
    public ManagerControlManagerDetailResponse managerDetails(Long managerId, Principal principal, Authentication authentication) {
        if (managerId == null || managerId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
        }
        Manager manager = accessPolicy.visibleManagers(principal, authentication).stream().filter(item -> managerId.equals(item.getId())).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер недоступен"));
        return managerDetails(manager, false);
    }

    ManagerControlManagerDetailResponse managerDetails(Manager manager, boolean syncConcrete) {
        LocalDate today = LocalDate.now();
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager).orElseGet(() -> transientControl(manager, today));
        List<ManagerDailyControlItem> items = control.getId() == null ? List.of() : dailyControlItemRepository.findByControl(control).stream().filter(this::isActiveControlItem).sorted(Comparator.comparingInt(this::detailItemRank).thenComparing(ManagerDailyControlItem::getLabel, String.CASE_INSENSITIVE_ORDER).thenComparing(ManagerDailyControlItem::getId)).toList();
        User user = manager.getUser();
        List<String> blockers = control.getId() == null ? List.of("Контроль еще не синхронизирован") : closeBlockers(control, items);
        return new ManagerControlManagerDetailResponse(manager.getId(), user == null ? null : user.getId(), safe(user == null ? null : user.getUsername()), managerName(manager), control.getId(), control.getControlDate(), control.getStatus().name(), control.getStartedAt(), control.getClosedAt(), control.getLastActivityAt(), control.getMorningStartedAt(), control.getMorningCompletedAt(), control.getDayCheckedAt(), control.getFinalCheckedAt(), control.getQualityScore(), control.getQualityGrade(), control.getRiskScore(), control.isFastClickRisk(), blockers.isEmpty(), blockers, items.stream().filter(cardLifecycle::isOpenActionItem).count(), items.stream().filter(cardLifecycle::isHandledActionItem).count(), control.getId() == null ? List.of() : workerExplanationStats(control), items.stream().map(item -> detailItem(manager, item, today, syncConcrete)).toList(), control.getId() == null ? List.of() : events(control));
    }

    ManagerDailyControl transientControl(Manager manager, LocalDate today) {
        return dailySnapshot.transientControl(manager, today);
    }

    @Transactional
    public ManagerControlManagerDetailResponse syncManagerDetails(Long managerId, Principal principal, Authentication authentication) {
        reconcileClientMessagesForControl();
        Manager manager = accessPolicy.visibleManagers(principal, authentication).stream().filter(item -> managerId != null && managerId.equals(item.getId())).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер недоступен"));
        managerControl(manager, LocalDate.now(), null, true, false);
        cardLifecycle.invalidateManagerPerformance();
        return managerDetails(manager, true);
    }

    List<String> closeBlockers(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.closeBlockers(control, items);
    }

    boolean isActiveControlItem(ManagerDailyControlItem item) {
        return dayLifecycle.isActiveControlItem(item);
    }

    List<ManagerControlEventResponse> events(ManagerDailyControl control) {
        return qualityQueries.events(control);
    }

    ManagerControlManagerResponse managerControl(Manager manager, LocalDate today, ManagerPerformanceScoreResponse managerPerformance, boolean persist, boolean includeOperationalMetrics) {
        return dailySnapshot.managerControl(manager, today, managerPerformance, persist, includeOperationalMetrics);
    }

    void syncManagerActionConcreteItems(Manager manager, LocalDate today) {
        ManagerDailyControl control = dailyControlRepository.findByControlDateAndManager(today, manager).orElse(null);
        if (control == null) {
            return;
        }
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        for (ManagerDailyControlItem item : items) {
            if (item == null || item.getGroup() != ManagerDailyControlGroup.ACTION) {
                continue;
            }
            if (item.getCount() <= 0) {
                syncConcreteExamples(item, List.of());
                continue;
            }
            syncConcreteExamples(item, detailExamples(manager, item, today));
            cardLifecycle.reopenParentItemIfConcreteOpen(item);
        }
    }

    List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats(ManagerDailyControl control) {
        return workerExplanationQueries.workerExplanationStats(control);
    }

    ManagerControlItemDetailResponse detailItem(Manager manager, ManagerDailyControlItem item, LocalDate today, boolean syncConcrete) {
        return concreteSnapshot.detailItem(manager, item, today, syncConcrete);
    }

    List<ManagerControlConcreteItemResponse> detailExamples(Manager manager, ManagerDailyControlItem item, LocalDate today) {
        return problemExamples.detailExamples(manager, item, today);
    }

    List<ManagerControlConcreteItemResponse> syncConcreteExamples(ManagerDailyControlItem parentItem, List<ManagerControlConcreteItemResponse> examples) {
        return concreteSnapshot.syncConcreteExamples(parentItem, examples);
    }

    int detailItemRank(ManagerDailyControlItem item) {
        return detailWorkflowRank(item) * 10 + detailStateRank(item);
    }

    int detailWorkflowRank(ManagerDailyControlItem item) {
        if (item == null) {
            return 999;
        }
        String reason = safe(item.getReasonCode());
        String section = safe(item.getSectionCode());
        if ("new_overdue".equals(section)) {
            return 10;
        }
        if (item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS) {
            return 10 + orderStatusDisplayRank(reason) * 10;
        }
        if ("REQUIRES_ATTENTION".equals(reason)) {
            return 10 + orderStatusDisplayRank("Требует внимания") * 10;
        }
        if ("correct_overdue".equals(section)) {
            return 10 + orderStatusDisplayRank("Коррекция") * 10;
        }
        if ("nagul_overdue".equals(section)) {
            return 45;
        }
        if ("recovery".equals(section)) {
            return 48;
        }
        if ("publish".equals(section)) {
            return 50;
        }
        if ("bad".equals(section)) {
            return 55;
        }
        if ("COMMON_INVOICES".equals(reason)) {
            return 75;
        }
        if ("CHAT_BINDING_ISSUES".equals(reason)) {
            return 80;
        }
        if ("OPEN_RISKS".equals(reason) || "risk".equals(section)) {
            return 150;
        }
        if ("WORKER_ACTIONS".equals(reason)) {
            return 160;
        }
        if ("OVERDUE_ORDERS".equals(reason)) {
            return 170;
        }
        if ("ORDERS_WORKLOAD".equals(reason) || "WORKER_WORKLOAD".equals(reason)) {
            return 900;
        }
        if (item.getGroup() == ManagerDailyControlGroup.WORKLOAD) {
            return 910 + workloadSectionRank(section);
        }
        return item.getGroup() == ManagerDailyControlGroup.ACTION ? 800 : 950;
    }

    int workloadSectionRank(String section) {
        return switch(safe(section)) {
            case "new" ->
                0;
            case "correct" ->
                1;
            case "nagul" ->
                2;
            default ->
                20;
        };
    }

    int detailStateRank(ManagerDailyControlItem item) {
        if (cardLifecycle.isOpenActionItem(item)) {
            return 0;
        }
        if (cardLifecycle.isHandledActionItem(item)) {
            return 1;
        }
        if (item != null && item.getGroup() == ManagerDailyControlGroup.ACTION) {
            return 2;
        }
        return 5;
    }

    int orderStatusDisplayRank(String status) {
        return problemExamples.orderStatusDisplayRank(status);
    }

    String managerName(Manager manager) {
        return dailySnapshot.managerName(manager);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }

    int statusRank(String status) {
        return switch(status) {
            case "RED" ->
                0;
            case "YELLOW" ->
                1;
            default ->
                2;
        };
    }
}
