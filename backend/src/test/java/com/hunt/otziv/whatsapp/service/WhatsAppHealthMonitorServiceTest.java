package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppHealthMonitorServiceTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private WhatsAppAuthAlertService whatsAppAuthAlertService;

    @Test
    void healthMonitorAlertsWhenClientWaitsForQr() {
        WhatsAppHealthMonitorService service = service();
        when(restTemplate.getForEntity("http://whatsapp_lika:3000/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"not_ready\",\"authenticated\":false,\"state\":\"qr\",\"hasQr\":true}"));
        when(managerRepository.findAllByClientIdWithUser("whatsapp_lika")).thenReturn(List.of());

        service.checkAllClientsHealth();

        verify(whatsAppAuthAlertService).notifyAuthIssue(
                eq("whatsapp_lika"),
                eq(null),
                eq("health monitor"),
                eq("whatsapp_not_ready"),
                any(),
                any(),
                eq(null),
                any()
        );
        verify(whatsAppAuthAlertService, never()).notifyRecovered(any(), any(), any(), any());
    }

    @Test
    void healthMonitorDoesNotAlertDuringStartupWarmup() {
        WhatsAppHealthMonitorService service = service();
        when(restTemplate.getForEntity("http://whatsapp_lika:3000/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"not_ready\",\"authenticated\":true,\"state\":\"authenticated\",\"hasQr\":false,\"message\":\"WhatsApp client is not ready\"}"));
        when(managerRepository.findAllByClientIdWithUser("whatsapp_lika")).thenReturn(List.of());

        service.checkAllClientsHealth();

        verify(whatsAppAuthAlertService, never()).notifyAuthIssue(any(), any(), any(), any(), any(), any(), any(), any());
        verify(whatsAppAuthAlertService).notifyRecovered(
                eq("whatsapp_lika"),
                eq("health monitor"),
                any(),
                any()
        );
    }

    private WhatsAppHealthMonitorService service() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.getHealthMonitor().setEnabled(true);
        properties.getHealthMonitor().setRestartEnabled(false);
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));
        return new WhatsAppHealthMonitorService(properties, restTemplate, managerRepository, whatsAppAuthAlertService);
    }
}
