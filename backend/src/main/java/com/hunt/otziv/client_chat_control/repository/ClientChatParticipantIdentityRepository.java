package com.hunt.otziv.client_chat_control.repository;

import com.hunt.otziv.client_chat_control.model.ClientChatParticipantIdentity;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientChatParticipantIdentityRepository extends JpaRepository<ClientChatParticipantIdentity, Long> {

    Optional<ClientChatParticipantIdentity> findByPlatformAndChatIdAndIdentityKeyAndActiveTrue(
            ClientChatPlatform platform,
            String chatId,
            String identityKey
    );

    Optional<ClientChatParticipantIdentity>
    findFirstByPlatformAndIdentityKeyAndSenderRoleAndActiveTrueOrderByUpdatedAtDesc(
            ClientChatPlatform platform,
            String identityKey,
            ClientChatSenderRole senderRole
    );
}
