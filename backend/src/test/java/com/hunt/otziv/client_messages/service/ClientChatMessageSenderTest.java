package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientChatMessageSenderTest {

    @Mock private WhatsAppService whatsAppService;
    @Mock private TelegramService telegramService;
    @Mock private MaxBotClient maxBotClient;

    private ClientChatMessageSender sender;
    private TelegramTransferCopyButton copyButton;

    @BeforeEach
    void setUp() {
        sender = new ClientChatMessageSender(whatsAppService, telegramService, maxBotClient);
        copyButton = TelegramTransferCopyButton.fromFrozenTransferNumber("2202208238396676").orElseThrow();
    }

    @Test
    void telegramPaymentUsesNativeCopyTextButton() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageWithCopyTextButton(
                12345L, "Счет и номер 2202208238396676",
                "Скопировать номер карты", "2202208238396676"
        )).thenReturn(true);

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group",
                "Счет и номер 2202208238396676", copyButton
        );

        assertTrue(result.sent());
        verify(telegramService).sendMessageWithCopyTextButton(
                12345L, "Счет и номер 2202208238396676",
                "Скопировать номер карты", "2202208238396676"
        );
        verify(telegramService, never()).sendMessage(12345L, "Счет и номер 2202208238396676");
        verify(telegramService, never()).sendMessage(12345L, "2202208238396676");
    }

    @Test
    void whatsappSendsCopyValueAsSeparateMessageWhenCopyMetadataExists() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        String message = "Счет";
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", message))
                .thenReturn("ok");
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "2202208238396676"))
                .thenReturn("ok");

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", message, copyButton
        );

        assertTrue(result.sent());
        InOrder delivery = inOrder(whatsAppService);
        delivery.verify(whatsAppService).sendMessageToGroup("manager", "whatsapp-group", message);
        delivery.verify(whatsAppService).sendMessageToGroup(
                "manager", "whatsapp-group", "2202208238396676"
        );
        verify(telegramService, never()).sendMessageWithCopyTextButton(
                12345L, message, "Скопировать номер карты", "2202208238396676"
        );
    }

    @Test
    void maxSendsCopyValueAsSeparateMessageWhenCopyMetadataExists() {
        Company company = company("https://max.ru/example", null, 98765L);
        String message = "Счет";
        when(maxBotClient.sendMessageToChat(98765L, message)).thenReturn(true);
        when(maxBotClient.sendMessageToChat(98765L, "2202208238396676")).thenReturn(true);

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", message, copyButton
        );

        assertTrue(result.sent());
        InOrder delivery = inOrder(maxBotClient);
        delivery.verify(maxBotClient).sendMessageToChat(98765L, message);
        delivery.verify(maxBotClient).sendMessageToChat(98765L, "2202208238396676");
        verify(telegramService, never()).sendMessageWithCopyTextButton(
                98765L, message, "Скопировать номер карты", "2202208238396676"
        );
    }

    @Test
    void copyOnlyFailureDoesNotFailSuccessfulPrimaryMessage() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "Счет")).thenReturn("ok");
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "2202208238396676"))
                .thenReturn("error");

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", "Счет", copyButton
        );

        assertTrue(result.sent());
        verify(whatsAppService).sendMessageToGroup("manager", "whatsapp-group", "2202208238396676");
    }

    @Test
    void primaryFailureDoesNotSendCopyOnlyMessage() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "Счет")).thenReturn("error");

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", "Счет", copyButton
        );

        assertFalse(result.sent());
        verify(whatsAppService, never()).sendMessageToGroup(
                "manager", "whatsapp-group", "2202208238396676"
        );
    }

    private Company company(String urlChat, Long telegramChatId, Long maxChatId) {
        Company company = new Company();
        company.setId(1L);
        company.setTitle("Компания");
        company.setUrlChat(urlChat);
        company.setTelegramGroupChatId(telegramChatId);
        company.setMaxGroupChatId(maxChatId);
        return company;
    }
}
