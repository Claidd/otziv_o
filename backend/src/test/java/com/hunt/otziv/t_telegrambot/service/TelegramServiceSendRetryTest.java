package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.t_telegrambot.dto.TelegramChatMigrationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramServiceSendRetryTest {

    @Test
    void copyTextCompatibleResponseRejectsMalformedSuccessWithoutMessageId() {
        TelegramService.CopyTextCompatibleSendMessage message =
                new TelegramService.CopyTextCompatibleSendMessage();

        assertThrows(
                TelegramApiRequestException.class,
                () -> message.deserializeResponse("{\"ok\":true,\"result\":{}}")
        );
    }

    @Test
    void sendMessageRetriesTransientNetworkFailure() {
        RetryableTelegramService service = new RetryableTelegramService(2);

        boolean sent = service.sendMessage(794146111L, "Отчёт", "HTML");

        assertTrue(sent);
        assertEquals(3, service.attempts);
        assertEquals(2, service.sleeps);
    }

    @Test
    void sendMessageStopsAfterRetryLimit() {
        RetryableTelegramService service = new RetryableTelegramService(3);

        boolean sent = service.sendMessage(794146111L, "Отчёт", "HTML");

        assertFalse(sent);
        assertEquals(3, service.attempts);
        assertEquals(2, service.sleeps);
    }

    @Test
    void copyTextButtonSerializesNativePayloadAndAcceptsEchoedUnknownField() throws Exception {
        CapturingCopyTelegramService service = new CapturingCopyTelegramService();

        boolean sent = service.sendMessageWithCopyTextButton(
                -100123L,
                "Оплата по номеру карты: 2202208238396676",
                "Скопировать номер карты",
                "2202208238396676"
        );

        assertTrue(sent);
        String json = new ObjectMapper().writeValueAsString(service.message);
        assertTrue(json.contains("\"copy_text\":{\"text\":\"2202208238396676\"}"));
        assertEquals(91, service.returnedMessageId);
    }

    @Test
    void copyTextButtonPreservesMigrationErrorAndRetriesNewChat() {
        CapturingMigrationService migrationService = new CapturingMigrationService();
        MigratedCopyTelegramService service = new MigratedCopyTelegramService(migrationService);

        boolean sent = service.sendMessageWithCopyTextButton(
                -5209142005L,
                "Оплата",
                "Скопировать номер телефона",
                "+79991234567"
        );

        assertTrue(sent);
        assertEquals(2, service.attempts);
        assertEquals("-5209142005", service.chatIds[0]);
        assertEquals("-1003538237871", service.chatIds[1]);
    }

    @Test
    void sendMessageRepairsMigratedGroupAndRetriesWithNewChatId() {
        CapturingMigrationService migrationService = new CapturingMigrationService();
        MigratedTelegramService service = new MigratedTelegramService(migrationService);

        boolean sent = service.sendMessage(-5209142005L, "Оплата получена");

        assertTrue(sent);
        assertEquals(2, service.attempts);
        assertEquals("-5209142005", service.chatIds[0]);
        assertEquals("-1003538237871", service.chatIds[1]);
        assertEquals(-5209142005L, migrationService.oldChatId);
        assertEquals(-1003538237871L, migrationService.newChatId);
    }

    @Test
    void protectedAuditMessageSetsTelegramContentProtectionFlag() {
        ProtectedTelegramService service = new ProtectedTelegramService();

        service.sendProtectedMessageWithInlineKeyboardMessageId(
                794146111L,
                "Защищённый аудит",
                "HTML",
                List.of()
        );

        assertTrue(service.protectContent);
    }

    @Test
    void photoNotificationKeepsCaptionParseModeAndKeyboard() {
        CapturingPhotoTelegramService service = new CapturingPhotoTelegramService();

        boolean sent = service.sendPhotoWithInlineKeyboard(
                -100123L,
                "https://cdn.example/card.png",
                "<b>Напоминание</b>",
                "HTML",
                List.of(List.of(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton(
                        "Открыть"
                )))
        );

        assertTrue(sent);
        assertEquals("-100123", service.photo.getChatId());
        assertEquals("<b>Напоминание</b>", service.photo.getCaption());
        assertEquals("HTML", service.photo.getParseMode());
        assertTrue(service.photo.getReplyMarkup() != null);
    }

    @Test
    void photoBytesAreUploadedAsMultipartFile() throws Exception {
        CapturingPhotoTelegramService service = new CapturingPhotoTelegramService();
        byte[] image = new byte[]{1, 2, 3, 4};

        boolean sent = service.sendPhotoBytesWithInlineKeyboard(
                -100123L,
                image,
                "card.jpg",
                "<b>Напоминание</b>",
                "HTML",
                List.of()
        );

        assertTrue(sent);
        assertTrue(service.photo.getPhoto().isNew());
        assertEquals("card.jpg", service.photo.getPhoto().getMediaName());
        assertArrayEquals(image, service.photo.getPhoto().getNewMediaStream().readAllBytes());
    }

    @Test
    void editMessageWithEmptyKeyboardExplicitlyRemovesInlineButtons() {
        CapturingEditTelegramService service = new CapturingEditTelegramService();

        boolean edited = service.editMessageText(-100123L, 12, "Риск обработан", "HTML", List.of());

        assertTrue(edited);
        assertTrue(service.edit.getReplyMarkup() != null);
        assertTrue(service.edit.getReplyMarkup().getKeyboard().isEmpty());
    }

    private static final class RetryableTelegramService extends TelegramService {
        private final int failuresBeforeSuccess;
        private int attempts;
        private int sleeps;

        private RetryableTelegramService(int failuresBeforeSuccess) {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    true,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        Message executeTelegramMessage(SendMessage message) throws TelegramApiException {
            attempts++;
            if (attempts <= failuresBeforeSuccess) {
                throw new TelegramApiException("temporary telegram failure", new SocketTimeoutException("Read timed out"));
            }
            return new Message();
        }

        @Override
        void sleepBeforeRetry(long delayMillis) {
            sleeps++;
        }
    }

    private static final class CapturingCopyTelegramService extends TelegramService {
        private SendMessage message;
        private int returnedMessageId;

        private CapturingCopyTelegramService() {
            super(new DefaultBotOptions(), "123456:abcdefghijklmnopqrstuvwxyz", "test_bot", true, "",
                    null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        Message executeTelegramMessage(SendMessage message) throws TelegramApiException {
            this.message = message;
            String response = "{\"ok\":true,\"result\":{\"message_id\":91,\"reply_markup\":"
                    + "{\"inline_keyboard\":[[{\"text\":\"copy\",\"copy_text\":{\"text\":\"2202208238396676\"}}]]}}}";
            Message result = message.deserializeResponse(response);
            returnedMessageId = result.getMessageId();
            return result;
        }
    }

    private static final class MigratedCopyTelegramService extends TelegramService {
        private int attempts;
        private final String[] chatIds = new String[2];

        private MigratedCopyTelegramService(TelegramChatMigrationService migrationService) {
            super(new DefaultBotOptions(), "123456:abcdefghijklmnopqrstuvwxyz", "test_bot", true, "",
                    null, null, null, null, null, null, null, null, null, migrationService, null);
        }

        @Override
        Message executeTelegramMessage(SendMessage message) throws TelegramApiException {
            chatIds[attempts] = message.getChatId();
            attempts++;
            String response = attempts == 1
                    ? "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: group chat was upgraded\","
                    + "\"parameters\":{\"migrate_to_chat_id\":-1003538237871}}"
                    : "{\"ok\":true,\"result\":{\"message_id\":92}}";
            return message.deserializeResponse(response);
        }
    }

    private static final class MigratedTelegramService extends TelegramService {
        private int attempts;
        private final String[] chatIds = new String[2];

        private MigratedTelegramService(TelegramChatMigrationService migrationService) {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    true,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    migrationService,
                    null);
        }

        @Override
        Message executeTelegramMessage(SendMessage message) throws TelegramApiException {
            chatIds[attempts] = message.getChatId();
            attempts++;
            if (attempts == 1) {
                throw migratedException(-1003538237871L);
            }
            return new Message();
        }
    }

    private static final class ProtectedTelegramService extends TelegramService {
        private boolean protectContent;

        private ProtectedTelegramService() {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    true,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        @Override
        Message executeTelegramMessage(SendMessage message) {
            protectContent = Boolean.TRUE.equals(message.getProtectContent());
            Message result = new Message();
            result.setMessageId(1);
            return result;
        }
    }

    private static final class CapturingPhotoTelegramService extends TelegramService {
        private SendPhoto photo;

        private CapturingPhotoTelegramService() {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    true,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        @Override
        Message executeTelegramPhoto(SendPhoto photo) {
            this.photo = photo;
            return new Message();
        }
    }

    private static final class CapturingEditTelegramService extends TelegramService {
        private EditMessageText edit;

        private CapturingEditTelegramService() {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    true,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        @Override
        void executeEditMessageText(EditMessageText edit) {
            this.edit = edit;
        }
    }

    private static TelegramApiRequestException migratedException(long newChatId) {
        return new TelegramApiRequestException("Bad Request: group chat was upgraded to a supergroup chat") {
            @Override
            public ResponseParameters getParameters() {
                return new ResponseParameters(newChatId, null);
            }
        };
    }

    private static final class CapturingMigrationService extends TelegramChatMigrationService {
        private Long oldChatId;
        private Long newChatId;

        private CapturingMigrationService() {
            super(null, null, null);
        }

        @Override
        public TelegramChatMigrationResult migrateChatId(Long oldChatId, Long newChatId) {
            this.oldChatId = oldChatId;
            this.newChatId = newChatId;
            return new TelegramChatMigrationResult(oldChatId, newChatId, 1, 0, 0);
        }
    }
}
