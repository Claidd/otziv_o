package com.hunt.otziv.notification_media.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ThematicStaffNotificationJobTest {

    @Mock
    private WorkerService workerService;
    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private StaffDailyProgressService progressService;
    @Mock
    private NotificationMediaDeliveryService mediaDeliveryService;
    @Mock
    private ThematicNotificationDispatchStore dispatchStore;
    @Mock
    private AppSettingService appSettingService;

    private ThematicStaffNotificationJob job;

    @BeforeEach
    void setUp() {
        job = new ThematicStaffNotificationJob(
                workerService,
                managerRepository,
                progressService,
                mediaDeliveryService,
                dispatchStore,
                appSettingService
        );
        when(appSettingService.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(dispatchStore.claim(anyString(), anyLong(), any(), anyInt())).thenReturn(true);
        lenient().when(mediaDeliveryService.send(anyString(), anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(true);
    }

    @Test
    void sendsSiteInactiveWhenWorkerHasNotLoggedInOrWorkedToday() {
        LocalDate date = LocalDate.of(2026, 7, 31);
        Worker worker = worker(11L, 101L, -101L, date.atStartOfDay().minusDays(1));
        DailyWorkProgressResponse progress = progress(date, 0, 5, null);
        prepare(worker, progress);

        job.dispatchAt(date.atTime(13, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_SITE_INACTIVE.code()),
                eq(-101L),
                eq(101L),
                anyString(),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void sendsDayStartWhenWorkerLoggedInButHasNotCompletedAnything() {
        LocalDate date = LocalDate.of(2026, 7, 31);
        Worker worker = worker(12L, 102L, -102L, date.atTime(9, 0));
        DailyWorkProgressResponse progress = progress(date, 0, 4, null);
        prepare(worker, progress);

        job.dispatchAt(date.atTime(11, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_DAY_START.code()),
                eq(-102L),
                eq(102L),
                anyString(),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void publicationTakesPriorityAtPublicationHour() {
        LocalDate date = LocalDate.of(2026, 7, 31);
        Worker worker = worker(13L, 103L, -103L, date.atTime(9, 0));
        DailyWorkProgressResponse progress = progress(date, 1, 5, date.atTime(10, 0));
        prepare(worker, progress);
        when(dispatchStore.activePublicationCounts(List.of(13L), date)).thenReturn(Map.of(13L, 3L));

        job.dispatchAt(date.atTime(16, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_PUBLICATION_PENDING.code()),
                eq(-103L),
                eq(103L),
                anyString(),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void doesNotSendPublicationReminderWhenCurrentProgressHasNoActiveTasks() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        Worker worker = worker(17L, 107L, -107L, date.atTime(9, 0));
        DailyWorkProgressResponse progress = progress(date, 7, 7, date.atTime(10, 0));
        prepare(worker, progress);
        when(dispatchStore.activePublicationCounts(List.of(17L), date)).thenReturn(Map.of(17L, 8L));

        job.dispatchAt(date.atTime(16, 0));

        verify(mediaDeliveryService, never()).send(
                eq(NotificationMediaEventCatalog.WORKER_PUBLICATION_PENDING.code()),
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                any()
        );
    }

    @Test
    void publicationReminderCannotClaimMoreTasksThanCurrentProgress() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        Worker worker = worker(18L, 108L, -108L, date.atTime(9, 0));
        DailyWorkProgressResponse progress = progress(date, 3, 5, date.atTime(10, 0));
        prepare(worker, progress);
        when(dispatchStore.activePublicationCounts(List.of(18L), date)).thenReturn(Map.of(18L, 8L));

        job.dispatchAt(date.atTime(16, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_PUBLICATION_PENDING.code()),
                eq(-108L),
                eq(108L),
                argThat(message -> message.contains("<b>2</b>")),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void continuesToNextThemeWhenEarlierThemeWasAlreadySent() {
        LocalDate date = LocalDate.of(2026, 7, 31);
        Worker worker = worker(16L, 106L, -106L, date.atTime(9, 0));
        DailyWorkProgressResponse progress = progress(date, 0, 5, null);
        prepare(worker, progress);
        when(dispatchStore.activePublicationCounts(List.of(16L), date)).thenReturn(Map.of(16L, 2L));
        when(dispatchStore.claim(
                eq(NotificationMediaEventCatalog.WORKER_DAY_START.code()),
                eq(106L),
                eq(date),
                anyInt()
        )).thenReturn(false);
        when(dispatchStore.claim(
                eq(NotificationMediaEventCatalog.WORKER_PUBLICATION_PENDING.code()),
                eq(106L),
                eq(date),
                anyInt()
        )).thenReturn(true);

        job.dispatchAt(date.atTime(16, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_PUBLICATION_PENDING.code()),
                eq(-106L),
                eq(106L),
                anyString(),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void doesNotSendWhenDailyClaimIsRejected() {
        LocalDate date = LocalDate.of(2026, 7, 31);
        Worker worker = worker(14L, 104L, -104L, date.atTime(9, 0));
        prepare(worker, progress(date, 0, 5, null));
        when(dispatchStore.claim(anyString(), anyLong(), any(), anyInt())).thenReturn(false);

        job.dispatchAt(date.atTime(11, 0));

        verify(mediaDeliveryService, never()).send(
                anyString(), anyLong(), anyLong(), anyString(), anyString(), any()
        );
    }

    @Test
    void sendsManagerMiddaySummaryForIncompleteTeam() {
        LocalDate date = LocalDate.of(2026, 7, 31);
        Worker worker = worker(15L, 105L, null, date.atTime(9, 0));
        DailyWorkProgressResponse progress = progress(date, 2, 5, date.atTime(10, 0));
        prepare(worker, progress);

        User managerUser = User.builder()
                .id(201L)
                .username("manager")
                .active(true)
                .telegramChatId(-201L)
                .workers(Set.of(worker))
                .build();
        Manager manager = Manager.builder().id(21L).user(managerUser).build();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(dispatchStore.activePublicationCounts(List.of(15L), date)).thenReturn(Map.of());

        job.dispatchAt(date.atTime(17, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_SLOWED.code()),
                eq(-201L),
                eq(201L),
                anyString(),
                eq("HTML"),
                eq(List.of())
        );
    }

    @Test
    void managerSummaryUsesCurrentGoalStateAndShowsWorkday() {
        LocalDate date = LocalDate.of(2026, 8, 8);
        Worker reachedEarlier = worker(19L, 109L, null, date.atTime(9, 0));
        Worker currentlyIncomplete = worker(20L, 110L, null, date.atTime(9, 0));
        List<Worker> workers = List.of(reachedEarlier, currentlyIncomplete);
        when(workerService.getAllWorkers()).thenReturn(workers);
        when(progressService.workerProgressByWorkers(workers, date)).thenReturn(Map.of(
                19L, progress(date, 8, 10, date.atTime(10, 0), true),
                20L, progress(date, 3, 10, date.atTime(10, 0), false)
        ));
        when(dispatchStore.activePublicationCounts(List.of(19L, 20L), date)).thenReturn(Map.of());

        User managerUser = User.builder()
                .id(202L)
                .username("manager-current")
                .active(true)
                .telegramChatId(-202L)
                .workers(Set.of(reachedEarlier, currentlyIncomplete))
                .build();
        Manager manager = Manager.builder().id(22L).user(managerUser).build();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));

        job.dispatchAt(date.atTime(17, 0));

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_SLOWED.code()),
                eq(-202L),
                eq(202L),
                argThat(message -> message.contains("Рабочий день: <b>08.08.2026</b>")
                        && message.contains("Дневную цель выполнили: <b>0 из 2</b>")),
                eq("HTML"),
                eq(List.of())
        );
    }

    private void prepare(Worker worker, DailyWorkProgressResponse progress) {
        when(workerService.getAllWorkers()).thenReturn(List.of(worker));
        when(progressService.workerProgressByWorkers(List.of(worker), progress.date()))
                .thenReturn(Map.of(worker.getId(), progress));
    }

    private Worker worker(
            long workerId,
            long userId,
            Long telegramGroupId,
            LocalDateTime lastLoginAt
    ) {
        User user = User.builder()
                .id(userId)
                .username("worker-" + workerId)
                .active(true)
                .workerTelegramGroupChatId(telegramGroupId)
                .lastLoginAt(lastLoginAt)
                .build();
        return Worker.builder().id(workerId).user(user).build();
    }

    private DailyWorkProgressResponse progress(
            LocalDate date,
            long completed,
            long total,
            LocalDateTime firstActivityAt
    ) {
        return progress(date, completed, total, firstActivityAt, total > 0 && completed >= total);
    }

    private DailyWorkProgressResponse progress(
            LocalDate date,
            long completed,
            long total,
            LocalDateTime firstActivityAt,
            boolean reached100
    ) {
        int percent = total <= 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        DailyWorkProgressResponse base = new DailyWorkProgressResponse(
                true,
                "WORKER",
                date,
                completed,
                Math.max(0, total - completed),
                total,
                percent,
                completed >= total,
                firstActivityAt,
                firstActivityAt,
                0,
                0,
                0,
                firstActivityAt,
                firstActivityAt,
                0,
                0,
                completed,
                completed,
                percent
        );
        return base.withWorkloadProgress(
                completed,
                total,
                percent,
                reached100,
                firstActivityAt,
                firstActivityAt
        );
    }
}
