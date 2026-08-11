package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.EndOfDayAchievementService;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProgressReadService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProgressReadService.Progress;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowCoordinator;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerTeamProgressEndOfDayJobTest {

    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private WorkerService workerService;
    @Mock
    private StaffDailyProgressService staffDailyProgressService;
    @Mock
    private ManagerTeamProgressService managerTeamProgressService;
    @Mock
    private ManagerPerformanceService managerPerformanceService;
    @Mock
    private EndOfDayAchievementService achievementService;
    @Mock
    private WorkloadShadowProgressReadService workloadShadowProgressReadService;
    @Mock
    private WorkloadShadowSettingsService workloadShadowSettingsService;
    @Mock
    private WorkloadShadowSettingsResponse workloadSettings;
    @Mock
    private WorkloadShadowCoordinator workloadShadowCoordinator;

    @InjectMocks
    private ManagerTeamProgressEndOfDayJob job;

    @BeforeEach
    void setUp() {
        lenient().when(workloadShadowSettingsService.current()).thenReturn(workloadSettings);
        lenient().when(workloadShadowSettingsService.shiftEnd(workloadSettings))
                .thenReturn(LocalTime.of(22, 0));
        lenient().when(workloadSettings.observationEnabled()).thenReturn(true);
        Progress complete = new Progress(1, 1, 0, 0, 100, true, true, null, null);
        lenient().when(workloadShadowProgressReadService.findFinalizedProgress(any(), any()))
                .thenReturn(Map.of(1L, complete, 7L, complete, 9L, complete));
    }

    @Test
    void excludesTasksOpenedDuringLastHourFromWorkerAndManagerAchievement() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1);
        User workerUser = User.builder().id(70L).fio("Анна").build();
        Worker worker = Worker.builder().id(7L).user(workerUser).build();
        User idleWorkerUser = User.builder().id(90L).fio("Ирина").build();
        Worker idleWorker = Worker.builder().id(9L).user(idleWorkerUser).build();
        User managerUser = User.builder().id(80L).workers(Set.of(worker, idleWorker)).build();
        Manager manager = Manager.builder().id(8L).user(managerUser).build();
        DailyWorkProgressResponse raw = progress(date, 34, 1, 35, 97);
        DailyWorkProgressResponse adjusted = progress(date, 34, 0, 34, 100);
        DailyWorkProgressResponse withoutTasks = progress(date, 0, 0, 0, 100);
        EndOfDayAchievementService.AchievementResult workerResult = result(
                EndOfDayAchievementService.ROLE_WORKER, 7L, 34, 34, 1);
        EndOfDayAchievementService.AchievementResult managerResult = result(
                EndOfDayAchievementService.ROLE_MANAGER, 8L, 1, 1, 1);

        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(workerService.getAllWorkers()).thenReturn(List.of(worker, idleWorker));
        when(staffDailyProgressService.workerProgressByWorkers(List.of(worker, idleWorker), date))
                .thenReturn(Map.of(7L, raw, 9L, withoutTasks));
        when(staffDailyProgressService.workerEndOfDayProgressByWorkers(
                List.of(worker, idleWorker), date, date.atTime(22, 0)))
                .thenReturn(Map.of(7L, adjusted, 9L, withoutTasks));
        when(workloadShadowProgressReadService.findFinalizedProgress(Set.of(7L, 9L), date))
                .thenReturn(Map.of(
                        7L, new Progress(34, 34, 1, 0, 100, true, true, null, null),
                        9L, new Progress(0, 0, 0, 0, 100, false, false, null, null)
                ));
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_WORKER), eq(7L), eq(70L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(workerResult);
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_MANAGER), eq(8L), eq(80L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(managerResult);

        job.prepareFinalProjection();
        job.capture();

        verify(workloadShadowCoordinator).recalculate("END_OF_DAY");
        verify(staffDailyProgressService).workerEndOfDayProgressByWorkers(
                List.of(worker, idleWorker), date, date.atTime(22, 0));
        verify(achievementService).saveResult(
                date, EndOfDayAchievementService.ROLE_WORKER, 7L, 70L,
                34, 34, 100, 1, true);
        verify(achievementService).saveResult(
                date, EndOfDayAchievementService.ROLE_MANAGER, 8L, 80L,
                1, 1, 100, 1, true);
        verify(achievementService).notifyWorker(worker, workerResult);
        verify(achievementService).notifyManager(manager, managerResult);
        verify(managerTeamProgressService).saveEndOfDaySnapshot(
                eq(date), eq(8L), eq(80L), eq(2),
                argThat(items -> items.size() == 1 && items.iterator().next().total() == 34));
        verify(achievementService, never()).saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_WORKER), eq(9L), eq(90L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean());
        verify(managerPerformanceService).invalidate();
    }

    @Test
    void skipsWholeDayWhenAdjustedProgressIsIncomplete() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1);
        Worker first = Worker.builder().id(7L).build();
        Worker second = Worker.builder().id(9L).build();
        DailyWorkProgressResponse completed = progress(date, 1, 0, 1, 100);

        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of());
        when(workerService.getAllWorkers()).thenReturn(List.of(first, second));
        when(staffDailyProgressService.workerProgressByWorkers(List.of(first, second), date))
                .thenReturn(Map.of(7L, completed, 9L, completed));
        when(staffDailyProgressService.workerEndOfDayProgressByWorkers(
                List.of(first, second), date, date.atTime(22, 0)))
                .thenReturn(Map.of(7L, completed));

        job.capture();

        verifyNoInteractions(managerTeamProgressService, achievementService, managerPerformanceService);
    }

    @Test
    void reportsLateUnitsFromFinalizedWorkloadDecision() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1);
        User workerUser = User.builder().id(5L).fio("Елена").build();
        Worker worker = Worker.builder().id(1L).user(workerUser).build();
        User managerUser = User.builder().id(30L).workers(Set.of(worker)).build();
        Manager manager = Manager.builder().id(3L).user(managerUser).build();
        DailyWorkProgressResponse unified = progress(date, 46, 0, 46, 100);
        EndOfDayAchievementService.AchievementResult workerResult = result(
                EndOfDayAchievementService.ROLE_WORKER, 1L, 46, 46, 5);
        EndOfDayAchievementService.AchievementResult managerResult = result(
                EndOfDayAchievementService.ROLE_MANAGER, 3L, 1, 1, 5);

        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(workerService.getAllWorkers()).thenReturn(List.of(worker));
        when(staffDailyProgressService.workerProgressByWorkers(List.of(worker), date))
                .thenReturn(Map.of(1L, unified));
        when(staffDailyProgressService.workerEndOfDayProgressByWorkers(
                List.of(worker), date, date.atTime(22, 0)))
                .thenReturn(Map.of(1L, unified));
        when(workloadShadowProgressReadService.findFinalizedProgress(Set.of(1L), date))
                .thenReturn(Map.of(1L, new Progress(
                        46,
                        46,
                        5,
                        0,
                        100,
                        true,
                        true,
                        date.atTime(18, 54),
                        date.atTime(18, 54)
                )));
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_WORKER), eq(1L), eq(5L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(workerResult);
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_MANAGER), eq(3L), eq(30L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(managerResult);

        job.capture();

        verify(achievementService).saveResult(
                date, EndOfDayAchievementService.ROLE_WORKER, 1L, 5L,
                46, 46, 100, 5, true);
        verify(achievementService).saveResult(
                date, EndOfDayAchievementService.ROLE_MANAGER, 3L, 30L,
                1, 1, 100, 5, true);
    }

    @Test
    void reachedOnceCountsForWorkerAndManagerAfterLaterWorkArrives() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1);
        User workerUser = User.builder().id(5L).fio("Елена").build();
        Worker worker = Worker.builder().id(1L).user(workerUser).build();
        User managerUser = User.builder().id(30L).workers(Set.of(worker)).build();
        Manager manager = Manager.builder().id(3L).user(managerUser).build();
        DailyWorkProgressResponse reachedEarlier = progress(date, 30, 5, 35, 86, true);
        EndOfDayAchievementService.AchievementResult workerResult = result(
                EndOfDayAchievementService.ROLE_WORKER, 1L, 35, 30, 0);
        EndOfDayAchievementService.AchievementResult managerResult = result(
                EndOfDayAchievementService.ROLE_MANAGER, 3L, 1, 1, 0);

        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(workerService.getAllWorkers()).thenReturn(List.of(worker));
        when(staffDailyProgressService.workerProgressByWorkers(List.of(worker), date))
                .thenReturn(Map.of(1L, reachedEarlier));
        when(staffDailyProgressService.workerEndOfDayProgressByWorkers(
                List.of(worker), date, date.atTime(22, 0)))
                .thenReturn(Map.of(1L, reachedEarlier));
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_WORKER), eq(1L), eq(5L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(workerResult);
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_MANAGER), eq(3L), eq(30L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(managerResult);

        job.capture();

        verify(achievementService).saveResult(
                date, EndOfDayAchievementService.ROLE_WORKER, 1L, 5L,
                35, 30, 100, 0, true);
        verify(achievementService).saveResult(
                date, EndOfDayAchievementService.ROLE_MANAGER, 3L, 30L,
                1, 1, 100, 0, true);
        verify(managerTeamProgressService).saveEndOfDaySnapshot(
                eq(date), eq(3L), eq(30L), eq(1),
                argThat(items -> items.size() == 1 && items.iterator().next().reached100()));
        verify(achievementService).notifyWorker(worker, workerResult);
        verify(achievementService).notifyManager(manager, managerResult);
    }

    private DailyWorkProgressResponse progress(LocalDate date, long completed, long active, long total, int percent) {
        return progress(date, completed, active, total, percent, total > 0 && percent >= 100 && active <= 0);
    }

    private DailyWorkProgressResponse progress(
            LocalDate date,
            long completed,
            long active,
            long total,
            int percent,
            boolean reached100
    ) {
        DailyWorkProgressResponse response = new DailyWorkProgressResponse(
                true, "WORKER", date, completed, active, total, percent, false,
                null, null, 0, 0, 0, null, null, 0, 0, 0, 0, 0
        );
        return response.withWorkloadProgress(
                completed,
                total,
                percent,
                reached100,
                reached100 ? date.atTime(20, 0) : null,
                reached100 ? date.atTime(20, 0) : null
        );
    }

    private EndOfDayAchievementService.AchievementResult result(
            String role,
            Long actorId,
            long eligible,
            long completed,
            long ignored
    ) {
        return new EndOfDayAchievementService.AchievementResult(
                LocalDate.now(ZoneId.of("Asia/Irkutsk")), role, actorId,
                eligible, completed, 100, ignored, true, 1, false
        );
    }
}
