package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskEventType;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskResponseSlaJob {

    private static final int SLA_BATCH_SIZE = 500;
    private static final long DELIVERY_CLAIM_TIMEOUT_MINUTES = 10;
    private static final long DELIVERY_CLAIM_CLOCK_SKEW_MINUTES = 1;

    private final WorkerRiskIncidentRepository incidentRepository;
    private final WorkerRiskEventService eventService;
    private final UserService userService;
    private final TelegramService telegramService;
    private final NotificationMediaDeliveryService notificationMediaDeliveryService;
    private final AppSettingService appSettingService;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(
            fixedDelayString = "${worker-risk.response-sla.poll-ms:300000}",
            initialDelayString = "${worker-risk.response-sla.initial-delay-ms:60000}"
    )
    public void process() {
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
        LocalDateTime afterDueAt = null;
        Long afterId = null;
        while (true) {
            var candidates = incidentRepository.findPendingResponseSlaAfter(
                    WorkerRiskIncidentStatus.OPEN,
                    afterDueAt,
                    afterId,
                    PageRequest.of(0, SLA_BATCH_SIZE)
            ).stream()
                    .filter(incident -> incident.getId() != null && incident.getResponseDueAt() != null)
                    .map(incident -> new SlaCandidate(incident.getId(), incident.getResponseDueAt()))
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            for (SlaCandidate candidate : candidates) {
                try {
                    DeliveryClaim claim = inNewTransaction(() -> claimDelivery(
                            candidate.incidentId(),
                            deadlineMinutes,
                            reminderMinutes,
                            restrictionEnabled
                    ));
                    if (claim != null) {
                        inNewTransaction(() -> {
                            deliverClaim(claim);
                            return null;
                        });
                    }
                } catch (RuntimeException exception) {
                    log.error("Не удалось обработать SLA риск-инцидента incidentId={}", candidate.incidentId(), exception);
                }
            }
            SlaCandidate last = candidates.get(candidates.size() - 1);
            afterDueAt = last.responseDueAt();
            afterId = last.incidentId();
            if (candidates.size() < SLA_BATCH_SIZE) {
                return;
            }
        }
    }

    private DeliveryClaim claimDelivery(
            Long incidentId,
            int deadlineMinutes,
            int reminderMinutes,
            boolean restrictionEnabled
    ) {
        WorkerRiskIncident incident = incidentRepository.findByIdForUpdate(incidentId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (incident == null
                || incident.getStatus() != WorkerRiskIncidentStatus.OPEN
                || incident.getResponseDueAt() == null
                || incident.getExplanationAcceptedAt() != null) {
            return null;
        }
        User worker = workerById(incident.getWorkerUserId());
        if (hasDeliveryClaim(incident)) {
            if (isFreshCompleteClaim(incident, now)) {
                return null;
            }
            suspendSla(
                    incident,
                    worker,
                    now,
                    "Не удалось подтвердить доставку предыдущего SLA-уведомления; срок остановлен, чтобы исключить дубли",
                    "ambiguous-telegram-delivery"
            );
            return null;
        }
        if (!canReplyToRisk(incident, worker)) {
            suspendUnanswerableSla(incident, worker, now);
            return null;
        }

        boolean overdue = !incident.getResponseDueAt().isAfter(now);
        LocalDateTime reminderDueAt = incident.getResponseDueAt()
                .minusMinutes(Math.max(0, deadlineMinutes - reminderMinutes));
        DeliveryKind kind;
        if (!overdue && incident.getExplanationReminderAt() == null && !reminderDueAt.isAfter(now)) {
            kind = DeliveryKind.REMINDER;
        } else if (overdue && restrictionEnabled && incident.getSectionRestrictedAt() == null) {
            kind = DeliveryKind.OVERDUE;
        } else {
            return null;
        }

        String claimToken = UUID.randomUUID().toString();
        incident.setSlaDeliveryClaimToken(claimToken);
        incident.setSlaDeliveryClaimedAt(now.truncatedTo(ChronoUnit.MICROS));
        incident.setSlaDeliveryClaimKind(kind.name());
        incidentRepository.save(incident);
        return new DeliveryClaim(incidentId, kind, claimToken);
    }

    private void deliverClaim(DeliveryClaim claim) {
        WorkerRiskIncident incident = incidentRepository.findByIdForUpdate(claim.incidentId()).orElse(null);
        if (incident == null || !matchesDeliveryClaim(incident, claim)) {
            return;
        }
        if (incident.getStatus() != WorkerRiskIncidentStatus.OPEN
                || incident.getResponseDueAt() == null
                || incident.getExplanationAcceptedAt() != null) {
            clearDeliveryClaim(incident);
            incidentRepository.save(incident);
            return;
        }
        User worker = workerById(incident.getWorkerUserId());
        if (!canReplyToRisk(incident, worker)) {
            suspendUnanswerableSla(incident, worker, LocalDateTime.now());
            return;
        }
        Long chatId = worker.getWorkerTelegramGroupChatId() != null
                ? worker.getWorkerTelegramGroupChatId()
                : worker.getTelegramChatId();
        if (chatId == null) {
            clearDeliveryClaim(incident);
            incidentRepository.save(incident);
            return;
        }
        boolean sent = notificationMediaDeliveryService.send(
                claim.kind() == DeliveryKind.OVERDUE
                        ? NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()
                        : NotificationMediaEventCatalog.WORKER_RISK_REMINDER.code(),
                chatId,
                worker.getId(),
                deliveryText(incident, claim.kind()),
                null,
                WorkerRiskTelegramCallbackService.explanationKeyboard(incident.getId())
        );
        if (!sent) {
            // A timeout may mean Telegram accepted the message but its acknowledgement was lost.
            // Keep the claim so another scheduler instance cannot immediately duplicate it.
            // A stale claim is later handled by suspending the SLA without restricting the worker.
            return;
        }
        LocalDateTime deliveredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        incident.setExplanationReminderAt(deliveredAt);
        if (claim.kind() == DeliveryKind.OVERDUE) {
            incident.setSectionRestrictedAt(deliveredAt);
            incident.setSectionRestrictionReleasedAt(null);
        }
        clearDeliveryClaim(incident);
        incidentRepository.save(incident);
        eventService.record(
                incident,
                WorkerRiskEventType.EXPLANATION_REMINDER_SENT,
                incident.getWorkerUserId(),
                "WORKER",
                claim.kind() == DeliveryKind.OVERDUE ? "telegram-overdue" : "telegram",
                claim.kind() == DeliveryKind.OVERDUE
                        ? Map.of("chatId", chatId, "overdue", true)
                        : Map.of("chatId", chatId)
        );
        if (claim.kind() == DeliveryKind.OVERDUE) {
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

    private boolean hasDeliveryClaim(WorkerRiskIncident incident) {
        return incident.getSlaDeliveryClaimToken() != null
                || incident.getSlaDeliveryClaimedAt() != null
                || incident.getSlaDeliveryClaimKind() != null;
    }

    private boolean isFreshCompleteClaim(WorkerRiskIncident incident, LocalDateTime now) {
        LocalDateTime claimedAt = incident.getSlaDeliveryClaimedAt();
        return incident.getSlaDeliveryClaimToken() != null
                && claimedAt != null
                && incident.getSlaDeliveryClaimKind() != null
                && !claimedAt.isAfter(now.plusMinutes(DELIVERY_CLAIM_CLOCK_SKEW_MINUTES))
                && claimedAt.plusMinutes(DELIVERY_CLAIM_TIMEOUT_MINUTES).isAfter(now);
    }

    private boolean matchesDeliveryClaim(WorkerRiskIncident incident, DeliveryClaim claim) {
        return Objects.equals(incident.getSlaDeliveryClaimToken(), claim.token())
                && Objects.equals(incident.getSlaDeliveryClaimKind(), claim.kind().name());
    }

    private void clearDeliveryClaim(WorkerRiskIncident incident) {
        incident.setSlaDeliveryClaimToken(null);
        incident.setSlaDeliveryClaimedAt(null);
        incident.setSlaDeliveryClaimKind(null);
    }

    private String deliveryText(WorkerRiskIncident incident, DeliveryKind kind) {
        String text = kind == DeliveryKind.OVERDUE
                ? "🔴 ОТВЕТ ПРОСРОЧЕН\nПояснение не получено в течение 3 часов."
                : "🟡 НУЖНО ОТВЕТИТЬ\nНапоминание: нужно пояснить замечание.";
        text += "\nПричина: " + clean(incident.getTitle())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nНажмите «Пояснить причину» и отправьте конкретный ответ."
                + "\nОтвет будет проверен DeepSeek."
                + "\nКод запроса: risk-" + incident.getId();
        if (kind == DeliveryKind.REMINDER) {
            text += "\nБез пояснения раздел «Специалист» будет временно ограничен.";
        }
        return text;
    }

    private boolean canReplyToRisk(WorkerRiskIncident incident, User worker) {
        return incident != null
                && worker != null
                && worker.isActive()
                && worker.getTelegramChatId() != null
                && Objects.equals(worker.getId(), incident.getWorkerUserId());
    }

    private User workerById(Long workerUserId) {
        if (workerUserId == null) {
            return null;
        }
        try {
            return userService.findByIdToUserInfo(workerUserId);
        } catch (java.util.NoSuchElementException exception) {
            return null;
        }
    }

    private void suspendUnanswerableSla(WorkerRiskIncident incident, User worker, LocalDateTime now) {
        suspendSla(
                incident,
                worker,
                now,
                "Личный Telegram специалиста не привязан",
                "response-channel-unavailable"
        );
    }

    private void suspendSla(
            WorkerRiskIncident incident,
            User worker,
            LocalDateTime now,
            String reason,
            String releaseReason
    ) {
        boolean releaseRestriction = incident.getSectionRestrictedAt() != null
                && incident.getSectionRestrictionReleasedAt() == null;
        incident.setResponseDueAt(null);
        incident.setExplanationReminderAt(null);
        clearDeliveryClaim(incident);
        if (releaseRestriction) {
            incident.setSectionRestrictionReleasedAt(now);
        }
        incidentRepository.save(incident);
        eventService.record(
                incident,
                WorkerRiskEventType.EXPLANATION_REQUEST_FAILED,
                worker == null ? incident.getWorkerUserId() : worker.getId(),
                "WORKER",
                "sla-job",
                Map.of("reason", reason)
        );
        if (releaseRestriction) {
            eventService.record(
                    incident,
                    WorkerRiskEventType.SPECIALIST_SECTION_RELEASED,
                    worker == null ? incident.getWorkerUserId() : worker.getId(),
                    "WORKER",
                    "sla-job",
                    Map.of("reason", releaseReason)
            );
        }
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
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

    private enum DeliveryKind {
        REMINDER,
        OVERDUE
    }

    private record DeliveryClaim(Long incidentId, DeliveryKind kind, String token) {
    }

    private record SlaCandidate(Long incidentId, LocalDateTime responseDueAt) {
    }
}
