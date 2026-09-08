package com.hunt.otziv.whatsapp;

import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppChatMessageCursor;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppServiceTest {

    @Test
    void groupSendPreservesEnvelopeIdentityAcrossUtf8HttpEncoding() throws Exception {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("fixture"); config.setUrl("http://fixture:3000");
        when(properties.getClients()).thenReturn(List.of(config));
        String original = "fixture \ud800x\udfff \ud83d\ude80";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenAnswer(call -> {
            org.springframework.http.HttpEntity<?> request = call.getArgument(1);
            String wireMessage = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(request.getBody().toString().getBytes(StandardCharsets.UTF_8)).path("message").asText();
            assertEquals(original, wireMessage);
            assertEquals(com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope.groupHash("fixture", "12345678@g.us", original),
                    com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope.groupHash("fixture", "12345678@g.us", wireMessage));
            return ResponseEntity.ok("{\"status\":\"ok\"}");
        });
        service.sendMessageToGroup("fixture", "12345678@g.us", original, "wire-operation");
        org.mockito.Mockito.verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void timeoutKeepsOperationIdentityAndPerformsOnlyOneSend() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("fixture"); config.setUrl("http://fixture:3000");
        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("response lost"));
        String result = service.sendMessageToGroup("fixture", "123@g.us", "fixture", "durable-operation");
        assertEquals("operation_unknown", com.hunt.otziv.whatsapp.dto.WhatsAppSendResult.parse(result).code());
        org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> request = org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).postForEntity(eq("http://fixture:3000/send-group"), request.capture(), eq(String.class));
        assertTrue(request.getValue().getBody().toString().contains("\"operationId\":\"durable-operation\""));
    }

    @Test
    void malformedSuccessFragmentIsNotDeliveryEvidence() {
        var result = com.hunt.otziv.whatsapp.dto.WhatsAppSendResult.parse("broken {\"status\":\"ok\"");
        assertFalse(result.isOk());
        assertEquals("operation_unknown", result.code());
    }

    @Test
    void operationLookupDoesNotSendAgain() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("fixture"); config.setUrl("http://fixture:3000");
        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.getForObject("http://fixture:3000/operations/durable-operation", String.class))
                .thenReturn("{\"operationId\":\"durable-operation\",\"state\":\"SUCCEEDED\",\"messageId\":\"verified-message\"}");
        var status = service.getOperationStatus("fixture", "durable-operation");
        assertEquals("verified-message", status.messageId());
        org.junit.jupiter.api.Assertions.assertNull(status.envelopeHash());
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void operationLookupRetainsExactEnvelopeEvidence() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("fixture"); config.setUrl("http://fixture:3000");
        when(properties.getClients()).thenReturn(List.of(config));
        String hash = com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope.groupHash("fixture", "12345678@g.us", "fixture");
        when(restTemplate.getForObject("http://fixture:3000/operations/durable-operation", String.class))
                .thenReturn("{\"operationId\":\"durable-operation\",\"state\":\"SUCCEEDED\",\"messageId\":\"verified-message\",\"envelopeHash\":\"" + hash + "\"}");
        assertEquals(hash, service.getOperationStatus("fixture", "durable-operation").envelopeHash());
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"123", "{}", "[]", "\"short\"", "\"ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789\""})
    void operationLookupRejectsMalformedEnvelopeEvidence(String value) {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("fixture"); config.setUrl("http://fixture:3000");
        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.getForObject("http://fixture:3000/operations/durable-operation", String.class))
                .thenReturn("{\"operationId\":\"durable-operation\",\"state\":\"SUCCEEDED\",\"messageId\":\"verified-message\",\"envelopeHash\":" + value + "}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.getOperationStatus("fixture", "durable-operation"));
    }

    @Test
    void operationLookupRejectsNumericMessageIdAsDeliveryEvidence() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("fixture"); config.setUrl("http://fixture:3000");
        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.getForObject("http://fixture:3000/operations/durable-operation", String.class))
                .thenReturn("{\"operationId\":\"durable-operation\",\"state\":\"SUCCEEDED\",\"messageId\":123}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.getOperationStatus("fixture", "durable-operation"));
    }

    @Mock
    private WhatsAppProperties properties;

    @Mock
    private RestTemplate restTemplate;

    @Mock private com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations businessOperations;

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

        String result = service.sendMessage("client1", "79086431055", "Тестовое сообщение", "fixture-operation");

        assertTrue(result.contains("\"status\":\"ok\""));
    }

    @Test
    void sendMessageToGroup_unknownClientReturnsStructuredError() {
        when(properties.getClients()).thenReturn(List.of());

        String result = service.sendMessageToGroup("whatsapp_lika", "120@g.us", "Тест", "fixture-operation");

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

        String result = service.sendMessageToGroup("whatsapp_lika", "120@g.us", "Тест", "fixture-operation");

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

        String result = service.sendMessageToGroup("whatsapp_lika", "120@g.us", "Тест", "fixture-operation");

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

    @Test
    void resolveGroupByInviteReturnsExactGroupWithoutListingChats() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("whatsapp_vika");
        config.setUrl("http://whatsapp_vika:3000");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(
                eq("http://whatsapp_vika:3000/groups/resolve-invite"),
                any(),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("""
                {"status":"ok","group":{"groupId":"1203633063@g.us","name":"Drivevision","inviteLink":"https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw"}}
                """));

        Optional<WhatsAppGroupInfo> group = service.resolveGroupByInvite(
                "whatsapp_vika",
                "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw?s=cl&p=i"
        );

        assertTrue(group.isPresent());
        assertEquals("1203633063@g.us", group.get().groupId());
        assertEquals("Drivevision", group.get().name());
    }

    @Test
    void reconcileGroupMessagesReturnsGatewayHistory() {
        WhatsAppProperties.ClientConfig config = new WhatsAppProperties.ClientConfig();
        config.setId("whatsapp_vika");
        config.setUrl("http://whatsapp_vika:3000");

        when(properties.getClients()).thenReturn(List.of(config));
        when(restTemplate.postForEntity(
                eq("http://whatsapp_vika:3000/groups/reconcile-messages"),
                any(),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("""
                {
                  "status":"ok",
                  "clientId":"whatsapp_vika",
                  "messages":[{
                    "clientId":"whatsapp_vika",
                    "groupId":"1203633063@g.us",
                    "groupName":"Клиент",
                    "from":"70000000000@lid",
                    "fromName":"Мария",
                    "messageId":"message-42",
                    "timestamp":1785136200,
                    "fromMe":false,
                    "systemGenerated":false,
                    "message":"Ответ сотрудника"
                  }]
                }
                """));

        var messages = service.reconcileGroupMessages(
                "whatsapp_vika",
                List.of(new WhatsAppChatMessageCursor("1203633063@g.us", 1785132000L))
        );

        assertEquals(1, messages.size());
        assertEquals("message-42", messages.getFirst().messageId());
        assertEquals("Ответ сотрудника", messages.getFirst().message());
    }
}
