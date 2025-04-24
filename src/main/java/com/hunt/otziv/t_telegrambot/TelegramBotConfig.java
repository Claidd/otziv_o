package com.hunt.otziv.t_telegrambot;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
@Slf4j
public class TelegramBotConfig {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 5000;

    @Bean
    public TelegramBotsApi telegramBotsApi(MyTelegramBot myTelegramBot) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
                telegramBotsApi.registerBot(myTelegramBot);
                log.info("Telegram бот успешно зарегистрирован");
                return telegramBotsApi;
            } catch (TelegramApiException e) {
                attempts++;
                log.error("Попытка регистрации {} из {}. Ошибка: {}",
                        attempts, MAX_RETRIES, e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Прервано ожидание повторной попытки", ie);
                }
            }
        }
        throw new RuntimeException("Не удалось зарегистрировать бота после " + MAX_RETRIES + " попыток");
    }

    @Bean
    public DefaultBotOptions botOptions() {
        DefaultBotOptions options = new DefaultBotOptions();

        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(30 * 1000)
                .setConnectTimeout(30 * 1000)
                .setConnectionRequestTimeout(30 * 1000)
                .build();

        options.setRequestConfig(requestConfig);
        options.setMaxThreads(1);
        options.setBaseUrl("https://api.telegram.org/bot");

        return options;
    }
}
