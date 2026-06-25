package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.LocalDateTime;
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
    public static final String SOURCE_WORKER_TASK_REQUEST = "MANAGER_CONTROL_WORKER_TASK_REQUEST";

    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;

    public static InlineKeyboardButton acceptButton(Long concreteItemId) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Принял");
        button.setCallbackData(CALLBACK_PREFIX + concreteItemId);
        return button;
    }

    @Transactional
    public Optional<String> handle(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getData() == null || !callbackQuery.getData().startsWith(CALLBACK_PREFIX)) {
            return Optional.empty();
        }

        Long concreteItemId = parseConcreteItemId(callbackQuery.getData());
        if (concreteItemId == null) {
            return Optional.of("Задача не найдена");
        }

        Long telegramUserId = callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId();
        if (telegramUserId == null) {
            return Optional.of("Не удалось определить пользователя");
        }

        User actor = userService.findByChatId(telegramUserId).orElse(null);
        if (actor == null || !actor.isActive()) {
            return Optional.of("Telegram не привязан к пользователю");
        }

        ManagerDailyControlConcreteItem item = concreteItemRepository.findById(concreteItemId).orElse(null);
        if (item == null) {
            return Optional.of("Задача не найдена");
        }

        User worker = workerUserForTask(item);
        if (worker == null || !Objects.equals(worker.getId(), actor.getId())) {
            return Optional.of("Эта кнопка предназначена назначенному работнику");
        }

        if (item.getWorkerNotificationAcceptedAt() == null) {
            item.setWorkerNotificationAcceptedAt(LocalDateTime.now());
            item.setWorkerNotificationAcceptedByUserId(actor.getId());
            item.setWorkerNotificationFailureReason(null);
            concreteItemRepository.save(item);
            personalReminderService.deleteSystemReminderBySource(actor, SOURCE_WORKER_TASK_REQUEST, item.getId());
        }
        return Optional.of("Принято. Менеджер увидит подтверждение.");
    }

    private Long parseConcreteItemId(String data) {
        try {
            return Long.parseLong(data.substring(CALLBACK_PREFIX.length()).trim());
        } catch (RuntimeException exception) {
            return null;
        }
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
                default -> null;
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
