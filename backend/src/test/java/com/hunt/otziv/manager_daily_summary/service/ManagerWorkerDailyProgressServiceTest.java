package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.api.FinalWorkerDayResults;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ManagerWorkerDailyProgressServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 25);

    @Mock private ManagerRepository managerRepository;
    @Mock private StaffDailyProgressService progressService;
    @Mock private FinalWorkerDayResults achievementService;
    @Mock private ManagerWorkerDayPlanReader dayPlanReader;
    private ManagerWorkerDailyProgressService service;

    @BeforeEach
    void setUp() {
        service = new ManagerWorkerDailyProgressService(managerRepository, progressService, achievementService, dayPlanReader);
    }

    @Test void auditIncludesOnlyTwoFinalizedFollowingDaysAndNeverInventsMissingResults() {
        var date=LocalDate.of(2026,10,30);
        var goal=new ManagerWorkerDailyProgressService.MissedWorkerGoal(14L,"Алёна К.",date,18,23);
        when(achievementService.workerDayResults(List.of(14L),date.plusDays(1)))
                .thenReturn(Map.of(14L,new FinalWorkerDayResults.WorkerDayResult(14L,47,47,true)));
        when(achievementService.workerDayResults(List.of(14L),date.plusDays(2)))
                .thenReturn(Map.of(14L,new FinalWorkerDayResults.WorkerDayResult(14L,24,24,true)));
        var rows=service.followingDayResults(goal,date.plusDays(3));
        assertEquals(2,rows.size());assertEquals(47,rows.getFirst().completed());assertEquals(24,rows.getLast().completed());
        assertFalse(rows.stream().anyMatch(row->!row.reached100()));
        assertEquals(1,service.followingDayResults(goal,date.plusDays(2)).size());
        when(achievementService.workerDayResults(List.of(14L),date.plusDays(1))).thenReturn(Map.of());
        assertEquals(0,service.followingDayResults(goal,date.plusDays(2)).size());
    }

    @Test
    void returnsCurrentAndPreviousProgressForWorkersAssignedToManager() {
        when(progressService.progressEnabled()).thenReturn(true);
        Worker worker = Worker.builder()
                .id(50L)
                .user(User.builder().id(500L).fio("Мария С.").username("maria").build())
                .build();
        Manager base = Manager.builder().id(1L).user(User.builder().id(10L).build()).build();
        Manager expanded = Manager.builder()
                .id(1L)
                .user(User.builder().id(10L).workers(Set.of(worker)).build())
                .build();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(base));
        when(managerRepository.findAllManagersWorkers(List.of(base))).thenReturn(List.of(expanded));
        when(progressService.workerEndOfDayProgressByWorkers(anyCollection(), eq(DATE), isNull()))
                .thenReturn(Map.of(50L, progress(6, 2, 8, 75, 1)));
        when(progressService.workerEndOfDayProgressByWorkers(anyCollection(), eq(DATE.minusDays(1)), isNull()))
                .thenReturn(Map.of(50L, progress(4, 4, 8, 50, 2)));
        when(progressService.aggregateTeamProgressResponses(
                anyCollection(), anyCollection(), eq(DATE), eq("WORKER_TEAM")))
                .thenReturn(progress(6, 2, 8, 75, 1));

        ManagerWorkerDailyProgressService.ManagerWorkerProgress result =
                service.progressByManagerIds(List.of(1L), DATE).get(1L);

        assertEquals(6, result.completed());
        assertEquals(8, result.total());
        assertEquals(2, result.active());
        assertEquals(1, result.overdue());
        assertEquals(75, result.progressBar().percent());
        assertEquals("Мария С.", result.workers().getFirst().workerName());
        assertEquals(50, result.workers().getFirst().previous().percent());
    }

    @Test
    void usesWriteCapableTransactionForFinalizedProjectionReconciliation()
            throws NoSuchMethodException {
        Transactional transaction = ManagerWorkerDailyProgressService.class
                .getMethod("progressByManagerIds", java.util.Collection.class, LocalDate.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertFalse(transaction.readOnly());
    }

    @Test
    void missedGoalsUseFinalizedResultInsteadOfRecomputedProgress() {
        Worker worker = Worker.builder().id(50L)
                .user(User.builder().id(500L).fio("Мария С.").build()).build();
        Manager manager = Manager.builder().id(1L)
                .user(User.builder().id(10L).workers(Set.of(worker)).build()).build();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(achievementService.workerDayResults(List.of(50L), DATE.minusDays(1)))
                .thenReturn(Map.of(50L, new FinalWorkerDayResults.WorkerDayResult(50L, 8, 4, false)));

        List<ManagerWorkerDailyProgressService.MissedWorkerGoal> missed =
                service.missedPreviousDayGoals(1L, DATE);

        assertEquals(1, missed.size());
        assertEquals("Мария С.", missed.getFirst().workerName());
        assertEquals(4, missed.getFirst().completed());
        assertEquals(8, missed.getFirst().total());
    }

    @Test
    void workBlockedByUnavailableAccountIsNotAMissedGoal() {
        // 01.11: 28 of 29, the 29th unit was a recovery task without an available account.
        stubManagerWithWorker(6L, "Альфия Л.");
        when(achievementService.workerDayResults(List.of(6L), DATE.minusDays(1)))
                .thenReturn(Map.of(6L, new FinalWorkerDayResults.WorkerDayResult(6L, 29, 28, false)));
        when(dayPlanReader.plans(List.of(6L), DATE.minusDays(1))).thenReturn(Map.of(6L,
                new ManagerWorkerDayPlanReader.DayPlan(29, 28, 1, 0, null, List.of(), true)));

        assertEquals(List.of(), service.missedPreviousDayGoals(1L, DATE));
    }

    @Test
    void missedGoalCountsOnlyWorkTheSpecialistCouldDoAndCarriesTheUnfinishedTasks() {
        stubManagerWithWorker(13L, "Виктория Ц.");
        var order = new ManagerWorkerDayPlanReader.UnfinishedTask("NEW", 28264L, 5,
                DATE.minusDays(1).atTime(21, 53));
        when(achievementService.workerDayResults(List.of(13L), DATE.minusDays(1)))
                .thenReturn(Map.of(13L, new FinalWorkerDayResults.WorkerDayResult(13L, 63, 57, false)));
        when(dayPlanReader.plans(List.of(13L), DATE.minusDays(1))).thenReturn(Map.of(13L,
                new ManagerWorkerDayPlanReader.DayPlan(63, 57, 1, 0, DATE.minusDays(1).atTime(20, 28),
                        List.of(order), true)));

        var goal = service.missedPreviousDayGoals(1L, DATE).getFirst();

        assertEquals(57, goal.completed());
        assertEquals(62, goal.total());
        assertEquals(5, goal.remaining());
        assertEquals(1, goal.blockedUnits());
        assertEquals(List.of(order), goal.unfinishedTasks());
        assertEquals(DATE.minusDays(1).atTime(20, 28), goal.lastReached100At());
    }

    @Test
    void blockedUnitsOfADifferentSnapshotAreNotSubtracted() {
        stubManagerWithWorker(13L, "Виктория Ц.");
        when(achievementService.workerDayResults(List.of(13L), DATE.minusDays(1)))
                .thenReturn(Map.of(13L, new FinalWorkerDayResults.WorkerDayResult(13L, 8, 7, false)));
        when(dayPlanReader.plans(List.of(13L), DATE.minusDays(1))).thenReturn(Map.of(13L,
                new ManagerWorkerDayPlanReader.DayPlan(9, 7, 1, 0, null, List.of(), true)));

        var goal = service.missedPreviousDayGoals(1L, DATE).getFirst();

        assertEquals(8, goal.total());
        assertEquals(0, goal.blockedUnits());
    }

    private void stubManagerWithWorker(Long workerId, String name) {
        Worker worker = Worker.builder().id(workerId)
                .user(User.builder().id(500L + workerId).fio(name).build()).build();
        Manager manager = Manager.builder().id(1L)
                .user(User.builder().id(10L).workers(Set.of(worker)).build()).build();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
    }

    private DailyWorkProgressResponse progress(
            long completed,
            long active,
            long total,
            int percent,
            long overdue
    ) {
        return new DailyWorkProgressResponse(
                true,
                "WORKER",
                DATE,
                completed,
                active,
                total,
                percent,
                false,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                total,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                overdue,
                0,
                0,
                0,
                0,
                0,
                false,
                null,
                null,
                "DAY",
                0,
                0,
                0,
                false
        );
    }
}
