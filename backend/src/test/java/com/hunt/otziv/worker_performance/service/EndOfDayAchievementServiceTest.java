package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndOfDayAchievementServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 17);

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private TelegramService telegramService;
    @Mock
    private GamificationEventService gamificationEventService;

    @InjectMocks
    private EndOfDayAchievementService service;

    @Test
    void calculatesThreeDayStreakAndKeepsNotificationPending() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(
                        Map.of("reached_100", true),
                        Map.of("reached_100", true),
                        Map.of("reached_100", true)
                ))
                .thenReturn(List.of());

        EndOfDayAchievementService.AchievementResult result = service.saveResult(
                DATE, EndOfDayAchievementService.ROLE_WORKER, 7L, 70L,
                35, 35, 100, 2, true
        );

        assertTrue(result.reached100());
        assertEquals(3, result.streakDays());
        assertEquals(2, result.ignoredLateCount());
    }

    @Test
    void dayWithoutManagerTasksKeepsExistingPersonalStreak() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(
                        Map.of("reached_100", true),
                        Map.of("reached_100", true)
                ))
                .thenReturn(List.of());

        EndOfDayAchievementService.AchievementResult result = service.saveResult(
                DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L, 80L,
                0, 0, 0, 0, false
        );

        assertEquals(2, result.streakDays());
        assertFalse(result.reached100());
    }

    @Test
    void sendsWorkerAchievementToGroupAndMentionsLateTasks() {
        User user = User.builder()
                .id(70L)
                .fio("Анна <А>")
                .workerTelegramGroupChatId(-700L)
                .telegramChatId(700L)
                .build();
        Worker worker = Worker.builder().id(7L).user(user).build();
        EndOfDayAchievementService.AchievementResult result = new EndOfDayAchievementService.AchievementResult(
                DATE, EndOfDayAchievementService.ROLE_WORKER, 7L,
                35, 35, 100, 2, true, 3, false
        );
        when(telegramService.sendMessage(eq(-700L), anyString(), eq("HTML"))).thenReturn(true);

        service.notifyWorker(worker, result);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-700L), text.capture(), eq("HTML"));
        assertTrue(text.getValue().contains("3 дня подряд на 100%"));
        assertTrue(text.getValue().contains("после 23:00"));
        assertTrue(text.getValue().contains("Анна &lt;А&gt;"));
        verify(gamificationEventService).recordWorkerMilestone(
                eq(GamificationEventService.WORKER_DAY_100), eq(worker), anyString(), any(), anyString());
        verify(gamificationEventService).recordWorkerMilestone(
                eq(GamificationEventService.WORKER_100_STREAK), eq(worker), anyString(), any(), anyString());
    }

    @Test
    void sendsWorkerIncompleteGoalAndResetDayCounter() {
        User user = User.builder()
                .id(70L)
                .fio("Анна")
                .workerTelegramGroupChatId(-700L)
                .build();
        Worker worker = Worker.builder().id(7L).user(user).build();
        EndOfDayAchievementService.AchievementResult result = new EndOfDayAchievementService.AchievementResult(
                DATE, EndOfDayAchievementService.ROLE_WORKER, 7L,
                35, 28, 80, 2, false, 0, false
        );
        when(telegramService.sendMessage(eq(-700L), anyString(), eq("HTML"))).thenReturn(true);

        service.notifyWorker(worker, result);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-700L), text.capture(), eq("HTML"));
        assertTrue(text.getValue().contains("цель на день не выполнена"));
        assertTrue(text.getValue().contains("28 из 35"));
        assertTrue(text.getValue().contains("Осталось выполнить: <b>7</b>"));
        assertTrue(text.getValue().contains("Счётчик дней на 100%: <b>0 дней</b>"));
        assertTrue(text.getValue().contains("после 23:00"));
        verify(gamificationEventService, never()).recordWorkerMilestone(
                anyString(), eq(worker), anyString(), any(), anyString());
    }

    @Test
    void doesNotSendManagerAchievementTwice() {
        User user = User.builder().id(80L).telegramChatId(800L).build();
        Manager manager = Manager.builder().id(8L).user(user).build();
        EndOfDayAchievementService.AchievementResult result = new EndOfDayAchievementService.AchievementResult(
                DATE, EndOfDayAchievementService.ROLE_MANAGER, 8L,
                4, 4, 100, 1, true, 4, true
        );

        service.notifyManager(manager, result);

        verify(telegramService, never()).sendMessage(anyLong(), anyString(), anyString());
        verify(gamificationEventService).recordManagerMilestone(
                eq(GamificationEventService.MANAGER_TEAM_DAY_100), eq(manager), anyString(), any(), anyString());
        verify(gamificationEventService).recordManagerMilestone(
                eq(GamificationEventService.MANAGER_TEAM_100_STREAK), eq(manager), anyString(), any(), anyString());
    }

    @Test
    void sendsManagerPersonalSuccessWithStreakAndSiteActivity() {
        User user = User.builder().id(80L).fio("Анна <М>").telegramChatId(800L).build();
        Manager manager = Manager.builder().id(8L).user(user).build();
        EndOfDayAchievementService.AchievementResult result = new EndOfDayAchievementService.AchievementResult(
                DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L,
                12, 12, 100, 0, true, 4, false
        );
        when(telegramService.sendMessage(eq(800L), anyString(), eq("HTML"))).thenReturn(true);

        assertTrue(service.notifyManagerWorkday(manager, result, 3 * 3600 + 17 * 60));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(800L), text.capture(), eq("HTML"));
        assertTrue(text.getValue().contains("День менеджера закрыт на 100%"));
        assertTrue(text.getValue().contains("4 дня подряд на 100%"));
        assertTrue(text.getValue().contains("Активная работа на сайте и в соцсетях: <b>3 ч 17 мин</b>"));
        assertTrue(text.getValue().contains("Анна &lt;М&gt;"));
    }

    @Test
    void sendsManagerPersonalIncompleteResultAndZeroStreak() {
        User user = User.builder().id(80L).fio("Анна").telegramChatId(800L).build();
        Manager manager = Manager.builder().id(8L).user(user).build();
        EndOfDayAchievementService.AchievementResult result = new EndOfDayAchievementService.AchievementResult(
                DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L,
                12, 9, 75, 0, false, 0, false
        );
        when(telegramService.sendMessage(eq(800L), anyString(), eq("HTML"))).thenReturn(true);

        assertTrue(service.notifyManagerWorkday(manager, result, 125));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(800L), text.capture(), eq("HTML"));
        assertTrue(text.getValue().contains("цель на день не выполнена"));
        assertTrue(text.getValue().contains("Выполнено на <b>75%</b>"));
        assertTrue(text.getValue().contains("Осталось к действию: <b>3</b>"));
        assertTrue(text.getValue().contains("Счётчик дней на 100%: <b>0 дней</b>"));
        assertTrue(text.getValue().contains("Активная работа на сайте и в соцсетях: <b>2 мин</b>"));
    }

    @Test
    void doesNotResendManagerPersonalResult() {
        User user = User.builder().id(80L).telegramChatId(800L).build();
        Manager manager = Manager.builder().id(8L).user(user).build();
        EndOfDayAchievementService.AchievementResult result = new EndOfDayAchievementService.AchievementResult(
                DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L,
                12, 12, 100, 0, true, 2, true
        );

        assertFalse(service.notifyManagerWorkday(manager, result, 3600));

        verify(telegramService, never()).sendMessage(anyLong(), anyString(), anyString());
    }
}
