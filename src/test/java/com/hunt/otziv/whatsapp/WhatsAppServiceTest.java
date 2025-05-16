package com.hunt.otziv.whatsapp;

import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.service.WhatsAppServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

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
        config.setUrl("http://localhost:3000/send");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        String result = service.sendMessage("client1", "79086431055", "Тестовое сообщение");

        assertTrue(result.contains("⏩ Ответ"));
    }
}

