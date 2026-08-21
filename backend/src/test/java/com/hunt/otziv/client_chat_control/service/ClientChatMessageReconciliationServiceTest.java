package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.dto.ClientChatReconciliationResult;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.dto.WhatsAppChatMessageCursor;
import com.hunt.otziv.whatsapp.dto.WhatsAppReconciledMessage;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientChatMessageReconciliationServiceTest {

    @Mock
    private ClientChatUnansweredItemRepository unansweredRepository;
    @Mock
    private ClientChatMessageRepository messageRepository;
    @Mock
    private ClientChatMessageTrackerService trackerService;
    @Mock
    private WhatsAppService whatsAppService;

    @InjectMocks
    private ClientChatMessageReconciliationService service;

    @Test
    void tracksMessagesFoundAfterTheOpenCardAndReportsClosedItems() {
        Manager manager = new Manager();
        manager.setId(3L);
        manager.setClientId("whatsapp_vika");

        ClientChatUnansweredItem open = new ClientChatUnansweredItem();
        open.setChatId("120363000000000000@g.us");
        open.setLastClientMessageAt(LocalDateTime.of(2026, 7, 27, 14, 0));
        when(unansweredRepository.findByManagerAndPlatformAndStatus(
                manager,
                ClientChatPlatform.WHATSAPP,
                ClientChatUnansweredStatus.OPEN
        )).thenReturn(List.of(open), List.of());
        when(whatsAppService.reconcileGroupMessages(eq("whatsapp_vika"), anyList()))
                .thenReturn(List.of(new WhatsAppReconciledMessage(
                        "whatsapp_vika",
                        "120363000000000000@g.us",
                        "Клиент",
                        "70000000000@lid",
                        "Мария",
                        "message-42",
                        1_785_136_200L,
                        false,
                        false,
                        "Ответ сотрудника"
                )));

        ClientChatReconciliationResult result = service.reconcileOpenWhatsAppMessages(manager);

        ArgumentCaptor<ClientChatMessageCommand> command = ArgumentCaptor.forClass(ClientChatMessageCommand.class);
        verify(trackerService).track(command.capture(), isNull());
        assertEquals("message-42", command.getValue().externalMessageId());
        assertEquals("Ответ сотрудника", command.getValue().messageText());
        assertEquals(1, result.requestedChats());
        assertEquals(1, result.receivedMessages());
        assertEquals(1, result.closedItems());

        ArgumentCaptor<List<WhatsAppChatMessageCursor>> cursors = ArgumentCaptor.forClass(List.class);
        verify(whatsAppService).reconcileGroupMessages(eq("whatsapp_vika"), cursors.capture());
        assertEquals("120363000000000000@g.us", cursors.getValue().getFirst().groupId());
        assertEquals(
                open.getLastClientMessageAt().minusSeconds(1).atZone(ZoneId.systemDefault()).toEpochSecond(),
                cursors.getValue().getFirst().afterTimestamp()
        );
    }

    @Test
    void restoresMissedOutgoingManualReplyAsStaffEvidence() {
        Manager manager = new Manager();
        manager.setId(3L);
        manager.setClientId("whatsapp_vika");
        ClientChatUnansweredItem open = new ClientChatUnansweredItem();
        open.setChatId("120363000000000000@g.us");
        open.setLastClientMessageAt(LocalDateTime.of(2026, 7, 27, 14, 0));
        when(unansweredRepository.findByManagerAndPlatformAndStatus(
                manager,
                ClientChatPlatform.WHATSAPP,
                ClientChatUnansweredStatus.OPEN
        )).thenReturn(List.of(open), List.of());
        when(whatsAppService.reconcileGroupMessages(eq("whatsapp_vika"), anyList()))
                .thenReturn(List.of(new WhatsAppReconciledMessage(
                        "whatsapp_vika",
                        "120363000000000000@g.us",
                        "Клиент",
                        "79990000000@c.us",
                        "",
                        "manual-reply-1",
                        1_785_136_200L,
                        true,
                        false,
                        "Ответ менеджера"
                )));

        service.reconcileOpenWhatsAppMessages(manager);

        ArgumentCaptor<ClientChatMessageCommand> command = ArgumentCaptor.forClass(ClientChatMessageCommand.class);
        verify(trackerService).track(command.capture(), eq(ClientChatSenderRole.STAFF));
        assertEquals(ClientChatDirection.OUTGOING, command.getValue().direction());
        assertEquals("manual-reply-1", command.getValue().externalMessageId());
    }

    @Test
    void keepsReconciledGatewayMessageClassifiedAsBot() {
        Manager manager = new Manager();
        manager.setId(3L);
        manager.setClientId("whatsapp_vika");
        ClientChatUnansweredItem open = new ClientChatUnansweredItem();
        open.setChatId("120363000000000000@g.us");
        open.setLastClientMessageAt(LocalDateTime.of(2026, 7, 27, 14, 0));
        when(unansweredRepository.findByManagerAndPlatformAndStatus(
                manager,
                ClientChatPlatform.WHATSAPP,
                ClientChatUnansweredStatus.OPEN
        )).thenReturn(List.of(open), List.of(open));
        when(whatsAppService.reconcileGroupMessages(eq("whatsapp_vika"), anyList()))
                .thenReturn(List.of(new WhatsAppReconciledMessage(
                        "whatsapp_vika",
                        "120363000000000000@g.us",
                        "Клиент",
                        "79990000000@c.us",
                        "",
                        "bot-message-1",
                        1_785_136_200L,
                        true,
                        true,
                        "Опубликован новый отзыв"
                )));

        service.reconcileOpenWhatsAppMessages(manager);

        verify(trackerService).track(
                org.mockito.ArgumentMatchers.any(ClientChatMessageCommand.class),
                eq(ClientChatSenderRole.BOT)
        );
    }
    @Test
    void verifiesOutgoingPaymentLinkAfterReconcilingSpecificWhatsAppGroup() {
        Manager manager = new Manager();
        manager.setId(3L);
        manager.setClientId("whatsapp_vika");
        String groupId = "120363000000000000@g.us";
        LocalDateTime preparedAt = LocalDateTime.of(2026, 8, 16, 14, 20);
        String messageText = "Здравствуйте. Ссылка на оплату: https://o-ogo.ru/pay/JrSKZEp7DdcPEwatdp7RQp8vk5Jemu6J";
        when(whatsAppService.reconcileGroupMessages(eq("whatsapp_vika"), anyList()))
                .thenReturn(List.of(new WhatsAppReconciledMessage(
                        "whatsapp_vika",
                        groupId,
                        "Клиент",
                        "79990000000@c.us",
                        "",
                        "bot-payment-1",
                        preparedAt.plusMinutes(1).atZone(ZoneId.systemDefault()).toEpochSecond(),
                        true,
                        true,
                        messageText
                )));
        ClientChatMessage stored = new ClientChatMessage();
        stored.setMessageText(messageText);
        when(messageRepository.findByPlatformAndChatIdAndDirectionAndMessageAtBetweenOrderByMessageAtAscIdAsc(
                eq(ClientChatPlatform.WHATSAPP),
                eq(groupId),
                eq(ClientChatDirection.OUTGOING),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(stored));

        boolean verified = service.reconcileWhatsAppGroupContainsOutgoingText(
                manager,
                groupId,
                preparedAt,
                "Повтор счета: https://o-ogo.ru/pay/JrSKZEp7DdcPEwatdp7RQp8vk5Jemu6J"
        );

        assertTrue(verified);
        verify(trackerService).track(
                org.mockito.ArgumentMatchers.any(ClientChatMessageCommand.class),
                eq(ClientChatSenderRole.BOT)
        );
    }
}
