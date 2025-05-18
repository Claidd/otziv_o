package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
}
