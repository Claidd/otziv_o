package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
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
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlWorkerTaskTelegramCallbackService {

    public static final String CALLBACK_PREFIX = "mc-task-ack:";
    public static final String EXPLANATION_CALLBACK_PREFIX = "mc-task-explain:";
    public static final String RISK_EXPLANATION_CALLBACK_PREFIX = "mc-task-risk-explain:";
    public static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";
    private static final String GROUP_TASK_EXPLANATION_MARKER = "Код запроса: task-";
    private static final String GROUP_RISK_EXPLANATION_MARKER = "Код запроса: risk-";
    private static final Duration REPEATED_TAP_WINDOW = Duration.ofSeconds(30);

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
    private final ManagerControlTransactionRunner transactions;

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

    /**
     * One short transaction decides the answer and writes the card under its row lock (the repeat
     * reminder pass and manager actions write the same card); the message edit and the explanation
     * prompt are sent only after that commit, so no lock is held during Telegram calls and a rolled
     * back decision sends nothing. A repeated tap of «Пояснить» while the previous prompt is fresh
     * (double tap, redelivered callback) answers the same way without sending a second prompt.
     */
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

        CallbackOutcome outcome = transactions.required(
                () -> decideCallback(concreteItemId, acceptCallback, callbackQuery));
        // Committed: the telegram calls below run without a transaction or card lock.
        outcome.telegram().forEach(this::sendCommitted);
        return Optional.ofNullable(outcome.answer());
    }

    /** A Telegram call decided inside the callback transaction, sent after it commits. */
    private record TelegramCall(boolean edit, boolean forceReply, long chatId, Integer messageId, String text) {
        static TelegramCall edit(long chatId, int messageId, String text) {
            return new TelegramCall(true, false, chatId, messageId, text);
        }

        static TelegramCall message(long chatId, String text) {
            return new TelegramCall(false, false, chatId, null, text);
        }

        static TelegramCall forceReply(long chatId, String text) {
            return new TelegramCall(false, true, chatId, null, text);
        }
    }

    private record CallbackOutcome(String answer, List<TelegramCall> telegram) {
        static CallbackOutcome answer(String answer) {
            return new CallbackOutcome(answer, List.of());
        }
    }

    private CallbackOutcome decideCallback(Long concreteItemId, boolean acceptCallback, CallbackQuery callbackQuery) {
        ManagerDailyControlConcreteItem item = concreteItemRepository.findByIdForUpdate(concreteItemId).orElse(null);
        if (item == null) {
            return CallbackOutcome.answer("Задача не найдена");
        }

        User worker = workerUserForTask(item);
        if (worker == null || worker.getId() == null) {
            return CallbackOutcome.answer("Специалист карточки не найден");
        }

        Long telegramUserId = callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId();
        User actor = telegramUserId == null
                ? null
                : userService.findByChatId(telegramUserId).filter(User::isActive).orElse(null);
        if (actor == null) {
            return CallbackOutcome.answer("Telegram не привязан к пользователю");
        }

        if (!Objects.equals(worker.getId(), actor.getId())) {
            return CallbackOutcome.answer("Эта кнопка предназначена назначенному специалисту");
        }

        if (acceptCallback) {
            acceptTask(item, actor, true);
            return CallbackOutcome.answer("Принято. Менеджер увидит подтверждение.");
        }

        if (isRiskTask(item)) {
            acceptTask(item, actor, false);
            return requestRiskExplanation(item, worker, actor, callbackQuery);
        }
        acceptTask(item, actor, false);
        return requestGeneralExplanation(item, worker, actor, callbackQuery);
    }

    private void sendCommitted(TelegramCall call) {
        try {
            if (call.edit()) {
                telegramService.editMessageText(call.chatId(), call.messageId(), call.text(), null, null);
            } else if (call.forceReply()) {
                telegramService.sendForceReplyMessage(call.chatId(), call.text());
            } else {
                telegramService.sendMessage(call.chatId(), call.text());
            }
        } catch (RuntimeException failure) {
            log.warn("Worker task callback message was not sent: chatId={}", call.chatId(), failure);
        }
    }

    /** The previous prompt is this fresh: a repeated tap answers again without a second prompt. */
    private static boolean promptStillFresh(LocalDateTime promptedAt, LocalDateTime now) {
        return promptedAt != null && !promptedAt.isAfter(now)
                && promptedAt.isAfter(now.minus(REPEATED_TAP_WINDOW));
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
        // Locked: the repeat-reminder pass and manager actions write the same card.
        ManagerDailyControlConcreteItem item = concreteItemRepository.findByIdForUpdate(itemId).orElse(null);
        if (item == null || isRiskTask(item)
                || item.getWorkerExplanationRequestedAt() == null
                || item.getWorkerExplanationAt() != null) {
            return false;
        }
        User itemWorker = workerUserForTask(item);
        if (itemWorker == null || !Objects.equals(itemWorker.getId(), groupWorker.getId())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        item.setWorkerExplanation(limit(messageText, 1000));
        item.setWorkerExplanationAt(now);
        item.setWorkerExplanationByUserId(actor.getId());
        item.setWorkerNotificationFailureReason(null);
        if (reopensForManagerReview(item, now)) {
            item.setStatus(ManagerDailyControlItemStatus.OPEN);
            item.setActionType(null);
            item.setResolvedAt(null);
            item.setFollowUpAt(null);
            reopenParentForManagerReview(item);
        }
        concreteItemRepository.save(item);
        personalReminderService.deleteSystemReminderBySource(groupWorker, SOURCE_WORKER_TASK_REQUEST, item.getId());
        String title = withoutFinancialInfo(item.getTitle());
        afterCommit(() -> telegramService.sendMessage(chatId,
                "🟢 ОТВЕТ ПОЛУЧЕН"
                        + "\nПояснение принято. Менеджер увидит его в карточке контроля."
                        + "\nКарточка: " + title));
        return true;
    }

    /**
     * A late explanation is always recorded, but it reopens a card for the manager only while the
     * card is still unresolved and its control day is current; a closed or past day stays closed.
     */
    private boolean reopensForManagerReview(ManagerDailyControlConcreteItem item, LocalDateTime now) {
        if (item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        return item.getControl() == null || ManagerControlDayLifecycle.isControlDayCurrent(item.getControl(), now);
    }

    /** Telegram only after the change is committed, so no card lock is held during the call. */
    private void afterCommit(Runnable send) {
        Runnable guarded = () -> {
            try {
                send.run();
            } catch (RuntimeException failure) {
                log.warn("Worker explanation confirmation was not sent", failure);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            guarded.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                guarded.run();
            }
        });
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

    private CallbackOutcome requestGeneralExplanation(
            ManagerDailyControlConcreteItem item,
            User worker,
            User actor,
            CallbackQuery callbackQuery
    ) {
        if (item.getWorkerExplanationAt() != null) {
            return CallbackOutcome.answer("Пояснение уже отправлено");
        }

        Long chatId = promptChatId(callbackQuery, worker);
        Integer messageId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getMessageId();

        LocalDateTime now = LocalDateTime.now();
        if (promptStillFresh(item.getWorkerExplanationPromptedAt(), now)) {
            return CallbackOutcome.answer(explanationReplyHint(chatId));
        }
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

        List<TelegramCall> calls = new ArrayList<>();
        if (chatId != null) {
            editGeneralTaskMessageAsAccepted(chatId, messageId, item, calls);
            calls.add(explanationPrompt(chatId, worker,
                    "Принято, нужен комментарий."
                            + "\nКарточка: " + withoutFinancialInfo(item.getTitle())
                            + "\nПричина: " + withoutFinancialInfo(item.getReason()),
                    GROUP_TASK_EXPLANATION_MARKER + item.getId()));
        }

        return new CallbackOutcome(explanationReplyHint(chatId), List.copyOf(calls));
    }

    private void editGeneralTaskMessageAsAccepted(
            Long chatId,
            Integer messageId,
            ManagerDailyControlConcreteItem item,
            List<TelegramCall> calls
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
        calls.add(TelegramCall.edit(chatId, messageId, text));
    }

    private CallbackOutcome requestRiskExplanation(
            ManagerDailyControlConcreteItem item,
            User worker,
            User actor,
            CallbackQuery callbackQuery
    ) {
        WorkerRiskIncident incident = riskIncidentRepository.findById(item.getEntityId()).orElse(null);
        if (incident == null) {
            return CallbackOutcome.answer("Риск не найден");
        }
        if (incident.getStatus() != WorkerRiskIncidentStatus.OPEN) {
            return CallbackOutcome.answer("Риск уже обработан");
        }
        if (incident.getWorkerExplanationAt() != null) {
            return CallbackOutcome.answer("Пояснение уже отправлено");
        }

        LocalDateTime now = LocalDateTime.now();
        if (promptStillFresh(item.getWorkerExplanationPromptedAt(), now)) {
            return CallbackOutcome.answer(explanationReplyHint(promptChatId(callbackQuery, worker)));
        }
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

        chatId = promptChatId(callbackQuery, worker);
        List<TelegramCall> calls = new ArrayList<>();
        if (chatId != null) {
            editTaskMessageAsAccepted(chatId, messageId, item, incident, calls);
            calls.add(explanationPrompt(chatId, worker,
                    "Принято, нужен комментарий."
                            + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                            + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                            + "\nПричина: " + safe(incident.getTitle()),
                    GROUP_RISK_EXPLANATION_MARKER + incident.getId()));
        }

        return new CallbackOutcome(explanationReplyHint(chatId), List.copyOf(calls));
    }

    /** The chat of the pressed button, otherwise the specialist's work group or private chat. */
    private static Long promptChatId(CallbackQuery callbackQuery, User worker) {
        Long chatId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getChatId();
        if (chatId != null) {
            return chatId;
        }
        return worker.getWorkerTelegramGroupChatId() == null
                ? worker.getTelegramChatId()
                : worker.getWorkerTelegramGroupChatId();
    }

    private String explanationReplyHint(Long chatId) {
        return chatId != null && chatId < 0
                ? "Ответьте на сообщение бота с кодом запроса"
                : "Напишите пояснение следующим сообщением";
    }

    private TelegramCall explanationPrompt(long chatId, User worker, String text, String marker) {
        if (chatId < 0) {
            String prompt = text + "\nОтветьте на это сообщение коротким пояснением." + "\n" + marker;
            if (worker != null && worker.getTelegramChatId() != null) {
                return TelegramCall.message(chatId, "Только назначенный специалист.\n" + prompt);
            }
            return TelegramCall.message(chatId,
                    text
                            + "\nTelegram специалиста не привязан к профилю. Привяжите его, чтобы адресно запросить пояснение."
                            + "\n" + marker);
        }
        return TelegramCall.forceReply(chatId, text + "\nНапишите пояснение следующим сообщением.");
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
            WorkerRiskIncident incident,
            List<TelegramCall> calls
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
        calls.add(TelegramCall.edit(chatId, messageId, text));
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
            log.warn("Control card specialist lookup failed: cardId={}, type={}, entityId={}", item.getId(),
                    item.getEntityType(), item.getEntityId(), exception);
            return null;
        }
    }

    /** Scheduler entry point; see {@link #remindPendingExplanation(Long, LocalDateTime)}. */
    public boolean remindPendingExplanation(ManagerDailyControlConcreteItem item, LocalDateTime now) {
        return item != null && item.getId() != null && remindPendingExplanation(item.getId(), now);
    }

    /**
     * Claim, send, then settle. The claim writes only the reminder time and count on the card
     * re-read under lock, so a concurrent pass sees it and does not send twice, and an explanation
     * committed meanwhile is never overwritten. Telegram is called without a transaction. A failed
     * send releases the claim (if nothing changed since) so the next pass retries.
     */
    public boolean remindPendingExplanation(Long itemId, LocalDateTime now) {
        return remindPendingExplanation(itemId, now, () -> { });
    }

    /**
     * As above; {@code claimFence} runs first inside the claim transaction (the scheduler renews its
     * DB lease there), and an exception from it leaves the card unclaimed and propagates.
     */
    public boolean remindPendingExplanation(Long itemId, LocalDateTime now, Runnable claimFence) {
        LocalDateTime checkedAt = now == null ? LocalDateTime.now() : now;
        ReminderClaim claim = transactions.required(() -> {
            claimFence.run();
            return claimReminder(itemId, checkedAt);
        });
        if (claim == null) {
            return false;
        }
        boolean sent;
        try {
            sent = notificationMediaDeliveryService.send(claim.eventCode(), claim.chatId(), claim.workerUserId(),
                    claim.text(), null, claim.keyboard());
        } catch (RuntimeException failure) {
            log.warn("Worker explanation reminder was not sent: cardId={}", itemId, failure);
            sent = false;
        }
        if (!sent) {
            transactions.required(() -> {
                releaseReminderClaim(itemId, claim);
                return null;
            });
        }
        return sent;
    }

    private record ReminderClaim(String eventCode, long chatId, Long workerUserId, String text,
                                 List<List<InlineKeyboardButton>> keyboard, LocalDateTime claimedAt,
                                 LocalDateTime previousReminderAt, int previousCount) {
    }

    private ReminderClaim claimReminder(Long itemId, LocalDateTime checkedAt) {
        ManagerDailyControlConcreteItem item = itemId == null ? null : concreteItemRepository.findByIdForUpdate(itemId).orElse(null);
        if (!isReminderDue(item, checkedAt)) {
            return null;
        }
        User worker = workerUserForTask(item);
        if (worker == null || worker.getId() == null || worker.getWorkerTelegramGroupChatId() == null) {
            return null;
        }
        LocalDateTime sentAt = item.getWorkerNotificationSentAt();
        boolean overdue = !checkedAt.isBefore(sentAt.plusHours(3));
        long waitingMinutes = Math.max(0, Duration.between(sentAt, checkedAt).toMinutes());
        String status = overdue
                ? "🔴 ПРОСРОЧЕНО: ответ не получен более 3 часов"
                : "🟡 ОЖИДАЕМ ОТВЕТ: " + waitingLabel(waitingMinutes);
        String text = status
                + "\nМенеджер ждёт пояснение."
                + "\nКарточка: " + withoutFinancialInfo(item.getTitle())
                + "\nПричина: " + withoutFinancialInfo(item.getReason());
        String eventCode = item.getWorkerReminderCount() > 0
                ? NotificationMediaEventCatalog.WORKER_TASK_OVERDUE.code()
                : NotificationMediaEventCatalog.WORKER_TASK_REPEAT.code();
        List<List<InlineKeyboardButton>> keyboard;
        if (item.getWorkerExplanationPromptedAt() != null) {
            String marker = isRiskTask(item)
                    ? GROUP_RISK_EXPLANATION_MARKER + item.getEntityId()
                    : GROUP_TASK_EXPLANATION_MARKER + item.getId();
            text = text + "\nТолько назначенный специалист: ответьте на это сообщение коротким пояснением.\n" + marker;
            keyboard = List.of();
        } else {
            text = text + "\nНажмите кнопку и отправьте короткое пояснение.";
            keyboard = List.of(List.of(isRiskTask(item) ? riskExplanationButton(item.getId()) : explanationButton(item.getId())));
        }
        ReminderClaim claim = new ReminderClaim(eventCode, worker.getWorkerTelegramGroupChatId(), worker.getId(), text, keyboard,
                checkedAt, item.getWorkerReminderSentAt(), item.getWorkerReminderCount());
        item.setWorkerReminderSentAt(checkedAt);
        item.setWorkerReminderCount(item.getWorkerReminderCount() + 1);
        concreteItemRepository.save(item);
        return claim;
    }

    /**
     * A pending explanation of a card that is not closed, on the current control day, whose last
     * reminder (if any) is at least three hours old. Requests stay pending through the 3-hour
     * follow-up (ACTION_TAKEN/DEFERRED), so they are not limited to OPEN cards.
     */
    private boolean isReminderDue(ManagerDailyControlConcreteItem item, LocalDateTime checkedAt) {
        if (item == null || item.getId() == null
                || item.getWorkerExplanationRequestedAt() == null
                || item.getWorkerNotificationSentAt() == null
                || item.getWorkerExplanationAt() != null
                || item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        if (item.getControl() != null && item.getControl().getControlDate() != null
                && !item.getControl().getControlDate().equals(checkedAt.toLocalDate())) {
            return false;
        }
        return item.getWorkerReminderSentAt() == null
                || !item.getWorkerReminderSentAt().isAfter(checkedAt.minusHours(3));
    }

    private void releaseReminderClaim(Long itemId, ReminderClaim claim) {
        ManagerDailyControlConcreteItem item = concreteItemRepository.findByIdForUpdate(itemId).orElse(null);
        if (item == null || !Objects.equals(item.getWorkerReminderSentAt(), claim.claimedAt())
                || item.getWorkerReminderCount() != claim.previousCount() + 1) {
            return;
        }
        item.setWorkerReminderSentAt(claim.previousReminderAt());
        item.setWorkerReminderCount(claim.previousCount());
        concreteItemRepository.save(item);
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
