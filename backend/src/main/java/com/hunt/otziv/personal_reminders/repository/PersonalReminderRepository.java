package com.hunt.otziv.personal_reminders.repository;

import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalReminderRepository extends JpaRepository<PersonalReminder, Long> {

    List<PersonalReminder> findByUserIdAndCompletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    Optional<PersonalReminder> findByIdAndUserId(Long id, Long userId);
}
