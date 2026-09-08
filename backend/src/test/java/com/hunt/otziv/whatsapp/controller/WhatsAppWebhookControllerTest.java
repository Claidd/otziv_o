package com.hunt.otziv.whatsapp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.webhook.security.WebhookSignatureVerifier;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupWebhookDeduplicator;
import com.hunt.otziv.whatsapp.service.service.GroupReplyService;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {
    private static final String SECRET = "secret-123";

    @Mock
    private GroupReplyService groupReplyService;
    @Mock
    private WhatsAppGroupWebhookDeduplicator groupWebhookDeduplicator;

    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WhatsAppWebhookController(
                groupReplyService,
                new ObjectMapper(),
                new WebhookSignatureVerifier(),
                groupWebhookDeduplicator
        );
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
        ReflectionTestUtils.setField(controller, "hmacRequired", false);
        org.mockito.Mockito.lenient().when(groupWebhookDeduplicator.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> { call.getArgument(1, Runnable.class).run(); return WhatsAppGroupWebhookDeduplicator.Result.APPLIED; });
    }

    @Test
    void parsesGatewaySystemMessageClassification() {
        String body = "{\"clientId\":\"whatsapp_vika\",\"groupId\":\"12001@g.us\","
                + "\"messageId\":\"message-1\",\"fromMe\":true,\"systemGenerated\":true,"
                + "\"message\":\"Автоматический отчет\"}";

        ResponseEntity<Void> response = controller.handleGroupReply(request(), SECRET, null, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<WhatsAppGroupReplyDTO> captor = ArgumentCaptor.forClass(WhatsAppGroupReplyDTO.class);
        verify(groupReplyService).processGroupReply(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getSystemGenerated());
        assertEquals("message-1", captor.getValue().getMessageId());
    }

    @Test
    void ignoresRepeatedGroupWebhookAfterSuccessfulDelivery() {
        String body = "{\"clientId\":\"whatsapp_vika\",\"groupId\":\"12001@g.us\","
                + "\"messageId\":\"message-1\",\"message\":\"Отключить уведомления\"}";
        org.mockito.Mockito.doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return WhatsAppGroupWebhookDeduplicator.Result.APPLIED; })
                .doReturn(WhatsAppGroupWebhookDeduplicator.Result.DUPLICATE)
                .when(groupWebhookDeduplicator).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        ResponseEntity<Void> first = controller.handleGroupReply(request(), SECRET, null, body);
        ResponseEntity<Void> duplicate = controller.handleGroupReply(request(), SECRET, null, body);

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, duplicate.getStatusCode());
        verify(groupReplyService, org.mockito.Mockito.times(1))
                .processGroupReply(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidGroupSecret() {
        String body = "{\"clientId\":\"whatsapp_vika\",\"groupId\":\"12001@g.us\","
                + "\"messageId\":\"message-1\",\"message\":\"hello\"}";

        ResponseEntity<Void> response = controller.handleGroupReply(request(), "wrong", null, body);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        org.mockito.Mockito.verifyNoInteractions(groupWebhookDeduplicator, groupReplyService);
    }

    @Test void inProgressMustNotAcknowledgeAnUncommittedEffect() {
        org.mockito.Mockito.doReturn(WhatsAppGroupWebhookDeduplicator.Result.IN_PROGRESS)
                .when(groupWebhookDeduplicator).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var response = controller.handleGroupReply(request(), SECRET, null,
                "{\"clientId\":\"a\",\"groupId\":\"1@g.us\",\"messageId\":\"m\",\"message\":\"hello\"}");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        org.mockito.Mockito.verifyNoInteractions(groupReplyService);
    }

    @Test void missingStableIdentityIsRejectedBeforeAnyEffect() {
        var response = controller.handleGroupReply(request(), SECRET, null,
                "{\"clientId\":\"a\",\"groupId\":\"1@g.us\",\"message\":\"hello\"}");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        org.mockito.Mockito.verifyNoInteractions(groupWebhookDeduplicator, groupReplyService);
    }

    @Test void processingOrCommitFailureCannotBecomeAnAck() {
        org.mockito.Mockito.doThrow(new IllegalStateException("commit failure"))
                .when(groupWebhookDeduplicator).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> controller.handleGroupReply(request(), SECRET, null,
                "{\"clientId\":\"a\",\"groupId\":\"1@g.us\",\"messageId\":\"m\",\"message\":\"hello\"}"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        return request;
    }
}
