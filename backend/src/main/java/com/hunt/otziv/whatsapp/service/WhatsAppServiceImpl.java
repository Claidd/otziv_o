package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.dto.WhatsAppUserStatusDto;
import com.hunt.otziv.whatsapp.exception.WhatsAppConfigurationException;
import com.hunt.otziv.whatsapp.service.fichi.LastSeenParser;
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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
        log.info("🚀 Попытка отправить сообщение в группу через {} на {}", clientId, groupId);

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

            log.info("📦 POST {} payload: {}", url, request.getBody());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("✅ Ответ от {}: {}", clientId, response.getBody());
            return response.getBody() != null ? response.getBody() : "ok";
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp-сообщение в группу не отправлено: {}", e.getMessage());
            return WhatsAppSendResult.error(e.getCode(), e.getMessage()).toJson();
        } catch (RestClientResponseException e) {
            String error = "WhatsApp API вернул HTTP " + e.getStatusCode().value();
            log.warn("{} для клиента {}. Ответ: {}", error, clientId, e.getResponseBodyAsString());
            return WhatsAppSendResult.error("http_error", error).toJson();
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
        log.info("🚀 Попытка отправить сообщение через {} на {}", clientId, phone);

        String normalized = normalizePhone(phone);
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

            log.info("📦 POST {} payload: {}", url, request.getBody());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            log.info("✅ Ответ от {}: {}", clientId, response.getBody());
            return response.getBody() != null ? response.getBody() : "ok";
        } catch (WhatsAppConfigurationException e) {
            log.warn("WhatsApp-сообщение не отправлено: {}", e.getMessage());
            return WhatsAppSendResult.error(e.getCode(), e.getMessage()).toJson();
        } catch (RestClientResponseException e) {
            String error = "WhatsApp API вернул HTTP " + e.getStatusCode().value();
            log.warn("{} для клиента {}. Ответ: {}", error, clientId, e.getResponseBodyAsString());
            return WhatsAppSendResult.error("http_error", error).toJson();
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

//    @Override
//    public String sendMessage(String clientId, String phone, String message) {
//        String normalized = normalizePhone(phone);
//        log.info("🚀 Попытка отправить сообщение через {} на {} (нормализовано: {})",
//                clientId, phone, normalized);
//
//        try {
//            String url = baseUrl(clientId) + "/send";
//            HttpEntity<String> request = jsonEntity(Map.of(
//                    "client", clientId,   // на Node не обязателен, но не мешает
//                    "phone", normalized,
//                    "message", message
//            ));
//
//            log.info("📦 POST {} payload: {}", url, request.getBody());
//            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
//            log.info("✅ Ответ от {}: {}", clientId, response.getBody());
//            return response.getBody() != null ? response.getBody() : "ok";
//        } catch (Exception e) {
//            log.error("❌ Ошибка при отправке через {}: {}", clientId, e.getMessage(), e);
//            return "{\"status\":\"error\",\"error\":\"" + e.getMessage() + "\"}";
//        }
//    }

    /**
     * Больше не используется в новой логике (отказались от проверки перед рассылкой).
     * Оставлено для обратной совместимости – можно удалить после ревизии.
     */
    @Deprecated
    public Optional<WhatsAppUserStatusDto> checkActiveUser(String clientId, String phone) {
        String normalized = normalizePhone(phone);
        String url = String.format("http://%s:3000/is-active-user?phone=%s", clientId, normalized);

        try {
            ResponseEntity<WhatsAppUserStatusDto> response =
                    restTemplate.getForEntity(url, WhatsAppUserStatusDto.class);

            WhatsAppUserStatusDto body = response.getBody();

            if (body != null && "ok".equals(body.getStatus())) {
                if (body.getRegistered() != null) {
                    return Optional.of(body);
                } else {
                    log.warn("📥 [ACTIVE USER] 'registered' = null для {}: {}", normalized, body);
                }
            } else {
                log.warn("📥 [ACTIVE USER] Неизвестный статус или пустой ответ для {}: {}", normalized, body);
            }
        } catch (Exception e) {
            log.warn("❌ [ACTIVE USER] Ошибка при запросе активности для {}: {}", normalized, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Тоже больше не используется в рассылке, оставлено как утилита.
     */
    @Deprecated
    private static final DateTimeFormatter IRKUTSK_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Irkutsk"));

    @Override
    @Deprecated
    public Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String clientId, String phone) {
        String normalized = normalizePhone(phone);
        String url = String.format("http://%s:3000/lastseen/%s", clientId, normalized);
        long start = System.currentTimeMillis();

        try {
            log.info("▶ [{}] Запрос статуса WhatsApp ({}), URL: {}", clientId, normalized, url);

            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null || !"ok".equals(body.get("status"))) {
                log.warn("📥 [{}] Не удалось получить статус для {}: {}", clientId, normalized, body);
                return Optional.empty();
            }

            boolean registered = Boolean.TRUE.equals(body.get("registered"));
            String rawLastSeen = (String) body.get("lastSeen");
            String stage = (String) body.getOrDefault("stage", "unknown");

            // парсер твоего проекта
            java.time.LocalDateTime parsedLastSeen = null;
            if (rawLastSeen != null && !rawLastSeen.isBlank()) {
                parsedLastSeen = LastSeenParser.parse(rawLastSeen).orElse(null);
            }

            String formattedLastSeen = parsedLastSeen != null ? parsedLastSeen.format(IRKUTSK_FORMAT) : null;

            log.info("✅ [{}] Статус для {} (stage={}): registered={}, lastSeen={}, rawLastSeen='{}' (elapsed: {} мс)",
                    clientId, normalized, stage, registered, formattedLastSeen, rawLastSeen, System.currentTimeMillis() - start);

            WhatsAppUserStatusDto dto = new WhatsAppUserStatusDto();
            dto.setStatus("ok");
            dto.setRegistered(registered);
            dto.setRawLastSeen(rawLastSeen);
            dto.setParsedLastSeen(parsedLastSeen);
            dto.setLastSeen(formattedLastSeen);

            if (registered && parsedLastSeen == null) {
                dto.setStatus("offline");
            }

            return Optional.of(dto);

        } catch (ResourceAccessException e) {
            log.error("⏱ [{}] Таймаут при запросе статуса для {}: {}", clientId, normalized, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("❌ [{}] Ошибка при получении статуса для {}: {}", clientId, normalized, e.getMessage());
            return Optional.empty();
        }
    }
}

    /**
     * Объединённый метод: проверяет регистрацию и получает lastSeen.
     */
    /**
     * Объединённый метод: проверяет регистрацию и получает lastSeen.
     * Node.js API возвращает {status:"ok", registered:true/false, lastSeen:"2025-07-21T07:06:00Z" или "сегодня в 07:06"}.
     *
     */
//    private static final DateTimeFormatter IRKUTSK_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
//            .withZone(ZoneId.of("Asia/Irkutsk"));

//    @Override
//    public Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String clientId, String phone) {
//        String url = String.format("http://%s:3000/lastseen/%s", clientId, phone);
//        long start = System.currentTimeMillis();
//
//        try {
//            log.info("▶ [{}] Запрос статуса WhatsApp ({}), URL: {}", clientId, phone, url);
//
//            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
//            Map<String, Object> body = response.getBody();
//
//            if (body == null || !"ok".equals(body.get("status"))) {
//                log.warn("📥 [{}] Не удалось получить статус для {}: {}", clientId, phone, body);
//                return Optional.empty();
//            }
//
//            boolean registered = Boolean.TRUE.equals(body.get("registered"));
//            String rawLastSeen = (String) body.get("lastSeen");
//            String stage = (String) body.getOrDefault("stage", "unknown");
//
//            LocalDateTime parsedLastSeen = null;
//            if (rawLastSeen != null && !rawLastSeen.isBlank()) {
//                parsedLastSeen = LastSeenParser.parse(rawLastSeen).orElse(null);
//            }
//
//            // Форматируем дату, если разобрана
//            String formattedLastSeen = parsedLastSeen != null ? parsedLastSeen.format(IRKUTSK_FORMAT) : null;
//
//            // Логируем этап и причину
//            log.info("✅ [{}] Статус для {} (stage={}): registered={}, lastSeen={}, rawLastSeen='{}' (elapsed: {} мс)",
//                    clientId, phone, stage, registered, formattedLastSeen, rawLastSeen, System.currentTimeMillis() - start);
//
//            if (!registered) {
//                log.info("ℹ [{}] Причина: номер не зарегистрирован (stage={}).", clientId, stage);
//            } else if (parsedLastSeen == null) {
//                log.info("ℹ [{}] Причина: lastSeen отсутствует (stage={}), считаем 'оффлайн'.", clientId, stage);
//            } else {
//                log.info("ℹ [{}] Причина: lastSeen найден (stage={}).", clientId, stage);
//            }
//
//            // Сохраняем результат в DTO
//            WhatsAppUserStatusDto dto = new WhatsAppUserStatusDto();
//            dto.setStatus("ok");
//            dto.setRegistered(registered);
//            dto.setRawLastSeen(rawLastSeen);
//            dto.setParsedLastSeen(parsedLastSeen);
//            dto.setLastSeen(formattedLastSeen);
//
//            // Если lastSeen не разобран — сразу выставляем "оффлайн"
//            if (registered && parsedLastSeen == null) {
//                dto.setStatus("offline");
//            }
//
//            return Optional.of(dto);
//
//        } catch (ResourceAccessException e) {
//            log.error("⏱ [{}] Таймаут при запросе статуса для {}: {}", clientId, phone, e.getMessage());
//            return Optional.empty();
//        } catch (Exception e) {
//            log.warn("❌ [{}] Ошибка при получении статуса для {}: {}", clientId, phone, e.getMessage());
//            return Optional.empty();
//        }
//    }







//    @Override
//    public Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String clientId, String phone) {
//        String url = String.format("http://%s:3000/lastseen/%s", clientId, phone);
//        long start = System.currentTimeMillis();
//
//        try {
//            log.info("▶ [{}] Запрос статуса WhatsApp ({}), URL: {}", clientId, phone, url);
//
//            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
//            Map<String, Object> body = response.getBody();
//
//            if (body == null || !"ok".equals(body.get("status"))) {
//                log.warn("📥 [{}] Не удалось получить статус для {}: {}", clientId, phone, body);
//                return Optional.empty();
//            }
//
//            boolean registered = Boolean.TRUE.equals(body.get("registered"));
//            String rawLastSeen = (String) body.get("lastSeen");
//            LocalDateTime parsedLastSeen = null;
//
//            // Если есть lastSeen — пробуем распарсить
//            if (rawLastSeen != null && !rawLastSeen.isBlank()) {
//                try {
//                    parsedLastSeen = LocalDateTime.parse(rawLastSeen); // ISO-формат
//                } catch (Exception e) {
//                    parsedLastSeen = LastSeenParser.parse(rawLastSeen).orElse(null);
//                }
//            }
//
//            // Логируем общую информацию и причину
//            log.info("✅ [{}] Статус получен для {}: registered={}, parsedLastSeen={}, rawLastSeen='{}' (elapsed: {} мс)",
//                    clientId, phone, registered, parsedLastSeen, rawLastSeen, System.currentTimeMillis() - start);
//
//            if (!registered) {
//                log.info("ℹ [{}] Причина: номер не зарегистрирован (проверка API или отсутствие header).", clientId);
//            } else if (rawLastSeen == null) {
//                log.info("ℹ [{}] Причина: зарегистрирован, но lastSeen отсутствует (возможно скрыт или приватность).", clientId);
//            } else {
//                log.info("ℹ [{}] Причина: зарегистрирован, статус обнаружен ('{}').", clientId, rawLastSeen);
//            }
//
//            // Формируем DTO
//            WhatsAppUserStatusDto dto = new WhatsAppUserStatusDto();
//            dto.setStatus("ok");
//            dto.setRegistered(registered);
//            dto.setRawLastSeen(rawLastSeen);
//            dto.setParsedLastSeen(parsedLastSeen);
//            dto.setLastSeen(parsedLastSeen != null ? parsedLastSeen.toString() : null);
//
//            return Optional.of(dto);
//
//        } catch (ResourceAccessException e) {
//            log.error("⏱ [{}] Таймаут при запросе статуса для {}: {}", clientId, phone, e.getMessage());
//            return Optional.empty();
//        } catch (Exception e) {
//            log.warn("❌ [{}] Ошибка при получении статуса для {}: {}", clientId, phone, e.getMessage());
//            return Optional.empty();
//        }
//    }





    /**
     * Получение lastSeen напрямую (если нужно).
     * Node.js API возвращает {status:"ok", lastSeen:"сегодня в 14:10"}.
     */
//    public Optional<LocalDateTime> fetchLastSeen(String clientId, String phone) {
//        String url = String.format("http://%s:3000/lastseen/%s", clientId, phone);
//        try {
//            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
//            Map<String, Object> body = response.getBody();
//
//            if (body == null) {
//                log.warn("📴 [LAST SEEN] Пустой ответ от {} для {}", clientId, phone);
//                return Optional.empty();
//            }
//
//            String raw = (String) body.get("lastSeen"); // а не "status"
//            if (raw == null || raw.isBlank()) {
//                log.info("📴 [{}] lastSeen скрыт или не найден для {}", clientId, phone);
//                return Optional.empty();
//            }
//
//            return LastSeenParser.parse(raw);
//
//        } catch (Exception e) {
//            log.warn("📴 [LAST SEEN] Ошибка при запросе lastSeen у клиента {} для {}: {}", clientId, phone, e.getMessage());
//            return Optional.empty();
//        }
//    }





