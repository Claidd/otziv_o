package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskEventType;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskResponseSlaJob {

    private final WorkerRiskIncidentRepository incidentRepository;
    private final WorkerRiskEventService eventService;
    private final UserService userService;
    private final TelegramService telegramService;
    private final AppSettingService appSettingService;

    @Scheduled(
            fixedDelayString = "${worker-risk.response-sla.poll-ms:300000}",
            initialDelayString = "${worker-risk.response-sla.initial-delay-ms:60000}"
    )
    @Transactional
    public void process() {
        LocalDateTime now = LocalDateTime.now();
        int deadlineMinutes = positive(
                appSettingService.getInt(AppSettingService.WORKER_RISK_EXPLANATION_DEADLINE_MINUTES, 180),
                180
        );
        int reminderMinutes = Math.min(
                deadlineMinutes,
                positive(
                        appSettingService.getInt(AppSettingService.WORKER_RISK_EXPLANATION_REMINDER_MINUTES, 120),
                        120
                )
        );
        boolean restrictionEnabled = appSettingService.getBoolean(
                AppSettingService.WORKER_RISK_SPECIALIST_SECTION_RESTRICTION_ENABLED,
                true
        );
        for (WorkerRiskIncident incident : incidentRepository.findPendingResponseSla(
                WorkerRiskIncidentStatus.OPEN,
                PageRequest.of(0, 500)
        )) {
            if (incident.getResponseDueAt() == null || incident.getExplanationAcceptedAt() != null) {
                continue;
            }
            LocalDateTime reminderDueAt = incident.getResponseDueAt()
                    .minusMinutes(Math.max(0, deadlineMinutes - reminderMinutes));
            if (incident.getExplanationReminderAt() == null && !reminderDueAt.isAfter(now)) {
                sendReminder(incident, now);
            }
            if (restrictionEnabled
                    && incident.getSectionRestrictedAt() == null
                    && !incident.getResponseDueAt().isAfter(now)) {
                incident.setSectionRestrictedAt(now);
                incidentRepository.save(incident);
                eventService.record(
                        incident,
                        WorkerRiskEventType.SPECIALIST_SECTION_RESTRICTED,
                        incident.getWorkerUserId(),
                        "WORKER",
                        "sla-job",
                        Map.of("responseDueAt", incident.getResponseDueAt().toString())
                );
            }
        }
    }

    private void sendReminder(WorkerRiskIncident incident, LocalDateTime now) {
        User worker = userService.findByUserName(incident.getWorkerUsername()).orElse(null);
        if (worker == null || !worker.isActive()) {
            return;
        }
        Long chatId = worker.getWorkerTelegramGroupChatId() != null
                ? worker.getWorkerTelegramGroupChatId()
                : worker.getTelegramChatId();
        if (chatId == null) {
            return;
        }
        String text = "⏳ Напоминание: нужно пояснить замечание."
                + "\nПричина: " + clean(incident.getTitle())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nОтветьте на это сообщение конкретным пояснением."
                + "\nКод запроса: risk-" + incident.getId()
                + "\nБез пояснения раздел «Специалист» будет временно ограничен.";
        if (telegramService.sendMessage(chatId, text)) {
            incident.setExplanationReminderAt(now);
            incidentRepository.save(incident);
            eventService.record(
                    incident,
                    WorkerRiskEventType.EXPLANATION_REMINDER_SENT,
                    incident.getWorkerUserId(),
                    "WORKER",
                    "telegram",
                    Map.of("chatId", chatId)
            );
        }
    }

    private int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
