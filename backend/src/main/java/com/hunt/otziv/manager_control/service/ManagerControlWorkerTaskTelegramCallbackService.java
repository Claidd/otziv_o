package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@RequiredArgsConstructor
public class ManagerControlWorkerTaskTelegramCallbackService {

    public static final String CALLBACK_PREFIX = "mc-task-ack:";
    public static final String EXPLANATION_CALLBACK_PREFIX = "mc-task-explain:";
    public static final String RISK_EXPLANATION_CALLBACK_PREFIX = "mc-task-risk-explain:";
    public static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";
    private static final String GROUP_TASK_EXPLANATION_MARKER = "Код запроса: task-";
    private static final String GROUP_RISK_EXPLANATION_MARKER = "Код запроса: risk-";

    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final UserRepository userRepository;
    private final TelegramService telegramService;
    private final NotificationMediaDeliveryService notificationMediaDeliveryService;

    public static InlineKeyboardButton acceptButton(Long concreteItemId) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Принял");
        button.setCallbackData(CALLBACK_PREFIX + concreteItemId);
        return button;
    }

    public static InlineKeyboardButton explanationButton(Long concreteItemId) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Пояснить");
        button.setCallbackData(EXPLANATION_CALLBACK_PREFIX + concreteItemId);
        return button;
    }

    public static InlineKeyboardButton riskExplanationButton(Long concreteItemId) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Пояснить причину");
        button.setCallbackData(RISK_EXPLANATION_CALLBACK_PREFIX + concreteItemId);
        return button;
    }

    @Transactional
    public Optional<String> handle(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getData() == null) {
            return Optional.empty();
        }

        boolean explanationCallback = callbackQuery.getData().startsWith(EXPLANATION_CALLBACK_PREFIX);
        boolean riskExplanationCallback = callbackQuery.getData().startsWith(RISK_EXPLANATION_CALLBACK_PREFIX);
        boolean acceptCallback = callbackQuery.getData().startsWith(CALLBACK_PREFIX);
        if (!explanationCallback && !riskExplanationCallback && !acceptCallback) {
            return Optional.empty();
        }

        Long concreteItemId = parseConcreteItemId(
                callbackQuery.getData(),
                riskExplanationCallback
                        ? RISK_EXPLANATION_CALLBACK_PREFIX
                        : explanationCallback ? EXPLANATION_CALLBACK_PREFIX : CALLBACK_PREFIX
        );
        if (concreteItemId == null) {
            return Optional.of("Задача не найдена");
        }

        ManagerDailyControlConcreteItem item = concreteItemRepository.findById(concreteItemId).orElse(null);
        if (item == null) {
            return Optional.of("Задача не найдена");
        }

        User worker = workerUserForTask(item);
        if (worker == null || worker.getId() == null) {
            return Optional.of("Специалист карточки не найден");
        }

        Long telegramUserId = callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId();
        User actor = telegramUserId == null
                ? null
                : userService.findByChatId(telegramUserId).filter(User::isActive).orElse(null);
        if (actor == null) {
            return Optional.of("Telegram не привязан к пользователю");
        }

        if (!Objects.equals(worker.getId(), actor.getId())) {
            return Optional.of("Эта кнопка предназначена назначенному специалисту");
        }

        if (acceptCallback) {
            acceptTask(item, actor, true);
            return Optional.of("Принято. Менеджер увидит подтверждение.");
        }

        if (isRiskTask(item)) {
            acceptTask(item, actor, false);
            return requestRiskExplanation(item, worker, actor, callbackQuery);
        }
        acceptTask(item, actor, false);
        return requestGeneralExplanation(item, worker, actor, callbackQuery);
    }

    @Transactional
    public boolean handleWorkerGroupTextMessage(
            long chatId,
            Long actorTelegramId,
            String replyToMessageText,
            boolean replyToBotMessage,
            String messageText
    ) {
        if (chatId >= 0 || safe(messageText).isBlank() || !replyToBotMessage) {
            return false;
        }
        User actor = actorTelegramId == null
                ? null
                : userService.findByChatId(actorTelegramId).filter(User::isActive).orElse(null);
        User groupWorker = workerUserForGroup(chatId);
        if (actor == null || actor.getId() == null || groupWorker == null || groupWorker.getId() == null
                || !Objects.equals(actor.getId(), groupWorker.getId())) {
            return false;
        }

        Long itemId = explanationMarkerId(replyToMessageText, GROUP_TASK_EXPLANATION_MARKER);
        if (itemId == null) {
            return false;
        }
        ManagerDailyControlConcreteItem item = concreteItemRepository.findById(itemId).orElse(null);
        if (item == null || isRiskTask(item)
                || item.getWorkerExplanationRequestedAt() == null
                || item.getWorkerExplanationAt() != null) {
            return false;
        }
        User itemWorker = workerUserForTask(item);
        if (itemWorker == null || !Objects.equals(itemWorker.getId(), groupWorker.getId())) {
            return false;
        }
        item.setWorkerExplanation(limit(messageText, 1000));
        item.setWorkerExplanationAt(LocalDateTime.now());
        item.setWorkerExplanationByUserId(actor.getId());
        item.setWorkerNotificationFailureReason(null);
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setActionType(null);
        item.setResolvedAt(null);
        item.setFollowUpAt(null);
        reopenParentForManagerReview(item);
        concreteItemRepository.save(item);
        personalReminderService.deleteSystemReminderBySource(groupWorker, SOURCE_WORKER_TASK_REQUEST, item.getId());
        telegramService.sendMessage(chatId,
                "🟢 ОТВЕТ ПОЛУЧЕН"
                        + "\nПояснение принято. Менеджер увидит его в карточке контроля."
                        + "\nКарточка: " + withoutFinancialInfo(item.getTitle()));
        return true;
    }

    private void reopenParentForManagerReview(ManagerDailyControlConcreteItem item) {
        ManagerDailyControlItem parent = item == null ? null : item.getParentItem();
        if (parent == null) {
            return;
        }
        parent.setStatus(ManagerDailyControlItemStatus.OPEN);
        parent.setActionType(null);
        parent.setResolvedAt(null);
        if (item.getControl() != null) {
            item.getControl().setLastActivityAt(LocalDateTime.now());
        }
    }

    private void acceptTask(ManagerDailyControlConcreteItem item, User actor, boolean deleteReminder) {
        if (item.getWorkerNotificationAcceptedAt() != null) {
            return;
        }
        item.setWorkerNotificationAcceptedAt(LocalDateTime.now());
        item.setWorkerNotificationAcceptedByUserId(actor.getId());
        item.setWorkerNotificationFailureReason(null);
        concreteItemRepository.save(item);
        if (deleteReminder) {
            personalReminderService.deleteSystemReminderBySource(actor, SOURCE_WORKER_TASK_REQUEST, item.getId());
        }
    }

    private Optional<String> requestGeneralExplanation(
            ManagerDailyControlConcreteItem item,
            User worker,
            User actor,
            CallbackQuery callbackQuery
    ) {
        if (item.getWorkerExplanationAt() != null) {
            return Optional.of("Пояснение уже отправлено");
        }

        LocalDateTime now = LocalDateTime.now();
        if (item.getWorkerExplanationRequestedAt() == null) {
            item.setWorkerExplanationRequestedAt(now);
        }
        item.setWorkerExplanationPromptedAt(now);
        item.setWorkerNotificationAcceptedAt(
                item.getWorkerNotificationAcceptedAt() == null ? now : item.getWorkerNotificationAcceptedAt()
        );
        item.setWorkerNotificationAcceptedByUserId(actor.getId());
        item.setWorkerNotificationFailureReason(null);
        concreteItemRepository.save(item);

        Long chatId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getMessageId();
        if (chatId == null) {
            chatId = worker.getWorkerTelegramGroupChatId() == null
                    ? worker.getTelegramChatId()
                    : worker.getWorkerTelegramGroupChatId();
        }
        if (chatId != null) {
            editGeneralTaskMessageAsAccepted(chatId, messageId, item);
            sendExplanationPrompt(chatId, worker,
                    "Принято, нужен комментарий."
                            + "\nКарточка: " + withoutFinancialInfo(item.getTitle())
                            + "\nПричина: " + withoutFinancialInfo(item.getReason()),
                    GROUP_TASK_EXPLANATION_MARKER + item.getId());
        }

        return Optional.of(explanationReplyHint(chatId));
    }

    private void editGeneralTaskMessageAsAccepted(
            Long chatId,
            Integer messageId,
            ManagerDailyControlConcreteItem item
    ) {
        if (chatId == null || messageId == null) {
            return;
        }
        String text = "🟡 ОЖИДАЕМ ОТВЕТ"
                + "\nМенеджер запросил пояснение."
                + "\nСтатус: принято, нужен комментарий"
                + "\nКарточка: " + withoutFinancialInfo(item.getTitle())
                + "\n" + withoutFinancialInfo(item.getSubtitle())
                + "\n" + withoutFinancialInfo(item.getReason())
                + "\n\nОтветьте на отдельное сообщение бота с кодом запроса.";
        telegramService.editMessageText(chatId, messageId, text, null, null);
    }

    private Optional<String> requestRiskExplanation(
            ManagerDailyControlConcreteItem item,
            User worker,
            User actor,
            CallbackQuery callbackQuery
    ) {
        WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
        if (incident == null) {
            return Optional.of("Риск не найден");
        }
        if (incident.getStatus() != WorkerRiskIncidentStatus.OPEN) {
            return Optional.of("Риск уже обработан");
        }
        if (incident.getWorkerExplanationAt() != null) {
            return Optional.of("Пояснение уже отправлено");
        }

        LocalDateTime now = LocalDateTime.now();
        if (item.getWorkerExplanationRequestedAt() == null) {
            item.setWorkerExplanationRequestedAt(now);
        }
        item.setWorkerExplanationPromptedAt(now);
        item.setWorkerNotificationAcceptedAt(
                item.getWorkerNotificationAcceptedAt() == null ? now : item.getWorkerNotificationAcceptedAt()
        );
        item.setWorkerNotificationAcceptedByUserId(actor.getId());
        item.setWorkerNotificationFailureReason(null);
        concreteItemRepository.save(item);

        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        if (incident.getExplanationRequestedAt() == null) {
            incident.setExplanationRequestedAt(now);
        }
        incident.setExplanationPromptedAt(now);

        Long chatId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getMessageId();
        if (chatId != null && messageId != null) {
            incident.setTelegramNotificationChatId(chatId);
            incident.setTelegramNotificationMessageId(messageId);
        }
        riskIncidentRepository.save(incident);

        if (chatId == null) {
            chatId = worker.getWorkerTelegramGroupChatId() == null
                    ? worker.getTelegramChatId()
                    : worker.getWorkerTelegramGroupChatId();
        }
        if (chatId != null) {
            editTaskMessageAsAccepted(chatId, messageId, item, incident);
            sendExplanationPrompt(chatId, worker,
                    "Принято, нужен комментарий."
                            + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                            + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                            + "\nПричина: " + safe(incident.getTitle()),
                    GROUP_RISK_EXPLANATION_MARKER + incident.getId());
        }

        return Optional.of(explanationReplyHint(chatId));
    }

    private String explanationReplyHint(Long chatId) {
        return chatId != null && chatId < 0
                ? "Ответьте на сообщение бота с кодом запроса"
                : "Напишите пояснение следующим сообщением";
    }

    private void sendExplanationPrompt(long chatId, User worker, String text, String marker) {
        if (chatId < 0) {
            String prompt = text + "\nОтветьте на это сообщение коротким пояснением." + "\n" + marker;
            if (worker != null && worker.getTelegramChatId() != null) {
                telegramService.sendMessage(chatId, "Только назначенный специалист.\n" + prompt);
            } else {
                telegramService.sendMessage(chatId,
                        text
                                + "\nTelegram специалиста не привязан к профилю. Привяжите его, чтобы адресно запросить пояснение."
                                + "\n" + marker);
            }
            return;
        }
        telegramService.sendForceReplyMessage(chatId, text + "\nНапишите пояснение следующим сообщением.");
    }

    private Long explanationMarkerId(String text, String marker) {
        String value = safe(text);
        int markerIndex = value.lastIndexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String rawId = value.substring(markerIndex + marker.length()).trim();
        int separator = rawId.indexOf('\n');
        if (separator >= 0) {
            rawId = rawId.substring(0, separator).trim();
        }
        try {
            return Long.parseLong(rawId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void editTaskMessageAsAccepted(
            Long chatId,
            Integer messageId,
            ManagerDailyControlConcreteItem item,
            WorkerRiskIncident incident
    ) {
        if (chatId == null || messageId == null) {
            return;
        }
        String text = "🟡 ОЖИДАЕМ ОТВЕТ"
                + "\nМенеджер запросил действие: проверьте открытый риск."
                + "\nСтатус: принято, нужен комментарий"
                + "\nКарточка: " + withoutFinancialInfo(item.getTitle())
                + "\n" + withoutFinancialInfo(item.getSubtitle())
                + "\n" + withoutFinancialInfo(item.getReason())
                + workerRiskCompanyLine(incident)
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nОтветьте на отдельное сообщение бота с кодом запроса.";
        telegramService.editMessageText(chatId, messageId, text, null, null);
    }

    private Long parseConcreteItemId(String data, String prefix) {
        try {
            return Long.parseLong(data.substring(prefix.length()).trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isRiskTask(ManagerDailyControlConcreteItem item) {
        return "RISK".equals(safe(item == null ? null : item.getEntityType()));
    }

    private User workerUserForGroup(long chatId) {
        return userRepository.findAllByWorkerTelegramGroupChatIdOrderById(chatId).stream()
                .filter(user -> user != null && user.isActive() && user.getId() != null)
                .findFirst()
                .orElse(null);
    }

    private User workerUserForTask(ManagerDailyControlConcreteItem item) {
        if (item == null || item.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(item.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(item.getEntityId());
                    yield task == null || task.getWorker() == null ? null : task.getWorker().getUser();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(item.getEntityId());
                    yield task == null || task.getWorker() == null ? null : task.getWorker().getUser();
                }
                case "PUBLISH_REVIEW" -> {
                    Review review = reviewRepository.findById(item.getEntityId()).orElse(null);
                    Worker worker = review == null ? null : review.getWorker();
                    if (worker == null) {
                        Order order = review == null || review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
                        worker = order == null ? null : order.getWorker();
                    }
                    yield worker == null ? null : worker.getUser();
                }
                case "NAGUL_REVIEW" -> {
                    Review review = reviewRepository.findById(item.getEntityId()).orElse(null);
                    Worker worker = review == null ? null : review.getWorker();
                    if (worker == null) {
                        Order order = review == null || review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
                        worker = order == null ? null : order.getWorker();
                    }
                    yield worker == null ? null : worker.getUser();
                }
                case "WORKER_ORDER_NEW", "WORKER_ORDER_CORRECT" -> {
                    Order order = orderRepository.findById(item.getEntityId()).orElse(null);
                    yield order == null || order.getWorker() == null ? null : order.getWorker().getUser();
                }
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
                    yield incident == null || incident.getWorkerUserId() == null
                            ? null
                            : userRepository.findById(incident.getWorkerUserId()).orElse(null);
                }
                default -> null;
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Transactional
    public boolean remindPendingExplanation(ManagerDailyControlConcreteItem item, LocalDateTime now) {
        if (item == null || item.getId() == null
                || item.getWorkerExplanationRequestedAt() == null
                || item.getWorkerNotificationSentAt() == null
                || item.getWorkerExplanationAt() != null
                || item.getStatus() != ManagerDailyControlItemStatus.OPEN) {
            return false;
        }
        User worker = workerUserForTask(item);
        if (worker == null || worker.getWorkerTelegramGroupChatId() == null) {
            return false;
        }

        LocalDateTime sentAt = item.getWorkerNotificationSentAt();
        LocalDateTime checkedAt = now == null ? LocalDateTime.now() : now;
        boolean overdue = !checkedAt.isBefore(sentAt.plusHours(3));
        long waitingMinutes = Math.max(0, Duration.between(sentAt, checkedAt).toMinutes());
        String status = overdue
                ? "🔴 ПРОСРОЧЕНО: ответ не получен более 3 часов"
                : "🟡 ОЖИДАЕМ ОТВЕТ: " + waitingLabel(waitingMinutes);
        String text = status
                + "\nМенеджер ждёт пояснение."
                + "\nКарточка: " + withoutFinancialInfo(item.getTitle())
                + "\nПричина: " + withoutFinancialInfo(item.getReason());

        long chatId = worker.getWorkerTelegramGroupChatId();
        String eventCode = item.getWorkerReminderCount() > 0
                ? NotificationMediaEventCatalog.WORKER_TASK_OVERDUE.code()
                : NotificationMediaEventCatalog.WORKER_TASK_REPEAT.code();
        boolean sent;
        if (item.getWorkerExplanationPromptedAt() != null) {
            String marker = isRiskTask(item)
                    ? GROUP_RISK_EXPLANATION_MARKER + item.getEntityId()
                    : GROUP_TASK_EXPLANATION_MARKER + item.getId();
            sent = notificationMediaDeliveryService.send(
                    eventCode,
                    chatId,
                    worker.getId(),
                    text + "\nТолько назначенный специалист: ответьте на это сообщение коротким пояснением.\n" + marker,
                    null,
                    List.of()
            );
        } else {
            sent = notificationMediaDeliveryService.send(
                    eventCode,
                    chatId,
                    worker.getId(),
                    text + "\nНажмите кнопку и отправьте короткое пояснение.",
                    null,
                    List.of(List.of(isRiskTask(item) ? riskExplanationButton(item.getId()) : explanationButton(item.getId())))
            );
        }
        if (!sent) {
            return false;
        }
        item.setWorkerReminderSentAt(checkedAt);
        item.setWorkerReminderCount(item.getWorkerReminderCount() + 1);
        concreteItemRepository.save(item);
        return true;
    }

    private String waitingLabel(long minutes) {
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours > 0 ? hours + " ч " + remainder + " мин" : remainder + " мин";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String withoutFinancialInfo(String value) {
        return safe(value)
                .replaceAll("(?iu)(?:сумма|стоимость|цена|к\\s+оплате|остаток)\\s*:?\\s*[+−-]?\\s*\\d[\\d\\s.,]*\\s*(?:руб(?:\\.|лей|ля)?|₽)?", "")
                .replaceAll("(?iu)[+−-]?\\s*\\d[\\d\\s.,]*\\s*(?:руб(?:\\.|лей|ля)?|₽)", "")
                .replaceAll("\\s*[·|]\\s*(?=[·|]|$)", "")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("^[\\s·|,;:-]+|[\\s·|,;:-]+$", "")
                .trim();
    }

    private String workerRiskCompanyLine(WorkerRiskIncident incident) {
        if (incident == null || incident.getOrderId() == null) {
            return "";
        }
        try {
            return orderRepository.findById(incident.getOrderId())
                    .filter(order -> order.getCompany() != null)
                    .map(order -> "\nКомпания: " + safe(order.getCompany().getTitle()))
                    .orElse("");
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String limit(String value, int maxLength) {
        String text = safe(value);
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
