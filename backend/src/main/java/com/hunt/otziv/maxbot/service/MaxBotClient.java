package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class MaxBotClient {

    private static final int MAX_MESSAGE_LENGTH = 3900;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String token;
    private final String baseUrl;

    public MaxBotClient(
            @Qualifier("maxBotRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${max.bot.token:}") String token,
            @Value("${max.bot.api-base-url:https://platform-api.max.ru}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.token = token;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public boolean isConfigured() {
        return hasText(token);
    }

    public boolean sendMessageToChat(Long chatId, String text) {
        return sendMessage("chatId", "chat_id", chatId, text);
    }

    public boolean sendMessageToUser(Long userId, String text) {
        return sendMessage("userId", "user_id", userId, text);
    }

    private boolean sendMessage(String recipientLabel, String recipientParam, Long recipientId, String text) {
        if (recipientId == null) {
            log.warn("MAX-сообщение не отправлено: {} пустой", recipientLabel);
            return false;
        }
        if (!isConfigured()) {
            log.warn("MAX-сообщение не отправлено: MAX_BOT_TOKEN пустой");
            return false;
        }
        if (!hasText(text)) {
            log.warn("MAX-сообщение не отправлено: текст пустой");
            return false;
        }

        boolean sent = true;
        for (String chunk : splitMessage(text)) {
            sent = sendSingleMessage(recipientLabel, recipientParam, recipientId, chunk) && sent;
        }
        return sent;
    }

    public JsonNode getUpdates(Long marker, int timeoutSeconds, String types) {
        if (!isConfigured()) {
            log.warn("MAX updates не запрошены: MAX_BOT_TOKEN пустой");
            return null;
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/updates")
                .queryParam("timeout", timeoutSeconds)
                .queryParam("limit", 100);
        if (marker != null) {
            builder.queryParam("marker", marker);
        }
        if (hasText(types)) {
            builder.queryParam("types", types);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    String.class
            );
            return parseJson(response.getBody(), "MAX updates");
        } catch (RestClientResponseException e) {
            log.warn("MAX updates HTTP {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            return null;
        } catch (ResourceAccessException e) {
            log.warn("MAX updates недоступны: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Ошибка при запросе MAX updates: {}", e.getMessage(), e);
            return null;
        }
    }

    public JsonNode getChat(Long chatId) {
        if (!isConfigured()) {
            log.warn("MAX chat info не запрошен: MAX_BOT_TOKEN пустой");
            return null;
        }
        if (chatId == null) {
            log.warn("MAX chat info не запрошен: chatId пустой");
            return null;
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/chats/" + chatId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    String.class
            );
            return parseJson(response.getBody(), "MAX chat info");
        } catch (RestClientResponseException e) {
            log.warn("MAX chat info HTTP {} for chatId={}: {}",
                    e.getStatusCode().value(), chatId, e.getResponseBodyAsString());
            return null;
        } catch (ResourceAccessException e) {
            log.warn("MAX chat info недоступен для chatId={}: {}", chatId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Ошибка при запросе MAX chat info chatId={}: {}", chatId, e.getMessage(), e);
            return null;
        }
    }

    public boolean registerWebhook(String webhookUrl, List<String> updateTypes, String secret) {
        if (!isConfigured()) {
            log.warn("MAX webhook не зарегистрирован: MAX_BOT_TOKEN пустой");
            return false;
        }
        if (!hasText(webhookUrl)) {
            log.warn("MAX webhook не зарегистрирован: URL пустой");
            return false;
        }
        if (updateTypes == null || updateTypes.isEmpty()) {
            log.warn("MAX webhook не зарегистрирован: список событий пустой");
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", webhookUrl);
        body.put("update_types", updateTypes);
        if (hasText(secret)) {
            body.put("secret", secret.trim());
        }

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + "/subscriptions",
                    new HttpEntity<>(body, headers()),
                    String.class
            );
            JsonNode result = parseJson(response.getBody(), "MAX webhook registration");
            if (result != null && result.has("success") && !result.path("success").asBoolean(false)) {
                log.warn("MAX webhook registration returned success=false for url={}: {}", webhookUrl, result);
                return false;
            }

            log.info("MAX webhook registered url={} updateTypes={}", webhookUrl, updateTypes);
            return true;
        } catch (RestClientResponseException e) {
            log.warn("MAX webhook registration HTTP {} for url={}. Ответ: {}",
                    e.getStatusCode().value(), webhookUrl, e.getResponseBodyAsString());
            return false;
        } catch (ResourceAccessException e) {
            log.warn("MAX webhook registration недоступна для url={}: {}", webhookUrl, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Ошибка при регистрации MAX webhook url={}: {}", webhookUrl, e.getMessage(), e);
            return false;
        }
    }

    private boolean sendSingleMessage(String recipientLabel, String recipientParam, Long recipientId, String text) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/messages")
                .queryParam(recipientParam, recipientId)
                .queryParam("disable_link_preview", true)
                .toUriString();

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(
                "text", text,
                "notify", true
        ), headers());

        try {
            restTemplate.postForEntity(url, request, String.class);
            log.info("MAX-сообщение отправлено {}={}", recipientLabel, recipientId);
            return true;
        } catch (RestClientResponseException e) {
            log.warn("MAX API вернул HTTP {} для {}={}. Ответ: {}",
                    e.getStatusCode().value(), recipientLabel, recipientId, e.getResponseBodyAsString());
            return false;
        } catch (ResourceAccessException e) {
            log.warn("MAX API недоступен для {}={}: {}", recipientLabel, recipientId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Ошибка при отправке MAX-сообщения {}={}: {}", recipientLabel, recipientId, e.getMessage(), e);
            return false;
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, token);
        return headers;
    }

    private JsonNode parseJson(String responseBody, String operation) {
        if (!hasText(responseBody)) {
            return null;
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("{} вернул ответ без JSON: {}", operation, abbreviate(responseBody));
            return null;
        }
    }

    private static List<String> splitMessage(String text) {
        if (text.length() <= MAX_MESSAGE_LENGTH) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\n")) {
            String part = line + "\n";
            if (current.length() > 0 && current.length() + part.length() > MAX_MESSAGE_LENGTH) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (part.length() <= MAX_MESSAGE_LENGTH) {
                current.append(part);
                continue;
            }
            for (int start = 0; start < part.length(); start += MAX_MESSAGE_LENGTH) {
                int end = Math.min(start + MAX_MESSAGE_LENGTH, part.length());
                chunks.add(part.substring(start, end));
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private static String trimTrailingSlash(String value) {
        if (!hasText(value)) {
            return "https://platform-api.max.ru";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
