package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_chat_control.model.ClientChatParticipantIdentity;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.repository.ClientChatParticipantIdentityRepository;
import com.hunt.otziv.u_users.model.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClientChatIdentityServiceTest {

    @Mock
    private ClientChatParticipantIdentityRepository repository;

    private ClientChatIdentityService service;

    @BeforeEach
    void setUp() {
        service = new ClientChatIdentityService(repository);
    }

    @Test
    void confirmedStaffExternalIdAppliesToAnotherChatOnSamePlatform() {
        String identityKey = "id:240161736638694@lid";
        when(repository.findByPlatformAndChatIdAndIdentityKeyAndActiveTrue(
                ClientChatPlatform.WHATSAPP,
                "new-group",
                identityKey
        )).thenReturn(Optional.empty());
        ClientChatParticipantIdentity staffIdentity = new ClientChatParticipantIdentity();
        staffIdentity.setSenderRole(ClientChatSenderRole.STAFF);
        when(repository.findFirstByPlatformAndIdentityKeyAndSenderRoleAndActiveTrueOrderByUpdatedAtDesc(
                ClientChatPlatform.WHATSAPP,
                identityKey,
                ClientChatSenderRole.STAFF
        )).thenReturn(Optional.of(staffIdentity));

        Optional<ClientChatSenderRole> role = service.knownRole(
                ClientChatPlatform.WHATSAPP,
                "new-group",
                "240161736638694@lid",
                "Мия О!"
        );

        assertEquals(Optional.of(ClientChatSenderRole.STAFF), role);
    }

    @Test
    void confirmedStaffIdentityReturnsLinkedActualUser() {
        String identityKey = "id:manager-telegram-id";
        User managerUser = User.builder().id(77L).active(true).build();
        ClientChatParticipantIdentity staffIdentity = new ClientChatParticipantIdentity();
        staffIdentity.setSenderRole(ClientChatSenderRole.STAFF);
        staffIdentity.setLinkedUser(managerUser);
        when(repository.findByPlatformAndChatIdAndIdentityKeyAndActiveTrue(
                ClientChatPlatform.TELEGRAM,
                "worker-group",
                identityKey
        )).thenReturn(Optional.of(staffIdentity));

        Optional<User> user = service.knownUser(
                ClientChatPlatform.TELEGRAM,
                "worker-group",
                "manager-telegram-id",
                "Менеджер"
        );

        assertEquals(Optional.of(managerUser), user);
    }

    @Test
    void clientIdentityDoesNotPropagateBetweenChats() {
        String identityKey = "id:client-1";
        when(repository.findByPlatformAndChatIdAndIdentityKeyAndActiveTrue(
                ClientChatPlatform.MAX,
                "another-chat",
                identityKey
        )).thenReturn(Optional.empty());
        when(repository.findFirstByPlatformAndIdentityKeyAndSenderRoleAndActiveTrueOrderByUpdatedAtDesc(
                ClientChatPlatform.MAX,
                identityKey,
                ClientChatSenderRole.STAFF
        )).thenReturn(Optional.empty());

        Optional<ClientChatSenderRole> role = service.knownRole(
                ClientChatPlatform.MAX,
                "another-chat",
                "client-1",
                "Клиент"
        );

        assertTrue(role.isEmpty());
        verify(repository).findFirstByPlatformAndIdentityKeyAndSenderRoleAndActiveTrueOrderByUpdatedAtDesc(
                ClientChatPlatform.MAX,
                identityKey,
                ClientChatSenderRole.STAFF
        );
    }
}
