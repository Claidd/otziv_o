package com.hunt.otziv.personal_reminders.repository;

import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface PersonalReminderRepository extends JpaRepository<PersonalReminder, Long> {
    boolean existsBySourceTypeAndSourceId(String sourceType, Long sourceId);
    List<PersonalReminder> findBySourceTypeAndSourceOrderIdAndCompletedAtIsNull(String sourceType, Long sourceOrderId);

    @Query(value = """
        SELECT r.personal_reminder_id FROM personal_reminders r
        LEFT JOIN orders o ON o.order_id = r.source_order_id
        LEFT JOIN order_statuses s ON s.order_status_id = o.order_status
        LEFT JOIN scheduled_client_message_state state ON state.state_id = r.source_id
        WHERE r.source_type = :sourceType AND r.completed_at IS NULL
          AND (o.order_id IS NULL OR s.order_status_id IS NULL
            OR s.order_status_title NOT IN ('Выставлен счет', 'Напоминание')
            OR state.state_id IS NULL OR state.state_status <> 'ACTIVE')
        ORDER BY r.personal_reminder_id
    """, nativeQuery = true)
    List<Long> findPaymentAttentionIdsToClose(@Param("sourceType") String sourceType, Pageable pageable);
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
