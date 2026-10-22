package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerDailyControlConcreteItemRepository extends CrudRepository<ManagerDailyControlConcreteItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT item FROM ManagerDailyControlConcreteItem item WHERE item.id = :id")
    java.util.Optional<ManagerDailyControlConcreteItem> findByIdForUpdate(@Param("id") Long id);

    List<ManagerDailyControlConcreteItem> findByParentItem(ManagerDailyControlItem parentItem);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT item
            FROM ManagerDailyControlConcreteItem item
            WHERE item.parentItem = :parentItem
            """)
    List<ManagerDailyControlConcreteItem> findByParentItemForUpdate(
            @Param("parentItem") ManagerDailyControlItem parentItem
    );

    List<ManagerDailyControlConcreteItem> findByParentItemIn(Collection<ManagerDailyControlItem> parentItems);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT item FROM ManagerDailyControlConcreteItem item WHERE item.parentItem IN :parentItems ORDER BY item.id")
    List<ManagerDailyControlConcreteItem> findByParentItemInForUpdate(
            @Param("parentItems") Collection<ManagerDailyControlItem> parentItems);


    List<ManagerDailyControlConcreteItem> findByControl(ManagerDailyControl control);

    @Query("""
            SELECT item FROM ManagerDailyControlConcreteItem item
            WHERE item.control.manager.id = :managerId
              AND item.deferredAt IS NOT NULL
              AND item.deferredAt <= :cutoff
              AND item.deferredAt < :reportEnd
              AND item.status <> :resolved
              AND NOT EXISTS (
                  SELECT later.id FROM ManagerDailyControlConcreteItem later
                  WHERE later.control.manager.id = :managerId
                    AND later.entityKey = item.entityKey
                    AND later.status = :resolved
                    AND later.resolvedAt >= item.deferredAt
              )
            ORDER BY item.deferredAt ASC, item.id ASC
            """)
    List<ManagerDailyControlConcreteItem> findOverdueDeferredForAudit(
            @Param("managerId") Long managerId,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("reportEnd") LocalDateTime reportEnd,
            @Param("resolved") ManagerDailyControlItemStatus resolved
    );

    @Query("""
            SELECT CASE WHEN COUNT(later) > 0 THEN true ELSE false END
            FROM ManagerDailyControlConcreteItem later
            WHERE later.control.manager.id = :managerId
              AND later.entityKey = :entityKey
              AND later.status = :resolved
              AND later.resolvedAt >= :deferredAt
            """)
    boolean existsResolutionAfterDeferral(
            @Param("managerId") Long managerId,
            @Param("entityKey") String entityKey,
            @Param("deferredAt") LocalDateTime deferredAt,
            @Param("resolved") ManagerDailyControlItemStatus resolved
    );

    List<ManagerDailyControlConcreteItem> findByEntityTypeAndEntityId(String entityType, Long entityId);

    List<ManagerDailyControlConcreteItem> findByEntityTypeStartingWithAndEntityId(String entityType, Long entityId);

    List<ManagerDailyControlConcreteItem> findByEntityTypeAndEntityIdAndControl_ControlDate(
            String entityType,
            Long entityId,
            LocalDate controlDate
    );

    boolean existsByEntityTypeAndEntityIdAndControl_Manager_User_Username(
            String entityType,
            Long entityId,
            String username
    );

    List<ManagerDailyControlConcreteItem> findByControlAndEntityTypeAndFollowUpAtAfter(
            ManagerDailyControl control,
            String entityType,
            LocalDateTime followUpAt
    );

    List<ManagerDailyControlConcreteItem> findByControlAndFollowUpAtAfter(
            ManagerDailyControl control,
            LocalDateTime followUpAt
    );

    List<ManagerDailyControlConcreteItem> findByWorkerNotificationAcceptedByUserIdAndWorkerExplanationRequestedAtIsNotNullAndWorkerExplanationAtIsNullOrderByWorkerExplanationPromptedAtDesc(
            Long workerNotificationAcceptedByUserId
    );

    List<ManagerDailyControlConcreteItem> findByWorkerNotificationUserIdAndWorkerExplanationRequestedAtIsNotNullAndWorkerExplanationAtIsNullOrderByWorkerExplanationPromptedAtDesc(
            Long workerNotificationUserId
    );

    @Query("""
            SELECT item
            FROM ManagerDailyControlConcreteItem item
            WHERE item.workerExplanationRequestedAt IS NOT NULL
              AND item.workerNotificationSentAt IS NOT NULL
              AND item.workerNotificationSentAt <= :cutoff
              AND item.workerExplanationAt IS NULL
              AND item.status = :status
              AND (item.workerReminderSentAt IS NULL OR item.workerReminderSentAt <= :cutoff)
            ORDER BY item.workerNotificationSentAt ASC
            """)
    List<ManagerDailyControlConcreteItem> findPendingWorkerExplanationReminders(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("status") ManagerDailyControlItemStatus status,
            Pageable pageable
    );
}
