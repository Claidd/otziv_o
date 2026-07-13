package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerSummaryFormatterTest {

    @Mock
    private ManagerPerformanceDailyRepository dailyRepository;
    private ManagerSummaryFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new ManagerSummaryFormatter(dailyRepository);
        when(dailyRepository.findTopByManager_IdAndSummaryDateLessThanOrderBySummaryDateDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(dailyRepository.findByManager_IdAndSummaryDateBetween(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void rendersSeparateSiteMessengerAndConfirmedActivity() {
        ManagerDailySummaryResponse row = new ManagerDailySummaryResponse(
                LocalDate.of(2026, 7, 13), 1L, 10L, "Анжелика <Б>", 86, "B",
                34, 29, 5, BigDecimal.valueOf(85.29), 2, 2, 2,
                540, 360, 840, 480, 1800, 46, 40,
                8, 7, 5040, 23040, 2820, 25860,
                3, 46, 40, 0, 57600, 3600, 3, "IDEAL", 180,
                "VERIFIED"
        );

        String message = formatter.format(List.of(row), true);

        assertTrue(message.contains("ТЕСТОВАЯ СВОДКА"));
        assertTrue(message.contains("Анжелика &lt;Б&gt;"));
        assertTrue(message.contains("Задачи: <b>29 из 34</b>"));
        assertTrue(message.contains("Подтверждённая активность: <b>7 ч 11 мин</b>"));
        assertTrue(message.contains("сайт: 6 ч 24 мин"));
        assertTrue(message.contains("мессенджеры вне сайта: 47 мин"));
    }

    @Test
    void rendersNoSlaDataWhenThereWereNoReplies() {
        ManagerDailySummaryResponse row = new ManagerDailySummaryResponse(
                LocalDate.of(2026, 7, 14), 1L, 10L, "Вика", 42, "F",
                70, 0, 70, BigDecimal.ZERO, 7, 9, 6,
                0, 0, 0, 0, 0, 0, 0,
                55, 0, 0, 0, 60, 60,
                0, 0, 0, 0, 480, 0, 3, "CONTROLLED", 0,
                "VERIFIED"
        );

        String message = formatter.format(List.of(row), true);

        assertTrue(message.contains("в нормативе нет данных"));
        assertTrue(message.contains("Среднее время всех ответов: <b>—</b>"));
    }
}
