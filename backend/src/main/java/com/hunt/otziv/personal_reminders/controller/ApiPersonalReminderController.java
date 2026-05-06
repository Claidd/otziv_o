package com.hunt.otziv.personal_reminders.controller;

import com.hunt.otziv.personal_reminders.dto.PersonalReminderRequest;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderResponse;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/personal-reminders")
@PreAuthorize("isAuthenticated()")
public class ApiPersonalReminderController {

    private final PersonalReminderService reminderService;

    @GetMapping
    public List<PersonalReminderResponse> list(Principal principal) {
        return reminderService.list(principal);
    }

    @PostMapping
    public PersonalReminderResponse create(
            Principal principal,
            @Valid @RequestBody PersonalReminderRequest request
    ) {
        return reminderService.create(principal, request);
    }

    @PutMapping("/{reminderId}")
    public PersonalReminderResponse update(
            Principal principal,
            @PathVariable Long reminderId,
            @Valid @RequestBody PersonalReminderRequest request
    ) {
        return reminderService.update(principal, reminderId, request);
    }

    @PostMapping("/{reminderId}/complete")
    public PersonalReminderResponse complete(
            Principal principal,
            @PathVariable Long reminderId
    ) {
        return reminderService.complete(principal, reminderId);
    }

    @DeleteMapping("/{reminderId}")
    public void delete(
            Principal principal,
            @PathVariable Long reminderId
    ) {
        reminderService.delete(principal, reminderId);
    }
}
