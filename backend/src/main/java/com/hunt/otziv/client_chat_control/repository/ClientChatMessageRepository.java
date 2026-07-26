package com.hunt.otziv.client_chat_control.repository;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientChatMessageRepository extends JpaRepository<ClientChatMessage, Long> {

    Optional<ClientChatMessage> findByPlatformAndChatIdAndExternalMessageId(
            ClientChatPlatform platform,
            String chatId,
            String externalMessageId
    );

    List<ClientChatMessage> findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
            Long managerId,
            LocalDateTime from,
            LocalDateTime to
    );

    @Query("""
        SELECT message
        FROM ClientChatMessage message
        WHERE message.actorUser.id = (
            SELECT managerUser.id
            FROM Manager manager
            JOIN manager.user managerUser
            WHERE manager.id = :managerId
        )
          AND message.messageAt >= :from
          AND message.messageAt < :to
        ORDER BY message.messageAt ASC, message.id ASC
    """)
    List<ClientChatMessage> findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
            @Param("managerId") Long managerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    List<ClientChatMessage> findByPlatformAndChatIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
            ClientChatPlatform platform,
            String chatId,
            LocalDateTime from,
            LocalDateTime to
    );

    Optional<ClientChatMessage> findFirstByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
            ClientChatPlatform platform,
            String chatId,
            ClientChatSenderRole senderRole,
            LocalDateTime messageAt
    );

    Optional<ClientChatMessage> findFirstByPlatformAndChatIdAndSenderRoleAndMessageAtBetweenOrderByMessageAtAscIdAsc(
            ClientChatPlatform platform,
            String chatId,
            ClientChatSenderRole senderRole,
            LocalDateTime from,
            LocalDateTime to
    );

    @Modifying
    @Query("""
        UPDATE ClientChatMessage message
        SET message.messageText = ''
        WHERE message.messageAt < :cutoff
          AND message.messageText IS NOT NULL
          AND message.messageText <> ''
    """)
    int anonymizeTextBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query(value = """
        DELETE FROM client_chat_messages
        WHERE id IN (
            SELECT candidate.id FROM (
                SELECT message.id
                FROM client_chat_messages message
                LEFT JOIN client_chat_unanswered_items unanswered
                  ON unanswered.last_client_message_id = message.id
                WHERE message.message_at < :cutoff
                  AND unanswered.id IS NULL
                ORDER BY message.id
                LIMIT 5000
            ) candidate
        )
    """, nativeQuery = true)
    int deleteUnreferencedBatchBefore(@Param("cutoff") LocalDateTime cutoff);
}
