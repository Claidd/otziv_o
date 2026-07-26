package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerPerformanceDaily;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerSummaryFormatterTest {

    @Mock private ManagerPerformanceDailyRepository dailyRepository;
    @Mock private ManagerCommunicationDailyReportSectionService communicationSectionService;
    @Mock private ManagerRiskDailyReportSectionService riskSectionService;
    @Mock private ManagerTeamDailyReportSectionService teamSectionService;
    @Mock private ManagerWorkerDailyProgressService workerProgressService;
    @Mock private ManagerDebtTaskDetailsService debtTaskDetailsService;
    private ManagerSummaryFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new ManagerSummaryFormatter(
                dailyRepository,
                communicationSectionService,
                riskSectionService,
                teamSectionService,
                workerProgressService,
                debtTaskDetailsService
        );
        when(dailyRepository.findBySummaryDateAndManager_Id(any(), anyLong()))
                .thenReturn(Optional.empty());
        when(dailyRepository.findByManager_IdAndSummaryDateBetween(anyLong(), any(), any())).thenReturn(List.of());
        when(workerProgressService.progressByManagerIds(anyList(), any())).thenReturn(Map.of());
        when(debtTaskDetailsService.tasks(any(), anyList())).thenReturn(Map.of());
        when(communicationSectionService.section(anyLong(), any())).thenReturn(new ManagerReportSection(
                "💬 <b>Клиентские сообщения</b>\n<b>Вывод.</b> Ответы в основном по существу.\n"
                        + "<b>Рекомендации:</b>\n• Всегда называть следующий шаг.",
                "Сообщения: закрыто 12 · формальных 1"
        ));
        when(riskSectionService.section(anyLong(), any())).thenReturn(new ManagerReportSection(
                "🛡 <b>Работа с рисками</b>\n<b>Прогресс.</b> Формальных пояснений нет.",
                "Риски: назначено 1 · открыто 0"
        ));
        lenient().when(teamSectionService.section(anyList())).thenReturn(
                "<b>Вывод.</b> Команда движется вперёд, но Вике нужна помощь с остатком.\n"
                        + "<b>Разбор по сотрудникам</b>\n"
                        + "<b>Вика · нужна помощь</b>\n"
                        + "• <b>Проблема:</b> есть сообщения без ответа\n"
                        + "• <b>Что сделать:</b> разобрать их до новой работы"
        );
    }

    @Test
    void putsHumanAnalysisBeforeCollapsedMetrics() {
        String message = formatter.format(List.of(row("Анжелика <Б>", "VERIFIED")), true);

        assertTrue(message.contains("АУДИТ РАБОТЫ МЕНЕДЖЕРОВ"));
        assertTrue(message.contains("Анжелика &lt;Б&gt;"));
        assertTrue(message.contains("👥 <b>Общий разбор команды</b>"));
        assertTrue(message.contains("Вика · нужна помощь"));
        assertTrue(message.contains("📌 <b>Общий результат менеджеров</b>"));
        assertTrue(message.contains("Обработано: <b>31 (82%)</b>"));
        assertTrue(message.contains("из них менеджерами — <b>29</b>, автоматически — <b>2</b>"));
        assertTrue(message.contains("<b>Анжелика &lt;Б&gt;</b> — за день обработано <b>31 из 38 (82%)</b>"));
        assertTrue(message.contains("Осталось открыто <b>7</b>. В это число входят: просроченные — <b>2</b>"));
        assertTrue(message.contains("сообщения без ответа — <b>2</b>, другие задачи — <b>1</b>"));
        assertTrue(message.contains("<b>Вывод.</b> Ответы в основном по существу."));
        assertTrue(message.contains("<b>Рекомендации:</b>"));
        assertTrue(message.contains("<blockquote expandable><b>📊 Цифры и показатели</b>"));
        assertTrue(message.indexOf("<b>Вывод.</b>") < message.indexOf("📊 Цифры и показатели"));
        assertTrue(message.contains("Оценка 86/100"));
        assertTrue(message.contains("Активность: 7 ч 11 мин"));
    }

    @Test
    void richReportUsesNativeTelegramHeadingsTableAndCollapsedDetails() {
        String message = formatter.formatRich(List.of(row("Вика", "CALCULATED")), true);

        assertTrue(message.startsWith("<h2>🧪 Аудит работы менеджеров"));
        assertTrue(message.contains("<h3>👥 Общий разбор команды</h3>"));
        assertTrue(message.contains("<h3>📌 Общий результат менеджеров</h3>"));
        assertTrue(message.contains("<li><b>Вика</b> — за день обработано <b>31 из 38 (82%)</b>"));
        assertTrue(message.contains("<h3>👤 Вика</h3>"));
        assertTrue(message.contains("<ul><li>Всегда называть следующий шаг.</li></ul>"));
        assertTrue(message.contains("<details><summary>📊 Цифры и показатели</summary>"));
        assertTrue(message.contains("<table bordered striped>"));
        assertTrue(message.contains("Предварительный анализ на 23:00"));
        assertFalse(message.contains("<blockquote expandable>"));
    }

    @Test
    void reportsMissingSlaDataHonestly() {
        ManagerDailySummaryResponse noReplies = new ManagerDailySummaryResponse(
                LocalDate.of(2026, 7, 14), 1L, 10L, "Вика", 42, "F",
                70, 0, 70, 0, 0, 0, 0, 0, BigDecimal.ZERO, 7, 9, 6, 48,
                0, 0, 0, 0, 0, 0, 0, 0,
                55, 0, 0, 55, 0, 0, 60, 60,
                0, 0, 0, 0, 480, 0, 3, "CONTROLLED", 0,
                "CALCULATED", LocalDateTime.of(2026, 7, 14, 3, 0)
        );

        String message = formatter.format(List.of(noReplies), true);

        assertTrue(message.contains("в SLA нет данных"));
        assertTrue(message.contains("Предварительный анализ на 03:00"));
    }

    @Test
    void buildsRegularAndRichVariantsFromOneAnalysisSnapshot() {
        clearInvocations(communicationSectionService, riskSectionService, teamSectionService);

        ManagerFormattedReport report = formatter.formatBoth(List.of(row("Вика", "VERIFIED")), true);

        assertTrue(report.html().contains("Вика"));
        assertTrue(report.richHtml().contains("<h3>👤 Вика</h3>"));
        verify(communicationSectionService, times(1)).section(1L, LocalDate.of(2026, 7, 13));
        verify(riskSectionService, times(1)).section(1L, LocalDate.of(2026, 7, 13));
        verify(teamSectionService, times(1)).section(anyList());
    }

    @Test
    void showsWhetherManagerAndAssignedWorkerImprovedSinceYesterday() {
        ManagerPerformanceDaily previousManager = new ManagerPerformanceDaily();
        previousManager.setAdjustedScore(90);
        previousManager.setTaskOpen(3);
        when(dailyRepository.findBySummaryDateAndManager_Id(LocalDate.of(2026, 7, 12), 1L))
                .thenReturn(Optional.of(previousManager));

        DailyWorkProgressResponse currentWorker = workerProgress(6, 2, 8, 75);
        DailyWorkProgressResponse previousWorker = workerProgress(4, 4, 8, 50);
        when(workerProgressService.progressByManagerIds(anyList(), any())).thenReturn(Map.of(
                1L,
                new ManagerWorkerDailyProgressService.ManagerWorkerProgress(
                        1L,
                        List.of(new ManagerWorkerDailyProgressService.WorkerProgress(
                                50L,
                                "Мария С.",
                                currentWorker,
                                previousWorker
                        )),
                        6,
                        8,
                        2,
                        0,
                        teamProgress(6, 2, 8, 75, 84, 77, 91, 65)
                )
        ));

        String ownerReport = formatter.formatRich(List.of(row("Вика", "CALCULATED")), true);
        ManagerFormattedReport personalReport = formatter.formatPersonal(row("Вика", "CALCULATED"));

        assertTrue(ownerReport.contains("Общая оценка менеджера снизилась: <b>90 → 86 из 100</b>"));
        assertTrue(ownerReport.contains("Открытых задач стало больше: <b>3 → 7</b>"));
        assertTrue(ownerReport.contains("<h3>👷 Работники менеджеров</h3>"));
        assertTrue(ownerReport.contains("Команда Вика: общий прогресс"));
        assertTrue(ownerReport.contains("Всего задач у команды: <b>8</b>. Выполнено: <b>6 (75%)</b>. Осталось: <b>2</b>"));
        assertTrue(ownerReport.contains("<b>Мария С.</b> — выполнено <b>6 из 8 (75%)</b>"));
        assertTrue(ownerReport.contains("Сравнение со вчера: выполнено <b>50% → 75%</b>"));
        assertFalse(ownerReport.contains("п.п."));
        assertFalse(ownerReport.contains("Прогресс-бар"));
        assertTrue(personalReport.richHtml().contains("<h3>📌 Ваш результат за день</h3>"));
        assertTrue(personalReport.richHtml().contains("<h4>Прогресс всей команды</h4>"));
        assertTrue(personalReport.richHtml().contains("Общая оценка работы всей команды: 84 из 100"));
        assertTrue(personalReport.richHtml().contains("Это справочная оценка, а не процент выполненных задач"));
        assertTrue(personalReport.richHtml().contains("доля выполненных задач — <b>75</b>"));
        assertTrue(personalReport.richHtml().contains("объём выполненной работы относительно дневного ориентира"));
    }

    @Test
    void doesNotShowMisleadingTeamEfficiencyBeforeAnyTaskIsCompleted() {
        DailyWorkProgressResponse currentWorker = workerProgress(0, 8, 8, 0);
        when(workerProgressService.progressByManagerIds(anyList(), any())).thenReturn(Map.of(
                1L,
                new ManagerWorkerDailyProgressService.ManagerWorkerProgress(
                        1L,
                        List.of(new ManagerWorkerDailyProgressService.WorkerProgress(
                                50L,
                                "Мария С.",
                                currentWorker,
                                null
                        )),
                        0,
                        8,
                        8,
                        0,
                        teamProgress(0, 8, 8, 0, 50, 100, 73, 0)
                )
        ));

        String report = formatter.formatPersonal(row("Вика", "CALCULATED")).richHtml();

        assertTrue(report.contains("Общая оценка работы команды пока не рассчитывается"));
        assertTrue(report.contains("за отчётный день ещё нет выполненных задач"));
        assertFalse(report.contains("Общая оценка работы всей команды: 50 из 100"));
    }

    @Test
    void listsConcreteDebtCardsLocationActionAndCompletionCriterion() {
        var category = new ManagerDebtTaskDetailsService.DebtCategory(
                "UNANSWERED_CLIENT_MESSAGES",
                "Неотвеченные сообщения",
                2,
                "/admin/manager-control/1",
                List.of(
                        new ManagerDebtTaskDetailsService.DebtItem(
                                1L,
                                "Компания Альфа",
                                "WhatsApp · 48 мин. без ответа",
                                "/companies/1",
                                ""
                        ),
                        new ManagerDebtTaskDetailsService.DebtItem(
                                2L,
                                "Компания Бета",
                                "Telegram · 26 мин. без ответа",
                                "/companies/2",
                                ""
                        )
                )
        );
        when(debtTaskDetailsService.tasks(any(), anyList())).thenReturn(Map.of(
                1L,
                new ManagerDebtTaskDetailsService.ManagerDebtTasks(1L, List.of(category))
        ));
        when(debtTaskDetailsService.location(anyString(), eq(category)))
                .thenReturn("«Контроль менеджеров» → Вика → «Неотвеченные сообщения»");
        when(debtTaskDetailsService.action(category))
                .thenReturn("открыть каждую переписку и отправить содержательный ответ");
        when(debtTaskDetailsService.completionCriterion(category))
                .thenReturn("счётчик стал 0");

        String report = formatter.formatRich(List.of(row("Вика", "CALCULATED")), false);

        assertTrue(report.contains("<h3>🧾 Что необходимо завершить</h3>"));
        assertTrue(report.contains("Компания Альфа — WhatsApp · 48 мин. без ответа"));
        assertTrue(report.contains("Компания Бета — Telegram · 26 мин. без ответа"));
        assertTrue(report.contains("<b>Где искать:</b> «Контроль менеджеров» → Вика"));
        assertTrue(report.contains("<b>Задача:</b> открыть каждую переписку"));
        assertTrue(report.contains("<b>Готово, когда:</b> счётчик стал 0"));
    }

    private DailyWorkProgressResponse workerProgress(long completed, long active, long total, int percent) {
        return new DailyWorkProgressResponse(
                true,
                "WORKER",
                LocalDate.of(2026, 7, 13),
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
                0
        );
    }

    private DailyWorkProgressResponse teamProgress(
            long completed,
            long active,
            long total,
            int percent,
            int efficiency,
            int speed,
            int discipline,
            int workload
    ) {
        return new DailyWorkProgressResponse(
                true,
                "WORKER_TEAM",
                LocalDate.of(2026, 7, 13),
                completed,
                active,
                total,
                percent,
                active == 0,
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
                total,
                efficiency,
                total,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                speed,
                discipline,
                workload,
                0,
                0,
                active == 0,
                null,
                null,
                "DAY",
                0,
                0,
                0,
                false
        );
    }

    private ManagerDailySummaryResponse row(String name, String aggregationStatus) {
        return new ManagerDailySummaryResponse(
                LocalDate.of(2026, 7, 13), 1L, 10L, name, 86, "B",
                38, 29, 7, 2, 7, 12, 5, 5, BigDecimal.valueOf(76.32), 2, 2, 2, 1,
                46, 540, 360, 840, 480, 1800, 46, 40,
                8, 7, 0, 1, 5040, 23040, 2820, 25860,
                3, 46, 40, 0, 57600, 3600, 3, "IDEAL", 180,
                aggregationStatus, LocalDateTime.of(2026, 7, 13, 23, 0)
        );
    }
}
