package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.EndOfDayAchievementService;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProgressReadService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProgressReadService.Progress;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowCoordinator;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
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
    private final WorkloadShadowProgressReadService workloadShadowProgressReadService;
    private final WorkloadShadowSettingsService workloadShadowSettingsService;
    private final WorkloadShadowCoordinator workloadShadowCoordinator;

    @Scheduled(cron = "${manager.performance.team-progress-prepare-cron:45 59 23 * * *}", zone = "${worker.progress.zone:Asia/Irkutsk}")
    public void prepareFinalProjection() {
        var workloadSettings = workloadShadowSettingsService.current();
        if (workloadSettings.observationEnabled()) {
            refreshFinalProjection(LocalDate.now(PROGRESS_ZONE));
        }
    }

    @Scheduled(cron = "${manager.performance.team-progress-cron:10 4 0 * * *}", zone = "${worker.progress.zone:Asia/Irkutsk}")
    public void capture() {
        // The report is deliberately settled after midnight so an action made at
        // 23:59:59 belongs to the day that has just ended.
        LocalDate date = LocalDate.now(PROGRESS_ZONE).minusDays(1);
        var workloadSettings = workloadShadowSettingsService.current();
        if (workloadSettings.observationEnabled()
                && workloadShadowCoordinator.isRunning()
                && !waitForProjection(date)) {
            return;
        }
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
        if (!completeAndStable(rawProgressByWorker, expectedWorkerIds)) {
            log.warn("Skipped manager team end-of-day snapshot for {}: worker progress is unavailable", date);
            return;
        }
        LocalDateTime cutoff = date.atTime(
                workloadShadowSettingsService.shiftEnd(workloadSettings)
        );
        Map<Long, DailyWorkProgressResponse> progressByWorker = staffDailyProgressService
                .workerEndOfDayProgressByWorkers(allWorkers, date, cutoff);
        if (!completeAndStable(progressByWorker, expectedWorkerIds)) {
            log.warn("Skipped end-of-day achievements for {}: adjusted worker progress is unavailable", date);
            return;
        }
        Map<Long, Progress> finalWorkload = workloadSettings.observationEnabled()
                ? workloadShadowProgressReadService.findFinalizedProgress(expectedWorkerIds, date)
                : Map.of();
        if (workloadSettings.observationEnabled()
                && !canonicalCoverageComplete(finalWorkload, progressByWorker)) {
            log.warn("Skipped end-of-day achievements for {}: finalized workload progress is incomplete", date);
            return;
        }
        Map<Long, Long> ignoredLateByWorker = allWorkers.stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Worker::getId,
                        worker -> finalWorkload.containsKey(worker.getId())
                                ? finalWorkload.get(worker.getId()).lateExcluded()
                                : ignoredLateCount(
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
                    recognizedPercent(progress),
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
                    .mapToInt(ManagerTeamProgressEndOfDayJob::recognizedPercent)
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
        return isEligible(progress) && progress.reached100();
    }

    private static boolean completeAndStable(
            Map<Long, DailyWorkProgressResponse> progress,
            Set<Long> expectedWorkerIds
    ) {
        if (progress == null) {
            return false;
        }
        if (expectedWorkerIds != null
                && !expectedWorkerIds.isEmpty()
                && !progress.keySet().containsAll(expectedWorkerIds)) {
            return false;
        }
        return progress.values().stream()
                .filter(Objects::nonNull)
                .noneMatch(DailyWorkProgressResponse::updating);
    }

    private static boolean canonicalCoverageComplete(
            Map<Long, Progress> workload,
            Map<Long, DailyWorkProgressResponse> visibleProgress
    ) {
        if (workload == null || visibleProgress == null) {
            return false;
        }
        return visibleProgress.entrySet().stream()
                .filter(entry -> isEligible(entry.getValue()))
                .map(Map.Entry::getKey)
                .allMatch(workload::containsKey);
    }

    private boolean refreshFinalProjection(LocalDate date) {
        if (workloadShadowCoordinator.isRunning()) {
            return waitForProjection(date);
        }
        try {
            workloadShadowCoordinator.recalculate("END_OF_DAY");
            return true;
        } catch (RuntimeException exception) {
            if (workloadShadowCoordinator.isRunning()) {
                return waitForProjection(date);
            }
            log.error("Skipped end-of-day achievements for {}: final workload refresh failed", date, exception);
            return false;
        }
    }

    private boolean waitForProjection(LocalDate date) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (workloadShadowCoordinator.isRunning() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Skipped end-of-day achievements for {}: final workload refresh was interrupted", date);
                return false;
            }
        }
        if (workloadShadowCoordinator.isRunning()) {
            log.warn("Skipped end-of-day achievements for {}: final workload refresh did not finish in time", date);
            return false;
        }
        return true;
    }

    private static int recognizedPercent(DailyWorkProgressResponse progress) {
        return isAt100(progress) ? 100 : progress.percent();
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
