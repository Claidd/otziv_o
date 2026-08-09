package com.hunt.otziv.client_chat_control.repository;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatResolutionType;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface ClientChatUnansweredItemRepository extends JpaRepository<ClientChatUnansweredItem, Long> {

    List<ClientChatUnansweredItem> findByLastClientMessage(ClientChatMessage lastClientMessage);

    Optional<ClientChatUnansweredItem> findFirstByPlatformAndChatIdAndStatusOrderByLastClientMessageAtDesc(
            ClientChatPlatform platform,
            String chatId,
            ClientChatUnansweredStatus status
    );

    List<ClientChatUnansweredItem> findByPlatformAndChatIdAndStatus(
            ClientChatPlatform platform,
            String chatId,
            ClientChatUnansweredStatus status
    );

    List<ClientChatUnansweredItem> findByManagerAndPlatformAndStatus(
            Manager manager,
            ClientChatPlatform platform,
            ClientChatUnansweredStatus status
    );

    List<ClientChatUnansweredItem> findByPlatformAndChatIdAndAuditRequiredTrue(
            ClientChatPlatform platform,
            String chatId
    );

    long countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
            Manager manager,
            ClientChatUnansweredStatus status,
            LocalDateTime cutoff
    );

    long countByManagerAndResolvedByUserIdAndClosedAtAfter(
            Manager manager,
            Long resolvedByUserId,
            LocalDateTime cutoff
    );

    long countByManagerAndAuditRequiredTrue(Manager manager);

    @Query("""
        SELECT item
        FROM ClientChatUnansweredItem item
        LEFT JOIN FETCH item.company
        WHERE item.manager = :manager
          AND item.status = :status
          AND item.lastClientMessageAt <= :cutoff
        ORDER BY item.lastClientMessageAt ASC, item.id ASC
    """)
    List<ClientChatUnansweredItem> findDueByManager(
            @Param("manager") Manager manager,
            @Param("status") ClientChatUnansweredStatus status,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
        SELECT item
        FROM ClientChatUnansweredItem item
        LEFT JOIN FETCH item.company
        WHERE item.manager = :manager
          AND item.auditRequired = true
        ORDER BY item.closedAt DESC, item.id DESC
    """)
    List<ClientChatUnansweredItem> findAuditRequiredByManager(
            @Param("manager") Manager manager,
            Pageable pageable
    );

    @Query("""
        SELECT item
        FROM ClientChatUnansweredItem item
        WHERE item.manager IN :managers
          AND (
                item.createdAt BETWEEN :from AND :to
                OR item.closedAt BETWEEN :from AND :to
                OR (item.status = :openStatus AND item.lastClientMessageAt <= :to)
          )
    """)
    List<ClientChatUnansweredItem> findPerformanceItems(
            @Param("managers") Collection<Manager> managers,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("openStatus") ClientChatUnansweredStatus openStatus
    );

    @Query("""
        SELECT DISTINCT item
        FROM ClientChatUnansweredItem item
        LEFT JOIN FETCH item.company
        LEFT JOIN FETCH item.resolutionMessage
        WHERE item.manager.id = :managerId
          AND (
                item.createdAt BETWEEN :from AND :to
                OR item.closedAt BETWEEN :from AND :to
                OR (item.status = :openStatus AND item.lastClientMessageAt <= :to)
          )
        ORDER BY item.lastClientMessageAt DESC, item.id DESC
    """)
    List<ClientChatUnansweredItem> findDailyReportItems(
            @Param("managerId") Long managerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("openStatus") ClientChatUnansweredStatus openStatus
    );

    @Query("""
        SELECT DISTINCT item
        FROM ClientChatUnansweredItem item
        JOIN item.manager manager
        JOIN manager.user managerUser
        LEFT JOIN FETCH item.company
        LEFT JOIN FETCH item.resolutionMessage
        WHERE manager.id = :managerId
          AND item.resolvedByUserId = managerUser.id
          AND item.closedAt >= :from
          AND item.closedAt < :to
          AND item.resolutionType IN :resolutionTypes
        ORDER BY item.closedAt ASC, item.id ASC
    """)
    List<ClientChatUnansweredItem> findManagerResolvedForDailyAudit(
            @Param("managerId") Long managerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("resolutionTypes") Collection<ClientChatResolutionType> resolutionTypes
    );

    @Modifying
    long deleteByStatusNotAndClosedAtBefore(ClientChatUnansweredStatus openStatus, LocalDateTime cutoff);
}
