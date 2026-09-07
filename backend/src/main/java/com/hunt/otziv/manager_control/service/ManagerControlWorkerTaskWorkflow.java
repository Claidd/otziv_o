package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlReminderWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlWorkerTaskWorkflow {

    private final ManagerControlConcreteSnapshotWorkflow concreteSnapshot;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlWorkerTaskLookup workerTaskLookup;

    static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";

    private final PersonalReminderService personalReminderService;

    private final NotificationMediaDeliveryService notificationMediaDeliveryService;

    private final WorkerRiskIncidentRepository riskIncidentRepository;

    boolean isWorkerRiskConcrete(ManagerDailyControlConcreteItem item) {
        return workerTaskLookup.isWorkerRiskConcrete(item);
    }

    boolean requiresWorkerExplanation(ManagerDailyControlConcreteItem concreteItem) {
        if (isWorkerRiskConcrete(concreteItem)) {
            return true;
        }
        Long ageDays = concreteItem == null ? null : concreteItem.getAgeDays();
        return ageDays == null || ageDays >= 2;
    }

    String specialistProblemLabel(ManagerDailyControlConcreteItem concreteItem) {
        return switch(safe(concreteItem == null ? null : concreteItem.getEntityType())) {
            case "RECOVERY_TASK" ->
                "проверьте восстановление";
            case "BAD_REVIEW_TASK" ->
                "проверьте плохой отзыв";
            case ENTITY_PUBLISH_REVIEW ->
                "проверьте публикацию";
            case ENTITY_NAGUL_REVIEW ->
                "проверьте выгул";
            case ENTITY_WORKER_ORDER_NEW ->
                "подготовьте текст нового заказа";
            case ENTITY_WORKER_ORDER_CORRECT ->
                "проверьте коррекцию";
            case "RISK" ->
                "проверьте открытый риск";
            default ->
                "проверьте проблему";
        };
    }

    void clearWorkerTelegramState(ManagerDailyControlConcreteItem concreteItem) {
        concreteSnapshot.clearWorkerTelegramState(concreteItem);
    }

    boolean notifyWorkerAboutTaskRequest(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control) {
        User workerUser = workerUserForTask(concreteItem);
        if (workerUser == null || workerUser.getId() == null || concreteItem.getId() == null) {
            concreteItem.setWorkerNotificationFailureReason("Специалист карточки не найден");
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setWorkerNotificationAttemptedAt(now);
        concreteItem.setWorkerNotificationUserId(workerUser.getId());
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
        concreteItem.setWorkerExplanationRequestedAt(null);
        concreteItem.setWorkerExplanationPromptedAt(null);
        concreteItem.setWorkerExplanation(null);
        concreteItem.setWorkerExplanationAt(null);
        concreteItem.setWorkerExplanationByUserId(null);
        concreteItem.setWorkerReminderSentAt(null);
        concreteItem.setWorkerReminderCount(0);
        boolean explanationRequired = requiresWorkerExplanation(concreteItem);
        if (explanationRequired) {
            concreteItem.setWorkerExplanationRequestedAt(now);
        }
        String title = workerTaskRequestTitle(concreteItem);
        String text = workerTaskTelegramText(concreteItem, explanationRequired);
        if (explanationRequired && !personalReminderService.hasOpenSystemReminder(workerUser, SOURCE_WORKER_TASK_REQUEST, concreteItem.getId())) {
            personalReminderService.createSystemReminderDueNow(workerUser, title, text, SOURCE_WORKER_TASK_REQUEST, concreteItem.getId(), orderIdForTask(concreteItem));
        }
        if (workerUser.getWorkerTelegramGroupChatId() == null) {
            concreteItem.setWorkerNotificationFailureReason("Telegram-группа специалиста не привязана");
            return false;
        }
        boolean sent = notificationMediaDeliveryService.send(NotificationMediaEventCatalog.WORKER_TASK_FIRST.code(), workerUser.getWorkerTelegramGroupChatId(), workerUser.getId(), text, null, explanationRequired ? List.of(List.of(workerTaskTelegramButton(concreteItem))) : List.of());
        if (sent) {
            concreteItem.setWorkerNotificationSentAt(now);
            if (isWorkerRiskConcrete(concreteItem)) {
                markRiskExplanationRequested(concreteItem, now);
            }
            return true;
        } else {
            concreteItem.setWorkerNotificationFailureReason("Telegram не отправил сообщение");
            return false;
        }
    }

    String workerTaskTelegramText(ManagerDailyControlConcreteItem concreteItem, boolean explanationRequired) {
        Long orderId = orderIdForTask(concreteItem);
        String company = workerTaskCompanyTitle(concreteItem);
        List<String> lines = new ArrayList<>();
        lines.add(explanationRequired ? "🟡 ОЖИДАЕМ ОТВЕТ" : "🔔 НАПОМИНАНИЕ");
        lines.add(explanationRequired ? "Нужно пояснение: " + specialistProblemLabel(concreteItem) + "." : "Напоминание: " + specialistProblemLabel(concreteItem) + ".");
        lines.add("Причина: " + workerFriendlyReason(concreteItem));
        if (orderId != null) {
            lines.add("Заказ: #" + orderId);
        }
        if (!company.isBlank()) {
            lines.add("Фирма: " + company);
        }
        lines.add(explanationRequired ? "Что сделать: нажмите кнопку и отправьте короткое пояснение следующим сообщением." : "Что сделать: проверьте задачу. Пояснение не требуется.");
        return lines.stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n"));
    }

    String workerFriendlyReason(ManagerDailyControlConcreteItem concreteItem) {
        String reason = safe(concreteItem == null ? null : concreteItem.getReason());
        if (reason.isBlank()) {
            reason = safe(concreteItem == null ? null : concreteItem.getStatusLabel());
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("client_text_reminder")) {
            return "Заказ ждет текст клиента, автонапоминание не ушло.";
        }
        reason = reason.replaceFirst("(?iu)^почему\\s+в\\s+контроле:\\s*", "");
        reason = reason.replaceAll("(?iu)\\bCLIENT_[A-Z0-9_]+\\b", "автонапоминание");
        reason = reason.replaceAll("\\s+", " ").trim();
        return reason.isBlank() ? "задача требует проверки" : compact(reason, 240);
    }

    String workerTaskCompanyTitle(ManagerDailyControlConcreteItem concreteItem) {
        Order order = orderForTask(concreteItem);
        String company = safe(order == null || order.getCompany() == null ? null : order.getCompany().getTitle());
        if (!company.isBlank()) {
            return company;
        }
        company = safe(concreteItem == null ? null : concreteItem.getTitle());
        return company.startsWith("Заказ #") ? "" : compact(company, 120);
    }

    Order orderForTask(ManagerDailyControlConcreteItem concreteItem) {
        return workerTaskLookup.orderForTask(concreteItem);
    }

    void markRiskExplanationRequested(ManagerDailyControlConcreteItem concreteItem, LocalDateTime now) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return;
        }
        WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
        if (incident == null || incident.getStatus() != WorkerRiskIncidentStatus.OPEN) {
            return;
        }
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        if (incident.getExplanationRequestedAt() == null) {
            incident.setExplanationRequestedAt(now == null ? LocalDateTime.now() : now);
        }
        riskIncidentRepository.save(incident);
    }

    InlineKeyboardButton workerTaskTelegramButton(ManagerDailyControlConcreteItem concreteItem) {
        if (isWorkerRiskConcrete(concreteItem)) {
            return ManagerControlWorkerTaskTelegramCallbackService.riskExplanationButton(concreteItem.getId());
        }
        return ManagerControlWorkerTaskTelegramCallbackService.explanationButton(concreteItem.getId());
    }

    String workerTaskRequestTitle(ManagerDailyControlConcreteItem concreteItem) {
        if (ENTITY_WORKER_ORDER_NEW.equals(safe(concreteItem == null ? null : concreteItem.getEntityType()))) {
            return "Подготовьте текст нового заказа";
        }
        return "Проверьте проблему";
    }

    User workerUserForTask(ManagerDailyControlConcreteItem concreteItem) {
        return workerTaskLookup.workerUserForTask(concreteItem);
    }

    Long orderIdForTask(ManagerDailyControlConcreteItem concreteItem) {
        return workerTaskLookup.orderIdForTask(concreteItem);
    }

    String compact(String value, int maxLength) {
        return problemExamples.compact(value, maxLength);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
