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
    @Query(value = """
            SELECT r.personal_reminder_id
            FROM personal_reminders r
            JOIN orders o ON o.order_id = r.source_order_id
            JOIN order_statuses s ON s.order_status_id = o.order_status
            WHERE r.user_id = :userId
              AND r.completed_at IS NULL
              AND r.source_type = 'BAD_REVIEW_ORDER_READY'
              AND s.order_status_title IN ('Оплачено', 'Бан')
            """, nativeQuery = true)
    List<Long> findSettledBadReviewReminderIds(@Param("userId") Long userId);

    boolean existsBySourceTypeAndSourceId(String sourceType, Long sourceId);
    List<PersonalReminder> findBySourceTypeAndSourceOrderIdAndCompletedAtIsNull(String sourceType, Long sourceOrderId);

    @Query("SELECT r.id FROM PersonalReminder r WHERE r.sourceType = :sourceType "
            + "AND r.completedAt IS NULL AND r.id > :afterId ORDER BY r.id")
    List<Long> findOpenSourceIdsAfter(@Param("sourceType") String sourceType,
                                      @Param("afterId") long afterId, Pageable pageable);

    interface ReconciliationCandidate {
        Long getId();
        String getSourceType();
        Long getSourceOrderId();
    }

    @Query("SELECT r.id AS id, r.sourceType AS sourceType, r.sourceOrderId AS sourceOrderId "
            + "FROM PersonalReminder r WHERE r.id = :id AND r.completedAt IS NULL")
    Optional<ReconciliationCandidate> findOpenReconciliationCandidate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PersonalReminder r WHERE r.id = :id AND r.completedAt IS NULL")
    Optional<PersonalReminder> findOpenByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PersonalReminder> findBySourceTypeAndSourceIdAndSourceOrderIdAndCompletedAtIsNullOrderByIdAsc(
            String sourceType, Long sourceId, Long sourceOrderId
    );
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
