package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import org.junit.jupiter.api.Test;

class ClientChatAutoIgnoreServiceTest {

    /** The owner keeps greetings in «Справочники → Фразы без ответа»; they are not in the default list. */
    private static final String PHRASES_WITH_GREETINGS = ClientChatAutoIgnoreService.DEFAULT_PHRASES
            + ",добрый день,здравствуйте,хорошего дня";

    @Test
    void reciprocalAcknowledgementsDoNotCreateUnansweredActions() {
        ClientChatAutoIgnoreService service = service(ClientChatAutoIgnoreService.DEFAULT_PHRASES, 60);

        for (String message : new String[]{
                "Вам спасибо",
                "И вам спасибо!",
                "Взаимно",
                "Хорошо спасибо большое"
        }) {
            assertTrue(service.shouldIgnore(message), message);
        }
        // A short message that starts with «да»/«нет» but carries content still needs an answer.
        for (String message : new String[]{"Нет счёта", "Да, не пришел", "нет денег", "нет отзывов"}) {
            assertFalse(service.shouldIgnore(message), message);
        }
        for (String message : new String[]{"Да", "Нет", "да спасибо", "нет, спасибо"}) {
            assertTrue(service.shouldIgnore(message), message);
        }
    }

    @Test
    void emojiAndRepeatedPunctuationAroundListedPhraseAreIgnored() {
        ClientChatAutoIgnoreService service = service(ClientChatAutoIgnoreService.DEFAULT_PHRASES, 60);

        for (String message : new String[]{
                "Спасибо 🙏",
                "Спасибо🙏🏻",
                "Спасибо!!! ❤️",
                "Благодарю 😊",
                "Хорошо, спасибо большое",
                "Ок 👍"
        }) {
            assertTrue(service.shouldIgnore(message), message);
        }
    }

    @Test
    void combinationOfListedPhrasesIsIgnored() {
        ClientChatAutoIgnoreService service = service(PHRASES_WITH_GREETINGS, 60);

        for (String message : new String[]{
                "Добрый день, спасибо!",
                "Спасибо, хорошего дня",
                "Добрый день! Спасибо большое 🙏",
                "Хорошо, спасибо, хорошего дня"
        }) {
            assertTrue(service.shouldIgnore(message), message);
        }
    }

    @Test
    void greetingOutsideTheListKeepsTheRemark() {
        ClientChatAutoIgnoreService service = service(ClientChatAutoIgnoreService.DEFAULT_PHRASES, 60);

        assertFalse(service.shouldIgnore("Добрый день, спасибо!"));
        assertFalse(service.shouldIgnore("Спасибо, хорошего дня"));
    }

    @Test
    void messagesThatNeedAnAnswerStayRemarks() {
        ClientChatAutoIgnoreService service = service(PHRASES_WITH_GREETINGS, 60);

        for (String message : new String[]{
                "[Вложение: image] Спасибо 🙏",
                "[вложение: document]",
                "[Вложение]",
                "Спасибо, а сколько стоит",
                "Спасибо, но отзывов нет",
                "Добрый день, нет",
                "Добрый день, да",
                "Здравствуйте ❓",
                "Спасибо⁉️",
                "Счет на оплату.pdf"
        }) {
            assertFalse(service.shouldIgnore(message), message);
        }
    }

    @Test
    void courtesyTextLongerThanMaxLengthStaysRemark() {
        String message = "Спасибо большое, хорошего дня 🙏";

        assertTrue(service(PHRASES_WITH_GREETINGS, 60).shouldIgnore(message));
        assertFalse(service(PHRASES_WITH_GREETINGS, 20).shouldIgnore(message));
    }

    @Test
    void combinationIsLimitedToTenWords() {
        ClientChatAutoIgnoreService service = service(ClientChatAutoIgnoreService.DEFAULT_PHRASES, 200);

        assertTrue(service.shouldIgnore("ок ок ок ок ок ок ок ок ок ок"));
        assertFalse(service.shouldIgnore("ок ок ок ок ок ок ок ок ок ок ок"));
    }

    @Test
    void blankMessagesStayIgnoredAndEmojiOnlyMessagesStayRemarks() {
        ClientChatAutoIgnoreService service = service(ClientChatAutoIgnoreService.DEFAULT_PHRASES, 60);

        for (String message : new String[]{null, "", "   ", "...", "!!!"}) {
            assertTrue(service.shouldIgnore(message), String.valueOf(message));
        }
        for (String message : new String[]{"🙏", "👍👍", "+", "❓"}) {
            assertFalse(service.shouldIgnore(message), message);
        }
    }

    @Test
    void emojiPutIntoTheListIsStillMatchedExactly() {
        ClientChatAutoIgnoreService service = service(ClientChatAutoIgnoreService.DEFAULT_PHRASES + ",👍", 60);

        assertTrue(service.shouldIgnore("👍"));
        assertTrue(service.shouldIgnore("👍!"));
        assertFalse(service.shouldIgnore("👍👍"));
    }

    @Test
    void disabledSettingKeepsEveryMessage() {
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_ENABLED,
                true
        )).thenReturn(false);

        assertFalse(new ClientChatAutoIgnoreService(settings).shouldIgnore("Спасибо 🙏"));
    }

    private static ClientChatAutoIgnoreService service(String phrases, int maxLength) {
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_ENABLED,
                true
        )).thenReturn(true);
        when(settings.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_MAX_LENGTH,
                60
        )).thenReturn(maxLength);
        when(settings.getStringAllowEmpty(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_PHRASES,
                ClientChatAutoIgnoreService.DEFAULT_PHRASES
        )).thenReturn(phrases);
        return new ClientChatAutoIgnoreService(settings);
    }
}
