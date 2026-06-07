package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.u_users.services.service.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramServiceUpdateHandlingTest {

    @Mock
    private ObjectProvider<PersonalService> personalServiceProvider;

    @Mock
    private UserService userService;

    @Mock
    private TelegramGroupLinkService telegramGroupLinkService;

    @Mock
    private PublicationProgressPreferenceService publicationProgressPreferenceService;

    @Test
    void groupTextMessageDoesNotStartUserLoginFlow() {
        CapturingTelegramService service = service();
        when(telegramGroupLinkService.handleGroupStartCommand(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        service.onUpdateReceived(textUpdate(-100123L, "supergroup", "Здравствуйте, хорошо Спасибо"));

        verify(userService, never()).findByChatId(anyLong());
        verify(userService, never()).findByUserName(anyString());
        assertTrue(service.sentMessages.isEmpty());
    }

    @Test
    void privateTextMessageCanStillStartUserLoginFlow() {
        CapturingTelegramService service = service();
        when(telegramGroupLinkService.handleGroupStartCommand(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(userService.findByChatId(123L)).thenReturn(Optional.empty());
        when(userService.findByUserName("unknown")).thenReturn(Optional.empty());

        service.onUpdateReceived(textUpdate(123L, "private", "unknown"));

        verify(userService).findByChatId(123L);
        verify(userService).findByUserName("unknown");
        assertEquals("Пользователь с таким username не найден. Введите свой логин:",
                service.sentMessages.getFirst().getText());
    }

    @Test
    void groupChatIdCommandStillResponds() {
        CapturingTelegramService service = service();
        when(telegramGroupLinkService.handleGroupStartCommand(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        service.onUpdateReceived(textUpdate(-100123L, "supergroup", "/chatid"));

        verify(userService, never()).findByChatId(anyLong());
        verify(userService, never()).findByUserName(anyString());
        assertEquals("chatId: `-100123`", service.sentMessages.getFirst().getText());
    }

    @Test
    void publicationProgressMessageIncludesInlineDisableButton() {
        CapturingTelegramService service = service();
        when(publicationProgressPreferenceService.appendTelegramOptOutHint("Отчет"))
                .thenReturn("Отчет\n\nНе хотите получать сообщение о каждом опубликованном отзыве?\nНажмите кнопку ниже.");
        when(publicationProgressPreferenceService.disableCallbackData(10L))
                .thenReturn("publication_progress:disable:10");

        boolean result = service.sendPublicationProgressMessage(-100123L, "Отчет", 10L);

        assertTrue(result);
        SendMessage sent = service.sentMessages.getFirst();
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertEquals("Отчет\n\nНе хотите получать сообщение о каждом опубликованном отзыве?\nНажмите кнопку ниже.", sent.getText());
        assertEquals("Отключить уведомления",
                markup.getKeyboard().getFirst().getFirst().getText());
        assertEquals("publication_progress:disable:10",
                markup.getKeyboard().getFirst().getFirst().getCallbackData());
        verifyNoInteractions(userService);
    }

    private CapturingTelegramService service() {
        return new CapturingTelegramService(
                personalServiceProvider,
                userService,
                telegramGroupLinkService,
                publicationProgressPreferenceService
        );
    }

    private static Update textUpdate(long chatId, String chatType, String text) {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType(chatType);

        Message message = new Message();
        message.setChat(chat);
        message.setText(text);

        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static final class CapturingTelegramService extends TelegramService {
        private final List<SendMessage> sentMessages = new ArrayList<>();

        private CapturingTelegramService(
                ObjectProvider<PersonalService> personalServiceProvider,
                UserService userService,
                TelegramGroupLinkService telegramGroupLinkService,
                PublicationProgressPreferenceService publicationProgressPreferenceService
        ) {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    true,
                    "",
                    personalServiceProvider,
                    userService,
                    telegramGroupLinkService,
                    publicationProgressPreferenceService);
        }

        @Override
        void executeTelegramMessage(SendMessage message) throws TelegramApiException {
            sentMessages.add(message);
        }
    }
}
