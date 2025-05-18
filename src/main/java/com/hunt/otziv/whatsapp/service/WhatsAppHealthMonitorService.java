package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppHealthMonitorService {

    private final WhatsAppProperties properties;
    private final RestTemplate restTemplate;

    @Scheduled(fixedDelay = 360000) // каждый 60 секунд
    public void checkAllClientsHealth() {
        for (WhatsAppProperties.ClientConfig client : properties.getClients()) {
            checkHealthForClient(client);
        }
    }

    private void checkHealthForClient(WhatsAppProperties.ClientConfig client) {
        String healthUrl = client.getUrl() + "/health";

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("❗ WhatsApp клиент '{}' недоступен, перезапускаем...", client.getId());
                restartWhatsAppContainer(client.getId());
            } else {
                log.info("✅ WhatsApp клиент '{}' доступен", client.getId());
            }
        } catch (Exception e) {
            log.error("❌ Ошибка при проверке {}: {}", client.getId(), e.getMessage());
            restartWhatsAppContainer(client.getId());
        }
    }

    private void restartWhatsAppContainer(String containerName) {
        try {
            Process process = Runtime.getRuntime().exec("docker restart " + containerName);
            process.waitFor();
            log.info("♻️ Перезапущен контейнер: {}", containerName);
        } catch (Exception e) {
            log.error("❌ Не удалось перезапустить контейнер {}: {}", containerName, e.getMessage());
        }
    }
}



