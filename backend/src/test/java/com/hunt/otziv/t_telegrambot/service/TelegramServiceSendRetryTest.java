package com.hunt.otziv.t_telegrambot.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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

    private static final class RetryableTelegramService extends TelegramService {
        private final int failuresBeforeSuccess;
        private int attempts;
        private int sleeps;

        private RetryableTelegramService(int failuresBeforeSuccess) {
            super(new DefaultBotOptions(),
                    "123456:abcdefghijklmnopqrstuvwxyz",
                    "test_bot",
                    "",
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
}
