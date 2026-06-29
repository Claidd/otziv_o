package com.hunt.otziv.client_chat_control.repository;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientChatMessageRepository extends JpaRepository<ClientChatMessage, Long> {

    Optional<ClientChatMessage> findByPlatformAndChatIdAndExternalMessageId(
            ClientChatPlatform platform,
            String chatId,
            String externalMessageId
    );
}
