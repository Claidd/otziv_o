package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.t_telegrambot.dto.TelegramReportScheduleSettingsResponse;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
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

    @Test
    void sendsMorningReportWithinCatchUpWindowAndClaimsRunKey() {
        NotificationSchedulerToTelegramImpl scheduler = schedulerAt("2026-05-21T03:31:00Z");
        Map<String, com.hunt.otziv.admin.dto.presonal.UserData> reportData = Map.of();
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
        com.hunt.otziv.admin.dto.presonal.UserData mixedData = com.hunt.otziv.admin.dto.presonal.UserData.builder()
                .fio("Виктория Викторовна")
                .role("ROLE_MANAGER")
                .orderToCheck(9L)
                .orderInCheck(8L)
                .orderInWaitingPay1(7L)
                .build();
        com.hunt.otziv.admin.dto.presonal.UserData workerData = com.hunt.otziv.admin.dto.presonal.UserData.builder()
                .fio("Виктория Викторовна")
                .role("ROLE_WORKER")
                .newOrders(1L)
                .correctOrders(2L)
                .inVigul(3L)
                .inPublish(4L)
                .build();
        User shallowUser = user(77L, "victoria", "Виктория Викторовна", "ROLE_WORKER");
        User workerUser = user(77L, "victoria", "Виктория Викторовна", "ROLE_WORKER");
        Map<String, com.hunt.otziv.admin.dto.presonal.UserData> allData = Map.of("Виктория Викторовна", mixedData);
        Map<String, com.hunt.otziv.admin.dto.presonal.UserData> workerOnlyData = Map.of("Виктория Викторовна", workerData);

        when(userService.getAllWorkerTelegramGroups()).thenReturn(Map.of("Виктория Викторовна", -123L));
        when(personalService.getPersonalsAndCountToMap()).thenReturn(allData);
        when(userService.findByFio("Виктория Викторовна")).thenReturn(Optional.of(shallowUser));
        when(userService.findByUserNameWithAssignments("victoria")).thenReturn(Optional.of(workerUser));
        when(personalService.getPersonalsAndCountToMapToWorker(77L)).thenReturn(workerOnlyData);
        when(personalService.displayResultToWorker(workerOnlyData)).thenReturn("worker-only report");
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());

        scheduler.sendDailyReportToWorkers();

        verify(telegramService).sendMessage(eq(-123L), eq("worker-only report"), eq("HTML"));
    }

    private NotificationSchedulerToTelegramImpl schedulerAt(String instant) {
        NotificationSchedulerToTelegramImpl scheduler = new NotificationSchedulerToTelegramImpl(
                telegramService,
                userService,
                personalService,
                settingsService
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
