package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerSummaryFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ManagerPerformanceDailyRepository dailyRepository;
    private final ManagerCommunicationDailyReportSectionService communicationSectionService;
    private final ManagerRiskDailyReportSectionService riskSectionService;
    private final ManagerTeamDailyReportSectionService teamSectionService;
    private final ManagerWorkerDailyProgressService workerProgressService;
    private final ManagerDebtTaskDetailsService debtTaskDetailsService;

    public String format(List<ManagerDailySummaryResponse> managers, boolean testMode) {
        return render(managers, testMode, false);
    }

    public String formatRich(List<ManagerDailySummaryResponse> managers, boolean testMode) {
        return render(managers, testMode, true);
    }

    public ManagerFormattedReport formatBoth(
            List<ManagerDailySummaryResponse> managers,
            boolean testMode
    ) {
        if (managers == null || managers.isEmpty()) {
            return new ManagerFormattedReport(
                    render(List.of(), testMode, false),
                    render(List.of(), testMode, true)
            );
        }
        List<ManagerView> views = managers.stream().map(this::view).toList();
        String teamAnalysis = teamSectionService.section(teamFacts(views));
        Map<Long, ManagerWorkerDailyProgressService.ManagerWorkerProgress> workers =
                workerProgress(managers);
        Map<Long, ManagerDebtTaskDetailsService.ManagerDebtTasks> debtTasks =
                debtTasks(managers);
        return new ManagerFormattedReport(
                renderViews(managers, views, teamAnalysis, workers, debtTasks, testMode, false),
                renderViews(managers, views, teamAnalysis, workers, debtTasks, testMode, true)
        );
    }

    public ManagerFormattedReport formatPersonal(ManagerDailySummaryResponse manager) {
        if (manager == null) {
            return new ManagerFormattedReport(
                    "📘 <b>Персональный разбор дня</b>\n\nНет данных для анализа.",
                    "<h2>📘 Персональный разбор дня</h2><p>Нет данных для анализа.</p>"
            );
        }
        ManagerView view = view(manager);
        ManagerWorkerDailyProgressService.ManagerWorkerProgress workers = workerProgress(List.of(manager))
                .get(manager.managerId());
        ManagerDebtTaskDetailsService.ManagerDebtTasks debtTasks = debtTasks(List.of(manager))
                .get(manager.managerId());
        String date = manager.date().format(DATE);
        StringBuilder html = new StringBuilder("📘 <b>Персональный разбор · ")
                .append(date).append("</b>\n\n")
                .append("👤 <b>").append(escape(manager.managerName())).append("</b>\n")
                .append(personalProgress(view, false));
        appendWorkerTeam(html, manager.managerName(), workers, false, false);
        appendDebtTasks(html, List.of(view), debtTasks == null
                ? Map.of()
                : Map.of(manager.managerId(), debtTasks), false);
        html.append("\n\n").append(view.communication().analysis()).append("\n\n")
                .append(view.risks().analysis()).append("\n\n")
                .append("🎯 <b>Фокус следующей смены.</b> ").append(managerFocus(manager))
                .append("\n\n<blockquote expandable><b>📊 Показатели</b>\n")
                .append(managerMetrics(view))
                .append("</blockquote>\n\n")
                .append("Нажмите <b>«Изучить отчёт»</b>, прочитайте примеры и подтвердите прочтение. ")
                .append("После этого бот последовательно задаст вопросы по конкретным замечаниям.");

        StringBuilder rich = new StringBuilder("<h2>📘 Персональный разбор · ")
                .append(date).append("</h2>")
                .append("<p>👤 <b>").append(escape(manager.managerName())).append("</b></p>")
                .append(personalProgress(view, true));
        appendWorkerTeam(rich, manager.managerName(), workers, true, false);
        appendDebtTasks(rich, List.of(view), debtTasks == null
                ? Map.of()
                : Map.of(manager.managerId(), debtTasks), true);
        rich.append(richAnalysisSection(
                        "💬 Клиентские сообщения",
                        view.communication().analysis(),
                        "💬 <b>Клиентские сообщения</b>"
                ))
                .append(richAnalysisSection(
                        "🛡 Работа с рисками",
                        view.risks().analysis(),
                        "🛡 <b>Работа с рисками</b>"
                ))
                .append("<p>🎯 <b>Фокус следующей смены.</b> ")
                .append(managerFocus(manager)).append("</p>")
                .append("<details><summary>📊 Показатели</summary><p>")
                .append(richLines(managerMetrics(view))).append("</p></details>")
                .append("<p>Нажмите <b>«Изучить отчёт»</b>, прочитайте примеры и подтвердите прочтение. ")
                .append("После этого бот последовательно задаст вопросы по конкретным замечаниям.</p>");
        return new ManagerFormattedReport(html.toString(), rich.toString());
    }

    private String render(List<ManagerDailySummaryResponse> managers, boolean testMode, boolean rich) {
        if (managers == null || managers.isEmpty()) {
            return rich
                    ? "<h2>🧭 Аудит работы менеджеров</h2><p>Нет данных для анализа.</p>"
                    : "🧭 <b>Аудит работы менеджеров</b>\n\nНет данных для анализа.";
        }

        List<ManagerView> views = managers.stream().map(this::view).toList();
        String teamAnalysis = teamSectionService.section(teamFacts(views));
        return renderViews(
                managers,
                views,
                teamAnalysis,
                workerProgress(managers),
                debtTasks(managers),
                testMode,
                rich
        );
    }

    private String renderViews(
            List<ManagerDailySummaryResponse> managers,
            List<ManagerView> views,
            String teamAnalysis,
            Map<Long, ManagerWorkerDailyProgressService.ManagerWorkerProgress> workers,
            Map<Long, ManagerDebtTaskDetailsService.ManagerDebtTasks> debtTasks,
            boolean testMode,
            boolean rich
    ) {
        String date = managers.getFirst().date().format(DATE);
        StringBuilder result = new StringBuilder();
        appendHeader(result, date, testMode, rich);
        appendProvisionalNote(result, managers, rich);
        appendTeamAnalysis(result, teamAnalysis, rich);
        appendProgressAndDebt(result, views, rich);
        appendDebtTasks(result, views, debtTasks, rich);
        appendWorkerTeams(result, views, workers, rich);

        for (int index = 0; index < views.size(); index++) {
            appendManagerAnalysis(result, views.get(index), rich, index > 0);
        }
        appendMetrics(result, views, rich);
        return result.toString().trim();
    }

    private void appendHeader(StringBuilder result, String date, boolean testMode, boolean rich) {
        String title = (testMode ? "🧪 " : "🧭 ") + "Аудит работы менеджеров · " + date;
        if (rich) {
            result.append("<h2>").append(title).append("</h2>");
        } else {
            result.append("<b>").append(title.toUpperCase()).append("</b>\n");
        }
    }

    private void appendProvisionalNote(
            StringBuilder result,
            List<ManagerDailySummaryResponse> managers,
            boolean rich
    ) {
        boolean provisional = managers.stream().anyMatch(row -> !"VERIFIED".equals(row.aggregationStatus()));
        if (!provisional) {
            return;
        }
        LocalDateTime snapshotAt = managers.stream()
                .map(ManagerDailySummaryResponse::snapshotAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        String note = "Предварительный анализ"
                + (snapshotAt == null ? "" : " на " + snapshotAt.format(TIME))
                + ". Итог дня фиксируется в 00:00.";
        if (rich) {
            result.append("<p><i>").append(note).append("</i></p>");
        } else {
            result.append("\n<i>").append(note).append("</i>\n");
        }
    }

    private void appendTeamAnalysis(StringBuilder result, String teamAnalysis, boolean rich) {
        if (teamAnalysis == null || teamAnalysis.isBlank()) {
            return;
        }
        if (rich) {
            result.append("<section><h3>👥 Общий разбор команды</h3>")
                    .append(richBody(teamAnalysis))
                    .append("</section>");
        } else {
            result.append("\n\n👥 <b>Общий разбор команды</b>\n")
                    .append(teamAnalysis);
        }
    }

    private void appendProgressAndDebt(StringBuilder result, List<ManagerView> views, boolean rich) {
        long total = views.stream().mapToLong(view -> view.row().taskTotal()).sum();
        long completed = views.stream().mapToLong(view -> view.row().taskCompleted()).sum();
        long autoClosed = views.stream().mapToLong(view -> view.row().taskAutoClosed()).sum();
        long processed = completed + autoClosed;
        long open = views.stream().mapToLong(view -> view.row().taskOpen()).sum();
        String teamProgress = "Всего задач у менеджеров: <b>" + total + "</b>. "
                + "Обработано: <b>" + processed + " (" + progressPercent(processed, total) + "%)</b>, "
                + "из них менеджерами — <b>" + completed + "</b>, автоматически — <b>"
                + autoClosed + "</b>. "
                + (open == 0 ? "Открытых задач не осталось." : "Осталось открыто: <b>" + open + "</b>.");

        if (rich) {
            result.append("<section><h3>📌 Общий результат менеджеров</h3><p>")
                    .append(teamProgress)
                    .append("</p><ul>");
            for (ManagerView view : views) {
                result.append("<li>").append(managerProgressAndDebt(view)).append("</li>");
            }
            result.append("</ul></section>");
            return;
        }

        result.append("\n\n📌 <b>Общий результат менеджеров</b>\n")
                .append(teamProgress);
        for (ManagerView view : views) {
            result.append("\n• ").append(managerProgressAndDebt(view));
        }
    }

    private String managerProgressAndDebt(ManagerView view) {
        ManagerDailySummaryResponse row = view.row();
        long open = Math.max(0, row.taskOpen());
        long processed = processed(row);
        StringBuilder result = new StringBuilder("<b>")
                .append(escape(row.managerName()))
                .append("</b> — за день обработано <b>").append(processed).append(" из ")
                .append(row.taskTotal()).append(" (")
                .append(progressPercent(processed, row.taskTotal())).append("%)</b>: ")
                .append("лично менеджером — <b>").append(row.taskCompleted()).append("</b>, ")
                .append("автоматически — <b>").append(row.taskAutoClosed()).append("</b>. ")
                .append(managerDayTrend(view));
        if (open == 0) {
            return result.append(" Открытых задач не осталось.").toString();
        }
        return result.append(" Осталось открыто <b>").append(open)
                .append("</b>. В это число входят: просроченные — <b>")
                .append(row.overdueCount())
                .append("</b>, риски — <b>").append(row.riskCount())
                .append("</b>, сообщения без ответа — <b>").append(row.unansweredCount())
                .append("</b>, другие задачи — <b>").append(row.taskOtherOpen()).append("</b>.")
                .toString();
    }

    private void appendDebtTasks(
            StringBuilder result,
            List<ManagerView> views,
            Map<Long, ManagerDebtTaskDetailsService.ManagerDebtTasks> debtTasks,
            boolean rich
    ) {
        if (debtTasks == null || debtTasks.isEmpty()) return;
        List<ManagerView> withDebt = views.stream()
                .filter(view -> {
                    ManagerDebtTaskDetailsService.ManagerDebtTasks tasks =
                            debtTasks.get(view.row().managerId());
                    return tasks != null && !tasks.categories().isEmpty();
                })
                .toList();
        if (withDebt.isEmpty()) return;

        if (rich) {
            result.append("<section><h3>🧾 Что необходимо завершить</h3>")
                    .append("<p>Это расшифровка всех открытых задач из раздела ")
                    .append("<b>«Контроль менеджеров»</b>. Для каждой группы указаны место, действие ")
                    .append("и понятный признак завершения.</p>");
        } else {
            result.append("\n\n🧾 <b>Что необходимо завершить</b>\n")
                    .append("Это расшифровка всех открытых задач из раздела ")
                    .append("<b>«Контроль менеджеров»</b>. Ниже указано, где найти задачу, ")
                    .append("что сделать и когда она считается завершённой.");
        }
        for (ManagerView view : withDebt) {
            ManagerDebtTaskDetailsService.ManagerDebtTasks managerTasks =
                    debtTasks.get(view.row().managerId());
            if (rich) {
                result.append("<h4>").append(escape(view.row().managerName())).append("</h4><ol>");
            } else {
                result.append("\n\n<b>").append(escape(view.row().managerName())).append("</b>");
            }
            int number = 1;
            for (ManagerDebtTaskDetailsService.DebtCategory category : managerTasks.categories()) {
                if (rich) {
                    result.append("<li><b>").append(escape(category.label())).append(" — ")
                            .append(category.count()).append("</b><br/>");
                } else {
                    result.append("\n").append(number).append(". <b>")
                            .append(escape(category.label())).append(" — ")
                            .append(category.count()).append("</b>");
                }
                appendDebtTaskLine(
                        result,
                        "Где искать",
                        debtTaskDetailsService.location(view.row().managerName(), category),
                        rich
                );
                appendDebtItems(result, category.items(), rich);
                appendDebtTaskLine(
                        result,
                        "Задача",
                        debtTaskDetailsService.action(category),
                        rich
                );
                appendDebtTaskLine(
                        result,
                        "Готово, когда",
                        debtTaskDetailsService.completionCriterion(category),
                        rich
                );
                if (rich) result.append("</li>");
                number++;
            }
            if (rich) result.append("</ol>");
        }
        if (rich) result.append("</section>");
    }

    private void appendDebtItems(
            StringBuilder result,
            List<ManagerDebtTaskDetailsService.DebtItem> items,
            boolean rich
    ) {
        if (items == null || items.isEmpty()) return;
        if (rich) {
            result.append("<br/><b>Карточки:</b><ul>");
            items.stream().limit(12).forEach(item ->
                    result.append("<li>").append(debtItemText(item)).append("</li>"));
            result.append("</ul>");
        } else {
            result.append("\n   <b>Карточки:</b>");
            items.stream().limit(12).forEach(item ->
                    result.append("\n   • ").append(debtItemText(item)));
        }
        if (items.size() > 12) {
            appendDebtTaskLine(result, "Ещё", (items.size() - 12) + " карточек в этом разделе", rich);
        }
    }

    private String debtItemText(ManagerDebtTaskDetailsService.DebtItem item) {
        String title = escape(item.title().isBlank() ? "Карточка без названия" : item.title());
        return item.detail().isBlank()
                ? title
                : title + " — " + escape(item.detail());
    }

    private void appendDebtTaskLine(
            StringBuilder result,
            String label,
            String value,
            boolean rich
    ) {
        appendDebtTaskLine(result, label, escape(value), rich, false);
    }

    private void appendDebtTaskLine(
            StringBuilder result,
            String label,
            String value,
            boolean rich,
            boolean escapeValue
    ) {
        String text = escapeValue ? escape(value) : value;
        if (rich) {
            result.append("<br/><b>").append(label).append(":</b> ").append(text);
        } else {
            result.append("\n   <b>").append(label).append(":</b> ").append(text);
        }
    }

    private String personalProgress(ManagerView view, boolean rich) {
        String body = managerProgressAndDebt(view);
        return rich
                ? "<section><h3>📌 Ваш результат за день</h3><p>" + body + "</p></section>"
                : "\n📌 <b>Ваш результат за день</b>\n" + body;
    }

    private String managerDayTrend(ManagerView view) {
        if (view.previousScore() == null || view.previousOpen() == null) {
            return "сравнение со вчера ещё недоступно";
        }
        String score = view.row().score() > view.previousScore()
                ? "Общая оценка менеджера выросла: <b>" + view.previousScore()
                + " → " + view.row().score() + " из 100</b>."
                : view.row().score() < view.previousScore()
                ? "Общая оценка менеджера снизилась: <b>" + view.previousScore()
                + " → " + view.row().score() + " из 100</b>."
                : "Общая оценка менеджера не изменилась: <b>" + view.row().score() + " из 100</b>.";
        if (view.row().taskOpen() == view.previousOpen()) {
            return score + " Открытых задач столько же, сколько вчера: <b>"
                    + view.row().taskOpen() + "</b>.";
        }
        return score + " Открытых задач "
                + (view.row().taskOpen() < view.previousOpen() ? "стало меньше" : "стало больше")
                + ": <b>" + view.previousOpen() + " → " + view.row().taskOpen() + "</b>.";
    }

    private long progressPercent(long completed, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(completed * 100.0 / total)));
    }

    private void appendWorkerTeams(
            StringBuilder result,
            List<ManagerView> views,
            Map<Long, ManagerWorkerDailyProgressService.ManagerWorkerProgress> workers,
            boolean rich
    ) {
        if (workers == null || workers.isEmpty()) {
            return;
        }
        if (rich) {
            result.append("<section><h3>👷 Работники менеджеров</h3>");
        } else {
            result.append("\n\n👷 <b>Работники менеджеров</b>");
        }
        for (ManagerView view : views) {
            appendWorkerTeam(
                    result,
                    view.row().managerName(),
                    workers.get(view.row().managerId()),
                    rich,
                    true
            );
        }
        if (rich) {
            result.append("</section>");
        }
    }

    private void appendWorkerTeam(
            StringBuilder result,
            String managerName,
            ManagerWorkerDailyProgressService.ManagerWorkerProgress team,
            boolean rich,
            boolean includeManagerName
    ) {
        if (team == null || team.workers().isEmpty()) {
            return;
        }
        String title = includeManagerName
                ? "Команда " + escape(managerName) + ": общий прогресс"
                : "Прогресс всей команды";
        var progressBar = team.progressBar();
        long completed = progressBar != null && progressBar.visible()
                ? progressBar.completed()
                : team.completed();
        long totalCount = progressBar != null && progressBar.visible()
                ? progressBar.total()
                : team.total();
        long active = progressBar != null && progressBar.visible()
                ? progressBar.active()
                : team.active();
        long overdue = progressBar != null && progressBar.visible()
                ? progressBar.totalOverdueCount()
                : team.overdue();
        long percent = progressBar != null && progressBar.visible()
                ? progressBar.percent()
                : progressPercent(completed, totalCount);
        String total = totalCount <= 0
                ? "На этот день у команды нет назначенных задач."
                : "Всего задач у команды: <b>" + totalCount + "</b>. "
                + "Выполнено: <b>" + completed + " (" + percent + "%)</b>. "
                + (active == 0
                ? "Открытых задач не осталось."
                : "Осталось: <b>" + active + "</b>"
                + (overdue > 0
                ? ", в том числе просрочено — <b>" + overdue + "</b>."
                : ". Просроченных задач нет."));
        String quality = totalCount <= 0 ? "" : progressBarQuality(progressBar);
        if (rich) {
            result.append("<h4>").append(title).append("</h4><p>").append(total);
            if (!quality.isBlank()) {
                result.append("<br/>").append(quality);
            }
            result.append("</p><ul>");
            for (ManagerWorkerDailyProgressService.WorkerProgress worker : team.workers()) {
                result.append("<li>").append(workerProgress(worker)).append("</li>");
            }
            result.append("</ul>");
            return;
        }
        result.append("\n<b>").append(title).append("</b>\n").append(total);
        if (!quality.isBlank()) {
            result.append("\n").append(quality);
        }
        for (ManagerWorkerDailyProgressService.WorkerProgress worker : team.workers()) {
            result.append("\n• ").append(workerProgress(worker));
        }
    }

    private String progressBarQuality(com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse progress) {
        if (progress == null || !progress.visible()) {
            return "";
        }
        if (progress.completed() <= 0) {
            return "<b>Общая оценка работы команды пока не рассчитывается:</b> "
                    + "за отчётный день ещё нет выполненных задач.";
        }
        return "<b>Общая оценка работы всей команды: " + progress.efficiencyScore() + " из 100.</b> "
                + "Это справочная оценка, а не процент выполненных задач. В неё входят: "
                + "доля выполненных задач — <b>" + progress.percent() + "</b>, "
                + "скорость завершения — <b>" + progress.speedScore() + "</b>, "
                + "соблюдение сроков — <b>" + progress.disciplineScore() + "</b>, "
                + "объём выполненной работы относительно дневного ориентира — <b>"
                + progress.workloadScore() + "</b>.";
    }

    private String workerProgress(ManagerWorkerDailyProgressService.WorkerProgress worker) {
        if (worker.current() == null) {
            return "<b>" + escape(worker.workerName()) + ":</b> данных за день нет";
        }
        var current = worker.current();
        if (current.total() <= 0) {
            return "<b>" + escape(worker.workerName())
                    + "</b> — на этот день задачи не назначены.";
        }
        StringBuilder result = new StringBuilder("<b>")
                .append(escape(worker.workerName()))
                .append("</b> — выполнено <b>")
                .append(current.completed()).append(" из ").append(current.total())
                .append(" (").append(current.percent()).append("%)</b>. ")
                .append(current.active() == 0
                        ? "Открытых задач не осталось. "
                        : "Осталось <b>" + current.active() + "</b>"
                        + (current.totalOverdueCount() > 0
                        ? ", в том числе просрочено — <b>" + current.totalOverdueCount() + "</b>. "
                        : ". Просроченных задач нет. "))
                .append(workerDayTrend(worker));
        return result.toString();
    }

    private String workerDayTrend(ManagerWorkerDailyProgressService.WorkerProgress worker) {
        if (worker.previous() == null || worker.previous().total() <= 0) {
            return "сравнение со вчера недоступно";
        }
        return "Сравнение со вчера: выполнено <b>" + worker.previous().percent()
                + "% → " + worker.current().percent() + "%</b>; открытых задач <b>"
                + worker.previous().active() + " → " + worker.current().active()
                + "</b>; просроченных <b>" + worker.previous().totalOverdueCount()
                + " → " + worker.current().totalOverdueCount() + "</b>.";
    }

    private void appendManagerAnalysis(
            StringBuilder result,
            ManagerView view,
            boolean rich,
            boolean divider
    ) {
        ManagerDailySummaryResponse row = view.row();
        if (rich) {
            if (divider) {
                result.append("<hr/>");
            }
            result.append("<h3>👤 ").append(escape(row.managerName())).append("</h3>");
            result.append(richAnalysisSection(
                    "💬 Клиентские сообщения",
                    view.communication().analysis(),
                    "💬 <b>Клиентские сообщения</b>"
            ));
            result.append(richAnalysisSection(
                    "🛡 Работа с рисками",
                    view.risks().analysis(),
                    "🛡 <b>Работа с рисками</b>"
            ));
            result.append("<p>🎯 <b>Фокус на следующий день.</b> ")
                    .append(managerFocus(row))
                    .append("</p>");
            return;
        }

        result.append("\n\n👤 <b>").append(escape(row.managerName())).append("</b>\n")
                .append(view.communication().analysis()).append("\n")
                .append(view.risks().analysis()).append("\n")
                .append("🎯 <b>Фокус на следующий день.</b> ").append(managerFocus(row));
    }

    private String managerFocus(ManagerDailySummaryResponse row) {
        if (row.unansweredCount() > 0) {
            return "Разобрать сообщения без ответа и по каждому зафиксировать содержательный результат.";
        }
        if (row.riskCount() > 0) {
            return "Закончить разбор рисков и принимать только пояснения с проверяемыми фактами.";
        }
        if (row.overdueCount() > 0 || row.problemOpenCount() > 0) {
            return "Сначала завершить просроченные и проблемные карточки, затем проверить качество закрытия.";
        }
        return "Сохранить текущий темп, но в каждом ответе давать клиенту ясный следующий шаг.";
    }

    private void appendMetrics(StringBuilder result, List<ManagerView> views, boolean rich) {
        long averageScore = Math.round(views.stream().mapToInt(view -> view.row().score()).average().orElse(0));
        long tasks = views.stream().mapToLong(view -> view.row().taskTotal()).sum();
        long completed = views.stream().mapToLong(view -> processed(view.row())).sum();
        long remaining = views.stream().mapToLong(view -> view.row().taskOpen()).sum();

        if (rich) {
            result.append("<details><summary>📊 Цифры и показатели</summary>")
                    .append("<p>Команда: средняя оценка <b>").append(averageScore).append("/100</b>")
                    .append(" · обработано <b>").append(completed).append(" из ").append(tasks).append("</b>")
                    .append(" · осталось <b>").append(remaining).append("</b></p>")
                    .append("<table bordered striped><tr><th>Менеджер</th><th>Оценка</th><th>Обработано</th><th>Осталось</th><th>SLA</th></tr>");
            for (ManagerView view : views) {
                ManagerDailySummaryResponse row = view.row();
                result.append("<tr><td>").append(escape(row.managerName())).append("</td>")
                        .append("<td>").append(row.score()).append("/100</td>")
                        .append("<td>").append(processed(row)).append("/").append(row.taskTotal()).append("</td>")
                        .append("<td>").append(row.taskOpen()).append("</td>")
                        .append("<td>").append(sla(row)).append("</td></tr>");
            }
            result.append("</table>");
            for (ManagerView view : views) {
                result.append("<h4>").append(escape(view.row().managerName())).append("</h4>")
                        .append("<p>").append(richLines(managerMetrics(view))).append("</p>");
            }
            result.append("</details>");
            return;
        }

        result.append("\n\n<blockquote expandable><b>📊 Цифры и показатели</b>\n")
                .append("Команда: средняя оценка ").append(averageScore).append("/100")
                .append(" · обработано ").append(completed).append(" из ").append(tasks)
                .append(" · осталось ").append(remaining);
        for (ManagerView view : views) {
            result.append("\n\n<b>").append(escape(view.row().managerName())).append("</b>\n")
                    .append(managerMetrics(view));
        }
        result.append("</blockquote>");
    }

    private String managerMetrics(ManagerView view) {
        ManagerDailySummaryResponse row = view.row();
        return "Оценка " + row.score() + "/100 · к прошлому дню " + view.dayDelta()
                + " · " + view.weekLabel().toLowerCase() + " " + view.weekDelta() + "\n"
                + "Карточки: обработано " + processed(row) + "/" + row.taskTotal()
                + " (менеджером " + row.taskCompleted()
                + ", автоматически " + row.taskAutoClosed() + ")"
                + " · осталось " + row.taskOpen()
                + " (просрочки " + row.overdueCount()
                + ", риски " + row.riskCount()
                + ", без ответа " + row.unansweredCount() + ")\n"
                + "Ответы: в среднем " + duration(row.allReplyAverageSeconds())
                + " · в SLA " + sla(row)
                + " · проблем решено " + row.problemResolvedCount()
                + " · открыто " + row.problemOpenCount() + "\n"
                + "Активность: " + duration(row.confirmedActiveSeconds())
                + " · чистая очередь " + duration(row.cleanQueueSeconds()) + "\n"
                + view.communication().metrics() + "\n"
                + view.risks().metrics();
    }

    private ManagerView view(ManagerDailySummaryResponse row) {
        var previous = dailyRepository.findBySummaryDateAndManager_Id(
                row.date().minusDays(1),
                row.managerId()
        );
        var week = dailyRepository.findByManager_IdAndSummaryDateBetween(
                row.managerId(),
                row.date().minusDays(7),
                row.date().minusDays(1)
        );
        String dayDelta = previous.map(item -> signed(row.score() - item.getAdjustedScore())).orElse("нет данных");
        String weekDelta = week.isEmpty()
                ? "нет данных"
                : signed(row.score() - (int) Math.round(week.stream()
                .mapToInt(item -> item.getAdjustedScore()).average().orElse(row.score())));
        String weekLabel = week.isEmpty()
                ? "История для сравнения:"
                : week.size() >= 7 ? "К среднему за 7 дней:" : "К среднему за " + week.size() + " дн.:";
        ManagerReportSection communication = safeSection(communicationSectionService.section(row.managerId(), row.date()));
        ManagerReportSection risks = safeSection(riskSectionService.section(row.managerId(), row.date()));
        return new ManagerView(
                row,
                dayDelta,
                weekDelta,
                weekLabel,
                previous.map(item -> item.getAdjustedScore()).orElse(null),
                previous.map(item -> item.getTaskOpen()).orElse(null),
                communication,
                risks
        );
    }

    private Map<Long, ManagerWorkerDailyProgressService.ManagerWorkerProgress> workerProgress(
            List<ManagerDailySummaryResponse> managers
    ) {
        if (managers == null || managers.isEmpty()) {
            return Map.of();
        }
        LocalDate date = managers.getFirst().date();
        if (date == null) {
            return Map.of();
        }
        try {
            Map<Long, ManagerWorkerDailyProgressService.ManagerWorkerProgress> result =
                    workerProgressService.progressByManagerIds(
                    managers.stream()
                            .map(ManagerDailySummaryResponse::managerId)
                            .filter(java.util.Objects::nonNull)
                            .toList(),
                    date
            );
            return result == null ? Map.of() : result;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private Map<Long, ManagerDebtTaskDetailsService.ManagerDebtTasks> debtTasks(
            List<ManagerDailySummaryResponse> managers
    ) {
        if (managers == null || managers.isEmpty() || managers.getFirst().date() == null) {
            return Map.of();
        }
        try {
            Map<Long, ManagerDebtTaskDetailsService.ManagerDebtTasks> result =
                    debtTaskDetailsService.tasks(
                            managers.getFirst().date(),
                            managers.stream()
                                    .map(ManagerDailySummaryResponse::managerId)
                                    .filter(java.util.Objects::nonNull)
                                    .toList()
                    );
            return result == null ? Map.of() : result;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private List<ManagerTeamDailyReportSectionService.ManagerFacts> teamFacts(List<ManagerView> views) {
        return views.stream()
                .map(view -> {
                    ManagerDailySummaryResponse row = view.row();
                    return new ManagerTeamDailyReportSectionService.ManagerFacts(
                            row.managerName(),
                            row.score(),
                            view.dayDelta(),
                            view.weekDelta(),
                            row.taskTotal(),
                            row.taskCompleted(),
                            row.taskAutoClosed(),
                            row.taskOpen(),
                            row.overdueCount(),
                            row.riskCount(),
                            row.unansweredCount(),
                            row.problemResolvedCount(),
                            row.problemOpenCount(),
                            row.replyCount(),
                            row.repliesInSla(),
                            row.confirmedActiveSeconds(),
                            view.communication().analysis(),
                            view.communication().metrics(),
                            view.risks().analysis(),
                            view.risks().metrics()
                    );
                })
                .toList();
    }

    private ManagerReportSection safeSection(ManagerReportSection section) {
        return section == null ? new ManagerReportSection("", "") : section;
    }

    private String richBody(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        List<String> bullets = new ArrayList<>();
        for (String rawLine : value.split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("• ")) {
                bullets.add(line.substring(2));
                continue;
            }
            appendRichBullets(result, bullets);
            result.append("<p>").append(line).append("</p>");
        }
        appendRichBullets(result, bullets);
        return result.toString();
    }

    private String richAnalysisSection(String title, String value, String leadingTitle) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String body = value.trim();
        if (body.startsWith(leadingTitle)) {
            body = body.substring(leadingTitle.length()).trim();
        }
        return "<section><h3>" + title + "</h3>" + richBody(body) + "</section>";
    }

    private void appendRichBullets(StringBuilder result, List<String> bullets) {
        if (bullets.isEmpty()) {
            return;
        }
        result.append("<ul>");
        bullets.forEach(item -> result.append("<li>").append(item).append("</li>"));
        result.append("</ul>");
        bullets.clear();
    }

    private String richLines(String value) {
        return value == null ? "" : value.replace("\n", "<br>");
    }

    private String sla(ManagerDailySummaryResponse row) {
        return row.replyCount() == 0
                ? "нет данных"
                : Math.round(row.repliesInSla() * 100.0 / row.replyCount()) + "%";
    }

    private String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private String duration(long seconds) {
        if (seconds <= 0) {
            return "—";
        }
        Duration duration = Duration.ofSeconds(seconds);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        if (hours > 0) {
            return hours + " ч " + minutes + " мин";
        }
        return minutes > 0 ? minutes + " мин" : Math.max(1, seconds) + " сек";
    }

    private long processed(ManagerDailySummaryResponse row) {
        if (row == null) return 0;
        return Math.max(0, row.taskCompleted()) + Math.max(0, row.taskAutoClosed());
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            return "Без имени";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record ManagerView(
            ManagerDailySummaryResponse row,
            String dayDelta,
            String weekDelta,
            String weekLabel,
            Integer previousScore,
            Long previousOpen,
            ManagerReportSection communication,
            ManagerReportSection risks
    ) {
    }
}
