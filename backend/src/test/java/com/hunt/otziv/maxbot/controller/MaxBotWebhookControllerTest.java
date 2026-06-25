package com.hunt.otziv.maxbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.maxbot.service.MaxBotUpdateService;
import com.hunt.otziv.webhook.security.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MaxBotWebhookControllerTest {

    private static final String SECRET = "secret-123";
    private static final String BODY = "{\"update_type\":\"message_created\",\"message\":{\"body\":{\"text\":\"/start abc\"}}}";

    @Mock
    private MaxBotUpdateService maxBotUpdateService;

    private WebhookSignatureVerifier signatureVerifier;
    private MaxBotWebhookController controller;

    @BeforeEach
    void setUp() {
        signatureVerifier = new WebhookSignatureVerifier();
        controller = new MaxBotWebhookController(maxBotUpdateService, new ObjectMapper(), signatureVerifier);
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
        ReflectionTestUtils.setField(controller, "hmacRequired", false);
    }

    @Test
    void acceptsValidSecretAndParsesBodyAfterAuthorization() {
        ResponseEntity<Void> response = controller.handleWebhook(SECRET, null, BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(maxBotUpdateService).handleUpdate(captor.capture());
        assertEquals("message_created", captor.getValue().path("update_type").asText());
    }

    @Test
    void rejectsInvalidSecretBeforeProcessingBody() {
        ResponseEntity<Void> response = controller.handleWebhook("wrong", null, BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(maxBotUpdateService, never()).handleUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsBadSignatureEvenWhenHmacIsOptional() {
        ResponseEntity<Void> response = controller.handleWebhook(SECRET, "sha256=bad", BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(maxBotUpdateService, never()).handleUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiresSignatureWhenConfigured() {
        ReflectionTestUtils.setField(controller, "hmacRequired", true);

        ResponseEntity<Void> missingSignature = controller.handleWebhook(SECRET, null, BODY);
        String signature = "sha256=" + signatureVerifier.hmacSha256Hex(BODY, SECRET);
        ResponseEntity<Void> validSignature = controller.handleWebhook(SECRET, signature, BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, missingSignature.getStatusCode());
        assertEquals(HttpStatus.OK, validSignature.getStatusCode());
    }

    @Test
    void rejectsWhenWebhookSecretIsNotConfigured() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        ResponseEntity<Void> response = controller.handleWebhook(SECRET, null, BODY);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(maxBotUpdateService, never()).handleUpdate(org.mockito.ArgumentMatchers.any());
    }
}
