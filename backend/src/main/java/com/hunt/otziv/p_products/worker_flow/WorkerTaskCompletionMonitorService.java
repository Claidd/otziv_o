package com.hunt.otziv.p_products.worker_flow;

import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerTaskCompletionMonitorService {

    public static final String ACTION_TASK_COMPLETED = "worker_task_completed";
    private static final String SOURCE_SUSPICIOUS_COMPLETION = "SUSPICIOUS_TASK_COMPLETION";
    private static final List<String> COMPLETION_ACTIONS = List.of(ACTION_TASK_COMPLETED);
    private static final int TEN_MINUTE_LIMIT = 10;
    private static final int HOUR_LIMIT = 30;
    private static final int DAY_LIMIT = 70;

    private final BusinessAuditEventRepository auditEventRepository;
    private final PersonalReminderService personalReminderService;
    private final UserService userService;
    private final TelegramService telegramService;

    public void warnIfSuspiciousCompletion(Authentication authentication, String taskSection, Long taskId) {
        if (!isPlainWorker(authentication)) {
            return;
        }

        String username = authentication.getName();
        User workerUser = userService.findByUserNameWithAssignments(username).orElse(null);
        if (workerUser == null || workerUser.getId() == null) {
            log.warn("Подозрительные закрытия не проверены: пользователь {} не найден", username);
            return;
        }

        CompletionCounters counters = counters(username);
        if (!counters.suspicious()) {
            return;
        }

        String workerText = workerWarningText(counters, taskSection, taskId);
        notifyUser(workerUser, "Предупреждение по закрытию задач", workerText, workerUser.getId());

        String managerText = managerWarningText(workerUser, counters, taskSection, taskId);
        recipients(workerUser).values().forEach(user ->
                notifyUser(user, "Проверьте закрытия специалиста", managerText, workerUser.getId())
        );

        log.warn(
                "Подозрительное массовое закрытие задач: workerUserId={}, username={}, taskSection={}, taskId={}, tenMinutes={}, hour={}, day={}",
                workerUser.getId(),
                username,
                taskSection,
                taskId,
                counters.tenMinutes(),
                counters.hour(),
                counters.day()
        );
    }

    private CompletionCounters counters(String actor) {
        LocalDateTime now = LocalDateTime.now();
        long tenMinutes = auditEventRepository.countByActorAndActionsSince(
                actor,
                COMPLETION_ACTIONS,
                now.minusMinutes(10)
        );
        long hour = auditEventRepository.countByActorAndActionsSince(
                actor,
                COMPLETION_ACTIONS,
                now.minusHours(1)
        );
        long day = auditEventRepository.countByActorAndActionsSince(
                actor,
                COMPLETION_ACTIONS,
                now.toLocalDate().atStartOfDay()
        );

        return new CompletionCounters(tenMinutes, hour, day);
    }

    private Map<Long, User> recipients(User workerUser) {
        Map<Long, User> result = new LinkedHashMap<>();

        if (workerUser.getManagers() != null) {
            workerUser.getManagers().stream()
                    .filter(Objects::nonNull)
                    .map(Manager::getUser)
                    .forEach(user -> addRecipient(result, user));
        }

        userService.getAllOwners("ROLE_OWNER").forEach(user -> addRecipient(result, user));
        result.remove(workerUser.getId());
        return result;
    }

    private void addRecipient(Map<Long, User> recipients, User user) {
        if (user == null || user.getId() == null || !user.isActive()) {
            return;
        }
        recipients.putIfAbsent(user.getId(), user);
    }

    private void notifyUser(User user, String title, String text, Long sourceId) {
        boolean alreadyOpen = false;
        try {
            alreadyOpen = personalReminderService.hasOpenSystemReminder(user, SOURCE_SUSPICIOUS_COMPLETION, sourceId);
            if (!alreadyOpen) {
                personalReminderService.createSystemReminderDueNow(
                        user,
                        limit(title, 120),
                        limit(text, 1000),
                        SOURCE_SUSPICIOUS_COMPLETION,
                        sourceId,
                        null
                );
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось создать предупреждение о массовом закрытии задач userId={}", user.getId(), e);
            alreadyOpen = true;
        }

        if (!alreadyOpen && user.getTelegramChatId() != null) {
            try {
                telegramService.sendMessage(user.getTelegramChatId(), text);
            } catch (RuntimeException e) {
                log.warn("Не удалось отправить Telegram-предупреждение о массовом закрытии задач userId={}", user.getId(), e);
            }
        }
    }

    private String workerWarningText(CompletionCounters counters, String taskSection, Long taskId) {
        return "Система заметила массовое закрытие задач."
                + "\nРаздел: " + clean(taskSection)
                + "\nПоследняя задача: #" + (taskId == null ? "-" : taskId)
                + countersText(counters)
                + "\n\nЗакрывайте задачу только после фактического выполнения. Подозрительные закрытия будут проверяться менеджером.";
    }

    private String managerWarningText(User workerUser, CompletionCounters counters, String taskSection, Long taskId) {
        return "Специалист массово закрывает задачи."
                + "\nСпециалист: " + workerName(workerUser)
                + "\nЛогин: " + clean(workerUser.getUsername())
                + "\nРаздел: " + clean(taskSection)
                + "\nПоследняя задача: #" + (taskId == null ? "-" : taskId)
                + countersText(counters)
                + "\n\nРекомендуется выборочно проверить фактическое выполнение закрытых задач.";
    }

    private String countersText(CompletionCounters counters) {
        return "\nЗакрыто за 10 минут: " + counters.tenMinutes()
                + "\nЗакрыто за час: " + counters.hour()
                + "\nЗакрыто сегодня: " + counters.day();
    }

    private boolean isPlainWorker(Authentication authentication) {
        return hasRole(authentication, "WORKER")
                && !hasRole(authentication, "ADMIN")
                && !hasRole(authentication, "OWNER")
                && !hasRole(authentication, "MANAGER");
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private String workerName(User user) {
        String fio = clean(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        return clean(user == null ? null : user.getUsername());
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private record CompletionCounters(long tenMinutes, long hour, long day) {

        boolean suspicious() {
            return tenMinutes >= TEN_MINUTE_LIMIT
                    || hour >= HOUR_LIMIT
                    || day >= DAY_LIMIT;
        }
    }
}
