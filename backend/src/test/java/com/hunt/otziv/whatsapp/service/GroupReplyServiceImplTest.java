package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class GroupReplyServiceImplTest {

    @Mock
    private CompanyService companyService;
    @Mock
    private WhatsAppGroupCompanyLinker groupCompanyLinker;
    @Mock
    private PublicationProgressPreferenceService publicationProgressPreferenceService;
    @Mock
    private WhatsAppInboundReplyOutbox replyOutbox;
    @Mock
    private ClientChatMessageTrackerService clientChatMessageTrackerService;
    @Mock private WhatsAppUnlinkedEventStore unlinkedEvents;

    @InjectMocks
    private GroupReplyServiceImpl service;

    @org.junit.jupiter.api.Test void unknownCompanyIsDurablyDeferredBeforeAcknowledgment() {
        var reply=new WhatsAppGroupReplyDTO();reply.setClientId("client");reply.setGroupId("unlinked@g.us");
        reply.setMessageId("message");reply.setMessage("Нужен ответ");
        service.processGroupReply(reply);
        org.mockito.Mockito.verify(unlinkedEvents).defer(reply,"company_missing");
        verifyNoInteractions(clientChatMessageTrackerService,replyOutbox);
        org.mockito.Mockito.verify(unlinkedEvents,org.mockito.Mockito.never()).resolved(org.mockito.ArgumentMatchers.any());
    }

    @org.junit.jupiter.api.Test void queueWriteFailurePreventsSuccessfulReceipt() {
        var reply=new WhatsAppGroupReplyDTO();reply.setGroupId("unlinked@g.us");reply.setMessage("Нужен ответ");
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable")).when(unlinkedEvents)
                .defer(reply,"company_missing");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.processGroupReply(reply)).hasMessage("database unavailable");
    }

    @org.junit.jupiter.api.Test void oldGroupMessageCannotBindAnUnverifiedChangedInviteByPhoneAndTitle() {
        var reply = new WhatsAppGroupReplyDTO(); reply.setGroupId("111@g.us");
        reply.setGroupName("Архидек. Отзывы"); reply.setFrom("70000000000@c.us"); reply.setMessage("hello");
        var company = new com.hunt.otziv.c_companies.model.Company(); company.setWhatsAppInvitePending(true);
        org.mockito.Mockito.when(companyService.getCompanyByTelephonAndTitle("70000000000", "Архидек"))
                .thenReturn(java.util.Optional.of(company));
        service.processGroupReply(reply);
        org.junit.jupiter.api.Assertions.assertNull(company.getGroupId());
        org.mockito.Mockito.verify(companyService,org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(com.hunt.otziv.c_companies.model.Company.class));
        verifyNoInteractions(replyOutbox,clientChatMessageTrackerService);
    }

    @org.junit.jupiter.api.Test void preferenceWithoutDurableInboundIdIsRejectedBeforeBusinessWrites() {
        var reply=new WhatsAppGroupReplyDTO();reply.setMessage("отключить уведомления");
        org.mockito.Mockito.when(publicationProgressPreferenceService.isPreferenceCommand(reply.getMessage())).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.processGroupReply(reply))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("messageId is required");
        verifyNoInteractions(companyService,groupCompanyLinker,replyOutbox,clientChatMessageTrackerService);
        org.mockito.Mockito.verify(publicationProgressPreferenceService,org.mockito.Mockito.never()).handleWhatsAppCommand(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[Вложение: broadcast_notification]",
            "[Вложение: ciphertext]",
            "[Вложение: debug]",
            "[Вложение: e2e_notification]",
            "[Вложение: gp2]",
            "[Вложение: group_notification]",
            "[Вложение: notification]",
            "[Вложение: notification_template]",
            "[Вложение: protocol]"
    })
    void ignoresSystemNotificationPlaceholders(String message) {
        WhatsAppGroupReplyDTO reply = new WhatsAppGroupReplyDTO();
        reply.setGroupId("120363000000000000@g.us");
        reply.setMessage(message);

        service.processGroupReply(reply);

        verifyNoInteractions(
                companyService,
                groupCompanyLinker,
                publicationProgressPreferenceService,
                replyOutbox,
                clientChatMessageTrackerService
        );
    }

    @org.junit.jupiter.api.Test void trackingFailurePropagatesSoReceiptRollsBack() {
        var reply = new WhatsAppGroupReplyDTO(); reply.setClientId("client"); reply.setGroupId("1@g.us");
        reply.setMessageId("first"); reply.setMessage("hello");
        var company = new com.hunt.otziv.c_companies.model.Company(); company.setGroupId("1@g.us");
        org.mockito.Mockito.when(companyService.findByGroupId("1@g.us")).thenReturn(java.util.Optional.of(company));
        org.mockito.Mockito.doThrow(new IllegalStateException("tracking failed")).when(clientChatMessageTrackerService)
                .track(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.processGroupReply(reply)).hasMessage("tracking failed");
    }

    @org.junit.jupiter.api.Test void groupEditIsOnlyRecordedAsAnEditOfTheOriginalMessage() {
        var edit = new WhatsAppGroupReplyDTO(); edit.setClientId("whatsapp_lika"); edit.setGroupId("1@g.us");
        edit.setMessageId("m-1:edit:1700000123456"); edit.setEditOf("m-1"); edit.setEditedAt(1_700_000_123L);
        edit.setMessage("Здравствуйте. Следующая публикация будет 22.10");

        service.processGroupEdit(edit);

        var captor = org.mockito.ArgumentCaptor.forClass(com.hunt.otziv.client_chat_control.dto.ClientChatMessageEditCommand.class);
        org.mockito.Mockito.verify(clientChatMessageTrackerService).trackEdit(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("m-1", captor.getValue().externalMessageId());
        org.junit.jupiter.api.Assertions.assertEquals("1@g.us", captor.getValue().chatId());
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(1_700_000_123L), java.time.ZoneId.systemDefault()), captor.getValue().editedAt());
        org.mockito.Mockito.verify(clientChatMessageTrackerService, org.mockito.Mockito.never())
                .track(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(companyService, groupCompanyLinker, publicationProgressPreferenceService, replyOutbox, unlinkedEvents);

        var reply = new WhatsAppGroupReplyDTO("whatsapp_lika", "1@g.us", null, null, null,
                "m-1:edit:1700000123456", null, false, null, "x");
        org.junit.jupiter.api.Assertions.assertNotEquals(WhatsAppGroupWebhookDeduplicator.key(reply),
                WhatsAppGroupWebhookDeduplicator.key(edit));
    }

    @org.junit.jupiter.api.Test void firstMessageIsTrackedAfterCompanyIsLinkedByGroupName() {
        var reply = new WhatsAppGroupReplyDTO(); reply.setClientId("client"); reply.setGroupId("1@g.us");
        reply.setGroupName("Company"); reply.setMessageId("first"); reply.setMessage("hello");
        var company = new com.hunt.otziv.c_companies.model.Company(); company.setGroupId("1@g.us");
        org.mockito.Mockito.when(companyService.findByGroupId("1@g.us"))
                .thenReturn(java.util.Optional.empty(), java.util.Optional.of(company));
        org.mockito.Mockito.when(groupCompanyLinker.linkByGroupName("1@g.us", "Company")).thenReturn(1);
        service.processGroupReply(reply);
        org.mockito.Mockito.verify(clientChatMessageTrackerService).track(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
