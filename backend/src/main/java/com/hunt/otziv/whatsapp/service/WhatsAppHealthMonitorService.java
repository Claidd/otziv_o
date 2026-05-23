package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Service
public class WhatsAppHealthMonitorService {

    private final WhatsAppProperties properties;
    private final RestTemplate restTemplate;

    public WhatsAppHealthMonitorService(
            WhatsAppProperties properties,
            @Qualifier("whatsAppRestTemplate") RestTemplate restTemplate
    ) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Scheduled(
            fixedDelayString = "${whatsapp.health-monitor.fixed-delay-ms:720000}",
            initialDelayString = "${whatsapp.health-monitor.initial-delay-ms:120000}"
    )
    public void checkAllClientsHealth() {
        WhatsAppProperties.HealthMonitor monitor = properties.getHealthMonitor();
        if (monitor == null || !monitor.isEnabled()) {
            return;
        }

        List<WhatsAppProperties.ClientConfig> clients = properties.getClients() != null
                ? properties.getClients()
                : List.of();
        if (clients.isEmpty()) {
            log.debug("WhatsApp health monitor skipped: no configured clients");
            return;
        }

        for (WhatsAppProperties.ClientConfig client : clients) {
            if (client == null || !hasText(client.getId()) || !hasText(client.getUrl())) {
                continue;
            }
            checkHealthForClient(client);
        }
    }



    private void checkHealthForClient(WhatsAppProperties.ClientConfig client) {
        String healthUrl = client.getUrl() + "/health";

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                handleUnavailableClient(client.getId(), "status=" + response.getStatusCode());
            } else {
                log.debug("WhatsApp client '{}' is healthy", client.getId());
            }
        } catch (Exception e) {
            handleUnavailableClient(client.getId(), e.getMessage());
        }
    }

    private void handleUnavailableClient(String clientId, String reason) {
        if (!properties.getHealthMonitor().isRestartEnabled()) {
            log.warn("WhatsApp client '{}' health check failed: {}. Docker restart is disabled", clientId, reason);
            return;
        }

        log.warn("WhatsApp client '{}' is unavailable: {}. Restarting container...", clientId, reason);
        restartWhatsAppContainer(clientId);
    }

    private void restartWhatsAppContainer(String containerName) {
        try {
            Process process = new ProcessBuilder("docker", "restart", containerName).start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Restarted WhatsApp container: {}", containerName);
            } else {
                log.error("Docker restart for WhatsApp container {} exited with code {}", containerName, exitCode);
            }
        } catch (Exception e) {
            log.error("Failed to restart WhatsApp container {}: {}", containerName, e.getMessage());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}



