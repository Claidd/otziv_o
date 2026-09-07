package com.hunt.otziv.t_telegrambot.config;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.meta.generics.LongPollingBot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TelegramLongPollingDependencyTest {
    @Test
    void applicationConfigurationStartsWithoutWebhookServerOrRegistration() throws Exception {
        TelegramService service = mock(TelegramService.class);
        assertNotNull(new TelegramBotConfig().telegramBotsApi(service, false, ""));
        verifyNoInteractions(service);
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.glassfish.grizzly.http.server.HttpServer"));
    }

    @Test
    void actualLongPollingSessionReceivesLocalHttpUpdateWithoutGrizzly() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/botfixture/getupdates", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"ok\":true,\"result\":[{\"update_id\":1}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        fixture.start();
        BotSession session = null;
        try {
            DefaultBotOptions options = new TelegramBotConfig().botOptions(false, "", 0, "HTTP", 1, 1000);
            options.setBaseUrl("http://127.0.0.1:" + fixture.getAddress().getPort() + "/bot");
            LongPollingBot bot = mock(LongPollingBot.class);
            when(bot.getBotUsername()).thenReturn("local-dependency-fixture");
            when(bot.getBotToken()).thenReturn("fixture");
            when(bot.getOptions()).thenReturn(options);
            doAnswer(call -> { delivered.countDown(); return null; }).when(bot).onUpdatesReceived(anyList());

            session = new TelegramBotsApi(TolerantTelegramBotSession.class).registerBot(bot);
            assertTrue(delivered.await(5, TimeUnit.SECONDS), "Local HTTP update must reach the actual session callback");
            verify(bot).clearWebhook();
            assertTrue(session.isRunning());
        } finally {
            if (session != null && session.isRunning()) {
                session.stop();
            }
            fixture.stop(0);
        }
    }
}
