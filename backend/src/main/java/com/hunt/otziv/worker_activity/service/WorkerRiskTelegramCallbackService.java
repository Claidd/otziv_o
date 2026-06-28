package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEvaluationService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskTelegramCallbackService {

    private static final String CALLBACK_PREFIX = "worker-risk:";
    private static final String EXPLANATION_CALLBACK_PREFIX = "worker-risk-explain:";
    private static final String SOURCE_MANAGER_WARNING = "WORKER_RISK_MANAGER_WARNING";
    private static final String SOURCE_MANAGER_VIOLATION = "WORKER_RISK_MANAGER_VIOLATION";
    private static final String SOURCE_WORKER_EXPLANATION = "WORKER_RISK_WORKER_EXPLANATION";
    private static final String WORKER_RISK_PENALTY_EVENT = "WORKER_RISK_PENALTY";
    private static final int DEFAULT_PENALTY_POINTS = 1;

    private final WorkerRiskIncidentRepository incidentRepository;
    private final GamificationScoreLedgerRepository scoreLedgerRepository;
    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;

    public static List<List<InlineKeyboardButton>> keyboard(Long incidentId) {
        return List.of(
                List.of(
                        button("Проверено", incidentId, "v"),
                        button("Игнор", incidentId, "i")
                ),
                List.of(
                        button("Разъяснение", incidentId, "e"),
                        button("Нарушение / штраф", incidentId, "p")
                )
        );
    }

    private static InlineKeyboardButton button(String text, Long incidentId, String code) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(CALLBACK_PREFIX + incidentId + ":" + code);
        return button;
    }

    public static List<List<InlineKeyboardButton>> explanationKeyboard(Long incidentId) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Пояснить причину");
        button.setCallbackData(EXPLANATION_CALLBACK_PREFIX + incidentId);
        return List.of(List.of(button));
    }

    @Transactional
    public Optional<String> handle(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getData() == null) {
            return Optional.empty();
        }
        if (callbackQuery.getData().startsWith(EXPLANATION_CALLBACK_PREFIX)) {
            return handleExplanationPrompt(callbackQuery);
        }
        if (!callbackQuery.getData().startsWith(CALLBACK_PREFIX)) {
            return Optional.empty();
        }

        Long actorTelegramId = callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId();
        if (actorTelegramId == null) {
            return Optional.of("Не удалось определить пользователя");
        }

        Optional<CallbackCommand> command = parse(callbackQuery.getData());
        if (command.isEmpty()) {
            return Optional.of("Команда не распознана");
        }

        User actor = userService.findByChatId(actorTelegramId).orElse(null);
        if (actor == null || !actor.isActive()) {
            return Optional.of("Telegram не привязан к пользователю");
        }

        WorkerRiskIncident incident = incidentRepository.findById(command.get().incidentId()).orElse(null);
        if (incident == null) {
            return Optional.of("Инцидент не найден");
        }
        if (!canAccess(actor, incident)) {
            return Optional.of("Нет доступа к этому инциденту");
        }
        if (incident.getStatus() != WorkerRiskIncidentStatus.OPEN) {
            return Optional.of("Инцидент уже обработан");
        }

        applyResolution(incident, command.get().action(), actor);
        return Optional.of(answerFor(command.get().action()));
    }

    @Transactional
    public boolean handleWorkerTextMessage(long chatId, User user, String messageText) {
        if (user == null || user.getId() == null || !user.isActive() || clean(messageText).isBlank()) {
            return false;
        }
        return findPendingWorkerExplanation(user)
                .map(incident -> saveWorkerExplanation(chatId, user, user, incident, messageText))
                .orElse(false);
    }

    @Transactional
    public boolean handleWorkerGroupTextMessage(long chatId, Long actorTelegramId, String messageText) {
        if (chatId >= 0 || clean(messageText).isBlank()) {
            return false;
        }

        WorkerRiskIncident incident = findPendingGroupExplanation(chatId).orElse(null);
        if (incident == null) {
            return false;
        }

        User worker = workerFor(incident).orElse(null);
        if (worker == null || worker.getId() == null || !worker.isActive()
                || !Objects.equals(worker.getWorkerTelegramGroupChatId(), chatId)) {
            return false;
        }

        User actor = actorTelegramId == null
                ? null
                : userService.findByChatId(actorTelegramId).filter(User::isActive).orElse(null);
        return saveWorkerExplanation(chatId, worker, actor, incident, messageText);
    }

    private boolean saveWorkerExplanation(
            long chatId,
            User worker,
            User actor,
            WorkerRiskIncident incident,
            String messageText
    ) {
        incident.setWorkerExplanation(limit(clean(messageText), 2000));
        incident.setWorkerExplanationAt(LocalDateTime.now());
        incident.setWorkerExplanationByUserId(actor != null && actor.getId() != null ? actor.getId() : worker.getId());
        incidentRepository.save(incident);
        personalReminderService.deleteSystemReminderBySource(worker, SOURCE_MANAGER_WARNING, incident.getId());

        telegramService.sendMessage(chatId,
                "Пояснение сохранено и отправлено менеджеру."
                        + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                        + "\nОтзыв: #" + valueOrDash(incident.getReviewId()));
        notifyReviewersAboutExplanation(worker, incident);
        return true;
    }

    private Optional<WorkerRiskIncident> findPendingWorkerExplanation(User worker) {
        return incidentRepository
                .findFirstByWorkerUserIdAndStatusAndResolutionActionAndWorkerExplanationAtIsNullAndExplanationPromptedAtIsNotNullOrderByExplanationPromptedAtDescCreatedAtDesc(
                        worker.getId(),
                        WorkerRiskIncidentStatus.OPEN,
                        WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                );
    }

    private Optional<WorkerRiskIncident> findPendingGroupExplanation(long chatId) {
        return incidentRepository.findPendingExplanationByWorkerGroupChatId(
                        chatId,
                        WorkerRiskIncidentStatus.OPEN,
                        WorkerRiskResolutionAction.EXPLANATION_REQUESTED,
                        PageRequest.of(0, 1)
                ).stream()
                .findFirst();
    }

    private Optional<CallbackCommand> parse(String callbackData) {
        String[] parts = callbackData.split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            Long incidentId = Long.parseLong(parts[1]);
            return Optional.of(new CallbackCommand(incidentId, actionFor(parts[2])));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> handleExplanationPrompt(CallbackQuery callbackQuery) {
        Long actorTelegramId = callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId();
        Long incidentId = parseExplanationIncidentId(callbackQuery.getData());
        if (incidentId == null) {
            return Optional.of("Инцидент не найден");
        }

        WorkerRiskIncident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            return Optional.of("Инцидент не найден");
        }
        if (incident.getWorkerExplanationAt() != null) {
            return Optional.of("Пояснение уже отправлено");
        }

        Long chatId = callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getChatId();
        User worker = workerFor(incident).orElse(null);
        if (worker == null || worker.getId() == null || !worker.isActive()) {
            return Optional.of("Специалист не найден");
        }

        if (chatId != null && chatId < 0) {
            if (!Objects.equals(worker.getWorkerTelegramGroupChatId(), chatId)) {
                return Optional.of("Эта группа не привязана к специалисту");
            }
        } else {
            if (actorTelegramId == null) {
                return Optional.of("Не удалось определить пользователя");
            }
            User actor = userService.findByChatId(actorTelegramId).orElse(null);
            if (actor == null || !actor.isActive()) {
                return Optional.of("Telegram не привязан к пользователю");
            }
            if (!Objects.equals(actor.getId(), incident.getWorkerUserId())) {
                return Optional.of("Эта кнопка предназначена специалисту");
            }
            chatId = chatId == null ? actor.getTelegramChatId() : chatId;
        }

        LocalDateTime now = LocalDateTime.now();
        incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        if (incident.getExplanationRequestedAt() == null) {
            incident.setExplanationRequestedAt(now);
        }
        incident.setExplanationPromptedAt(now);
        incidentRepository.save(incident);

        if (chatId != null) {
            telegramService.sendMessage(chatId,
                    "Напишите пояснение следующим сообщением."
                            + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                            + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                            + "\nПричина: " + clean(incident.getTitle()));
        }
        return Optional.of("Напишите пояснение следующим сообщением");
    }

    private Optional<User> workerFor(WorkerRiskIncident incident) {
        if (incident == null) {
            return Optional.empty();
        }
        return userService.findByUserName(incident.getWorkerUsername())
                .filter(user -> Objects.equals(user.getId(), incident.getWorkerUserId()));
    }

    private Long parseExplanationIncidentId(String callbackData) {
        String raw = callbackData == null ? "" : callbackData.substring(EXPLANATION_CALLBACK_PREFIX.length()).trim();
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private WorkerRiskResolutionAction actionFor(String code) {
        return switch (code) {
            case "v" -> WorkerRiskResolutionAction.VERIFIED;
            case "i" -> WorkerRiskResolutionAction.FALSE_POSITIVE;
            case "e" -> WorkerRiskResolutionAction.EXPLANATION_REQUESTED;
            case "p" -> WorkerRiskResolutionAction.VIOLATION_CONFIRMED;
            default -> throw new IllegalArgumentException("Unknown worker risk callback");
        };
    }

    private boolean canAccess(User actor, WorkerRiskIncident incident) {
        if (hasRole(actor, "ROLE_ADMIN")) {
            return true;
        }
        if (hasRole(actor, "ROLE_OWNER")) {
            Set<Manager> managers = userService.findManagersByUserName(actor.getUsername());
            return userService.findAllRelevantUserIdsForOwner(managers).contains(incident.getWorkerUserId());
        }
        if (hasRole(actor, "ROLE_MANAGER")) {
            return userService.findAllRelevantUserIdsForManagerIds(
                    userService.findManagerIdsByUserId(actor.getId())
            ).contains(incident.getWorkerUserId());
        }
        return false;
    }

    private boolean hasRole(User user, String roleName) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().stream().anyMatch(role -> roleName.equals(role.getName()));
    }

    private void applyResolution(WorkerRiskIncident incident, WorkerRiskResolutionAction action, User actor) {
        incident.setStatus(statusFor(action));
        incident.setResolutionAction(action);
        incident.setResolvedAt(LocalDateTime.now());
        incident.setResolvedByUserId(actor.getId());
        incident.setResolvedByUsername(actor.getUsername());
        incident.setPenaltyPoints(action == WorkerRiskResolutionAction.VIOLATION_CONFIRMED ? DEFAULT_PENALTY_POINTS : 0);

        if (action == WorkerRiskResolutionAction.EXPLANATION_REQUESTED) {
            requestWorkerExplanation(incident);
        } else if (action == WorkerRiskResolutionAction.VIOLATION_CONFIRMED) {
            recordPenalty(incident);
            notifyWorkerViolation(incident);
        }

        WorkerRiskIncident savedIncident = incidentRepository.save(incident);
        deleteResolvedRiskReminders(savedIncident);
    }

    private WorkerRiskIncidentStatus statusFor(WorkerRiskResolutionAction action) {
        return switch (action) {
            case FALSE_POSITIVE, NORMAL_ACCOUNT_SELECTION -> WorkerRiskIncidentStatus.IGNORED;
            case EXPLANATION_REQUESTED, WORKER_WARNED -> WorkerRiskIncidentStatus.OPEN;
            case VIOLATION_CONFIRMED -> WorkerRiskIncidentStatus.VIOLATION;
            case VERIFIED -> WorkerRiskIncidentStatus.RESOLVED;
        };
    }

    private void deleteResolvedRiskReminders(WorkerRiskIncident incident) {
        if (incident == null || incident.getStatus() == WorkerRiskIncidentStatus.OPEN) {
            return;
        }

        personalReminderService.deleteSystemRemindersBySource(
                WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT,
                incident.getId()
        );
        personalReminderService.deleteSystemRemindersBySource(SOURCE_MANAGER_WARNING, incident.getId());
        personalReminderService.deleteSystemRemindersBySource(SOURCE_WORKER_EXPLANATION, incident.getId());
    }

    private void requestWorkerExplanation(WorkerRiskIncident incident) {
        User worker = userService.findByUserName(incident.getWorkerUsername()).orElse(null);
        if (worker == null || !worker.isActive()) {
            return;
        }
        incident.setExplanationRequestedAt(LocalDateTime.now());

        String text = "Менеджер проверил подозрительное действие и просит дать пояснение."
                + "\nПричина: " + clean(incident.getTitle())
                + "\nДействие: " + clean(incident.getAction())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nПожалуйста, напишите менеджеру, что произошло, и подтвердите фактическое выполнение. "
                + "Рабочие кнопки нужно нажимать только после реального выполнения задачи.";

        if (!personalReminderService.hasOpenSystemReminder(worker, SOURCE_MANAGER_WARNING, incident.getId())) {
            try {
                personalReminderService.createSystemReminderDueNow(
                        worker,
                        "Нужно пояснение по действию",
                        text,
                        SOURCE_MANAGER_WARNING,
                        incident.getId(),
                        incident.getOrderId()
                );
                if (worker.getWorkerTelegramGroupChatId() != null) {
                    telegramService.sendMessageWithInlineKeyboard(
                            worker.getWorkerTelegramGroupChatId(),
                            text,
                            null,
                            explanationKeyboard(incident.getId())
                    );
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось отправить запрос пояснения по риск-инциденту incidentId={}, workerUserId={}",
                        incident.getId(),
                        incident.getWorkerUserId(),
                        exception);
            }
        }
    }

    private void notifyReviewersAboutExplanation(User worker, WorkerRiskIncident incident) {
        String text = "Специалист дал пояснение по подозрительному действию."
                + "\nСпециалист: " + firstNonBlank(incident.getWorkerName(), incident.getWorkerUsername())
                + "\nПричина: " + clean(incident.getTitle())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nПояснение:\n" + clean(incident.getWorkerExplanation())
                + "\n\nОткройте раздел «Риски» и завершите проверку: «Проверено», «Игнор» или «Нарушение / штраф».";

        for (User recipient : recipients(worker).values()) {
            try {
                if (!personalReminderService.hasOpenSystemReminder(recipient, SOURCE_WORKER_EXPLANATION, incident.getId())) {
                    personalReminderService.createSystemReminderDueNow(
                            recipient,
                            "Получено пояснение специалиста",
                            limit(text, 1000),
                            SOURCE_WORKER_EXPLANATION,
                            incident.getId(),
                            incident.getOrderId()
                    );
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось создать напоминание о пояснении incidentId={}, userId={}",
                        incident.getId(),
                        recipient.getId(),
                        exception);
            }

            if (recipient.getTelegramChatId() != null) {
                try {
                    telegramService.sendMessage(recipient.getTelegramChatId(), text);
                } catch (RuntimeException exception) {
                    log.warn("Не удалось отправить пояснение специалиста incidentId={}, userId={}",
                            incident.getId(),
                            recipient.getId(),
                            exception);
                }
            }
        }
    }

    private Map<Long, User> recipients(User workerUser) {
        Map<Long, User> result = new LinkedHashMap<>();

        if (workerUser.getManagers() != null) {
            workerUser.getManagers().stream()
                    .filter(Objects::nonNull)
                    .map(Manager::getUser)
                    .forEach(user -> addRecipient(result, user));
        }

        safeUsers(userService.getAllOwners("ROLE_OWNER")).forEach(user -> addRecipient(result, user));
        safeUsers(userService.getAllOwners("ROLE_ADMIN")).forEach(user -> addRecipient(result, user));
        result.remove(workerUser.getId());
        return result;
    }

    private void addRecipient(Map<Long, User> recipients, User user) {
        if (user != null && user.getId() != null && user.isActive()) {
            recipients.putIfAbsent(user.getId(), user);
        }
    }

    private List<User> safeUsers(List<User> users) {
        return users == null ? List.of() : users;
    }

    private void notifyWorkerViolation(WorkerRiskIncident incident) {
        User worker = userService.findByUserName(incident.getWorkerUsername()).orElse(null);
        if (worker == null || !worker.isActive()) {
            return;
        }

        String text = "Менеджер подтвердил нарушение по подозрительному действию."
                + "\nПричина: " + clean(incident.getTitle())
                + "\nДействие: " + clean(incident.getAction())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\nШтрафные баллы: " + incident.getPenaltyPoints()
                + "\n\nЕсли задача сделана неверно, менеджер может вернуть поддерживаемые карточки в работу "
                + "из раздела рисков. Для остальных случаев дождитесь указаний менеджера.";

        if (!personalReminderService.hasOpenSystemReminder(worker, SOURCE_MANAGER_VIOLATION, incident.getId())) {
            try {
                personalReminderService.createSystemReminderDueNow(
                        worker,
                        "Подтверждено нарушение",
                        text,
                        SOURCE_MANAGER_VIOLATION,
                        incident.getId(),
                        incident.getOrderId()
                );
                if (worker.getWorkerTelegramGroupChatId() != null) {
                    telegramService.sendMessage(worker.getWorkerTelegramGroupChatId(), text);
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось отправить уведомление о нарушении incidentId={}, workerUserId={}",
                        incident.getId(),
                        incident.getWorkerUserId(),
                        exception);
            }
        }
    }

    private void recordPenalty(WorkerRiskIncident incident) {
        String uniqueScoreKey = "worker-risk-penalty:" + incident.getId();
        if (scoreLedgerRepository.existsByUniqueScoreKey(uniqueScoreKey)) {
            return;
        }
        int penaltyPoints = Math.max(1, incident.getPenaltyPoints());
        scoreLedgerRepository.save(GamificationScoreLedger.builder()
                .eventType(WORKER_RISK_PENALTY_EVENT)
                .actorUserId(incident.getWorkerUserId())
                .actorRole("WORKER")
                .actorName(firstNonBlank(incident.getWorkerName(), incident.getWorkerUsername()))
                .points(-penaltyPoints)
                .rulePoints(-penaltyPoints)
                .basePoints(0)
                .orderId(incident.getOrderId())
                .reviewId(incident.getReviewId())
                .uniqueScoreKey(uniqueScoreKey)
                .sourceEventCreatedAt(incident.getResolvedAt() == null ? LocalDateTime.now() : incident.getResolvedAt())
                .build());
    }

    private String answerFor(WorkerRiskResolutionAction action) {
        return switch (action) {
            case FALSE_POSITIVE -> "Инцидент проигнорирован";
            case EXPLANATION_REQUESTED -> "Разъяснение запрошено";
            case VIOLATION_CONFIRMED -> "Нарушение зафиксировано";
            case VERIFIED -> "Инцидент проверен";
            default -> "Статус обновлен";
        };
    }

    private String firstNonBlank(String first, String fallback) {
        String value = clean(first);
        return value.isBlank() ? clean(fallback) : value;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        String cleaned = clean(value);
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength - 1) + "…";
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private record CallbackCommand(Long incidentId, WorkerRiskResolutionAction action) {
    }
}
