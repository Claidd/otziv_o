package com.hunt.otziv.client_chat_control.repository;

import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
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

@Repository
public interface ClientChatUnansweredItemRepository extends JpaRepository<ClientChatUnansweredItem, Long> {

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

    long countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
            Manager manager,
            ClientChatUnansweredStatus status,
            LocalDateTime cutoff
    );

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
}
