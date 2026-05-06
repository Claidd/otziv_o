package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.personal_reminders.dto.PersonalReminderRequest;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderResponse;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PersonalReminderService {

    private static final int DEFAULT_TIMER_MINUTES = 30;
    private static final int MAX_TIMER_MINUTES = 10_080;

    private final PersonalReminderRepository reminderRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<PersonalReminderResponse> list(Principal principal) {
        User user = currentUser(principal);

        return reminderRepository.findByUserIdAndCompletedAtIsNullOrderByUpdatedAtDesc(user.getId()).stream()
                .sorted(Comparator
                        .comparingInt(this::dueRank)
                        .thenComparing(reminder -> reminder.getRemindAt() == null ? Instant.MAX : reminder.getRemindAt())
                        .thenComparing(PersonalReminder::getUpdatedAt, Comparator.reverseOrder()))
                .map(PersonalReminderResponse::from)
                .toList();
    }

    @Transactional
    public PersonalReminderResponse create(Principal principal, PersonalReminderRequest request) {
        User user = currentUser(principal);
        PersonalReminder reminder = new PersonalReminder();
        reminder.setUser(user);
        applyRequest(reminder, request, Instant.now());

        return PersonalReminderResponse.from(reminderRepository.save(reminder));
    }

    @Transactional
    public PersonalReminderResponse update(Principal principal, Long reminderId, PersonalReminderRequest request) {
        User user = currentUser(principal);
        PersonalReminder reminder = findOwnedReminder(reminderId, user);
        applyRequest(reminder, request, Instant.now());

        return PersonalReminderResponse.from(reminderRepository.save(reminder));
    }

    @Transactional
    public PersonalReminderResponse complete(Principal principal, Long reminderId) {
        User user = currentUser(principal);
        PersonalReminder reminder = findOwnedReminder(reminderId, user);
        reminder.setCompletedAt(Instant.now());

        return PersonalReminderResponse.from(reminderRepository.save(reminder));
    }

    @Transactional
    public void delete(Principal principal, Long reminderId) {
        User user = currentUser(principal);
        PersonalReminder reminder = findOwnedReminder(reminderId, user);
        reminderRepository.delete(reminder);
    }

    private void applyRequest(PersonalReminder reminder, PersonalReminderRequest request, Instant now) {
        String mode = normalizeMode(request.reminderMode());
        Instant remindAt = null;
        Integer timerMinutes = null;

        if ("datetime".equals(mode)) {
            remindAt = request.remindAt();
            if (remindAt == null) {
                mode = "none";
            }
        }

        if ("timer".equals(mode)) {
            timerMinutes = normalizeTimerMinutes(request.timerMinutes());
            remindAt = now.plus(timerMinutes, ChronoUnit.MINUTES);
        }

        reminder.setTitle(trimOrDefault(request.title(), "Заметка"));
        reminder.setText(trimOrDefault(request.text(), ""));
        reminder.setReminderMode(mode);
        reminder.setRemindAt(remindAt);
        reminder.setTimerMinutes(timerMinutes);
    }

    private PersonalReminder findOwnedReminder(Long reminderId, User user) {
        return reminderRepository.findByIdAndUserId(reminderId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заметка не найдена"));
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }

        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "none" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "datetime", "timer" -> normalized;
            default -> "none";
        };
    }

    private Integer normalizeTimerMinutes(Integer value) {
        if (value == null || value < 1) {
            return DEFAULT_TIMER_MINUTES;
        }

        return Math.min(value, MAX_TIMER_MINUTES);
    }

    private String trimOrDefault(String value, String fallback) {
        if (value == null || value.trim().isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    private int dueRank(PersonalReminder reminder) {
        Instant remindAt = reminder.getRemindAt();
        if (remindAt == null) {
            return 2;
        }

        return remindAt.isAfter(Instant.now()) ? 1 : 0;
    }
}
