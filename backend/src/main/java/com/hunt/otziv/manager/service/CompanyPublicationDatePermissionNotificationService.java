package com.hunt.otziv.manager.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.UserService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyPublicationDatePermissionNotificationService {

    private final UserService userService;
    private final TelegramService telegramService;
    private final ManagerPermissionService managerPermissionService;
    private final CompanyRepository companyRepository;

    public void notifyEnabledByManager(
            Long companyId,
            String companyTitle,
            Authentication authentication
    ) {
        if (!managerPermissionService.hasRole(authentication, "MANAGER")) {
            return;
        }

        try {
            notifyRecipients(companyId, companyTitle, authentication);
        } catch (RuntimeException exception) {
            log.warn(
                    "Не удалось подготовить уведомление о разрешении смены дат companyId={}",
                    companyId,
                    exception
            );
        }
    }

    private void notifyRecipients(Long companyId, String companyTitle, Authentication authentication) {
        String manager = actorLabel(authentication);
        String company = companyTitle == null || companyTitle.isBlank()
                ? "Компания #" + companyId
                : companyTitle.trim() + " (#" + companyId + ")";
        String text = "Менеджер включил разрешение специалистам менять даты публикации."
                + "\nКомпания: " + company
                + "\nСпециалист: " + workerLabel(companyId)
                + "\nМенеджер: " + manager
                + "\n\nРазрешение действует для отзывов во всех заказах этой компании.";

        recipients().values().forEach(user -> send(user, text));
    }

    private String workerLabel(Long companyId) {
        if (companyId == null) {
            return "не назначен";
        }
        return companyRepository.findByIdWithWorkers(companyId)
                .map(company -> company.getWorkers() == null
                        ? List.<Worker>of()
                        : company.getWorkers().stream().filter(worker -> worker != null).toList())
                .orElseGet(List::of)
                .stream()
                .map(Worker::getUser)
                .filter(user -> user != null && user.isActive())
                .sorted(Comparator.comparing(user -> userLabel(user).toLowerCase()))
                .map(this::userLabel)
                .filter(label -> !label.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse("не назначен");
    }

    private String userLabel(User user) {
        if (user == null) {
            return "";
        }
        String fio = user.getFio() == null ? "" : user.getFio().trim();
        String username = user.getUsername() == null ? "" : user.getUsername().trim();
        if (!fio.isBlank() && !username.isBlank()) {
            return fio + " (" + username + ")";
        }
        return !fio.isBlank() ? fio : username;
    }

    private String actorLabel(Authentication authentication) {
        String username = authentication == null ? "" : authentication.getName();
        return userService.findByUserName(username)
                .map(user -> user.getFio() == null || user.getFio().isBlank()
                        ? username
                        : user.getFio().trim() + " (" + username + ")")
                .orElse(username == null || username.isBlank() ? "неизвестный менеджер" : username);
    }

    private Map<Long, User> recipients() {
        Map<Long, User> result = new LinkedHashMap<>();
        addRecipients(result, userService.getAllOwners("ROLE_OWNER"));
        addRecipients(result, userService.getAllOwners("ROLE_ADMIN"));
        return result;
    }

    private void addRecipients(Map<Long, User> recipients, List<User> users) {
        if (users == null) {
            return;
        }
        users.stream()
                .filter(user -> user != null && user.getId() != null && user.isActive())
                .forEach(user -> recipients.putIfAbsent(user.getId(), user));
    }

    private void send(User recipient, String text) {
        if (recipient.getTelegramChatId() == null) {
            return;
        }
        try {
            telegramService.sendMessage(recipient.getTelegramChatId(), text);
        } catch (RuntimeException exception) {
            log.warn(
                    "Не удалось отправить уведомление о разрешении смены дат userId={}",
                    recipient.getId(),
                    exception
            );
        }
    }
}
