package com.hunt.otziv.whatsapp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.webhook.security.WebhookSignatureVerifier;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private static final String SECRET = "secret-123";
    private static final String BODY = "{\"clientId\":\"client-1\",\"from\":\"+79990000000\",\"message\":\"hello\"}";

    @Mock
    private ReplyService replyService;

    private WebhookSignatureVerifier signatureVerifier;
    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        signatureVerifier = new WebhookSignatureVerifier();
        controller = new WhatsAppWebhookController(replyService, new ObjectMapper(), signatureVerifier);
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
        ReflectionTestUtils.setField(controller, "hmacRequired", false);
    }

    @Test
    void acceptsValidSecretAndParsesBodyAfterAuthorization() {
        ResponseEntity<Void> response = controller.handleReply(request(), SECRET, null, BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<WhatsAppReplyDTO> captor = ArgumentCaptor.forClass(WhatsAppReplyDTO.class);
        verify(replyService).processIncomingReply(captor.capture());
        assertEquals("client-1", captor.getValue().getClientId());
        assertEquals("hello", captor.getValue().getMessage());
    }

    @Test
    void rejectsInvalidSecretBeforeProcessingBody() {
        ResponseEntity<Void> response = controller.handleReply(request(), "wrong", null, BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(replyService, never()).processIncomingReply(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsBadSignatureEvenWhenHmacIsOptional() {
        ResponseEntity<Void> response = controller.handleReply(request(), SECRET, "sha256=bad", BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(replyService, never()).processIncomingReply(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiresSignatureWhenConfigured() {
        ReflectionTestUtils.setField(controller, "hmacRequired", true);

        ResponseEntity<Void> missingSignature = controller.handleReply(request(), SECRET, null, BODY);
        String signature = "sha256=" + signatureVerifier.hmacSha256Hex(BODY, SECRET);
        ResponseEntity<Void> validSignature = controller.handleReply(request(), SECRET, signature, BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, missingSignature.getStatusCode());
        assertEquals(HttpStatus.OK, validSignature.getStatusCode());
    }

    @Test
    void rejectsWhenWebhookSecretIsNotConfigured() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        ResponseEntity<Void> response = controller.handleReply(request(), SECRET, null, BODY);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(replyService, never()).processIncomingReply(org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        return request;
    }
}
