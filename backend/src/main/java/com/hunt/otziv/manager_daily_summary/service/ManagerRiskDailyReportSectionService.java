package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerRiskDailyReportSectionService {

    private final WorkerRiskIncidentRepository repository;

    @Transactional(readOnly = true)
    public String format(Long managerId, LocalDate date) {
        return section(managerId, date).combined();
    }

    @Transactional(readOnly = true)
    public ManagerReportSection section(Long managerId, LocalDate date) {
        if (managerId == null || date == null) {
            return new ManagerReportSection("", "");
        }
        LocalDateTime dayFrom = date.atStartOfDay();
        LocalDateTime dayTo = date.plusDays(1).atStartOfDay();
        LocalDateTime weekFrom = date.minusDays(6).atStartOfDay();
        List<WorkerRiskIncident> incidents = repository.findPerformanceIncidentsByAssignedManagerId(
                List.of(managerId),
                weekFrom,
                dayTo,
                WorkerRiskIncidentStatus.OPEN
        );
        List<WorkerRiskIncident> today = incidents.stream()
                .filter(item -> between(item.getCreatedAt(), dayFrom, dayTo))
                .toList();
        List<WorkerRiskIncident> week = incidents.stream()
                .filter(item -> between(item.getCreatedAt(), weekFrom, dayTo))
                .toList();
        long decisionsToday = incidents.stream()
                .filter(item -> between(item.getResolvedAt(), dayFrom, dayTo))
                .count();
        long open = incidents.stream()
                .filter(item -> item.getStatus() == WorkerRiskIncidentStatus.OPEN)
                .count();
        long logical = responses(today, WorkerRiskExplanationQuality.LOGICAL);
        long partial = responses(today, WorkerRiskExplanationQuality.PARTIAL);
        long contradictory = responses(today, WorkerRiskExplanationQuality.CONTRADICTORY);
        long irrelevant = responses(today, WorkerRiskExplanationQuality.IRRELEVANT);
        long manualReview = responses(today, WorkerRiskExplanationQuality.NEEDS_REVIEW);
        long overdue = today.stream()
                .filter(item -> item.getResponseDueAt() != null)
                .filter(item -> item.getExplanationAcceptedAt() == null)
                .filter(item -> !item.getResponseDueAt().isAfter(dayTo))
                .count();
        long restricted = today.stream().filter(item -> item.getSectionRestrictedAt() != null).count();
        long audit = incidents.stream().filter(WorkerRiskIncident::isAuditRequired).count();
        long justified = today.stream()
                .filter(item -> "MANAGER_JUSTIFIED".equals(item.getDecisionQuality()))
                .count();
        long weekQuestionable = week.stream()
                .filter(item -> item.getExplanationQuality() != null)
                .filter(item -> item.getExplanationQuality() != WorkerRiskExplanationQuality.LOGICAL)
                .count();
        long averageDecisionMinutes = averageDecisionMinutes(today);

        List<WorkerRiskIncident> examples = today.stream()
                .filter(item -> item.isAuditRequired()
                        || item.getExplanationQuality() == WorkerRiskExplanationQuality.PARTIAL
                        || item.getExplanationQuality() == WorkerRiskExplanationQuality.CONTRADICTORY
                        || item.getExplanationQuality() == WorkerRiskExplanationQuality.IRRELEVANT)
                .limit(2)
                .toList();
        StringBuilder analysis = new StringBuilder("🛡 <b>Работа с рисками</b>\n");
        if (partial + contradictory + irrelevant > 0) {
            analysis.append("<b>Вывод.</b> Часть пояснений не доказывает, что проблема действительно проверена и решена.\n")
                    .append("<b>Рекомендация.</b> Не принимайте «Хорошо» и «Проверим»: просите назвать действие, результат и проверяемый факт.");
        } else if (overdue > 0) {
            analysis.append("<b>Вывод.</b> Качество пояснений приемлемое, но разбор рисков затягивается.\n")
                    .append("<b>Рекомендация.</b> Запрашивайте пояснение раньше и завершайте решение до истечения SLA.");
        } else if (audit > 0) {
            analysis.append("<b>Вывод.</b> Есть решения, которым не хватает подтверждённых фактов.\n")
                    .append("<b>Рекомендация.</b> Проверьте очередь аудита и зафиксируйте основание каждого решения.");
        } else if (!today.isEmpty()) {
            analysis.append("<b>Прогресс.</b> Сегодняшние риски разобраны без обнаруженных формальных отписок.");
        } else {
            analysis.append("<b>Вывод.</b> Новых рисков за день не было.");
        }
        if (!examples.isEmpty()) {
            analysis.append("\n<b>Примеры для разбора</b>");
            for (WorkerRiskIncident example : examples) {
                analysis.append("\n• «").append(escape(shortText(example.getTitle(), 110))).append("»");
                if (hasText(example.getWorkerExplanation())) {
                    analysis.append(" → «")
                            .append(escape(shortText(example.getWorkerExplanation(), 90)))
                            .append("»");
                }
                if (hasText(example.getExplanationQualityReason())) {
                    analysis.append(". ").append(escape(shortText(example.getExplanationQualityReason(), 140)));
                }
                if (example.isAuditRequired()) {
                    analysis.append(" Нужен аудит владельца.");
                }
            }
        }

        String metrics = "Риски: назначено " + today.size()
                + " · решений " + decisionsToday
                + " · открыто " + open
                + " · ждут аудита " + audit
                + "\nПояснения: по существу " + logical
                + " · неполные " + partial
                + " · противоречивые " + contradictory
                + " · не по теме " + irrelevant
                + (manualReview > 0 ? " · ручная проверка " + manualReview : "")
                + "\nSLA: просрочено " + overdue
                + " · ограничений " + restricted
                + " · среднее решение " + duration(averageDecisionMinutes)
                + " · решений без подтверждённого ответа " + justified
                + "\n7 дней: рисков " + week.size()
                + " · сомнительных пояснений " + weekQuestionable;
        return new ManagerReportSection(analysis.toString(), metrics);
    }

    private long responses(List<WorkerRiskIncident> incidents, WorkerRiskExplanationQuality quality) {
        return incidents.stream().filter(item -> item.getExplanationQuality() == quality).count();
    }

    private long averageDecisionMinutes(List<WorkerRiskIncident> incidents) {
        List<Long> values = incidents.stream()
                .filter(item -> item.getCreatedAt() != null && item.getResolvedAt() != null)
                .filter(item -> !item.getResolvedAt().isBefore(item.getCreatedAt()))
                .map(item -> Duration.between(item.getCreatedAt(), item.getResolvedAt()).toMinutes())
                .toList();
        return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private boolean between(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
        return value != null && !value.isBefore(from) && value.isBefore(to);
    }

    private String duration(long minutes) {
        if (minutes <= 0) return "—";
        if (minutes < 60) return minutes + " мин";
        return minutes / 60 + " ч " + minutes % 60 + " мин";
    }

    private String shortText(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
