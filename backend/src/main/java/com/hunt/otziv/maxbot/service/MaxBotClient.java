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
            @Value("${max.bot.api-base-url:https://platform-api2.max.ru}") String baseUrl
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

    /**
     * One operation-owned attempt: no chunking, fallback or retry after an uncertain result.
     * The caller owns durable deduplication; MAX does not receive an invented idempotency header.
     */
    public SendOnceResult sendMessageToChatOnce(Long chatId, String text) {
        if (chatId == null || !hasText(text)) return new SendOnceResult(null, "invalid_request");
        if (text.length() > MAX_MESSAGE_LENGTH) return new SendOnceResult(null, "payload_too_large");
        if (!isConfigured()) return new SendOnceResult(null, "max_not_configured");

        String url = UriComponentsBuilder.fromUriString(baseUrl + "/messages")
                .queryParam("chat_id", chatId)
                .queryParam("disable_link_preview", true)
                .toUriString();
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("text", text, "notify", true), headers());
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && hasText(response.getBody())) {
                JsonNode result = objectMapper.readTree(response.getBody());
                // POST /messages returns Message.body.mid, an opaque string rather than a Telegram-style integer.
                // https://dev.max.ru/docs-api/methods/POST/messages
                JsonNode receipt = result == null ? null : result.path("message").path("body").path("mid");
                boolean rejected = result != null && (result.hasNonNull("error") || result.hasNonNull("code")
                        || (result.has("success") && (!result.path("success").isBoolean() || !result.path("success").booleanValue())));
                if (!rejected && receipt != null && receipt.isTextual() && validReceiptId(receipt.textValue())) {
                    return new SendOnceResult(receipt.textValue(), null);
                }
            }
        } catch (Exception unconfirmed) {
            log.warn("MAX operation has no confirmed result ({})", unconfirmed.getClass().getSimpleName());
        }
        return new SendOnceResult(null, "operation_unknown");
    }

    public record SendOnceResult(String messageId, String errorCode) {
        public boolean confirmed() { return errorCode == null && validReceiptId(messageId); }
    }

    /** Upload is separate from message delivery and its token may be reused by a durable campaign. */
    public String uploadDocument(byte[] bytes, String filename) {
        if (!isConfigured()) throw new IllegalStateException("MAX не настроен");
        String step = "upload-url";
        try {
            JsonNode slot = objectMapper.readTree(restTemplate.postForObject(baseUrl + "/uploads?type=file", new HttpEntity<>(headers()), String.class));
            if (slot == null || !hasText(slot.path("url").asText())) {
                log.warn("MAX upload slot missing url (code={})", safeCode(slot == null ? null : slot.path("code").asText()));
                throw new IllegalStateException("MAX upload url missing");
            }
            java.net.URI uploadUrl = java.net.URI.create(slot.path("url").asText());
            step = "upload-host";
            // File bytes go only to MAX's own upload hosts; the bot token is never sent there.
            if (!trustedUploadHost(uploadUrl)) {
                log.warn("MAX upload refused: unexpected upload host {}", uploadUrl.getHost());
                throw new IllegalStateException("Unexpected MAX upload host");
            }
            step = "file-upload";
            var body = new org.springframework.util.LinkedMultiValueMap<String,Object>();
            body.add("data", new org.springframework.core.io.ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            });
            var uploadHeaders = new HttpHeaders();
            uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            String uploaded = restTemplate.postForObject(uploadUrl,new HttpEntity<>(body,uploadHeaders),String.class);
            step = "upload-token";
            String uploadToken = objectMapper.readTree(uploaded).path("token").asText();
            if (uploadToken.isBlank() || uploadToken.length() > 8192) throw new IllegalStateException("MAX upload token missing");
            return uploadToken;
        } catch (Exception failed) {
            log.warn("MAX file upload failed at {}: {}", step, describe(failed));
            throw new IllegalStateException("MAX не подтвердил загрузку файла",failed);
        }
    }

    /** MAX's own upload hosts (file uploads use *.oneme.ru, media also *.okcdn.ru), over HTTPS only. */
    static boolean trustedUploadHost(java.net.URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(java.util.Locale.ROOT);
        return "https".equals(url.getScheme()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443)
                && (host.endsWith(".oneme.ru") || host.endsWith(".okcdn.ru"));
    }

    /** MAX answers this while it still processes an uploaded file; the message was refused, not sent. */
    public static final String ATTACHMENT_NOT_READY = "max_attachment_not_ready";
    /** Pauses before re-sending a refused file message, as MAX recommends: growing, about 15 s in all. */
    private static final long[] ATTACHMENT_READY_PAUSES_MS = {1000, 2000, 4000, 8000};
    private java.util.function.LongPredicate pause = MaxBotClient::sleep;

    void pauseWith(java.util.function.LongPredicate pause) { this.pause = pause; }

    /**
     * Exactly one accepted message. A message refused with attachment.not.ready was not sent, so it is
     * re-sent after a pause; any other uncertain answer is returned at once without another request.
     */
    public SendOnceResult sendDocumentToChatOnce(Long chatId, String text, String uploadToken) {
        if (!isConfigured()) return new SendOnceResult(null,"max_not_configured");
        if (chatId == null || !hasText(text) || !hasText(uploadToken)) return new SendOnceResult(null,"invalid_request");
        var payload = Map.<String,Object>of("text",text,"notify",true,"attachments",
                List.of(Map.of("type","file","payload",Map.of("token",uploadToken))));
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/messages").queryParam("chat_id",chatId).toUriString();
        for (int attempt = 0; ; attempt++) {
            SendOnceResult result = postDocumentMessage(url, payload);
            if (!ATTACHMENT_NOT_READY.equals(result.errorCode()) || attempt >= ATTACHMENT_READY_PAUSES_MS.length
                    || !pause.test(ATTACHMENT_READY_PAUSES_MS[attempt])) {
                if (ATTACHMENT_NOT_READY.equals(result.errorCode()))
                    log.warn("MAX file message refused: the file is still being processed after {} attempts", attempt + 1);
                return result;
            }
        }
    }

    private SendOnceResult postDocumentMessage(String url, Map<String,Object> payload) {
        try {
            String response = restTemplate.postForObject(url,new HttpEntity<>(payload,headers()),String.class);
            JsonNode node = objectMapper.readTree(response);
            JsonNode receipt = node.path("message").path("body").path("mid");
            String id = receipt.asText();
            boolean rejected = node.hasNonNull("error") || node.hasNonNull("code")
                    || (node.has("success") && (!node.path("success").isBoolean() || !node.path("success").booleanValue()));
            if (!rejected && receipt.isTextual() && validReceiptId(id)) return new SendOnceResult(id,null);
            if ("attachment.not.ready".equals(node.path("code").asText())) return new SendOnceResult(null,ATTACHMENT_NOT_READY);
            log.warn("MAX file message has no confirmed receipt (code={})", safeCode(node.path("code").asText()));
        } catch (RestClientResponseException error) {
            String code = null;
            try { code = objectMapper.readTree(error.getResponseBodyAsString()).path("code").asText(); }
            catch (Exception ignored) { /* Unconfirmed */ }
            if ("attachment.not.ready".equals(code)) return new SendOnceResult(null,ATTACHMENT_NOT_READY);
            log.warn("MAX file message unconfirmed: HTTP {} (code={})", error.getStatusCode().value(), safeCode(code));
        } catch (Exception unconfirmed) {
            log.warn("MAX document operation unconfirmed ({})",unconfirmed.getClass().getSimpleName());
        }
        return new SendOnceResult(null,"operation_unknown");
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** HTTP status, MAX error code and exception types only: upload URLs carry a signature. */
    private String describe(Exception failure) {
        if (failure instanceof RestClientResponseException http) {
            String code = null;
            try { code = objectMapper.readTree(http.getResponseBodyAsString()).path("code").asText(); }
            catch (Exception ignored) { /* Not JSON */ }
            return "HTTP " + http.getStatusCode().value() + " (code=" + safeCode(code) + ")";
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return failure.getClass().getSimpleName() + (root == failure ? "" : " caused by " + root.getClass().getSimpleName());
    }

    private static String safeCode(String code) {
        if (!hasText(code)) return "-";
        String cleaned = code.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.isEmpty() ? "-" : cleaned.substring(0, Math.min(80, cleaned.length()));
    }

    private static boolean validReceiptId(String messageId) {
        return messageId != null && messageId.matches("(?:mid\\.)?[a-zA-Z0-9_\\-]+")
                && !messageId.matches("-?[0]+|-[0-9]+");
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
            log.warn("MAX updates HTTP {}", e.getStatusCode().value());
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

    /** Strict history lookup for an archive decision. Never acknowledges updates or sends messages. */
    public JsonNode archiveHistoryPage(long chatId,long upperMillis) {
        if(!isConfigured()) throw new IllegalStateException("MAX history is not configured");
        String url=UriComponentsBuilder.fromUriString(baseUrl+"/messages")
                .queryParam("chat_id",chatId).queryParam("from",upperMillis).queryParam("count",100).toUriString();
        try {
            var response=restTemplate.exchange(url,HttpMethod.GET,new HttpEntity<>(headers()),String.class);
            JsonNode body=objectMapper.readTree(response.getBody());
            if(body==null || !response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("MAX history is unavailable");
            return body;
        } catch(Exception unavailable) { throw new IllegalStateException("MAX history is unavailable",unavailable); }
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
            return "https://platform-api2.max.ru";
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
