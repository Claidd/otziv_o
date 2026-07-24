package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerPerformanceService {

    private static final long PROBLEM_SLA_HOURS = 8;
    private static final long CLIENT_REPLY_SLA_MINUTES = 30;
    private static final long RISK_SLA_HOURS = 2;
    private static final Duration SCORE_CACHE_TTL = Duration.ofMinutes(2);
    private static final ZoneId PERFORMANCE_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final Set<String> SPECIALIST_ENTITY_TYPES = Set.of(
            "WORKER_ORDER_NEW",
            "WORKER_ORDER_CORRECT",
            "NAGUL_REVIEW",
            "PUBLISH_REVIEW",
            "BAD_REVIEW_TASK",
            "RECOVERY_TASK"
    );

    private final ManagerRepository managerRepository;
    private final ManagerDailyControlRepository controlRepository;
    private final ManagerDailyControlItemRepository itemRepository;
    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ManagerDailyControlEventRepository controlEventRepository;
    private final ClientChatUnansweredItemRepository unansweredItemRepository;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final ManagerTeamProgressService managerTeamProgressService;

    private volatile LocalDate cachedScoreDate;
    private volatile Instant cachedScoreAt;
    private volatile List<ManagerPerformanceScoreResponse> cachedScore = List.of();

    public void invalidate() {
        cachedScoreDate = null;
        cachedScoreAt = null;
        cachedScore = List.of();
    }

    @Transactional(readOnly = true)
    public List<ManagerPerformanceScoreResponse> score(LocalDate selectedDate) {
        LocalDate today = LocalDate.now(PERFORMANCE_ZONE);
        LocalDate date = selectedDate == null ? today : selectedDate;
        List<ManagerPerformanceScoreResponse> cached = cachedScoreIfFresh(date);
        if (cached != null) {
            return cached;
        }
        LocalDate fromDate = date.withDayOfMonth(1);
        LocalDate toDate = date;
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.plusDays(1).atStartOfDay().minusNanos(1);
        LocalDateTime evaluatedAt = date.equals(today) ? LocalDateTime.now(PERFORMANCE_ZONE) : to;

        List<Manager> managers = managerRepository.findAllWithUserAndImage();
        if (managers.isEmpty()) {
            return List.of();
        }
        managers = managerRepository.findAllManagersWorkers(managers);
        LocalDate teamProgressTo = date.equals(today) ? date.minusDays(1) : date;
        Map<Long, ManagerTeamProgressService.TeamProgressStats> teamProgressByManagerId =
                teamProgressTo.isBefore(fromDate)
                        ? Map.of()
                        : managerTeamProgressService.statisticsByManagerIds(
                                managers.stream().map(Manager::getId).filter(Objects::nonNull).toList(),
                                fromDate,
                                teamProgressTo
                        );

        List<ManagerDailyControl> controls = controlRepository.findByControlDateBetween(fromDate, toDate);
        List<ManagerDailyControlItem> items = controls.isEmpty()
                ? List.of()
                : itemRepository.findByControlIn(controls);
        List<ManagerDailyControlConcreteItem> concreteItems = items.isEmpty()
                ? List.of()
                : concreteItemRepository.findByParentItemIn(items);
        List<ClientChatUnansweredItem> clientItems = unansweredItemRepository.findPerformanceItems(
                managers,
                from,
                to,
                ClientChatUnansweredStatus.OPEN
        );

        Map<Long, List<ManagerDailyControl>> controlsByManagerId = controls.stream()
                .filter(control -> control.getManager() != null && control.getManager().getId() != null)
                .collect(Collectors.groupingBy(control -> control.getManager().getId()));
        Map<Long, List<ManagerDailyControlItem>> itemsByControlId = items.stream()
                .filter(item -> item.getControl() != null && item.getControl().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getControl().getId()));
        Map<Long, List<ManagerDailyControlConcreteItem>> concreteByControlId = concreteItems.stream()
                .filter(item -> item.getControl() != null && item.getControl().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getControl().getId()));
        Map<Long, List<ClientChatUnansweredItem>> clientItemsByManagerId = clientItems.stream()
                .filter(item -> item.getManager() != null && item.getManager().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getManager().getId()));

        Map<Long, List<WorkerRiskIncident>> risksByWorkerUserId = riskIncidentsByWorkerUserId(managers, from, to);

        List<ManagerPerformanceScoreResponse> result = managers.stream()
                .map(manager -> managerScore(
                        manager,
                        controlsByManagerId.getOrDefault(manager.getId(), List.of()),
                        itemsByControlId,
                        concreteByControlId,
                        clientItemsByManagerId.getOrDefault(manager.getId(), List.of()),
                        risksByWorkerUserId,
                        evaluatedAt,
                        teamProgressByManagerId.getOrDefault(
                                manager.getId(),
                                ManagerTeamProgressService.TeamProgressStats.empty()
                        )
                ))
                .sorted(Comparator
                        .comparingInt(ManagerPerformanceScoreResponse::loadAdjustedPerformanceScore).reversed()
                        .thenComparing(ManagerPerformanceScoreResponse::performanceScore, Comparator.reverseOrder())
                        .thenComparing(ManagerPerformanceScoreResponse::workloadIndex, Comparator.reverseOrder())
                        .thenComparing(ManagerPerformanceScoreResponse::managerId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        cachedScoreDate = date;
        cachedScoreAt = Instant.now();
        cachedScore = result;
        return result;
    }

    private List<ManagerPerformanceScoreResponse> cachedScoreIfFresh(LocalDate date) {
        Instant cachedAt = cachedScoreAt;
        if (cachedAt == null || cachedScoreDate == null || !cachedScoreDate.equals(date)) {
            return null;
        }
        if (cachedAt.plus(SCORE_CACHE_TTL).isBefore(Instant.now())) {
            return null;
        }
        return cachedScore;
    }

    private ManagerPerformanceScoreResponse managerScore(
            Manager manager,
            List<ManagerDailyControl> controls,
            Map<Long, List<ManagerDailyControlItem>> itemsByControlId,
            Map<Long, List<ManagerDailyControlConcreteItem>> concreteByControlId,
            List<ClientChatUnansweredItem> clientItems,
            Map<Long, List<WorkerRiskIncident>> risksByWorkerUserId,
            LocalDateTime evaluatedAt,
            ManagerTeamProgressService.TeamProgressStats teamProgress
    ) {
        List<ManagerDailyControlItem> items = controls.stream()
                .map(ManagerDailyControl::getId)
                .filter(Objects::nonNull)
                .flatMap(controlId -> itemsByControlId.getOrDefault(controlId, List.of()).stream())
                .toList();
        List<ManagerDailyControlConcreteItem> concreteItems = controls.stream()
                .map(ManagerDailyControl::getId)
                .filter(Objects::nonNull)
                .flatMap(controlId -> concreteByControlId.getOrDefault(controlId, List.of()).stream())
                .toList();
        List<WorkerRiskIncident> riskIncidents = workerUserIds(manager).stream()
                .flatMap(workerUserId -> risksByWorkerUserId.getOrDefault(workerUserId, List.of()).stream())
                .toList();

        long actionTotal = actionItems(items).stream()
                .mapToLong(ManagerDailyControlItem::getCount)
                .sum();
        long openCount = concreteItems.stream()
                .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                .count();
        long handledCount = concreteItems.stream()
                .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN)
                .count();
        if (concreteItems.isEmpty()) {
            openCount = actionItems(items).stream()
                    .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                    .mapToLong(ManagerDailyControlItem::getCount)
                    .sum();
            handledCount = actionItems(items).stream()
                    .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN)
                    .mapToLong(ManagerDailyControlItem::getCount)
                    .sum();
        }
        long acceptedCount = controls.stream()
                .filter(control -> control.getMorningCompletedAt() != null)
                .count();
        long closedCount = controls.stream()
                .filter(control -> isEffectivelyClosed(control, itemsByControlId, concreteByControlId))
                .count();
        long fastClickCount = controls.stream()
                .filter(ManagerDailyControl::isFastClickRisk)
                .count();

        SlaStats problemSla = concreteSla(concreteItems.stream()
                .filter(item -> !"CLIENT_CHAT_UNANSWERED".equals(item.getEntityType()))
                .filter(item -> !"RISK".equals(item.getEntityType()))
                .toList(), Duration.ofHours(PROBLEM_SLA_HOURS), evaluatedAt);
        SlaStats specialistSla = concreteSla(concreteItems.stream()
                .filter(item -> SPECIALIST_ENTITY_TYPES.contains(item.getEntityType()))
                .toList(), Duration.ofHours(PROBLEM_SLA_HOURS), evaluatedAt);
        SlaStats clientSla = clientSla(clientItems, evaluatedAt);
        SlaStats riskSla = riskSla(riskIncidents, evaluatedAt);

        long workloadOrder = workloadCount(items, "ORDERS_WORKLOAD");
        long workloadWorker = workloadCount(items, "WORKER_WORKLOAD");
        long workloadTotal = workloadOrder + workloadWorker;
        long periodDays = periodDays(controls, items);
        long openRiskCount = riskIncidents.stream().filter(risk -> risk.getStatus() == WorkerRiskIncidentStatus.OPEN).count();
        long unansweredClientCount = clientItems.stream().filter(item -> item.getStatus() == ClientChatUnansweredStatus.OPEN).count();
        long overdueOrderCount = monthlyCount(items, "OVERDUE_ORDERS");
        double avgDailyOverdue = round1(overdueOrderCount / (double) periodDays);
        double avgDailyAction = round1(actionTotal / (double) periodDays);
        double avgDailyWorkload = round1(workloadTotal + avgDailyAction);
        long incomingProblemCount = actionTotal + createdInPeriod(clientItems) + createdInPeriod(riskIncidents);
        long backlogCount = openCount + openRiskCount + unansweredClientCount;
        double workloadIndex = round1(
                workloadOrder * 1.0
                        + workloadWorker * 0.7
                        + avgDailyAction * 3.0
                        + openRiskCount * 4.0
                        + unansweredClientCount * 3.0
                        + avgDailyOverdue * 3.5
        );

        double averageOverdueAgeDays = averageOverdueAgeDays(concreteItems);
        double eligibleOrderEpisodes = workloadOrder * (double) periodDays;
        double overdueBase = Math.max(1.0, Math.max(overdueOrderCount,
                Math.max(actionTotal, eligibleOrderEpisodes)));
        double overdueRate = round1(Math.min(100.0, (overdueOrderCount * 100.0) / overdueBase));
        double reopenRate = round1(reopenRate(controls));
        double riskResolutionAvgHours = round1(averageRiskResolutionHours(riskIncidents));
        double clientReplyMedianMinutes = round1(clientReplyPercentileMinutes(clientItems, 0.50));
        double clientReplyP90Minutes = round1(clientReplyPercentileMinutes(clientItems, 0.90));

        int problemSpeedScore = slaSpeedScore(problemSla);
        int clientResponseScore = slaSpeedScore(clientSla);
        int overdueControlScore = clampScore((int) Math.round(100 - overdueRate * 1.5 - averageOverdueAgeDays * 2));
        int riskQualityScore = riskQualityScore(riskIncidents);
        int specialistRiskScore = clampScore((int) Math.round(
                (slaSpeedScore(specialistSla) * 0.40)
                        + (slaSpeedScore(riskSla) * 0.35)
                        + (riskQualityScore * 0.25)
        ));
        int controlDisciplineScore = controlDisciplineScore(controls, acceptedCount, closedCount, fastClickCount);
        int stabilityScore = clampScore((int) Math.round(100 - reopenRate - deferredRate(items) * 0.5));
        boolean hasBasePerformanceData = hasPerformanceData(controls, concreteItems, clientItems, riskIncidents);
        int basePerformanceScore = hasBasePerformanceData
                ? clampScore((int) Math.round(
                problemSpeedScore * 0.20
                        + clientResponseScore * 0.25
                        + overdueControlScore * 0.25
                        + specialistRiskScore * 0.15
                        + controlDisciplineScore * 0.10
                        + stabilityScore * 0.05
        ))
                : 0;
        ManagerTeamProgressService.TeamProgressStats safeTeamProgress = teamProgress == null
                ? ManagerTeamProgressService.TeamProgressStats.empty()
                : teamProgress;
        int performanceScore = safeTeamProgress.hasData()
                ? clampScore((int) Math.round(basePerformanceScore * 0.85 + safeTeamProgress.score() * 0.15))
                : basePerformanceScore;
        int loadAdjustedPerformanceScore = loadAdjustedPerformanceScore(performanceScore, workloadIndex, incomingProblemCount);

        return new ManagerPerformanceScoreResponse(
                manager.getId(),
                managerUserId(manager),
                performanceScore,
                loadAdjustedPerformanceScore,
                grade(loadAdjustedPerformanceScore, !controls.isEmpty() || safeTeamProgress.hasData()),
                workloadIndex,
                workloadLevel(workloadIndex),
                workloadTotal,
                workloadOrder,
                workloadWorker,
                actionTotal,
                incomingProblemCount,
                backlogCount,
                avgDailyWorkload,
                avgDailyOverdue,
                openCount,
                handledCount,
                round1(problemSla.rate()),
                round1(clientSla.rate()),
                overdueRate,
                averageOverdueAgeDays,
                clientReplyMedianMinutes,
                clientReplyP90Minutes,
                riskResolutionAvgHours,
                reopenRate,
                acceptedCount,
                closedCount,
                fastClickCount,
                problemSpeedScore,
                clientResponseScore,
                overdueControlScore,
                specialistRiskScore,
                riskQualityScore,
                controlDisciplineScore,
                stabilityScore,
                safeTeamProgress.eligibleDays(),
                safeTeamProgress.reached100Days(),
                safeTeamProgress.incompleteDays(),
                safeTeamProgress.reached100Rate(),
                safeTeamProgress.averageProgressPercent(),
                safeTeamProgress.missedWorkerDays(),
                safeTeamProgress.score()
        );
    }

    private Map<Long, List<WorkerRiskIncident>> riskIncidentsByWorkerUserId(
            List<Manager> managers,
            LocalDateTime from,
            LocalDateTime to
    ) {
        List<Long> workerUserIds = managers.stream()
                .flatMap(manager -> workerUserIds(manager).stream())
                .distinct()
                .toList();
        if (workerUserIds.isEmpty()) {
            return Map.of();
        }
        return riskIncidentRepository
                .findPerformanceIncidents(workerUserIds, from, to, WorkerRiskIncidentStatus.OPEN)
                .stream()
                .collect(Collectors.groupingBy(WorkerRiskIncident::getWorkerUserId));
    }

    private SlaStats concreteSla(
            List<ManagerDailyControlConcreteItem> items,
            Duration sla,
            LocalDateTime evaluatedAt
    ) {
        long total = 0;
        long inSla = 0;
        long speedScoreSum = 0;
        for (ManagerDailyControlConcreteItem item : items) {
            if (item.getCreatedAt() == null) {
                continue;
            }
            LocalDateTime actionAt = earliestNonNull(item.getLastManualTouchAt(), item.getResolvedAt());
            Duration elapsed = slaElapsed(item.getCreatedAt(), actionAt, evaluatedAt);
            if (elapsed == null) {
                continue;
            }
            total++;
            speedScoreSum += speedScore(elapsed, sla);
            if (elapsed != null && elapsed.compareTo(sla) <= 0) {
                inSla++;
            }
        }
        return new SlaStats(total, inSla, speedScoreSum);
    }

    private SlaStats clientSla(List<ClientChatUnansweredItem> items, LocalDateTime evaluatedAt) {
        long total = 0;
        long inSla = 0;
        long speedScoreSum = 0;
        for (ClientChatUnansweredItem item : items) {
            if (item.getLastClientMessageAt() == null) {
                continue;
            }
            LocalDateTime answerAt = item.getClosedAt();
            Duration sla = Duration.ofMinutes(CLIENT_REPLY_SLA_MINUTES);
            Duration elapsed = slaElapsed(item.getLastClientMessageAt(), answerAt, evaluatedAt);
            if (elapsed == null) {
                continue;
            }
            total++;
            speedScoreSum += speedScore(elapsed, sla);
            if (elapsed != null && elapsed.compareTo(sla) <= 0) {
                inSla++;
            }
        }
        return new SlaStats(total, inSla, speedScoreSum);
    }

    private SlaStats riskSla(List<WorkerRiskIncident> risks, LocalDateTime evaluatedAt) {
        long total = 0;
        long inSla = 0;
        long speedScoreSum = 0;
        for (WorkerRiskIncident risk : risks) {
            if (risk.getCreatedAt() == null) {
                continue;
            }
            LocalDateTime actionAt = earliestNonNull(
                    risk.getResolvedAt(),
                    risk.getExplanationRequestedAt()
            );
            Duration sla = Duration.ofHours(RISK_SLA_HOURS);
            Duration elapsed = slaElapsed(risk.getCreatedAt(), actionAt, evaluatedAt);
            if (elapsed == null) {
                continue;
            }
            total++;
            speedScoreSum += speedScore(elapsed, sla);
            if (elapsed != null && elapsed.compareTo(sla) <= 0) {
                inSla++;
            }
        }
        return new SlaStats(total, inSla, speedScoreSum);
    }

    private long workloadCount(List<ManagerDailyControlItem> items, String reasonCode) {
        long days = Math.max(1, items.stream()
                .map(ManagerDailyControlItem::getControl)
                .filter(Objects::nonNull)
                .map(ManagerDailyControl::getControlDate)
                .filter(Objects::nonNull)
                .distinct()
                .count());
        long total = items.stream()
                .filter(item -> reasonCode.equals(item.getReasonCode()))
                .mapToLong(ManagerDailyControlItem::getCount)
                .sum();
        return Math.round(total / (double) days);
    }

    private long monthlyCount(List<ManagerDailyControlItem> items, String reasonCode) {
        return items.stream()
                .filter(item -> reasonCode.equals(item.getReasonCode()))
                .mapToLong(ManagerDailyControlItem::getCount)
                .sum();
    }

    private long periodDays(List<ManagerDailyControl> controls, List<ManagerDailyControlItem> items) {
        long controlDays = controls.stream()
                .map(ManagerDailyControl::getControlDate)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        if (controlDays > 0) {
            return controlDays;
        }
        return Math.max(1, items.stream()
                .map(ManagerDailyControlItem::getControl)
                .filter(Objects::nonNull)
                .map(ManagerDailyControl::getControlDate)
                .filter(Objects::nonNull)
                .distinct()
                .count());
    }

    private List<ManagerDailyControlItem> actionItems(List<ManagerDailyControlItem> items) {
        return items.stream()
                .filter(item -> item.getGroup() == ManagerDailyControlGroup.ACTION)
                .filter(item -> item.getItemType() != ManagerDailyControlItemType.ORDER_STATUS)
                .toList();
    }

    private double averageOverdueAgeDays(List<ManagerDailyControlConcreteItem> items) {
        return items.stream()
                .filter(item -> "ORDER".equals(item.getEntityType()))
                .filter(item -> item.getParentItem() != null)
                .filter(item -> "OVERDUE_ORDERS".equals(item.getParentItem().getReasonCode()))
                .filter(item -> item.getStatus() == null || item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                .map(ManagerDailyControlConcreteItem::getAgeDays)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);
    }

    private double reopenRate(List<ManagerDailyControl> controls) {
        if (controls.isEmpty()) {
            return 0;
        }
        long reopened = controlEventRepository.countByControlInAndEventType(
                controls,
                ManagerDailyControlEventType.CONTROL_REOPENED
        );
        return Math.min(100.0, (reopened * 100.0) / controls.size());
    }

    private double deferredRate(List<ManagerDailyControlItem> items) {
        long action = items.stream()
                .filter(item -> item.getGroup() == ManagerDailyControlGroup.ACTION)
                .count();
        if (action == 0) {
            return 0;
        }
        long deferred = items.stream()
                .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.DEFERRED)
                .count();
        return (deferred * 100.0) / action;
    }

    private double averageRiskResolutionHours(List<WorkerRiskIncident> risks) {
        return risks.stream()
                .filter(risk -> risk.getCreatedAt() != null && risk.getResolvedAt() != null)
                .filter(risk -> !risk.getResolvedAt().isBefore(risk.getCreatedAt()))
                .mapToLong(risk -> Duration.between(risk.getCreatedAt(), risk.getResolvedAt()).toHours())
                .average()
                .orElse(0);
    }

    private double clientReplyPercentileMinutes(List<ClientChatUnansweredItem> items, double percentile) {
        List<Long> durations = items.stream()
                .filter(item -> item.getLastClientMessageAt() != null && item.getClosedAt() != null)
                .filter(item -> !item.getClosedAt().isBefore(item.getLastClientMessageAt()))
                .map(item -> Duration.between(item.getLastClientMessageAt(), item.getClosedAt()).toMinutes())
                .sorted()
                .toList();
        if (durations.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(Math.max(0, Math.min(1, percentile)) * durations.size()) - 1;
        return durations.get(Math.max(0, Math.min(durations.size() - 1, index)));
    }

    private long createdInPeriod(List<?> items) {
        return items == null ? 0 : items.size();
    }

    private int riskQualityScore(List<WorkerRiskIncident> risks) {
        if (risks.isEmpty()) {
            return 100;
        }
        double average = risks.stream()
                .mapToInt(this::riskQualityItemScore)
                .average()
                .orElse(100);
        return clampScore((int) Math.round(average));
    }

    private int riskQualityItemScore(WorkerRiskIncident risk) {
        if (risk == null) {
            return 0;
        }
        if (risk.getStatus() == WorkerRiskIncidentStatus.RESOLVED
                || risk.getStatus() == WorkerRiskIncidentStatus.IGNORED) {
            return 100;
        }
        if (risk.getStatus() == WorkerRiskIncidentStatus.VIOLATION) {
            return 90;
        }
        return switch (risk.getResolutionAction() == null ? "" : risk.getResolutionAction().name()) {
            case "VIOLATION_CONFIRMED" -> 85;
            case "WORKER_WARNED" -> 70;
            case "EXPLANATION_REQUESTED" -> 60;
            case "VERIFIED", "FALSE_POSITIVE", "NORMAL_ACCOUNT_SELECTION" -> 100;
            default -> risk.getExplanationRequestedAt() != null ? 50 : 0;
        };
    }

    private int controlDisciplineScore(
            List<ManagerDailyControl> controls,
            long acceptedCount,
            long closedCount,
            long fastClickCount
    ) {
        if (controls.isEmpty()) {
            return 0;
        }
        double total = controls.size();
        return clampScore((int) Math.round(
                (acceptedCount / total) * 45
                        + (closedCount / total) * 45
                        + 10
                        - (fastClickCount / total) * 20
        ));
    }

    private int slaSpeedScore(SlaStats stats) {
        if (stats.total() == 0) {
            return 100;
        }
        return clampScore((int) Math.round(stats.averageSpeedScore()));
    }

    private boolean hasPerformanceData(
            List<ManagerDailyControl> controls,
            List<ManagerDailyControlConcreteItem> concreteItems,
            List<ClientChatUnansweredItem> clientItems,
            List<WorkerRiskIncident> riskIncidents
    ) {
        return !controls.isEmpty()
                || !concreteItems.isEmpty()
                || !clientItems.isEmpty()
                || !riskIncidents.isEmpty();
    }

    private int loadAdjustedPerformanceScore(int performanceScore, double workloadIndex, long incomingProblemCount) {
        if (performanceScore <= 0 && incomingProblemCount == 0) {
            return 0;
        }
        double bonus = 0;
        if (workloadIndex >= 180) {
            bonus = 6;
        } else if (workloadIndex >= 90) {
            bonus = 4;
        } else if (workloadIndex >= 35) {
            bonus = 2;
        }
        if (performanceScore < 55) {
            bonus *= 0.5;
        }
        if (incomingProblemCount == 0 && workloadIndex < 10) {
            bonus -= 3;
        }
        return clampScore((int) Math.round(performanceScore + bonus));
    }

    private int speedScore(Duration elapsed, Duration sla) {
        if (elapsed == null || sla == null || sla.isZero() || sla.isNegative()) {
            return 0;
        }
        double ratio = Math.max(0, elapsed.toNanos()) / (double) sla.toNanos();
        if (ratio <= 0.25) {
            return 100;
        }
        if (ratio <= 0.50) {
            return 85;
        }
        if (ratio <= 0.75) {
            return 70;
        }
        if (ratio <= 1.00) {
            return 55;
        }
        if (ratio <= 1.50) {
            return 35;
        }
        if (ratio <= 2.00) {
            return 20;
        }
        return 0;
    }

    private Duration slaElapsed(
            LocalDateTime startedAt,
            LocalDateTime actionAt,
            LocalDateTime evaluatedAt
    ) {
        if (startedAt == null) {
            return null;
        }
        LocalDateTime effectiveActionAt = actionAt == null ? evaluatedAt : actionAt;
        if (effectiveActionAt == null || effectiveActionAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, effectiveActionAt);
    }

    private boolean isEffectivelyClosed(
            ManagerDailyControl control,
            Map<Long, List<ManagerDailyControlItem>> itemsByControlId,
            Map<Long, List<ManagerDailyControlConcreteItem>> concreteByControlId
    ) {
        if (control.getClosedAt() != null) {
            return true;
        }
        if (control.getId() == null) {
            return false;
        }
        List<ManagerDailyControlItem> controlItems = itemsByControlId.getOrDefault(control.getId(), List.of());
        boolean hasOpenActionItem = actionItems(controlItems).stream()
                .anyMatch(item -> item.getStatus() == null || item.getStatus() == ManagerDailyControlItemStatus.OPEN);
        if (hasOpenActionItem) {
            return false;
        }
        return concreteByControlId.getOrDefault(control.getId(), List.of()).stream()
                .filter(item -> item.getParentItem() != null)
                .filter(item -> item.getParentItem().getGroup() == ManagerDailyControlGroup.ACTION)
                .filter(item -> item.getParentItem().getItemType() != ManagerDailyControlItemType.ORDER_STATUS)
                .noneMatch(item -> item.getStatus() == null || item.getStatus() == ManagerDailyControlItemStatus.OPEN);
    }

    private int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String grade(int score, boolean hasData) {
        if (!hasData) {
            return "-";
        }
        return ManagerPerformanceGrade.of(score);
    }

    private String workloadLevel(double workloadIndex) {
        if (workloadIndex >= 180) {
            return "EXTREME";
        }
        if (workloadIndex >= 90) {
            return "HIGH";
        }
        if (workloadIndex >= 35) {
            return "NORMAL";
        }
        return "LOW";
    }

    private List<Long> workerUserIds(Manager manager) {
        User user = manager == null ? null : manager.getUser();
        if (user == null || user.getWorkers() == null) {
            return List.of();
        }
        return user.getWorkers().stream()
                .filter(Objects::nonNull)
                .map(Worker::getUser)
                .filter(Objects::nonNull)
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Long managerUserId(Manager manager) {
        return manager == null || manager.getUser() == null ? null : manager.getUser().getId();
    }

    private LocalDateTime earliestNonNull(LocalDateTime... values) {
        return java.util.Arrays.stream(values)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record SlaStats(long total, long inSla, long speedScoreSum) {
        double rate() {
            return total == 0 ? 100 : (inSla * 100.0) / total;
        }

        double averageSpeedScore() {
            return total == 0 ? 100 : speedScoreSum / (double) total;
        }
    }
}
