package com.hunt.otziv.personal_reminders.repository;

import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface PersonalReminderRepository extends JpaRepository<PersonalReminder, Long> {
    boolean existsBySourceTypeAndSourceIdAndCompletedAtIsNull(String sourceType, Long sourceId);

    List<PersonalReminder> findByUserIdAndCompletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PersonalReminder> findByUserIdAndSourceTypeAndSourceIdAndCompletedAtIsNullOrderByIdAsc(
            Long userId,
            String sourceType,
            Long sourceId
    );

    Optional<PersonalReminder> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndSourceTypeAndSourceIdAndCompletedAtIsNull(Long userId, String sourceType, Long sourceId);

    void deleteByUserIdAndTitleAndTextAndCompletedAtIsNull(Long userId, String title, String text);

    void deleteByUserIdAndTitleStartingWithAndTextContainingAndCompletedAtIsNull(
            Long userId,
            String titlePrefix,
            String textFragment
    );

    void deleteByUserIdAndSourceTypeAndSourceIdAndCompletedAtIsNull(Long userId, String sourceType, Long sourceId);

    void deleteBySourceTypeAndSourceIdAndCompletedAtIsNull(String sourceType, Long sourceId);
}
