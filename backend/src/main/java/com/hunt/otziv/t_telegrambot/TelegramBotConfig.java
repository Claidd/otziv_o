package com.hunt.otziv.t_telegrambot;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.util.List;
import java.util.regex.Pattern;

@Configuration
@Slf4j
public class TelegramBotConfig {

    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("\\d{6,}:[A-Za-z0-9_-]{20,}");

    @Bean
    public TelegramBotsApi telegramBotsApi(
            TelegramService telegramService,
            @Value("${telegram.bot.registration-enabled:true}") boolean registrationEnabled,
            @Value("${telegram.bot.token:}") String botToken
    ) throws TelegramApiException {
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(TolerantTelegramBotSession.class);
        if (!registrationEnabled) {
            log.info("Telegram bot registration is disabled");
            return telegramBotsApi;
        }
        if (botToken == null || botToken.isBlank()) {
            log.warn("Telegram bot registration skipped: token is empty");
            return telegramBotsApi;
        }
        if (!looksLikeTelegramBotToken(botToken)) {
            log.warn("Telegram bot registration skipped: TELEGRAM_BOT_TOKEN has an invalid format");
            return telegramBotsApi;
        }

        try {
            telegramBotsApi.registerBot(telegramService);
            log.info("Telegram бот успешно зарегистрирован");
        } catch (TelegramApiRequestException e) {
            if (isNotFound(e)) {
                log.warn("Telegram бот не зарегистрирован: Telegram вернул 404 на deleteWebhook. Проверьте TELEGRAM_BOT_TOKEN и настройки TELEGRAM_PROXY_*/OPENAI_PROXY_*. Приложение продолжает запуск. Ошибка: {}", e.getMessage());
            } else {
                log.warn("Telegram бот не зарегистрирован, приложение продолжает запуск: {}", e.getMessage());
                log.debug("Telegram registration exception", e);
            }
        } catch (TelegramApiException e) {
            log.warn("Telegram бот не зарегистрирован, приложение продолжает запуск: {}", e.getMessage());
            log.debug("Telegram registration exception", e);
        }
        return telegramBotsApi;
    }

    @Bean
    public DefaultBotOptions botOptions(
            @Value("${telegram.bot.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${telegram.bot.proxy.host:}") String proxyHost,
            @Value("${telegram.bot.proxy.port:8888}") int proxyPort,
            @Value("${telegram.bot.proxy.type:HTTP}") String proxyType,
            @Value("${telegram.bot.long-polling.timeout-seconds:50}") int longPollingTimeoutSeconds,
            @Value("${telegram.bot.request-timeout-ms:10000}") int requestTimeoutMillis
    ) {
        DefaultBotOptions options = new DefaultBotOptions();
        int normalizedLongPollingTimeoutSeconds = Math.max(1, longPollingTimeoutSeconds);
        int normalizedRequestTimeoutMillis = Math.max(1_000, requestTimeoutMillis);

        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(normalizedRequestTimeoutMillis)
                .setConnectTimeout(normalizedRequestTimeoutMillis)
                .setConnectionRequestTimeout(normalizedRequestTimeoutMillis)
                .build();

        options.setRequestConfig(requestConfig);
        options.setMaxThreads(1);
        options.setBaseUrl("https://api.telegram.org/bot");
        options.setGetUpdatesTimeout(normalizedLongPollingTimeoutSeconds);
        options.setAllowedUpdates(List.of("message", "my_chat_member"));

        if (proxyEnabled && proxyHost != null && !proxyHost.isBlank()) {
            options.setProxyHost(proxyHost);
            options.setProxyPort(proxyPort);
            options.setProxyType(resolveProxyType(proxyType));
            log.info("Telegram будет использовать proxy {}:{} ({}), longPollingTimeout={}s, requestTimeout={}ms",
                    proxyHost, proxyPort, options.getProxyType(), normalizedLongPollingTimeoutSeconds, normalizedRequestTimeoutMillis);
        } else {
            options.setProxyType(DefaultBotOptions.ProxyType.NO_PROXY);
            log.info("Telegram proxy отключён, используется прямое соединение, longPollingTimeout={}s, requestTimeout={}ms",
                    normalizedLongPollingTimeoutSeconds, normalizedRequestTimeoutMillis);
        }

        return options;
    }

    private DefaultBotOptions.ProxyType resolveProxyType(String rawType) {
        try {
            return DefaultBotOptions.ProxyType.valueOf(rawType == null ? "HTTP" : rawType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Неизвестный telegram.bot.proxy.type='{}', используется HTTP", rawType);
            return DefaultBotOptions.ProxyType.HTTP;
        }
    }

    private boolean looksLikeTelegramBotToken(String botToken) {
        return BOT_TOKEN_PATTERN.matcher(botToken.trim()).matches();
    }

    private boolean isNotFound(TelegramApiRequestException e) {
        return Integer.valueOf(404).equals(e.getErrorCode())
                || (e.getMessage() != null && e.getMessage().contains("[404]"));
    }
}
