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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
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

    @InjectMocks
    private ManagerTeamProgressEndOfDayJob job;

    @Test
    void excludesTasksOpenedDuringLastHourFromWorkerAndManagerAchievement() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk"));
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
                List.of(worker, idleWorker), date, date.atTime(23, 0)))
                .thenReturn(Map.of(7L, adjusted, 9L, withoutTasks));
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_WORKER), eq(7L), eq(70L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(workerResult);
        when(achievementService.saveResult(
                eq(date), eq(EndOfDayAchievementService.ROLE_MANAGER), eq(8L), eq(80L),
                anyLong(), anyLong(), anyDouble(), anyLong(), anyBoolean()
        )).thenReturn(managerResult);

        job.capture();

        verify(staffDailyProgressService).workerEndOfDayProgressByWorkers(
                List.of(worker, idleWorker), date, date.atTime(23, 0));
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
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk"));
        Worker first = Worker.builder().id(7L).build();
        Worker second = Worker.builder().id(9L).build();
        DailyWorkProgressResponse completed = progress(date, 1, 0, 1, 100);

        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of());
        when(workerService.getAllWorkers()).thenReturn(List.of(first, second));
        when(staffDailyProgressService.workerProgressByWorkers(List.of(first, second), date))
                .thenReturn(Map.of(7L, completed, 9L, completed));
        when(staffDailyProgressService.workerEndOfDayProgressByWorkers(
                List.of(first, second), date, date.atTime(23, 0)))
                .thenReturn(Map.of(7L, completed));

        job.capture();

        verifyNoInteractions(managerTeamProgressService, achievementService, managerPerformanceService);
    }

    private DailyWorkProgressResponse progress(LocalDate date, long completed, long active, long total, int percent) {
        return new DailyWorkProgressResponse(
                true, "WORKER", date, completed, active, total, percent, false,
                null, null, 0, 0, 0, null, null, 0, 0, 0, 0, 0
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
