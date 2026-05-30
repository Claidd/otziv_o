package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class WhatsAppHealthMonitorService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WhatsAppProperties properties;
    private final RestTemplate restTemplate;
    private final ManagerRepository managerRepository;
    private final WhatsAppAuthAlertService whatsAppAuthAlertService;

    public WhatsAppHealthMonitorService(
            WhatsAppProperties properties,
            @Qualifier("whatsAppRestTemplate") RestTemplate restTemplate,
            ManagerRepository managerRepository,
            WhatsAppAuthAlertService whatsAppAuthAlertService
    ) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.managerRepository = managerRepository;
        this.whatsAppAuthAlertService = whatsAppAuthAlertService;
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
            } else if (looksLikeAuthUnavailable(response.getBody())) {
                notifyManagersAboutAuthIssue(client.getId(), response.getBody());
            } else {
                notifyManagersAboutRecovery(client.getId());
                log.debug("WhatsApp client '{}' is healthy", client.getId());
            }
        } catch (Exception e) {
            handleUnavailableClient(client.getId(), e.getMessage());
        }
    }

    private boolean looksLikeAuthUnavailable(String body) {
        if (!hasText(body)) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.has("authenticated") && !node.path("authenticated").asBoolean(true)) {
                return true;
            }
            if ("qr".equalsIgnoreCase(node.path("state").asText(null))
                    || "qr".equalsIgnoreCase(node.path("status").asText(null))) {
                return true;
            }
            if (node.has("hasQr") && node.path("hasQr").asBoolean(false)) {
                return true;
            }
        } catch (Exception ignored) {
            // Fall back to text markers below.
        }
        String normalized = body.toLowerCase(Locale.ROOT);
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

    private void notifyManagersAboutAuthIssue(String clientId, String body) {
        List<Manager> managers = managerRepository.findAllByClientIdWithUser(clientId);
        whatsAppAuthAlertService.notifyAuthIssue(
                clientId,
                null,
                "health monitor",
                "whatsapp_not_ready",
                limit(body, 300),
                LocalDateTime.now().withNano(0),
                null,
                managers
        );
    }

    private void notifyManagersAboutRecovery(String clientId) {
        List<Manager> managers = managerRepository.findAllByClientIdWithUser(clientId);
        whatsAppAuthAlertService.notifyRecovered(
                clientId,
                "health monitor",
                LocalDateTime.now().withNano(0),
                managers
        );
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
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



