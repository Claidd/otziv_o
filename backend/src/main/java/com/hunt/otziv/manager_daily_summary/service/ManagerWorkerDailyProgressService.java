package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
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
}
