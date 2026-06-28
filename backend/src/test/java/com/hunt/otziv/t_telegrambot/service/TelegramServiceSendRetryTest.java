package com.hunt.otziv.t_telegrambot.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramServiceSendRetryTest {

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
                    null);
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        void executeTelegramMessage(SendMessage message) throws TelegramApiException {
            attempts++;
            if (attempts <= failuresBeforeSuccess) {
                throw new TelegramApiException("temporary telegram failure", new SocketTimeoutException("Read timed out"));
            }
        }

        @Override
        void sleepBeforeRetry(long delayMillis) {
            sleeps++;
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
                    migrationService);
        }

        @Override
        void executeTelegramMessage(SendMessage message) throws TelegramApiException {
            chatIds[attempts] = message.getChatId();
            attempts++;
            if (attempts == 1) {
                throw migratedException(-1003538237871L);
            }
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
            super(null, null);
        }

        @Override
        public TelegramChatMigrationResult migrateChatId(Long oldChatId, Long newChatId) {
            this.oldChatId = oldChatId;
            this.newChatId = newChatId;
            return new TelegramChatMigrationResult(oldChatId, newChatId, 1, 0);
        }
    }
}
