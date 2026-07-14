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
                38, 29, 7, 2, 7, 12, 5, 5, BigDecimal.valueOf(76.32), 2, 2, 2, 1,
                46, 540, 360, 840, 480, 1800, 46, 40,
                8, 7, 0, 1, 5040, 23040, 2820, 25860,
                3, 46, 40, 0, 57600, 3600, 3, "IDEAL", 180,
                "VERIFIED", java.time.LocalDateTime.of(2026, 7, 13, 23, 0)
        );

        String message = formatter.format(List.of(row), true);

        assertTrue(message.contains("ТЕСТОВАЯ СВОДКА"));
        assertTrue(message.contains("Анжелика &lt;Б&gt;"));
        assertTrue(message.contains("Обработано: <b>29 из 38</b>"));
        assertTrue(message.contains("Осталось к действию: <b>7</b>"));
        assertTrue(message.contains("Снято автоматически: <b>2</b>"));
        assertTrue(message.contains("Текущий рейтинг за месяц"));
        assertTrue(message.contains("История для сравнения: <b>нет данных</b>"));
        assertTrue(message.contains("Первый ответ: <b>9 мин</b>, медиана 6 мин · ответов: 46"));
        assertTrue(message.contains("Проблемные карточки: <b>8</b>"));
        assertTrue(message.contains("Подтверждённая активность: <b>7 ч 11 мин</b>"));
        assertTrue(message.contains("сайт: 6 ч 24 мин"));
        assertTrue(message.contains("мессенджеры вне сайта: 47 мин"));
    }

    @Test
    void rendersNoSlaDataWhenThereWereNoReplies() {
        ManagerDailySummaryResponse row = new ManagerDailySummaryResponse(
                LocalDate.of(2026, 7, 14), 1L, 10L, "Вика", 42, "F",
                70, 0, 70, 0, 0, 0, 0, 0, BigDecimal.ZERO, 7, 9, 6, 48,
                0, 0, 0, 0, 0, 0, 0, 0,
                55, 0, 0, 55, 0, 0, 60, 60,
                0, 0, 0, 0, 480, 0, 3, "CONTROLLED", 0,
                "CALCULATED", java.time.LocalDateTime.of(2026, 7, 14, 3, 0)
        );

        String message = formatter.format(List.of(row), true);

        assertTrue(message.contains("в нормативе нет данных"));
        assertTrue(message.contains("Среднее время всех ответов: <b>—</b>"));
        assertTrue(message.contains("Предварительные данные на 03:00"));
        assertTrue(message.contains("Предварительный результат"));
    }
}
