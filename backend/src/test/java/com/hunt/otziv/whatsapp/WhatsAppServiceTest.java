package com.hunt.otziv.whatsapp;

import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.service.WhatsAppServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppServiceTest {

    @Mock
    private WhatsAppProperties properties;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private WhatsAppServiceImpl service; // вместо WhatsAppService

    @Test
    void testSendMessage_success() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("client1");
        config.setUrl("http://localhost:3000");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        String result = service.sendMessage("client1", "79086431055", "Тестовое сообщение");

        assertTrue(result.contains("\"status\":\"ok\""));
    }

    @Test
    void sendMessageToGroup_unknownClientReturnsStructuredError() {
        when(properties.getClients()).thenReturn(List.of());

        String result = service.sendMessageToGroup("whatsapp_lika", "120@g.us", "Тест");

        assertTrue(result.contains("\"status\":\"error\""));
        assertTrue(result.contains("\"code\":\"unknown_client\""));
        assertTrue(result.contains("whatsapp_lika"));
    }

    @Test
    void sendMessageToGroup_notReadyHttpBodyReturnsAuthCode() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("whatsapp_lika");
        config.setUrl("http://localhost:3000");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable",
                        "{\"status\":\"not_ready\",\"authenticated\":false,\"state\":\"qr\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                ));

        String result = service.sendMessageToGroup("whatsapp_lika", "120@g.us", "Тест");

        assertTrue(result.contains("\"status\":\"error\""));
        assertTrue(result.contains("\"code\":\"whatsapp_not_ready\""));
        assertTrue(result.contains("not_ready"));
    }

    @Test
    void sendMessageToGroup_startupNotReadyHttpBodyDoesNotReturnAuthCode() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("whatsapp_lika");
        config.setUrl("http://localhost:3000");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable",
                        "{\"status\":\"not_ready\",\"authenticated\":true,\"state\":\"authenticated\",\"hasQr\":false}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                ));

        String result = service.sendMessageToGroup("whatsapp_lika", "120@g.us", "Тест");

        assertTrue(result.contains("\"status\":\"error\""));
        assertTrue(result.contains("\"code\":\"not_ready\""));
        assertFalse(result.contains("\"code\":\"whatsapp_not_ready\""));
    }

    @Test
    void getClientStatus_returnsQrFromGateway() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("whatsapp_lika");
        config.setUrl("http://whatsapp_lika:3000");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.getForEntity("http://whatsapp_lika:3000/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"clientId\":\"whatsapp_lika\",\"ready\":false,\"authenticated\":false,\"state\":\"qr\",\"hasQr\":true}"));
        when(restTemplate.getForEntity("http://whatsapp_lika:3000/qr", String.class))
                .thenReturn(ResponseEntity.ok("{\"clientId\":\"whatsapp_lika\",\"ready\":false,\"authenticated\":false,\"state\":\"qr\",\"hasQr\":true,\"qrDataUrl\":\"data:image/png;base64,abc\"}"));

        var status = service.getClientStatus("whatsapp_lika");

        assertTrue(status.configured());
        assertTrue(status.hasQr());
        assertTrue(status.qrDataUrl().contains("data:image/png"));
    }

    @Test
    void getClientStatus_unknownClientIsNotConfigured() {
        when(properties.getClients()).thenReturn(List.of());

        var status = service.getClientStatus("whatsapp_lika");

        assertTrue(!status.configured());
        assertTrue(status.message().contains("whatsapp_lika"));
    }
}

