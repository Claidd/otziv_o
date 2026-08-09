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
    private ManagerWorkerDailyProgressService service;

    @BeforeEach
    void setUp() {
        service = new ManagerWorkerDailyProgressService(managerRepository, progressService);
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
