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
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.BlockedTask;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.Kind;
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
    @Mock
    private MandatoryAccountBlockers accountBlockers;

    private ThematicStaffNotificationJob job;

    private static final MandatoryAccountBlockers.WorkloadDay SHIFT = new MandatoryAccountBlockers.WorkloadDay(
            java.time.ZoneId.of("Asia/Irkutsk"), java.time.LocalTime.of(10, 0), java.time.LocalTime.of(22, 0), true);

    @BeforeEach
    void setUp() {
        job = new ThematicStaffNotificationJob(
                workerService,
                managerRepository,
                progressService,
                mediaDeliveryService,
                dispatchStore,
                appSettingService,
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                accountBlockers
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

    @Test
    void accountBlockerNoticeListsTodaysBlockedCardsWithTheButtonsTheSpecialistSees() {
        LocalDate date = LocalDate.of(2026, 11, 6);
        Worker worker = worker(6L, 60L, -600L, date.atTime(9, 0));
        when(workerService.getAllWorkers()).thenReturn(List.of(worker));
        when(dispatchStore.recipientsWithDispatch(anyString(), eq(date))).thenReturn(Set.of());
        when(accountBlockers.blockedTasks(Set.of(6L), date.atTime(10, 15))).thenReturn(Map.of(6L, List.of(
                new BlockedTask(6L, Kind.RECOVERY, 1160L, 5L, "Цветы <Опт>", 900L, true),
                new BlockedTask(6L, Kind.PUBLISH, 77L, 5L, "Цветы <Опт>", 901L, false))));

        job.dispatchAccountBlockersAt(date.atTime(10, 15), SHIFT);

        verify(dispatchStore).claim(NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code(), 60L, date,
                Integer.MAX_VALUE);
        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code()),
                eq(-600L),
                eq(60L),
                argThat(text -> text.contains("Рабочий день: <b>06.11.2026</b>")
                        && text.contains("• Восстановление · «Цветы &lt;Опт&gt;», заказ №900 — нажмите «Получить аккаунт»")
                        && text.contains("• Публикация · «Цветы &lt;Опт&gt;», заказ №901 — нажмите «смена»")
                        && text.contains("напишите менеджеру")),
                eq("HTML"),
                eq(List.of())
        );
        verify(dispatchStore).markSent(NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code(), 60L, date);
        // Before the midday hour the manager is not involved yet.
        verify(managerRepository, never()).findAllWithUserAndImage();
    }

    @Test
    void accountBlockerNoticeStaysInsideTheShiftAndOnlyWhenBlockersCount() {
        LocalDate date = LocalDate.of(2026, 11, 6);

        job.dispatchAccountBlockersAt(date.atTime(10, 14), SHIFT);
        job.dispatchAccountBlockersAt(date.atTime(22, 0), SHIFT);
        job.dispatchAccountBlockersAt(date.atTime(12, 0), new MandatoryAccountBlockers.WorkloadDay(
                SHIFT.zone(), SHIFT.shiftStart(), SHIFT.shiftEnd(), false));

        verify(workerService, never()).getAllWorkers();
        verify(accountBlockers, never()).blockedTasks(any(), any());
        verify(mediaDeliveryService, never()).send(anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void accountBlockerNoticeIsSentOncePerDayAndSkipsWorkersWithoutBlockers() {
        LocalDate date = LocalDate.of(2026, 11, 6);
        Worker notified = worker(6L, 60L, -600L, date.atTime(9, 0));
        Worker clear = worker(7L, 70L, -700L, date.atTime(9, 0));
        Worker noChat = worker(8L, 80L, null, date.atTime(9, 0));
        when(workerService.getAllWorkers()).thenReturn(List.of(notified, clear, noChat));
        when(dispatchStore.recipientsWithDispatch(
                NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code(), date)).thenReturn(Set.of(60L));
        when(accountBlockers.blockedTasks(Set.of(7L), date.atTime(11, 0))).thenReturn(Map.of());

        job.dispatchAccountBlockersAt(date.atTime(11, 0), SHIFT);

        verify(accountBlockers).blockedTasks(Set.of(7L), date.atTime(11, 0));
        verify(mediaDeliveryService, never()).send(anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void managerGetsShortVersionFromMiddayWhileCardsAreStillBlocked() {
        LocalDate date = LocalDate.of(2026, 11, 6);
        Worker worker = worker(6L, 60L, null, date.atTime(9, 0));
        worker.getUser().setFio("Альфия");
        when(workerService.getAllWorkers()).thenReturn(List.of(worker));
        when(dispatchStore.recipientsWithDispatch(anyString(), eq(date))).thenReturn(Set.of());
        User managerUser = User.builder().id(201L).username("manager").active(true).telegramChatId(201L)
                .workers(Set.of(worker)).build();
        Manager manager = Manager.builder().id(21L).user(managerUser).auditTelegramGroupChatId(-2100L).build();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(accountBlockers.blockedTasks(Set.of(6L), date.atTime(13, 0))).thenReturn(Map.of(6L, List.of(
                new BlockedTask(6L, Kind.RECOVERY, 1160L, 5L, "Цветы", null, true),
                new BlockedTask(6L, Kind.NAGUL, 12L, 5L, "Цветы", 900L, false),
                new BlockedTask(6L, Kind.NAGUL, 13L, 5L, "Цветы", 900L, false))));

        job.dispatchAccountBlockersAt(date.atTime(13, 0), SHIFT);

        verify(mediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.MANAGER_WORKER_ACCOUNT_BLOCKED.code()),
                eq(-2100L),
                eq(201L),
                argThat(text -> text.contains("• Альфия: <b>3</b> (восстановление, выгул ×2)")
                        && text.contains("«Получить аккаунт» или «смена»")),
                eq("HTML"),
                eq(List.of())
        );
        // The specialist has no chat, so only the manager hears about it.
        verify(mediaDeliveryService, never()).send(
                eq(NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code()),
                anyLong(), anyLong(), anyString(), anyString(), any());
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
