package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.service.PersonalService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.t_telegrambot.dto.TelegramReportScheduleSettingsResponse;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.contains;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerToTelegramImplTest {

    @Mock
    private TelegramService telegramService;

    @Mock
    private UserService userService;

    @Mock
    private PersonalService personalService;

    @Mock
    private TelegramReportScheduleSettingsService settingsService;

    @Mock
    private NotificationMediaDeliveryService notificationMediaDeliveryService;

    @Test
    void sendsMorningReportWithinCatchUpWindowAndClaimsRunKey() {
        NotificationSchedulerToTelegramImpl scheduler = schedulerAt("2026-05-21T03:31:00Z");
        Map<String, com.hunt.otziv.admin.dto.personal.UserData> reportData = Map.of();
        when(settingsService.settings()).thenReturn(settings());
        when(settingsService.claimMorningRun("2026-05-21 morning 11:30 Asia/Irkutsk")).thenReturn(true);
        when(personalService.getPersonalsAndCountToMap()).thenReturn(reportData);
        when(personalService.displayResultToTelegramAdmin(reportData)).thenReturn("report");

        scheduler.sendConfiguredDailyReports();

        verify(settingsService).claimMorningRun("2026-05-21 morning 11:30 Asia/Irkutsk");
        verify(settingsService, never()).claimEveningRun(anyString());
        verify(telegramService).sendMessage(794146111L, "report", "HTML");
        verify(telegramService).sendMessage(794146111L, "Доброе утро! Отчёт за сегодня готов", "HTML");
    }

    @Test
    void skipsEveningReportWhenRunKeyIsAlreadyClaimed() {
        NotificationSchedulerToTelegramImpl scheduler = schedulerAt("2026-05-21T14:01:00Z");
        when(settingsService.settings()).thenReturn(settings());
        when(settingsService.claimEveningRun("2026-05-21 evening 22:00 Asia/Irkutsk")).thenReturn(false);

        scheduler.sendConfiguredDailyReports();

        verify(settingsService).claimEveningRun("2026-05-21 evening 22:00 Asia/Irkutsk");
        verify(personalService, never()).getPersonalsAndCountToMap();
        verify(telegramService, never()).sendMessage(794146111L, "Доброе утро! Отчёт за сегодня готов", "HTML");
    }

    @Test
    void sendsWorkerOnlyReportWhenRecipientHasWorkerRole() {
        NotificationSchedulerToTelegramImpl scheduler = schedulerAt("2026-05-21T14:01:00Z");
        com.hunt.otziv.admin.dto.personal.UserData mixedData = com.hunt.otziv.admin.dto.personal.UserData.builder()
                .fio("Виктория Викторовна")
                .role("ROLE_MANAGER")
                .orderToCheck(9L)
                .orderInCheck(8L)
                .orderInWaitingPay1(7L)
                .build();
        com.hunt.otziv.admin.dto.personal.UserData workerData = com.hunt.otziv.admin.dto.personal.UserData.builder()
                .fio("Виктория Викторовна")
                .role("ROLE_WORKER")
                .newOrders(1L)
                .correctOrders(2L)
                .inVigul(3L)
                .inPublish(4L)
                .build();
        User shallowUser = user(77L, "victoria", "Виктория Викторовна", "ROLE_WORKER");
        User workerUser = user(77L, "victoria", "Виктория Викторовна", "ROLE_WORKER");
        Map<String, com.hunt.otziv.admin.dto.personal.UserData> allData = Map.of("Виктория Викторовна", mixedData);
        Map<String, com.hunt.otziv.admin.dto.personal.UserData> workerOnlyData = Map.of("Виктория Викторовна", workerData);

        when(userService.getAllWorkerTelegramGroups()).thenReturn(Map.of("Виктория Викторовна", -123L));
        when(personalService.getPersonalsAndCountToMap()).thenReturn(allData);
        when(userService.findByFio("Виктория Викторовна")).thenReturn(Optional.of(shallowUser));
        when(userService.findByUserNameWithAssignments("victoria")).thenReturn(Optional.of(workerUser));
        when(personalService.getPersonalsAndCountToMapToWorker(77L)).thenReturn(workerOnlyData);
        when(personalService.displayResultToWorker(workerOnlyData)).thenReturn("worker-only report");
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());

        scheduler.sendDailyReportToWorkers();

        verifyNoInteractions(notificationMediaDeliveryService);
        verify(telegramService).sendMessage(eq(-123L), eq("worker-only report"), eq("HTML"));
    }

    @ParameterizedTest
    @CsvSource({
            "32,36,89,true,WORKER_PROGRESS_SLOWED",
            "98,100,98,true,WORKER_PROGRESS_SLOWED",
            "999,1000,100,true,WORKER_PROGRESS_SLOWED",
            "36,36,100,false,WORKER_PROGRESS_GROWING"
    })
    void mediaMatchesPersonalReportEvenWhenGeneralSummaryHasDifferentProgress(
            long completed, long total, int percent, boolean reachedOnce, String eventCode) {
        DailyWorkProgressResponse progress = mock(DailyWorkProgressResponse.class);
        // The old condition would use this historical flag instead of the remaining work.
        org.mockito.Mockito.lenient().when(progress.reached100()).thenReturn(reachedOnce);
        org.mockito.Mockito.lenient().when(progress.percent()).thenReturn(percent);
        when(progress.visible()).thenReturn(true);
        when(progress.completed()).thenReturn(completed);
        when(progress.total()).thenReturn(total);
        if (completed == total) {
            when(progress.active()).thenReturn(0L);
        }
        sendPersonalReport(progress);
        verify(notificationMediaDeliveryService).sendMediaOnly(
                eq(eventCode), eq(-123L), eq(77L),
                contains("Результат на момент отправки: <b>" + completed + " из " + total + "</b>"), eq("HTML"));
        verify(telegramService).sendMessage(-123L, "personal report", "HTML");
    }

    @ParameterizedTest
    @CsvSource({"true,false,0", "true,true,36", "false,false,36"})
    void skipsOutcomePictureWhenNoTasksOrProgressUnavailable(boolean visible, boolean updating, long total) {
        DailyWorkProgressResponse progress = mock(DailyWorkProgressResponse.class);
        when(progress.visible()).thenReturn(visible);
        org.mockito.Mockito.lenient().when(progress.updating()).thenReturn(updating);
        org.mockito.Mockito.lenient().when(progress.total()).thenReturn(total);
        sendPersonalReport(progress);
        verifyNoInteractions(notificationMediaDeliveryService);
        verify(telegramService).sendMessage(-123L, "personal report", "HTML");
    }

    private void sendPersonalReport(DailyWorkProgressResponse progress) {
        User worker = user(77L, "julia", "Юля К.", "ROLE_WORKER");
        var general = com.hunt.otziv.admin.dto.personal.UserData.builder()
                .fio("Юля К.").role("ROLE_WORKER").build();
        var personal = com.hunt.otziv.admin.dto.personal.UserData.builder()
                .fio("Юля К.").role("ROLE_WORKER").dailyProgress(progress).build();
        var personalData = Map.of("Юля К.", personal);
        when(userService.getAllWorkerTelegramGroups()).thenReturn(Map.of("Юля К.", -123L));
        when(personalService.getPersonalsAndCountToMap()).thenReturn(Map.of("Юля К.", general));
        when(userService.findByFio("Юля К.")).thenReturn(Optional.of(worker));
        when(userService.findByUserNameWithAssignments("julia")).thenReturn(Optional.of(worker));
        when(personalService.getPersonalsAndCountToMapToWorker(77L)).thenReturn(personalData);
        when(personalService.displayResultToWorker(personalData)).thenReturn("personal report");
        schedulerAt("2026-05-21T14:01:00Z").sendDailyReportToWorkers();
    }

    private NotificationSchedulerToTelegramImpl schedulerAt(String instant) {
        NotificationSchedulerToTelegramImpl scheduler = new NotificationSchedulerToTelegramImpl(
                telegramService,
                userService,
                personalService,
                settingsService,
                notificationMediaDeliveryService
        );
        scheduler.setClock(Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")));
        scheduler.setCatchUpWindow(Duration.ofMinutes(15));
        return scheduler;
    }

    private TelegramReportScheduleSettingsResponse settings() {
        return new TelegramReportScheduleSettingsResponse(
                true,
                "11:30",
                true,
                "22:00",
                "Asia/Irkutsk",
                "",
                ""
        );
    }

    private User user(Long id, String username, String fio, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setFio(fio);
        user.setRoles(List.of(role));
        return user;
    }
}
