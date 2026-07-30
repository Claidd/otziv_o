package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.NotificationProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadEmergencyNotificationDeliveryService {

    private final WorkloadEmergencyNotificationStateService stateService;
    private final TelegramService telegramService;

    public int deliverDue() {
        var claimed = stateService.claim();
        int completed = 0;
        for (NotificationProjection row : claimed.notifications()) {
            if (row == null || row.getAssignmentId() == null) {
                continue;
            }
            String lastError = null;
            try {
                if (!"SENT".equals(row.getTargetNotificationStatus())) {
                    if (send(row.getTargetGroupChatId(), targetMessage(row))) {
                        stateService.targetSent(
                                row.getAssignmentId(),
                                claimed.processingToken()
                        );
                    } else {
                        lastError = "Telegram не принял сообщение группы специалиста";
                    }
                }
                if (!"SENT".equals(row.getAuditNotificationStatus())) {
                    if (send(row.getAuditGroupChatId(), auditMessage(row))) {
                        stateService.auditSent(
                                row.getAssignmentId(),
                                claimed.processingToken()
                        );
                    } else {
                        lastError = append(
                                lastError,
                                "Telegram не принял сообщение audit-группы"
                        );
                    }
                }
            } catch (RuntimeException exception) {
                lastError = append(
                        lastError,
                        exception.getClass().getSimpleName() + ": "
                                + exception.getMessage()
                );
                log.warn(
                        "Emergency assignment notification failed assignmentId={}",
                        row.getAssignmentId(),
                        exception
                );
            } finally {
                stateService.finish(
                        row.getAssignmentId(),
                        claimed.processingToken(),
                        lastError
                );
            }
            if (lastError == null) {
                completed++;
            }
        }
        return completed;
    }

    private boolean send(Long chatId, String message) {
        return chatId != null
                && chatId < 0
                && telegramService.sendMessage(chatId, message, "HTML");
    }

    private String targetMessage(NotificationProjection row) {
        return """
                <b>Аварийно назначена одна карточка</b>

                Специалист: <b>%s</b>
                Компания: <b>%s</b>
                Карточка: <b>#%d</b>

                Причина: у команды исходного менеджера исчерпаны подходящие получатели. Компания и остальные заказы не передавались.
                """.formatted(
                html(row.getTargetWorkerName()),
                html(row.getCompanyTitle()),
                number(row.getReviewId())
        ).trim();
    }

    private String auditMessage(NotificationProjection row) {
        return """
                <b>⚠️ Аварийное межкомандное назначение карточки</b>

                Компания: <b>%s</b>
                Карточка: <b>#%d</b>
                От: %s
                Кому: <b>%s</b>

                Передана только одна активная карточка. Компания, заказ и остальные этапы не менялись. Событие сохранено в журнале мониторинга.
                """.formatted(
                html(row.getCompanyTitle()),
                number(row.getReviewId()),
                html(row.getSourceWorkerName()),
                html(row.getTargetWorkerName())
        ).trim();
    }

    private String append(String left, String right) {
        return left == null || left.isBlank() ? right : left + "; " + right;
    }

    private long number(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private String html(String value) {
        if (value == null) {
            return "—";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
