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
        if (managerId == null || date == null) {
            return "";
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

        StringBuilder result = new StringBuilder();
        result.append("\n🛡 <b>Работа с рисками</b>\n")
                .append("Сегодня назначено: <b>").append(today.size()).append("</b>")
                .append(" · решений: ").append(decisionsToday)
                .append(" · открыто: ").append(open).append("\n")
                .append("Ответы специалистов: по существу ").append(logical)
                .append(" · неполные ").append(partial)
                .append(" · противоречивые ").append(contradictory)
                .append(" · не по теме ").append(irrelevant);
        if (manualReview > 0) {
            result.append(" · ручная проверка ").append(manualReview);
        }
        result.append("\nSLA: просрочено ").append(overdue)
                .append(" · ограничений ").append(restricted)
                .append(" · среднее решение ").append(duration(averageDecisionMinutes)).append("\n")
                .append("Обоснованные решения без подтверждённого ответа: ").append(justified)
                .append(" · ждут аудита: <b>").append(audit).append("</b>\n")
                .append("За 7 дней: рисков ").append(week.size())
                .append(" · сомнительных ответов ").append(weekQuestionable);

        List<WorkerRiskIncident> examples = today.stream()
                .filter(item -> item.isAuditRequired()
                        || item.getExplanationQuality() == WorkerRiskExplanationQuality.PARTIAL
                        || item.getExplanationQuality() == WorkerRiskExplanationQuality.CONTRADICTORY
                        || item.getExplanationQuality() == WorkerRiskExplanationQuality.IRRELEVANT)
                .limit(3)
                .toList();
        if (!examples.isEmpty()) {
            result.append("\nКонкретные случаи:");
            for (WorkerRiskIncident example : examples) {
                result.append("\n• «").append(escape(shortText(example.getTitle(), 140))).append("»");
                if (hasText(example.getWorkerExplanation())) {
                    result.append(" — ответ «")
                            .append(escape(shortText(example.getWorkerExplanation(), 120)))
                            .append("»");
                }
                if (hasText(example.getExplanationQualityReason())) {
                    result.append(". ").append(escape(shortText(example.getExplanationQualityReason(), 220)));
                }
                if (example.isAuditRequired()) {
                    result.append(" Решение требует аудита владельца.");
                }
            }
        }
        if (partial + contradictory + irrelevant > 0) {
            result.append("\nСовет: не принимайте «Хорошо», «Проверим» и другие общие ответы — просите назвать действие, результат и проверяемый факт.");
        } else if (overdue > 0) {
            result.append("\nСовет: запросите пояснение раньше и завершите разбор до истечения трёхчасового SLA.");
        } else if (audit > 0) {
            result.append("\nСовет: разберите очередь аудита и зафиксируйте, какие факты подтверждают каждое решение.");
        } else if (!today.isEmpty()) {
            result.append("\nПрогресс: ответы по сегодняшним рискам разобраны без обнаруженных формальных отписок.");
        }
        return result.toString();
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
