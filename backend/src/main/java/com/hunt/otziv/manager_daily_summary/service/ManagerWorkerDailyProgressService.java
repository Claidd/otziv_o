package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.api.FinalWorkerDayResults;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerWorkerDailyProgressService {

    private final ManagerRepository managerRepository;
    private final StaffDailyProgressService progressService;
    private final FinalWorkerDayResults achievementService;
    private final ManagerWorkerDayPlanReader dayPlanReader;

    /*
     * Historical progress reads reconcile the finalized workload projection
     * before returning it. Keep this orchestration transaction write-capable:
     * a read-only outer transaction would otherwise force the nested
     * reconciliation UPDATE to fail and leave the returned history stale.
     */
    @Transactional
    public Map<Long, ManagerWorkerProgress> progressByManagerIds(
            Collection<Long> managerIds,
            LocalDate date
    ) {
        if (managerIds == null || managerIds.isEmpty() || date == null || !progressService.progressEnabled()) {
            return Map.of();
        }
        Set<Long> selectedIds = managerIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (selectedIds.isEmpty()) {
            return Map.of();
        }

        List<Manager> selected = managerRepository.findAllWithUserAndImage().stream()
                .filter(manager -> manager.getId() != null && selectedIds.contains(manager.getId()))
                .toList();
        if (selected.isEmpty()) {
            return Map.of();
        }
        List<Manager> expanded = managerRepository.findAllManagersWorkers(selected);
        Map<Long, Worker> workers = expanded.stream()
                .filter(manager -> manager.getUser() != null && manager.getUser().getWorkers() != null)
                .flatMap(manager -> manager.getUser().getWorkers().stream())
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .collect(Collectors.toMap(
                        Worker::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<Long, DailyWorkProgressResponse> current = progressService.workerEndOfDayProgressByWorkers(
                workers.values(),
                date,
                null
        );
        Map<Long, DailyWorkProgressResponse> previous = progressService.workerEndOfDayProgressByWorkers(
                workers.values(),
                date.minusDays(1),
                null
        );

        Map<Long, ManagerWorkerProgress> result = new LinkedHashMap<>();
        for (Manager manager : expanded) {
            if (manager.getId() == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
                continue;
            }
            List<WorkerProgress> rows = manager.getUser().getWorkers().stream()
                    .filter(Objects::nonNull)
                    .filter(worker -> worker.getId() != null)
                    .map(worker -> new WorkerProgress(
                            worker.getId(),
                            workerName(worker),
                            current.get(worker.getId()),
                            previous.get(worker.getId())
                    ))
                    .sorted(java.util.Comparator.comparing(WorkerProgress::workerName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            result.put(manager.getId(), aggregate(manager.getId(), rows, date));
        }
        return result;
    }

    @Transactional
    public List<MissedWorkerGoal> missedPreviousDayGoals(Long managerId, LocalDate reportDate) {
        if (managerId == null || reportDate == null) {
            return List.of();
        }
        List<Manager> selected = managerRepository.findAllWithUserAndImage().stream()
                .filter(manager -> managerId.equals(manager.getId()))
                .toList();
        if (selected.isEmpty()) {
            return List.of();
        }
        Manager manager = managerRepository.findAllManagersWorkers(selected).stream()
                .filter(candidate -> managerId.equals(candidate.getId()))
                .findFirst().orElse(null);
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return List.of();
        }
        List<Worker> workers = manager.getUser().getWorkers().stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .sorted(java.util.Comparator.comparing(this::workerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        LocalDate goalDate = reportDate.minusDays(1);
        Map<Long, FinalWorkerDayResults.WorkerDayResult> results = achievementService.workerDayResults(
                workers.stream().map(Worker::getId).toList(), goalDate);
        List<Worker> missed = workers.stream()
                .filter(worker -> {
                    FinalWorkerDayResults.WorkerDayResult result = results.get(worker.getId());
                    return result != null && result.eligibleCount() > 0 && !result.reached100();
                })
                .toList();
        if (missed.isEmpty()) return List.of();
        Map<Long, ManagerWorkerDayPlanReader.DayPlan> plans =
                dayPlanReader.plans(missed.stream().map(Worker::getId).toList(), goalDate);
        return missed.stream()
                .map(worker -> missedGoal(worker, results.get(worker.getId()),
                        plans == null ? null : plans.get(worker.getId()), goalDate))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The end-of-day result already excludes late work. Its final snapshot still counts
     * work blocked by an unavailable account; the specialist could not do that work, so
     * it is not a remark. When nothing else is left, the day is not a missed goal.
     */
    private MissedWorkerGoal missedGoal(Worker worker, FinalWorkerDayResults.WorkerDayResult result,
                                        ManagerWorkerDayPlanReader.DayPlan plan, LocalDate goalDate) {
        boolean sameSnapshot = plan != null && plan.finalized() && plan.eligibleUnits() == result.eligibleCount();
        long blocked = sameSnapshot ? Math.min(plan.externalBlockedUnits(), result.eligibleCount()) : 0;
        long required = Math.max(0, result.eligibleCount() - blocked);
        if (result.completedCount() >= required) return null;
        return new MissedWorkerGoal(worker.getId(), workerName(worker), goalDate,
                result.completedCount(), required, blocked,
                plan == null ? List.of() : plan.unfinished(),
                sameSnapshot ? plan.lastReached100At() : null);
    }

    record FollowingWorkerGoal(LocalDate date, long completed, long total, boolean reached100) { }

    /** Input is an authorized goal returned above; never expose arbitrary workers through this helper. */
    List<FollowingWorkerGoal> followingDayResults(MissedWorkerGoal authorizedGoal, LocalDate today) {
        if (authorizedGoal==null || authorizedGoal.workerId()==null || authorizedGoal.date()==null || today==null) return List.of();
        List<FollowingWorkerGoal> result=new java.util.ArrayList<>();
        for(int offset=1;offset<=2;offset++) {
            LocalDate date=authorizedGoal.date().plusDays(offset);
            if (!date.isBefore(today)) break;
            var rows=achievementService.workerDayResults(List.of(authorizedGoal.workerId()),date);
            var day=rows==null?null:rows.get(authorizedGoal.workerId());
            if (day!=null) result.add(new FollowingWorkerGoal(date,day.completedCount(),day.eligibleCount(),day.reached100()));
        }
        return List.copyOf(result);
    }

    private ManagerWorkerProgress aggregate(
            Long managerId,
            List<WorkerProgress> workers,
            LocalDate date
    ) {
        DailyWorkProgressResponse progressBar = progressService.aggregateTeamProgressResponses(
                workers.stream()
                        .map(WorkerProgress::current)
                        .filter(Objects::nonNull)
                        .toList(),
                workers.stream()
                        .map(WorkerProgress::workerId)
                        .filter(Objects::nonNull)
                        .toList(),
                date,
                "WORKER_TEAM"
        );
        return new ManagerWorkerProgress(
                managerId,
                workers,
                workers.stream().map(WorkerProgress::current).filter(Objects::nonNull)
                        .mapToLong(DailyWorkProgressResponse::completed).sum(),
                workers.stream().map(WorkerProgress::current).filter(Objects::nonNull)
                        .mapToLong(DailyWorkProgressResponse::total).sum(),
                workers.stream().map(WorkerProgress::current).filter(Objects::nonNull)
                        .mapToLong(DailyWorkProgressResponse::active).sum(),
                workers.stream().map(WorkerProgress::current).filter(Objects::nonNull)
                        .mapToLong(DailyWorkProgressResponse::totalOverdueCount).sum(),
                progressBar
        );
    }

    private String workerName(Worker worker) {
        if (worker.getUser() == null) {
            return "Работник #" + worker.getId();
        }
        if (hasText(worker.getUser().getFio())) {
            return worker.getUser().getFio().trim();
        }
        if (hasText(worker.getUser().getUsername())) {
            return worker.getUser().getUsername().trim();
        }
        return "Работник #" + worker.getId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ManagerWorkerProgress(
            Long managerId,
            List<WorkerProgress> workers,
            long completed,
            long total,
            long active,
            long overdue,
            DailyWorkProgressResponse progressBar
    ) {
        public ManagerWorkerProgress {
            workers = workers == null ? List.of() : List.copyOf(workers);
        }
    }

    public record WorkerProgress(
            Long workerId,
            String workerName,
            DailyWorkProgressResponse current,
            DailyWorkProgressResponse previous
    ) {
    }

    /**
     * @param total units the specialist could and had to finish: the finalized goal without
     *              late work and without work blocked by an unavailable account
     * @param blockedUnits units excluded because the account was unavailable
     * @param unfinishedTasks unfinished mandatory work of the goal day; units of one order
     *                        and section are one task. Empty when the day plan is unknown.
     * @param lastReached100At when the specialist last had the whole required load closed
     */
    public record MissedWorkerGoal(
            Long workerId,
            String workerName,
            LocalDate date,
            long completed,
            long total,
            long blockedUnits,
            List<ManagerWorkerDayPlanReader.UnfinishedTask> unfinishedTasks,
            java.time.LocalDateTime lastReached100At
    ) {
        public MissedWorkerGoal {
            unfinishedTasks = unfinishedTasks == null ? List.of() : List.copyOf(unfinishedTasks);
        }

        public MissedWorkerGoal(Long workerId, String workerName, LocalDate date, long completed, long total) {
            this(workerId, workerName, date, completed, total, 0, List.of(), null);
        }

        public long remaining() {
            return Math.max(0, total - completed);
        }
    }
}
