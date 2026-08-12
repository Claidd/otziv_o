package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppClientStatusDto;
import com.hunt.otziv.whatsapp.dto.WhatsAppChatMessageCursor;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.dto.WhatsAppReconciledMessage;
import com.hunt.otziv.whatsapp.dto.WhatsAppReconciledMessagesResponse;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.exception.WhatsAppConfigurationException;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


@Service
@Slf4j
public class WhatsAppServiceImpl implements WhatsAppService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WhatsAppProperties properties;
    private final RestTemplate restTemplate;

    public WhatsAppServiceImpl(
            WhatsAppProperties properties,
            @Qualifier("whatsAppRestTemplate") RestTemplate restTemplate
    ) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    // ==== Helpers ====

    private String baseUrl(String clientId) {
        if (!hasText(clientId)) {
            throw new WhatsAppConfigurationException("missing_client", "WhatsApp-клиент не указан");
        }

        List<WhatsAppProperties.ClientConfig> clients = properties.getClients() != null
                ? properties.getClients()
                : List.of();

        Optional<WhatsAppProperties.ClientConfig> clientOpt = clients
                .stream()
                .filter(c -> clientId.equals(c.getId()))
                .findFirst();

        if (clientOpt.isEmpty()) {
            String availableClients = clients.stream()
                    .map(WhatsAppProperties.ClientConfig::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.joining(", "));
            String suffix = availableClients.isBlank() ? "" : ". Доступные клиенты: " + availableClients;
            throw new WhatsAppConfigurationException(
                    "unknown_client",
                    "Неизвестный WhatsApp-клиент: " + clientId + suffix
            );
        }

        String url = clientOpt.get().getUrl();
        if (url == null || url.isBlank()) {
            throw new WhatsAppConfigurationException("empty_client_url", "Пустой URL у WhatsApp-клиента: " + clientId);
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 8XXXXXXXXXX -> 7XXXXXXXXXX; оставляет другие форматы как есть */
    private String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D+", "");
        if (digits.startsWith("8") && digits.length() == 11) {
            return "7" + digits.substring(1);
        }
        return digits;
    }

    private HttpEntity<String> jsonEntity(Map<String, Object> payload) throws JsonProcessingException {
        String jsonBody = MAPPER.writeValueAsString(payload);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(jsonBody, headers);
    }

    // ==== Public API ====

    @Override
    public String sendMessageToGroup(String clientId, String groupId, String message) {
        log.info("WhatsApp group send request started: clientId={}, groupIdPresent={}", clientId, hasText(groupId));

        if (groupId == null || groupId.isBlank()) {
            log.warn("WhatsApp-сообщение в группу не отправлено: groupId пустой");
            return WhatsAppSendResult.error("missing_group_id", "groupId не должен быть пустым").toJson();
        }
        if (!hasText(message)) {
            log.warn("WhatsApp-сообщение в группу не отправлено: текст сообщения пустой");
            return WhatsAppSendResult.error("missing_message", "Сообщение не должно быть пустым").toJson();
        }

        try {
            String url = baseUrl(clientId) + "/send-group";
            HttpEntity<String> request = jsonEntity(Map.of(
                    "groupId", groupId,
                    "message", message
            ));

            log.info("WhatsApp group send request: url={}, groupIdPresent={}, messageLength={}",
                    url, hasText(groupId), message.length());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("WhatsApp group send response: clientId={}, status={}", clientId, response.getStatusCode().value());
            return response.getBody() != null ? response.getBody() : "ok";
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp-сообщение в группу не отправлено: {}", e.getMessage());
            return WhatsAppSendResult.error(e.getCode(), e.getMessage()).toJson();
        } catch (RestClientResponseException e) {
            String error = "WhatsApp API вернул HTTP " + e.getStatusCode().value();
            log.warn("{} для клиента {}", error, clientId);
            return httpSendError(e, error).toJson();
        } catch (ResourceAccessException e) {
            String error = "WhatsApp-клиент недоступен: " + e.getMessage();
            log.warn("{} ({})", error, clientId);
            return WhatsAppSendResult.error("client_unavailable", error).toJson();
        } catch (JsonProcessingException e) {
            log.error("Не удалось собрать JSON для WhatsApp-сообщения в группу через {}", clientId, e);
            return WhatsAppSendResult.error("invalid_payload", "Не удалось собрать JSON для WhatsApp").toJson();
        } catch (Exception e) {
            log.error("❌ Ошибка при отправке в группу через {}: {}", clientId, e.getMessage(), e);
            return WhatsAppSendResult.error("unexpected_error", e.getMessage()).toJson();
        }
    }

    public String sendMessage(String clientId, String phone, String message) {
        String normalized = normalizePhone(phone);
        log.info("WhatsApp send request started: clientId={}, phone={}", clientId, maskPhone(normalized));
        if (!hasText(normalized)) {
            log.warn("WhatsApp-сообщение не отправлено: телефон пустой");
            return WhatsAppSendResult.error("missing_phone", "Телефон не должен быть пустым").toJson();
        }
        if (!hasText(message)) {
            log.warn("WhatsApp-сообщение не отправлено: текст сообщения пустой");
            return WhatsAppSendResult.error("missing_message", "Сообщение не должно быть пустым").toJson();
        }

        try {
            String url = baseUrl(clientId) + "/send";
            HttpEntity<String> request = jsonEntity(Map.of(
                    "client", clientId,
                    "phone", normalized,
                    "message", message
            ));

            log.info("WhatsApp send request: url={}, clientId={}, phone={}, messageLength={}",
                    url, clientId, maskPhone(normalized), message.length());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            log.info("WhatsApp send response: clientId={}, status={}", clientId, response.getStatusCode().value());
            return response.getBody() != null ? response.getBody() : "ok";
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp-сообщение не отправлено: {}", e.getMessage());
            return WhatsAppSendResult.error(e.getCode(), e.getMessage()).toJson();
        } catch (RestClientResponseException e) {
            String error = "WhatsApp API вернул HTTP " + e.getStatusCode().value();
            log.warn("{} для клиента {}", error, clientId);
            return httpSendError(e, error).toJson();
        } catch (ResourceAccessException e) {
            String error = "WhatsApp-клиент недоступен: " + e.getMessage();
            log.warn("{} ({})", error, clientId);
            return WhatsAppSendResult.error("client_unavailable", error).toJson();
        } catch (JsonProcessingException e) {
            log.error("Не удалось собрать JSON для WhatsApp-сообщения через {}", clientId, e);
            return WhatsAppSendResult.error("invalid_payload", "Не удалось собрать JSON для WhatsApp").toJson();
        } catch (Exception e) {
            log.error("❌ Ошибка при отправке сообщения через {}: {}", clientId, e.getMessage(), e);
            return WhatsAppSendResult.error("unexpected_error", e.getMessage()).toJson();
        }
    }

    @Override
    public List<WhatsAppGroupInfo> listGroups(String clientId) {
        return listGroups(clientId, false);
    }

    @Override
    public List<WhatsAppGroupInfo> listGroups(String clientId, boolean forceRefresh) {
        try {
            String url = baseUrl(clientId) + "/groups" + (forceRefresh ? "?refresh=1" : "");
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return parseGroups(response.getBody());
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp-группы не запрошены: {}", e.getMessage());
            return List.of();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.debug("WhatsApp-клиент {} не поддерживает GET /groups", clientId);
            } else {
                log.warn("WhatsApp API вернул HTTP {} при запросе групп клиента {}. Ответ: {}",
                        e.getStatusCode().value(), clientId, e.getResponseBodyAsString());
            }
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("WhatsApp-клиент {} недоступен при запросе групп: {}", clientId, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("Не удалось получить WhatsApp-группы клиента {}: {}", clientId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<WhatsAppReconciledMessage> reconcileGroupMessages(
            String clientId,
            List<WhatsAppChatMessageCursor> chats
    ) {
        if (chats == null || chats.isEmpty()) {
            return List.of();
        }
        try {
            String url = baseUrl(clientId) + "/groups/reconcile-messages";
            HttpEntity<String> request = jsonEntity(Map.of("chats", chats));
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            WhatsAppReconciledMessagesResponse payload = MAPPER.readValue(
                    response.getBody() == null ? "{}" : response.getBody(),
                    WhatsAppReconciledMessagesResponse.class
            );
            return payload.messages() == null ? List.of() : payload.messages();
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp-сообщения не сверены: {}", e.getMessage());
        } catch (RestClientResponseException e) {
            log.warn(
                    "WhatsApp API вернул HTTP {} при сверке сообщений клиента {}",
                    e.getStatusCode().value(),
                    clientId
            );
        } catch (ResourceAccessException e) {
            log.warn("WhatsApp-клиент {} недоступен при сверке сообщений: {}", clientId, e.getMessage());
        } catch (JsonProcessingException e) {
            log.warn("Не удалось разобрать ответ сверки WhatsApp-клиента {}", clientId, e);
        } catch (Exception e) {
            log.warn("Не удалось сверить WhatsApp-сообщения клиента {}", clientId, e);
        }
        return List.of();
    }

    @Override
    public Optional<WhatsAppGroupInfo> resolveGroupByInvite(String clientId, String inviteLinkOrCode) {
        if (!hasText(inviteLinkOrCode)) {
            return Optional.empty();
        }

        try {
            String url = baseUrl(clientId) + "/groups/resolve-invite";
            HttpEntity<String> request = jsonEntity(Map.of("inviteLink", inviteLinkOrCode.trim()));
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            Optional<WhatsAppGroupInfo> group = parseResolvedGroup(response.getBody());
            if (group.isEmpty()) {
                log.warn("WhatsApp-клиент {} разрешил invite-ссылку без groupId", clientId);
            }
            return group;
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp invite-ссылка не разрешена: {}", e.getMessage());
            return Optional.empty();
        } catch (RestClientResponseException e) {
            log.warn("WhatsApp API вернул HTTP {} при прямом поиске группы клиента {}. Ответ: {}",
                    e.getStatusCode().value(), clientId, limit(e.getResponseBodyAsString(), 500));
            return Optional.empty();
        } catch (ResourceAccessException e) {
            log.warn("WhatsApp-клиент {} недоступен при прямом поиске группы: {}", clientId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Не удалось напрямую найти WhatsApp-группу клиента {} по invite-ссылке: {}",
                    clientId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public WhatsAppClientStatusDto getClientStatus(String clientId) {
        try {
            String baseUrl = baseUrl(clientId);
            JsonNode health = getJson(baseUrl + "/health").orElse(MAPPER.createObjectNode());
            JsonNode qr = shouldFetchQr(health)
                    ? getJsonAllowingNotFound(baseUrl + "/qr").orElse(null)
                    : null;

            return statusFrom(clientId, health, qr);
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp QR status unavailable: {}", e.getMessage());
            return WhatsAppClientStatusDto.unconfigured(clientId, e.getMessage());
        } catch (ResourceAccessException e) {
            String message = "WhatsApp-клиент недоступен: " + e.getMessage();
            log.warn("{} ({})", message, clientId);
            return WhatsAppClientStatusDto.unavailable(clientId, message);
        } catch (Exception e) {
            String message = "Не удалось получить статус WhatsApp: " + e.getMessage();
            log.warn("{} ({})", message, clientId);
            return WhatsAppClientStatusDto.unavailable(clientId, message);
        }
    }

    private Optional<JsonNode> getJson(String url) throws JsonProcessingException {
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        String body = response.getBody();
        return hasText(body) ? Optional.of(MAPPER.readTree(body)) : Optional.empty();
    }

    private WhatsAppSendResult httpSendError(RestClientResponseException e, String errorPrefix) {
        String body = e.getResponseBodyAsString();
        String message = errorPrefix + (hasText(body) ? ". Ответ: " + limit(body, 500) : "");
        String code = httpSendErrorCode(body);
        if (!hasText(code) && e.getStatusCode().value() == 503) {
            code = "whatsapp_http_503";
        }
        return WhatsAppSendResult.error(hasText(code) ? code : "http_error", message);
    }

    private String httpSendErrorCode(String body) {
        if (!hasText(body)) {
            return null;
        }

        try {
            JsonNode node = MAPPER.readTree(body);
            String status = firstText(node, "status");
            String code = firstText(node, "code");
            if (looksLikeAuthUnavailable(status) || looksLikeAuthUnavailable(code) || looksLikeAuthUnavailable(body)) {
                return "whatsapp_not_ready";
            }
            if (hasText(code)) {
                return code;
            }
            if (hasText(status) && !"error".equalsIgnoreCase(status)) {
                return status;
            }
        } catch (JsonProcessingException ignored) {
            if (looksLikeAuthUnavailable(body)) {
                return "whatsapp_not_ready";
            }
        }
        return null;
    }

    private boolean looksLikeAuthUnavailable(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("authenticated=false")
                || normalized.contains("\"authenticated\":false")
                || normalized.contains("\"authenticated\": false")
                || normalized.contains("\"state\":\"qr\"")
                || normalized.contains("\"state\": \"qr\"")
                || normalized.contains("\"hasqr\":true")
                || normalized.contains("\"hasqr\": true")
                || normalized.contains("scan it")
                || normalized.contains("не авториз");
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private Optional<JsonNode> getJsonAllowingNotFound(String url) throws JsonProcessingException {
        try {
            return getJson(url);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 && hasText(e.getResponseBodyAsString())) {
                return Optional.of(MAPPER.readTree(e.getResponseBodyAsString()));
            }
            throw e;
        }
    }

    private boolean shouldFetchQr(JsonNode health) {
        return !booleanField(health, "ready") || booleanField(health, "hasQr");
    }

    private WhatsAppClientStatusDto statusFrom(String clientId, JsonNode health, JsonNode qr) {
        JsonNode source = qr != null && hasText(textField(qr, "qrDataUrl", null)) ? qr : health;
        String message = firstText(source, "message");
        if (!hasText(message) && qr != null) {
            message = firstText(qr, "message");
        }

        return new WhatsAppClientStatusDto(
                textField(source, "clientId", clientId),
                true,
                booleanField(source, "ready"),
                booleanField(source, "authenticated"),
                textField(source, "state", "unknown"),
                textField(source, "lastQrAt", null),
                textField(source, "lastReadyAt", null),
                textField(source, "lastError", null),
                booleanField(source, "hasQr"),
                textField(source, "qrDataUrl", null),
                message
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String maskPhone(String value) {
        if (!hasText(value)) {
            return "";
        }
        String digits = value.replaceAll("\\D+", "");
        if (digits.length() < 4) {
            return "***";
        }
        return "***" + digits.substring(digits.length() - 4);
    }

    private List<WhatsAppGroupInfo> parseGroups(String rawBody) throws JsonProcessingException {
        if (!hasText(rawBody)) {
            return List.of();
        }

        JsonNode groupsNode = groupsNode(MAPPER.readTree(rawBody));
        if (groupsNode == null || !groupsNode.isArray()) {
            return List.of();
        }

        List<WhatsAppGroupInfo> groups = new ArrayList<>();
        for (JsonNode groupNode : groupsNode) {
            String groupId = firstText(groupNode, "groupId", "id", "chatId", "jid");
            if (!hasText(groupId)) {
                continue;
            }

            groups.add(new WhatsAppGroupInfo(
                    groupId,
                    firstText(groupNode, "name", "title", "subject"),
                    firstText(groupNode, "inviteLink", "inviteCode", "invite", "link", "url")
            ));
        }
        return groups;
    }

    private Optional<WhatsAppGroupInfo> parseResolvedGroup(String rawBody) throws JsonProcessingException {
        if (!hasText(rawBody)) {
            return Optional.empty();
        }

        JsonNode root = MAPPER.readTree(rawBody);
        JsonNode groupNode = root != null && root.path("group").isObject() ? root.path("group") : root;
        if (groupNode == null || !groupNode.isObject()) {
            return Optional.empty();
        }

        String groupId = firstText(groupNode, "groupId", "id", "chatId", "jid");
        if (!hasText(groupId)) {
            return Optional.empty();
        }
        return Optional.of(new WhatsAppGroupInfo(
                groupId,
                firstText(groupNode, "name", "title", "subject"),
                firstText(groupNode, "inviteLink", "inviteCode", "invite", "link", "url")
        ));
    }

    private JsonNode groupsNode(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        for (String field : List.of("groups", "data", "chats")) {
            JsonNode node = root.path(field);
            if (node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private String textField(JsonNode node, String field, String fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && hasText(value.asText()) ? value.asText().trim() : fallback;
    }

    private boolean booleanField(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }
}
