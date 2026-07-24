package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ClientChatMessageTrackerServiceTest {

    @Mock private ClientChatMessageRepository messageRepository;
    @Mock private ClientChatUnansweredItemRepository unansweredRepository;
    @Mock private ClientChatParticipantClassifier participantClassifier;
    @Mock private ClientChatAutoIgnoreService autoIgnoreService;
    @Mock private ClientChatCompanyResolutionService companyResolutionService;
    @Mock private AppSettingService appSettingService;
    @Mock private GamificationEventService gamificationEventService;

    private ClientChatMessageTrackerService service;

    @BeforeEach
    void setUp() {
        service = new ClientChatMessageTrackerService(
                messageRepository,
                unansweredRepository,
                participantClassifier,
                autoIgnoreService,
                companyResolutionService,
                appSettingService,
                gamificationEventService
        );
        when(appSettingService.getBoolean("manager-control.unanswered-client-messages.enabled", true)).thenReturn(true);
        when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(any(), any(), any()))
                .thenReturn(Optional.empty());
        Company company = new Company();
        company.setTitle("Компания");
        Manager manager = new Manager();
        when(companyResolutionService.resolve(ClientChatPlatform.WHATSAPP, "12001@g.us"))
                .thenReturn(new ClientChatCompanyResolutionService.Resolution(company, manager, List.of(company), false));
        when(messageRepository.save(any(ClientChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void explicitStaffReplyClosesOpenUnansweredItemEvenWhenDirectionIsOutgoing() {
        ClientChatUnansweredItem open = new ClientChatUnansweredItem();
        open.setStatus(ClientChatUnansweredStatus.OPEN);
        open.setFirstOpenedAt(LocalDateTime.now().minusHours(1));
        when(unansweredRepository.findByPlatformAndChatIdAndStatus(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                ClientChatUnansweredStatus.OPEN
        )).thenReturn(List.of(open));

        service.track(command("manual-1"), ClientChatSenderRole.STAFF);

        assertEquals(ClientChatUnansweredStatus.ANSWERED, open.getStatus());
        assertEquals("Ответ сотрудника", open.getCloseReason());
        verify(unansweredRepository).save(open);
    }

    @Test
    void explicitBotMessageDoesNotCloseOpenUnansweredItems() {
        service.track(command("bot-1"), ClientChatSenderRole.BOT);

        verify(unansweredRepository, never()).findByPlatformAndChatIdAndStatus(any(), any(), any());
    }

    @Test
    void generatesStableFingerprintWhenProviderMessageIdIsMissing() {
        ClientChatMessageCommand command = command(null);

        service.track(command, ClientChatSenderRole.BOT);

        ArgumentCaptor<ClientChatMessage> captor = ArgumentCaptor.forClass(ClientChatMessage.class);
        verify(messageRepository).save(captor.capture());
        String generatedId = captor.getValue().getExternalMessageId();
        assertEquals(67, generatedId.length());
        assertEquals("fp:", generatedId.substring(0, 3));
    }

    @Test
    void tracksMessageWithoutTextWhenProviderIdExists() {
        ClientChatMessageCommand source = command("media-1");
        ClientChatMessageCommand media = new ClientChatMessageCommand(
                source.platform(), source.direction(), source.chatId(), source.chatTitle(), source.externalMessageId(),
                source.senderExternalId(), source.senderName(), "", source.messageAt());

        service.track(media, ClientChatSenderRole.BOT);

        ArgumentCaptor<ClientChatMessage> captor = ArgumentCaptor.forClass(ClientChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("[Нетекстовое сообщение]", captor.getValue().getMessageText());
    }

    private static ClientChatMessageCommand command(String messageId) {
        return new ClientChatMessageCommand(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.OUTGOING,
                "12001@g.us",
                "Группа",
                messageId,
                "12001@g.us",
                "Менеджер",
                "Ответ менеджера",
                LocalDateTime.now()
        );
    }
}
