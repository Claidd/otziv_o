package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_performance.service.EndOfDayAchievementService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerPersonalDayResultServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private EndOfDayAchievementService achievementService;

    @InjectMocks
    private ManagerPersonalDayResultService service;

    @Test
    void persistsPersonalResultAndSendsSiteActivityToManager() {
        User user = User.builder().id(80L).telegramChatId(800L).build();
        Manager manager = Manager.builder().id(8L).user(user).build();
        ManagerDailySummaryResponse summary = summary(8L, 80L, 14, 14, 0, 0, 100, 7260);
        EndOfDayAchievementService.AchievementResult result =
                new EndOfDayAchievementService.AchievementResult(
                        DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L,
                        14, 14, 100, 0, true, 3, false
                );
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(achievementService.saveResult(
                DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L, 80L,
                14, 14, 100, 0, true
        )).thenReturn(result);
        when(achievementService.notifyManagerWorkday(manager, result, 7260)).thenReturn(true);

        assertEquals(1, service.send(DATE, List.of(summary)));

        verify(achievementService).notifyManagerWorkday(manager, result, 7260);
    }

    @Test
    void unfinishedManagerTasksDoNotCountAsHundredPercentDay() {
        Manager manager = Manager.builder().id(8L).user(User.builder().id(80L).build()).build();
        ManagerDailySummaryResponse summary = summary(8L, 80L, 14, 11, 0, 3, 78.57, 600);
        EndOfDayAchievementService.AchievementResult result =
                new EndOfDayAchievementService.AchievementResult(
                        DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L,
                        14, 11, 78.6, 0, false, 0, false
                );
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(achievementService.saveResult(
                eq(DATE), eq(EndOfDayAchievementService.ROLE_MANAGER_WORKDAY), eq(8L), eq(80L),
                eq(14L), eq(11L), eq(78.57), eq(0L), eq(false)
        )).thenReturn(result);

        service.send(DATE, List.of(summary));

        verify(achievementService).notifyManagerWorkday(manager, result, 600);
    }

    @Test
    void automaticallyClosedTasksCountTowardCompletedDay() {
        Manager manager = Manager.builder().id(8L).user(User.builder().id(80L).build()).build();
        ManagerDailySummaryResponse summary = summary(8L, 80L, 15, 6, 9, 0, 100, 600);
        EndOfDayAchievementService.AchievementResult result =
                new EndOfDayAchievementService.AchievementResult(
                        DATE, EndOfDayAchievementService.ROLE_MANAGER_WORKDAY, 8L,
                        15, 15, 100, 0, true, 3, false
                );
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(achievementService.saveResult(
                eq(DATE), eq(EndOfDayAchievementService.ROLE_MANAGER_WORKDAY), eq(8L), eq(80L),
                eq(15L), eq(15L), eq(100.0), eq(0L), eq(true)
        )).thenReturn(result);

        service.send(DATE, List.of(summary));

        verify(achievementService).notifyManagerWorkday(manager, result, 600);
    }

    private ManagerDailySummaryResponse summary(
            Long managerId,
            Long userId,
            long total,
            long completed,
            long autoClosed,
            long open,
            double percent,
            long siteActiveSeconds
    ) {
        return new ManagerDailySummaryResponse(
                DATE, managerId, userId, "Менеджер", 80, "B",
                total, completed, open, autoClosed, completed, 0, 0, 0, BigDecimal.valueOf(percent),
                0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                siteActiveSeconds, 0, siteActiveSeconds,
                0, 0, 0, 0, 0, 0, 0, "NOT_COMPLETED", 0,
                "VERIFIED", LocalDateTime.of(2026, 7, 23, 23, 59)
        );
    }
}
