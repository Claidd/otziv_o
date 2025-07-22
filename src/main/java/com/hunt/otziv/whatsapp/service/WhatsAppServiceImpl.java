package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppUserStatusDto;
import com.hunt.otziv.whatsapp.service.fichi.LastSeenParser;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppServiceImpl implements WhatsAppService {
    private final WhatsAppProperties properties;
    private final RestTemplate restTemplate;

    public String sendMessageToGroup(String clientId, String groupId, String message) {
        log.info("🚀 Попытка отправить сообщение в группу через {} на {}", clientId, groupId);

        if (groupId == null) {
            log.error("❌ groupId равен null. Сообщение не может быть отправлено.");
            return "❌ Ошибка: groupId не должен быть null";
        }

        Optional<WhatsAppProperties.ClientConfig> clientOpt = properties.getClients()
                .stream()
                .filter(c -> c.getId().equals(clientId))
                .findFirst();

        if (clientOpt.isEmpty()) {
            log.warn("❌ Неизвестный клиент: {}", clientId);
            return "❌ Неизвестный клиент: " + clientId;
        }

        String url = clientOpt.get().getUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        log.info("➡️ URL: {}", url + "/send-group");

        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(Map.of(
                    "groupId", groupId,
                    "message", message
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            log.info("📦 Отправляем запрос на {}, payload: {}", url, jsonBody);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url + "/send-group", request, String.class
            );

            log.info("✅ Ответ от {}: {}", clientId, response.getBody());
            return "⏩ Ответ: " + response.getBody();
        } catch (Exception e) {
            log.error("❌ Ошибка при отправке сообщения в группу через {}: {}", clientId, e.getMessage(), e);
            return "❌ Ошибка: " + e.getMessage();
        }
    }


    public String sendMessage(String clientId, String phone, String message) {
        log.info("🚀 Попытка отправить сообщение через {} на {}", clientId, phone);

        Optional<WhatsAppProperties.ClientConfig> clientOpt = properties.getClients()
                .stream()
                .filter(c -> c.getId().equals(clientId))
                .findFirst();

        if (clientOpt.isEmpty()) {
            log.warn("❌ Неизвестный клиент: {}", clientId);
            return "❌ Неизвестный клиент: " + clientId;
        }

        String url = clientOpt.get().getUrl();
        log.info("➡️ URL: {}", url + "/send");

        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(Map.of(
                    "client", clientId,
                    "phone", phone,
                    "message", message
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            log.info("📦 Отправляем запрос на {}, payload: {}", url, jsonBody);

//            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url + "/send", request, String.class
            );


            log.info("✅ Ответ от {}: {}", clientId, response.getBody());
            return "⏩ Ответ: " + response.getBody();
        } catch (Exception e) {
            log.error("❌ Ошибка при отправке сообщения через {}: {}", clientId, e.getMessage(), e);
            return "❌ Ошибка: " + e.getMessage();
        }
    }

    public Optional<WhatsAppUserStatusDto> checkActiveUser(String clientId, String phone) {
        String url = String.format("http://%s:3000/is-active-user?phone=%s", clientId, phone);

        try {
            ResponseEntity<WhatsAppUserStatusDto> response =
                    restTemplate.getForEntity(url, WhatsAppUserStatusDto.class);

            WhatsAppUserStatusDto body = response.getBody();

            if (body != null && "ok".equals(body.getStatus())) {
                if (body.getRegistered() != null) {
                    return Optional.of(body);
                } else {
                    log.warn("📥 [ACTIVE USER] Ответ получен, но поле 'registered' = null для {}: {}", phone, body);
                }
            } else {
                log.warn("📥 [ACTIVE USER] Неизвестный статус или пустой ответ для {}: {}", phone, body);
            }
        } catch (Exception e) {
            log.warn("❌ [ACTIVE USER] Ошибка при запросе активности для {}: {}", phone, e.getMessage());
        }

        return Optional.empty();
    }



    /**
     * Объединённый метод: проверяет регистрацию и получает lastSeen.
     */
    /**
     * Объединённый метод: проверяет регистрацию и получает lastSeen.
     * Node.js API возвращает {status:"ok", registered:true/false, lastSeen:"2025-07-21T07:06:00Z" или "сегодня в 07:06"}.
     *
     */
    private static final DateTimeFormatter IRKUTSK_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Irkutsk"));

    @Override
    public Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String clientId, String phone) {
        String url = String.format("http://%s:3000/lastseen/%s", clientId, phone);
        long start = System.currentTimeMillis();

        try {
            log.info("▶ [{}] Запрос статуса WhatsApp ({}), URL: {}", clientId, phone, url);

            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null || !"ok".equals(body.get("status"))) {
                log.warn("📥 [{}] Не удалось получить статус для {}: {}", clientId, phone, body);
                return Optional.empty();
            }

            boolean registered = Boolean.TRUE.equals(body.get("registered"));
            String rawLastSeen = (String) body.get("lastSeen");
            String stage = (String) body.getOrDefault("stage", "unknown");

            LocalDateTime parsedLastSeen = null;
            if (rawLastSeen != null && !rawLastSeen.isBlank()) {
                parsedLastSeen = LastSeenParser.parse(rawLastSeen).orElse(null);
            }

            // Форматируем дату, если разобрана
            String formattedLastSeen = parsedLastSeen != null ? parsedLastSeen.format(IRKUTSK_FORMAT) : null;

            // Логируем этап и причину
            log.info("✅ [{}] Статус для {} (stage={}): registered={}, lastSeen={}, rawLastSeen='{}' (elapsed: {} мс)",
                    clientId, phone, stage, registered, formattedLastSeen, rawLastSeen, System.currentTimeMillis() - start);

            if (!registered) {
                log.info("ℹ [{}] Причина: номер не зарегистрирован (stage={}).", clientId, stage);
            } else if (parsedLastSeen == null) {
                log.info("ℹ [{}] Причина: lastSeen отсутствует (stage={}), считаем 'оффлайн'.", clientId, stage);
            } else {
                log.info("ℹ [{}] Причина: lastSeen найден (stage={}).", clientId, stage);
            }

            // Сохраняем результат в DTO
            WhatsAppUserStatusDto dto = new WhatsAppUserStatusDto();
            dto.setStatus("ok");
            dto.setRegistered(registered);
            dto.setRawLastSeen(rawLastSeen);
            dto.setParsedLastSeen(parsedLastSeen);
            dto.setLastSeen(formattedLastSeen);

            // Если lastSeen не разобран — сразу выставляем "оффлайн"
            if (registered && parsedLastSeen == null) {
                dto.setStatus("offline");
            }

            return Optional.of(dto);

        } catch (ResourceAccessException e) {
            log.error("⏱ [{}] Таймаут при запросе статуса для {}: {}", clientId, phone, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("❌ [{}] Ошибка при получении статуса для {}: {}", clientId, phone, e.getMessage());
            return Optional.empty();
        }
    }







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
    public Optional<LocalDateTime> fetchLastSeen(String clientId, String phone) {
        String url = String.format("http://%s:3000/lastseen/%s", clientId, phone);
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null) {
                log.warn("📴 [LAST SEEN] Пустой ответ от {} для {}", clientId, phone);
                return Optional.empty();
            }

            String raw = (String) body.get("lastSeen"); // а не "status"
            if (raw == null || raw.isBlank()) {
                log.info("📴 [{}] lastSeen скрыт или не найден для {}", clientId, phone);
                return Optional.empty();
            }

            return LastSeenParser.parse(raw);

        } catch (Exception e) {
            log.warn("📴 [LAST SEEN] Ошибка при запросе lastSeen у клиента {} для {}: {}", clientId, phone, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Boolean> isRegisteredInWhatsApp(String clientId, String phone) {
        String url = String.format("http://%s:3000/check-registered?phone=%s", clientId, phone);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode node = new ObjectMapper().readTree(response.getBody());
                if ("ok".equals(node.get("status").asText())) {
                    return Optional.of(node.get("registered").asBoolean());
                }
            }
        } catch (Exception e) {
            log.warn("❗ Ошибка при проверке регистрации WhatsApp: {}", e.getMessage());
        }
        return Optional.empty(); // если ошибка — пусть решает основная логика
    }


}
