package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlDayActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlReminderWorkflow {

    private final ManagerControlDailySnapshotWorkflow dailySnapshot;

    private final ManagerControlDayLifecycle dayLifecycle;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlClientMessageText clientMessageText;

    static final String SOURCE_CONTROL_OWNER = "MANAGER_CONTROL_OWNER";

    private final ManagerRepository managerRepository;

    private final UserRepository userRepository;

    private final PersonalReminderService personalReminderService;

    private final TelegramService telegramService;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlItemRepository dailyControlItemRepository;

    private final PlatformTransactionManager transactionManager;

    record OwnerTelegramMessage(long chatId, String text) {}

    /**
     * One short transaction per manager; owner Telegram messages are sent only
     * after that manager's transaction commits. Previously every manager was
     * processed in one transaction holding card row locks, with Telegram
     * calls inside it, and one failing manager rolled back all the others.
     */
    public void runTestModeNotifications() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<Long> managerIds = transaction.execute(status -> managerRepository.findAllWithUserAndImage().stream()
                .map(Manager::getId).filter(Objects::nonNull).toList());
        if (managerIds == null || managerIds.isEmpty()) {
            return;
        }
        boolean previousDayDue = !now.toLocalTime().isBefore(MORNING_STAGE_START);
        for (Long managerId : managerIds) {
            List<OwnerTelegramMessage> outgoing = new ArrayList<>();
            try {
                transaction.executeWithoutResult(status -> {
                    Manager manager = managerRepository.findByIdWithUser(managerId)
                            .map(found -> managerRepository.findAllManagersWorkers(List.of(found)))
                            .flatMap(found -> found.stream().findFirst())
                            .orElse(null);
                    if (manager == null) {
                        return;
                    }
                    managerControl(manager, today, null, true, false);
                    dailyControlRepository.findByControlDateAndManager(today, manager).ifPresent(control -> {
                        sendOverdueStageNotifications(control, now, false, outgoing);
                        autoCloseControlIfReady(control, now);
                    });
                    if (previousDayDue) {
                        dailyControlRepository.findByControlDateAndManager(today.minusDays(1), manager).ifPresent(control -> {
                            sendOverdueStageNotifications(control, now, true, outgoing);
                            autoCloseControlIfReady(control, now);
                        });
                    }
                });
            } catch (RuntimeException failure) {
                outgoing.clear();
                log.warn("Manager control pass failed: managerId={}, errorType={}", managerId, failure.getClass().getSimpleName(), failure);
                continue;
            }
            outgoing.forEach(message -> telegramService.sendMessage(message.chatId(), message.text()));
        }
    }

    void sendOverdueStageNotifications(ManagerDailyControl control, LocalDateTime now, boolean previousDayOnly,
                                       List<OwnerTelegramMessage> outgoing) {
        List<ManagerDailyControlItem> items = activeControlItems(dailyControlItemRepository.findByControl(control));
        long openAction = items.stream().filter(cardLifecycle::isOpenActionItem).count();
        boolean changed = false;
        if ((previousDayOnly || control.getControlDate().isBefore(now.toLocalDate())) && !now.toLocalTime().isBefore(MORNING_STAGE_START) && control.getFinalCheckedAt() == null && control.getEveningNotificationSentAt() == null) {
            control.setEveningNotificationSentAt(now);
            String text = overdueStageText(control, "конец дня", "05:00", openAction);
            cardLifecycle.saveEvent(control, null, null, ManagerDailyControlEventType.TEST_NOTIFICATION, null, text);
            notifyOwners(control, "Просрочен конец дня", text, outgoing);
            changed = true;
        }
        if (changed) {
            dailyControlRepository.save(control);
        }
    }

    boolean autoCloseControlIfReady(ManagerDailyControl control, LocalDateTime now) {
        return dailySnapshot.autoCloseControlIfReady(control, now);
    }

    String overdueStageText(ManagerDailyControl control, String stageName, String deadline, long openAction) {
        return "Просрочен " + stageName + ": " + managerName(control.getManager()) + ", дата " + control.getControlDate() + ", дедлайн " + deadline + ", открытых пунктов " + openAction;
    }

    /** Reminders are written in the caller's transaction; Telegram messages are queued for after commit. */
    void notifyOwners(ManagerDailyControl control, String title, String text, List<OwnerTelegramMessage> outgoing) {
        List<User> recipients = new ArrayList<>();
        recipients.addAll(userRepository.findAllOwners("ROLE_OWNER"));
        recipients.addAll(userRepository.findAllOwners("ROLE_ADMIN"));
        recipients.stream().filter(Objects::nonNull).filter(user -> user.getId() != null).collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left)).values().forEach(user -> {
            createReminder(user, title, text, SOURCE_CONTROL_OWNER, control.getId());
            if (user.getTelegramChatId() != null) {
                outgoing.add(new OwnerTelegramMessage(user.getTelegramChatId(), title + "\n" + text));
            }
        });
    }

    void notifyOwnersAboutDeferredConcreteItem(ManagerDailyControl control, ManagerDailyControlConcreteItem concreteItem, Principal principal) {
        if (control == null || concreteItem == null) {
            return;
        }
        String title = "Карточка контроля отложена";
        String text = String.join("\n", title, "Менеджер контроля: " + managerName(control.getManager()), "Отложил: " + currentUserDisplayName(principal), "Карточка: " + safe(concreteItem.getTitle()), "Статус: " + safe(concreteItem.getStatusLabel()), "Проблема: " + safe(concreteItem.getReason()), "Комментарий: " + safe(concreteItem.getComment()), "Ссылка: " + deferredConcreteItemUrl(control, concreteItem));
        List<User> recipients = new ArrayList<>();
        recipients.addAll(userRepository.findAllOwners("ROLE_OWNER"));
        recipients.addAll(userRepository.findAllOwners("ROLE_ADMIN"));
        recipients.stream().filter(Objects::nonNull).filter(user -> user.getId() != null && user.getTelegramChatId() != null).collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left)).values().forEach(user -> telegramService.sendMessage(user.getTelegramChatId(), text));
    }

    String deferredConcreteItemUrl(ManagerDailyControl control, ManagerDailyControlConcreteItem concreteItem) {
        String targetUrl = safe(concreteItem == null ? null : concreteItem.getTargetUrl());
        if (!targetUrl.isBlank()) {
            return clientMessageText.absoluteAppUrl(targetUrl);
        }
        Long managerId = control == null || control.getManager() == null ? null : control.getManager().getId();
        return clientMessageText.absoluteAppUrl(managerId == null ? "/admin/manager-control" : "/admin/manager-control/" + managerId);
    }

    String currentUserDisplayName(Principal principal) {
        User user = accessPolicy.currentUser(principal);
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Неизвестный пользователь" : username;
    }

    void createReminder(User user, String title, String text, String sourceType, Long sourceId) {
        if (user == null || user.getId() == null || sourceId == null) {
            return;
        }
        if (personalReminderService.hasOpenSystemReminder(user, sourceType, sourceId)) {
            return;
        }
        personalReminderService.createSystemReminderDueNow(user, title, text, sourceType, sourceId, null);
    }

    List<ManagerDailyControlItem> activeControlItems(List<ManagerDailyControlItem> items) {
        return dayLifecycle.activeControlItems(items);
    }

    ManagerControlManagerResponse managerControl(Manager manager, LocalDate today, ManagerPerformanceScoreResponse managerPerformance, boolean persist, boolean includeOperationalMetrics) {
        return dailySnapshot.managerControl(manager, today, managerPerformance, persist, includeOperationalMetrics);
    }

    String managerName(Manager manager) {
        return dailySnapshot.managerName(manager);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
