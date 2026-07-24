package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.EndOfDayAchievementService;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerTeamProgressEndOfDayJob {

    private static final ZoneId PROGRESS_ZONE = ZoneId.of("Asia/Irkutsk");

    private final ManagerRepository managerRepository;
    private final WorkerService workerService;
    private final StaffDailyProgressService staffDailyProgressService;
    private final ManagerTeamProgressService managerTeamProgressService;
    private final ManagerPerformanceService managerPerformanceService;
    private final EndOfDayAchievementService achievementService;

    @Scheduled(cron = "${manager.performance.team-progress-cron:50 59 23 * * *}", zone = "${worker.progress.zone:Asia/Irkutsk}")
    public void capture() {
        LocalDate date = LocalDate.now(PROGRESS_ZONE);
        List<Manager> managers = managerRepository.findAllWithUserAndImage();
        if (!managers.isEmpty()) {
            managers = managerRepository.findAllManagersWorkers(managers);
        }
        List<Worker> allWorkers = workerService.getAllWorkers();
        Set<Long> expectedWorkerIds = allWorkers.stream()
                .filter(Objects::nonNull)
                .map(Worker::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, DailyWorkProgressResponse> rawProgressByWorker = staffDailyProgressService.workerProgressByWorkers(
                allWorkers,
                date
        );
        if (!expectedWorkerIds.isEmpty() && !rawProgressByWorker.keySet().containsAll(expectedWorkerIds)) {
            log.warn("Skipped manager team end-of-day snapshot for {}: worker progress is unavailable", date);
            return;
        }
        LocalDateTime cutoff = date.atTime(23, 0);
        Map<Long, DailyWorkProgressResponse> progressByWorker = staffDailyProgressService
                .workerEndOfDayProgressByWorkers(allWorkers, date, cutoff);
        if (!expectedWorkerIds.isEmpty() && !progressByWorker.keySet().containsAll(expectedWorkerIds)) {
            log.warn("Skipped end-of-day achievements for {}: adjusted worker progress is unavailable", date);
            return;
        }
        Map<Long, Long> ignoredLateByWorker = allWorkers.stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Worker::getId,
                        worker -> ignoredLateCount(
                                rawProgressByWorker.get(worker.getId()),
                                progressByWorker.get(worker.getId())
                        ),
                        Math::max
                ));
        for (Worker worker : allWorkers) {
            if (worker == null || worker.getId() == null) {
                continue;
            }
            DailyWorkProgressResponse progress = progressByWorker.get(worker.getId());
            if (!isEligible(progress)) {
                continue;
            }
            EndOfDayAchievementService.AchievementResult result = achievementService.saveResult(
                    date,
                    EndOfDayAchievementService.ROLE_WORKER,
                    worker.getId(),
                    worker.getUser() == null ? null : worker.getUser().getId(),
                    progress.total(),
                    progress.completed(),
                    progress.percent(),
                    ignoredLateByWorker.getOrDefault(worker.getId(), 0L),
                    isAt100(progress)
            );
            achievementService.notifyWorker(worker, result);
        }
        for (Manager manager : managers) {
            User managerUser = manager.getUser();
            Set<Worker> workers = managerUser == null || managerUser.getWorkers() == null
                    ? Set.of()
                    : managerUser.getWorkers();
            List<DailyWorkProgressResponse> progress = workers.stream()
                    .filter(Objects::nonNull)
                    .map(Worker::getId)
                    .filter(Objects::nonNull)
                    .map(progressByWorker::get)
                    .filter(Objects::nonNull)
                    .toList();
            List<DailyWorkProgressResponse> eligibleProgress = progress.stream()
                    .filter(ManagerTeamProgressEndOfDayJob::isEligible)
                    .toList();
            managerTeamProgressService.saveEndOfDaySnapshot(
                    date,
                    manager.getId(),
                    managerUser == null ? null : managerUser.getId(),
                    workers.size(),
                    eligibleProgress
            );
            if (eligibleProgress.isEmpty()) {
                continue;
            }
            long workersAt100 = eligibleProgress.stream().filter(ManagerTeamProgressEndOfDayJob::isAt100).count();
            long ignoredLateCount = workers.stream()
                    .filter(Objects::nonNull)
                    .map(Worker::getId)
                    .filter(Objects::nonNull)
                    .mapToLong(workerId -> ignoredLateByWorker.getOrDefault(workerId, 0L))
                    .sum();
            double averageProgress = eligibleProgress.stream()
                    .mapToInt(DailyWorkProgressResponse::percent)
                    .sum() / (double) eligibleProgress.size();
            EndOfDayAchievementService.AchievementResult managerResult = achievementService.saveResult(
                    date,
                    EndOfDayAchievementService.ROLE_MANAGER,
                    manager.getId(),
                    managerUser == null ? null : managerUser.getId(),
                    eligibleProgress.size(),
                    workersAt100,
                    averageProgress,
                    ignoredLateCount,
                    workersAt100 == eligibleProgress.size()
            );
            achievementService.notifyManager(manager, managerResult);
        }
        managerPerformanceService.invalidate();
        log.info("Captured manager team end-of-day progress for {} managers on {}", managers.size(), date);
    }

    private static boolean isAt100(DailyWorkProgressResponse progress) {
        return isEligible(progress) && progress.percent() >= 100 && progress.active() <= 0;
    }

    private static boolean isEligible(DailyWorkProgressResponse progress) {
        return progress != null && progress.visible() && progress.total() > 0;
    }

    private static long ignoredLateCount(
            DailyWorkProgressResponse raw,
            DailyWorkProgressResponse adjusted
    ) {
        if (raw == null || adjusted == null) {
            return 0;
        }
        return Math.max(0, raw.total() - adjusted.total());
    }
}
