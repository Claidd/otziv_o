package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ClientChatMessageTrackerServiceTest {

    @Mock private ClientChatMessageRepository messageRepository;
    @Mock private ClientChatUnansweredItemRepository unansweredRepository;
    @Mock private ClientChatParticipantClassifier participantClassifier;
    @Mock private ClientChatAutoIgnoreService autoIgnoreService;
    @Mock private ClientChatCompanyResolutionService companyResolutionService;
    @Mock private AppSettingService appSettingService;
    @Mock private GamificationEventService gamificationEventService;
    @Mock private ClientChatIdentityService identityService;
    @Mock private ClientChatNoResponseAiReviewService noResponseAiReviewService;

    @Mock private org.springframework.context.ApplicationEventPublisher events;

    private ClientChatMessageTrackerService service;

    @BeforeEach
    void setUp() {
        service = new ClientChatMessageTrackerService(
                messageRepository,
                unansweredRepository,
                participantClassifier,
                autoIgnoreService,
                companyResolutionService,
                appSettingService,
                gamificationEventService,
                identityService,
                new ClientChatResolutionPolicy(),
                new ClientChatReplyQualityService(),
                noResponseAiReviewService,
                events
        );
        lenient().when(appSettingService.getBoolean("manager-control.unanswered-client-messages.enabled", true)).thenReturn(true);
        lenient().when(appSettingService.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_GUARD_ENABLED,
                false
        )).thenReturn(false);
        lenient().when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(any(), any(), any()))
                .thenReturn(Optional.empty());
        Company company = new Company();
        company.setTitle("Компания");
        Manager manager = new Manager();
        lenient().when(companyResolutionService.resolve(ClientChatPlatform.WHATSAPP, "12001@g.us"))
                .thenReturn(new ClientChatCompanyResolutionService.Resolution(company, manager, List.of(company), false));
        lenient().when(messageRepository.save(any(ClientChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void explicitStaffReplyClosesOpenUnansweredItemEvenWhenDirectionIsOutgoing() {
        ClientChatUnansweredItem open = new ClientChatUnansweredItem();
        open.setStatus(ClientChatUnansweredStatus.OPEN);
        open.setFirstOpenedAt(LocalDateTime.now().minusHours(1));
        when(unansweredRepository.findByPlatformAndChatIdAndStatus(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                ClientChatUnansweredStatus.OPEN
        )).thenReturn(List.of(open));

        service.track(command("manual-1"), ClientChatSenderRole.STAFF);

        assertEquals(ClientChatUnansweredStatus.ANSWERED, open.getStatus());
        assertEquals("Ответ сотрудника", open.getCloseReason());
        assertEquals(ClientChatSenderRole.STAFF, open.getResolutionMessage().getSenderRole());
        verify(unansweredRepository).save(open);
    }

    @Test
    void emojiOnlyStaffMessageDoesNotCloseClientQuestion() {
        ClientChatMessageCommand reaction = new ClientChatMessageCommand(
                ClientChatPlatform.WHATSAPP, ClientChatDirection.OUTGOING,
                "12001@g.us", "Компания", "reaction-1", "12001@g.us",
                "Менеджер", "👌", LocalDateTime.now());

        service.track(reaction, ClientChatSenderRole.STAFF);

        verify(messageRepository).save(any(ClientChatMessage.class));
        verify(unansweredRepository, never()).findByPlatformAndChatIdAndStatus(
                ClientChatPlatform.WHATSAPP, "12001@g.us", ClientChatUnansweredStatus.OPEN);
    }

    @Test
    void incomingOwnerMessageIsIgnoredAndDoesNotCloseManagersOpenClientCard() {
        Role ownerRole = new Role();
        ownerRole.setName("ROLE_OWNER");
        User owner = User.builder().id(9L).active(true).roles(Set.of(ownerRole)).build();
        when(participantClassifier.resolveStaffUser(
                eq(ClientChatPlatform.WHATSAPP),
                eq("12001@g.us"),
                eq("79991112233@c.us"),
                eq("Владелец"),
                any(Company.class)
        )).thenReturn(Optional.of(owner));
        ClientChatMessageCommand ownerMessage = new ClientChatMessageCommand(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "12001@g.us",
                "Компания",
                "owner-message-new",
                "79991112233@c.us",
                "Владелец",
                "Спасибо",
                LocalDateTime.now()
        );

        service.track(ownerMessage, ClientChatSenderRole.STAFF);

        ArgumentCaptor<ClientChatMessage> saved = ArgumentCaptor.forClass(ClientChatMessage.class);
        verify(messageRepository).save(saved.capture());
        assertEquals(ClientChatSenderRole.STAFF, saved.getValue().getSenderRole());
        assertSame(owner, saved.getValue().getActorUser());
        verify(unansweredRepository, never()).findByPlatformAndChatIdAndStatus(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                ClientChatUnansweredStatus.OPEN
        );
    }

    @Test
    void delayedEarlierStaffMessageDoesNotCloseNewerClientQuestion() {
        ClientChatUnansweredItem open = new ClientChatUnansweredItem();
        open.setStatus(ClientChatUnansweredStatus.OPEN);
        open.setLastClientMessageAt(LocalDateTime.now());
        when(unansweredRepository.findByPlatformAndChatIdAndStatus(
                ClientChatPlatform.WHATSAPP, "12001@g.us", ClientChatUnansweredStatus.OPEN
        )).thenReturn(List.of(open));
        ClientChatMessageCommand olderReply = new ClientChatMessageCommand(
                ClientChatPlatform.WHATSAPP, ClientChatDirection.OUTGOING,
                "12001@g.us", "Компания", "older-staff-reply", "12001@g.us",
                "Менеджер", "Ответ", LocalDateTime.now().minusMinutes(5));

        service.track(olderReply, ClientChatSenderRole.STAFF);

        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void storesActualStaffUserInsteadOfAssumingCompanyManager() {
        User actualAuthor = User.builder().id(88L).active(true).build();
        when(participantClassifier.resolveStaffUser(
                org.mockito.ArgumentMatchers.eq(ClientChatPlatform.WHATSAPP),
                org.mockito.ArgumentMatchers.eq("12001@g.us"),
                org.mockito.ArgumentMatchers.eq("12001@g.us"),
                org.mockito.ArgumentMatchers.eq("Менеджер"),
                org.mockito.ArgumentMatchers.any(Company.class)
        )).thenReturn(Optional.of(actualAuthor));

        service.track(command("actual-author-1"), ClientChatSenderRole.STAFF);

        ArgumentCaptor<ClientChatMessage> captor = ArgumentCaptor.forClass(ClientChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertSame(actualAuthor, captor.getValue().getActorUser());
    }

    @Test
    void reconciliationReclassifiesExistingLidMessageWhenPhoneBelongsToStaff() {
        Company company = new Company();
        company.setTitle("Компания");
        ClientChatMessage existing = new ClientChatMessage();
        existing.setId(500L);
        existing.setPlatform(ClientChatPlatform.WHATSAPP);
        existing.setDirection(ClientChatDirection.INCOMING);
        existing.setSenderRole(ClientChatSenderRole.CLIENT);
        existing.setChatId("12001@g.us");
        existing.setExternalMessageId("owner-message-1");
        existing.setSenderExternalId("240161736638694@lid");
        existing.setCompany(company);
        existing.setMessageText("Сообщение владельца");
        existing.setMessageAt(LocalDateTime.now().minusMinutes(10));
        ClientChatUnansweredItem falseCard = openItem("Сообщение владельца");
        falseCard.setStatus(ClientChatUnansweredStatus.ANSWERED);
        falseCard.setLastClientMessage(existing);
        User owner = User.builder().id(9L).active(true).build();
        ClientChatMessageCommand reconciled = new ClientChatMessageCommand(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "12001@g.us",
                "Компания",
                "owner-message-1",
                "79991112233@c.us",
                "Владелец",
                "Сообщение владельца",
                existing.getMessageAt()
        );
        when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                "owner-message-1"
        )).thenReturn(Optional.of(existing));
        when(participantClassifier.classify(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "12001@g.us",
                "79991112233@c.us",
                "Владелец",
                company
        )).thenReturn(ClientChatSenderRole.STAFF);
        when(participantClassifier.resolveStaffUser(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                "79991112233@c.us",
                "Владелец",
                company
        )).thenReturn(Optional.of(owner));
        when(unansweredRepository.findByLastClientMessage(existing)).thenReturn(List.of(falseCard));

        service.track(reconciled);

        assertEquals(ClientChatSenderRole.STAFF, existing.getSenderRole());
        assertEquals("79991112233@c.us", existing.getSenderExternalId());
        assertSame(owner, existing.getActorUser());
        assertEquals(ClientChatUnansweredStatus.MISCLASSIFIED, falseCard.getStatus());
        assertEquals("STAFF_AUTHOR_RECLASSIFIED", falseCard.getResolutionReasonCode());
        assertFalse(falseCard.isAuditRequired());
        verify(messageRepository).save(existing);
        verify(unansweredRepository).save(falseCard);
    }

    @Test
    void laterMeaningfulStaffReplyAutomaticallyClearsExistingAudit() {
        ClientChatUnansweredItem audit = openItem("Когда отправите готовые тексты?");
        audit.setStatus(ClientChatUnansweredStatus.ANSWERED);
        audit.setAuditRequired(true);
        when(unansweredRepository.findByPlatformAndChatIdAndAuditRequiredTrue(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us"
        )).thenReturn(List.of(audit));
        ClientChatMessageCommand source = command("follow-up-1");
        ClientChatMessageCommand meaningfulReply = new ClientChatMessageCommand(
                source.platform(),
                source.direction(),
                source.chatId(),
                source.chatTitle(),
                source.externalMessageId(),
                source.senderExternalId(),
                source.senderName(),
                "Отправим готовые тексты сегодня до 18:00",
                source.messageAt()
        );

        service.track(meaningfulReply, ClientChatSenderRole.STAFF);

        assertFalse(audit.isAuditRequired());
        assertEquals("AUDIT_AUTO_CLEARED_BY_FOLLOW_UP", audit.getResolutionReasonCode());
        assertEquals("Отправим готовые тексты сегодня до 18:00", audit.getResolutionReplyText());
        assertEquals(
                com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality.GOOD,
                audit.getReplyQuality()
        );
        verify(unansweredRepository).save(audit);
    }

    @Test
    void laterGenericStaffReplyDoesNotClearProblemAudit() {
        ClientChatUnansweredItem audit = openItem("Почему ссылка не работает?");
        audit.setStatus(ClientChatUnansweredStatus.ANSWERED);
        audit.setAuditRequired(true);
        when(unansweredRepository.findByPlatformAndChatIdAndAuditRequiredTrue(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us"
        )).thenReturn(List.of(audit));
        ClientChatMessageCommand source = command("follow-up-2");
        ClientChatMessageCommand genericReply = new ClientChatMessageCommand(
                source.platform(),
                source.direction(),
                source.chatId(),
                source.chatTitle(),
                source.externalMessageId(),
                source.senderExternalId(),
                source.senderName(),
                "Спасибо",
                source.messageAt()
        );

        service.track(genericReply, ClientChatSenderRole.STAFF);

        assertTrue(audit.isAuditRequired());
        verify(unansweredRepository, never()).save(audit);
    }

    @Test
    void actionCompletedWithoutReplyEvidenceStaysOpenForManager() {
        ClientChatUnansweredItem open = openItem("Когда отправите готовые тексты?");
        when(unansweredRepository.findById(54L)).thenReturn(Optional.of(open));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        54L,
                        ManagerDailyControlActionType.RESOLVED,
                        "Я ответила",
                        10L
                )
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        assertFalse(open.isAuditRequired());
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void deferredMessageRecordsCommentButRemainsOpen() {
        ClientChatUnansweredItem open = openItem("А мы начали работать с вами?");
        when(unansweredRepository.findById(62L)).thenReturn(Optional.of(open));

        service.markFromManagerControl(
                62L,
                ManagerDailyControlActionType.DEFERRED,
                "Ответ был в личных сообщениях",
                10L
        );

        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        assertEquals("FOLLOW_UP_RECORDED", open.getResolutionReasonCode());
        assertEquals("Ответ был в личных сообщениях", open.getResolutionComment());
        assertEquals(10L, open.getResolvedByUserId());
        verify(unansweredRepository).save(open);
    }

    @Test
    void actionCompletedWithStaffReplyEvidenceClosesAsAnswered() {
        ClientChatUnansweredItem open = openItem("Когда отправите готовые тексты?");
        open.setPlatform(ClientChatPlatform.WHATSAPP);
        open.setChatId("12001@g.us");
        ClientChatMessage reaction = new ClientChatMessage();
        reaction.setSenderRole(ClientChatSenderRole.STAFF);
        reaction.setMessageText("👌");
        reaction.setMessageAt(LocalDateTime.now().minusMinutes(1));
        ClientChatMessage reply = new ClientChatMessage();
        reply.setSenderRole(ClientChatSenderRole.STAFF);
        reply.setMessageText("Отправим готовые тексты сегодня до 18:00");
        reply.setMessageAt(LocalDateTime.now());
        when(unansweredRepository.findById(59L)).thenReturn(Optional.of(open));
        when(messageRepository.findByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                ClientChatSenderRole.STAFF,
                open.getLastClientMessageAt()
        )).thenReturn(List.of(reaction, reply));

        service.markFromManagerControl(
                59L,
                ManagerDailyControlActionType.RESOLVED,
                "Ответ проверен",
                10L
        );

        assertEquals(ClientChatUnansweredStatus.ANSWERED, open.getStatus());
        assertSame(reply, open.getResolutionMessage());
        assertFalse(open.isAuditRequired());
        verify(unansweredRepository).save(open);
    }

    @Test
    void ownerCommentCannotCloseCardWithoutReplyEvidence() {
        ClientChatUnansweredItem open = openItem("Клиент просит выполнить действие");
        when(unansweredRepository.findById(60L)).thenReturn(Optional.of(open));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        60L,
                        ManagerDailyControlActionType.RESOLVED,
                        "Проверено владельцем: ответ клиенту не требовался",
                        10L
                )
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        assertFalse(open.isManualOverride());
        assertFalse(open.isAuditRequired());
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void preparedReviewClosesExactCurrentMessageWithoutCallingProviderAgain() {
        ClientChatUnansweredItem open = openItem("Спасибо большое");
        open.setId(56L);
        var review = aiReview(true, "Благодарность без вопроса");
        var prepared = new com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview(
                56L, null, open.getLastMessageText(), open.getLastClientMessageAt(), null, review);
        when(unansweredRepository.findByIdForUpdate(56L)).thenReturn(Optional.of(open));
        when(noResponseAiReviewService.stillApplicable(review)).thenReturn(true);
        service.markFromManagerControlWithReview(56L, ManagerDailyControlActionType.ACKNOWLEDGED,
                "Подтверждение клиента", 10L, prepared);
        assertEquals(ClientChatUnansweredStatus.NO_RESPONSE_NEEDED, open.getStatus());
        assertTrue(open.getResolutionComment().contains("DeepSeek подтвердил"));
        verify(noResponseAiReviewService, never()).review(any());
        verify(unansweredRepository).save(open);
    }

    @Test
    void changedMessageRejectsPreparedReviewAndKeepsCardOpen() {
        ClientChatUnansweredItem open = openItem("Когда опубликуете отзывы?");
        open.setId(56L);
        var prepared = new com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview(
                56L, null, "Спасибо большое", open.getLastClientMessageAt(), null, aiReview(true, "Благодарность"));
        when(unansweredRepository.findByIdForUpdate(56L)).thenReturn(Optional.of(open));
        var error = assertThrows(ResponseStatusException.class, () ->
                service.markFromManagerControlWithReview(56L, ManagerDailyControlActionType.ACKNOWLEDGED,
                        "", 10L, prepared));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        verify(noResponseAiReviewService, never()).review(any());
        verify(unansweredRepository, never()).save(any());
    }

    @Test
    void reassignedMessageRejectsPreparedReviewEvenWhenTextIsUnchanged() {
        ClientChatUnansweredItem open = openItem("Спасибо большое");
        open.setId(56L);
        Manager reassigned = new Manager(); reassigned.setId(99L); open.setManager(reassigned);
        var prepared = new com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview(
                56L, null, open.getLastMessageText(), open.getLastClientMessageAt(), 10L,
                aiReview(true, "Благодарность"));
        when(unansweredRepository.findByIdForUpdate(56L)).thenReturn(Optional.of(open));
        var error = assertThrows(ResponseStatusException.class, () ->
                service.markFromManagerControlWithReview(56L, ManagerDailyControlActionType.ACKNOWLEDGED,
                        "", 10L, prepared));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        verify(noResponseAiReviewService, never()).review(any());
        verify(unansweredRepository, never()).save(any());
    }

    @Test
    void questionCannotBeMarkedAsNoResponseNeeded() {
        ClientChatUnansweredItem open = openItem("Когда опубликуете отзывы?");
        when(unansweredRepository.findById(55L)).thenReturn(Optional.of(open));
        when(noResponseAiReviewService.review("Когда опубликуете отзывы?")).thenReturn(
                aiReview(true, "Ошибочно распознано как подтверждение")
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        55L,
                        com.hunt.otziv.manager_control.model.ManagerDailyControlActionType.ACKNOWLEDGED,
                        "Сообщение клиента не требует ответа",
                        10L
                )
        );
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        verify(noResponseAiReviewService).review("Когда опубликуете отзывы?");
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void acknowledgementCanBeMarkedAsNoResponseNeeded() {
        ClientChatUnansweredItem open = openItem("Спасибо большое");
        when(unansweredRepository.findById(56L)).thenReturn(Optional.of(open));
        when(noResponseAiReviewService.review("Спасибо большое")).thenReturn(
                aiReview(true, "Самостоятельная благодарность без вопроса")
        );

        service.markFromManagerControl(
                56L,
                com.hunt.otziv.manager_control.model.ManagerDailyControlActionType.ACKNOWLEDGED,
                "Подтверждение клиента",
                10L
        );

        assertEquals(ClientChatUnansweredStatus.NO_RESPONSE_NEEDED, open.getStatus());
        assertEquals("DEEPSEEK_NO_RESPONSE_CONFIRMED", open.getResolutionReasonCode());
        assertTrue(open.getResolutionComment().contains("DeepSeek подтвердил"));
        assertFalse(open.isManualOverride());
        verify(unansweredRepository).save(open);
    }

    @Test
    void deepSeekRejectionKeepsAmbiguousMessageOpen() {
        ClientChatUnansweredItem open = openItem("До завтра");
        when(unansweredRepository.findById(63L)).thenReturn(Optional.of(open));
        when(noResponseAiReviewService.review("До завтра")).thenReturn(
                aiReview(false, "Неясно, ожидает ли клиент продолжения")
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        63L,
                        ManagerDailyControlActionType.ACKNOWLEDGED,
                        "Не требует ответа",
                        10L
                )
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("DeepSeek не подтвердил"));
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void unavailableDeepSeekKeepsMessageOpen() {
        ClientChatUnansweredItem open = openItem("Спасибо");
        when(unansweredRepository.findById(64L)).thenReturn(Optional.of(open));
        when(noResponseAiReviewService.review("Спасибо")).thenReturn(
                new ClientChatNoResponseAiReviewService.Review(
                        false,
                        false,
                        "UNAVAILABLE",
                        0,
                        "DeepSeek временно недоступен",
                        "deepseek"
                )
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        64L,
                        ManagerDailyControlActionType.ACKNOWLEDGED,
                        "Благодарность клиента",
                        10L
                )
        );

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void genericConfirmedReplyIsClosedButQueuedForQualityAudit() {
        ClientChatUnansweredItem open = openItem("Почему до сих пор не работает ссылка?");
        when(unansweredRepository.findById(57L)).thenReturn(Optional.of(open));
        when(appSettingService.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_REPLY_QUALITY_SHADOW_ENABLED,
                true
        )).thenReturn(true);

        service.markConfirmedReply(57L, "Ответ отправлен", 10L, "Проверим");

        assertEquals(ClientChatUnansweredStatus.ANSWERED, open.getStatus());
        assertEquals("Проверим", open.getResolutionReplyText());
        assertTrue(open.isAuditRequired());
    }

    @Test
    void correctiveReplyClosesAuditAndStoresActualText() {
        ClientChatUnansweredItem closed = openItem("Когда исправите ошибку?");
        closed.setStatus(ClientChatUnansweredStatus.ANSWERED);
        closed.setAuditRequired(true);
        when(unansweredRepository.findById(58L)).thenReturn(Optional.of(closed));

        service.markAuditReplySent(
                58L,
                10L,
                "Сейчас проверим ошибку и сообщим подтвержденный срок исправления",
                "WhatsApp"
        );

        assertFalse(closed.isAuditRequired());
        assertEquals(
                "Сейчас проверим ошибку и сообщим подтвержденный срок исправления",
                closed.getResolutionReplyText()
        );
        assertEquals("AUDIT_FOLLOW_UP_SENT", closed.getResolutionReasonCode());
        verify(unansweredRepository).save(closed);
    }

    @Test
    void explicitBotMessageDoesNotCloseOpenUnansweredItems() {
        service.track(command("bot-1"), ClientChatSenderRole.BOT);

        verify(unansweredRepository, never()).findByPlatformAndChatIdAndStatus(any(), any(), any());
    }

    @Test
    void generatesStableFingerprintWhenProviderMessageIdIsMissing() {
        ClientChatMessageCommand command = command(null);

        service.track(command, ClientChatSenderRole.BOT);

        ArgumentCaptor<ClientChatMessage> captor = ArgumentCaptor.forClass(ClientChatMessage.class);
        verify(messageRepository).save(captor.capture());
        String generatedId = captor.getValue().getExternalMessageId();
        assertEquals(67, generatedId.length());
        assertEquals("fp:", generatedId.substring(0, 3));
    }

    @Test
    void tracksMessageWithoutTextWhenProviderIdExists() {
        ClientChatMessageCommand source = command("media-1");
        ClientChatMessageCommand media = new ClientChatMessageCommand(
                source.platform(), source.direction(), source.chatId(), source.chatTitle(), source.externalMessageId(),
                source.senderExternalId(), source.senderName(), "", source.messageAt());

        service.track(media, ClientChatSenderRole.BOT);

        ArgumentCaptor<ClientChatMessage> captor = ArgumentCaptor.forClass(ClientChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("[Нетекстовое сообщение]", captor.getValue().getMessageText());
    }

    @Test
    void answerOutsideTheChatClosesAsManualOverrideWaitingForTheOwnersCheck() {
        ClientChatUnansweredItem item = openItem("Здравствуйте, когда будет счёт?");
        item.setId(6914L);
        when(unansweredRepository.findByIdForUpdate(6914L)).thenReturn(Optional.of(item));

        service.markAnsweredOutsideChat(6914L, 30L, "Ответила клиенту лично в WhatsApp 05.11 в 22:10");

        assertEquals(ClientChatUnansweredStatus.ACTION_COMPLETED, item.getStatus());
        assertEquals(com.hunt.otziv.client_chat_control.model.ClientChatResolutionType.ACTION_COMPLETED, item.getResolutionType());
        assertEquals(ClientChatMessageTrackerService.ANSWERED_OUTSIDE_CHAT, item.getResolutionReasonCode());
        assertEquals("Ответила клиенту лично в WhatsApp 05.11 в 22:10", item.getResolutionComment());
        assertEquals(30L, item.getResolvedByUserId());
        assertTrue(item.isManualOverride());
        assertTrue(item.isAuditRequired());
        assertTrue(ClientChatMessageTrackerService.ownerCheckPending(item));
        verify(unansweredRepository).save(item);
        verify(gamificationEventService, never()).recordManagerClientReply(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void answerOutsideTheChatNeedsAMeaningfulCommentAndAnOpenMessage() {
        ClientChatUnansweredItem item = openItem("Вопрос");
        item.setId(1L);
        when(unansweredRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(item));

        ResponseStatusException empty = assertThrows(ResponseStatusException.class,
                () -> service.markAnsweredOutsideChat(1L, 30L, "  ок "));
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, item.getStatus());

        item.setStatus(ClientChatUnansweredStatus.ANSWERED);
        ResponseStatusException closed = assertThrows(ResponseStatusException.class,
                () -> service.markAnsweredOutsideChat(1L, 30L, "Ответила лично по телефону"));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, closed.getStatusCode());
        verify(unansweredRepository, never()).save(any());
    }

    @Test
    void answerOutsideTheChatKeepsTheFastClickGuard() {
        Manager manager = new Manager();
        ClientChatUnansweredItem item = openItem("Вопрос");
        item.setId(2L);
        item.setManager(manager);
        when(unansweredRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(item));
        when(appSettingService.getBoolean(AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_GUARD_ENABLED, false))
                .thenReturn(true);
        lenient().when(appSettingService.getInt(any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call -> call.getArgument(1));
        when(unansweredRepository.countByManagerAndResolvedByUserIdAndClosedAtAfter(eq(manager), eq(30L), any()))
                .thenReturn(5L);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.markAnsweredOutsideChat(2L, 30L, "Ответила лично по телефону"));

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, failure.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, item.getStatus());
    }

    @Test
    void ownersCheckKeepsTheManagersClaimAndAuthorship() {
        ClientChatUnansweredItem item = openItem("Вопрос");
        item.setId(3L);
        item.setStatus(ClientChatUnansweredStatus.ACTION_COMPLETED);
        item.setResolutionReasonCode(ClientChatMessageTrackerService.ANSWERED_OUTSIDE_CHAT);
        item.setResolutionComment("Ответила лично по телефону");
        item.setResolvedByUserId(30L);
        item.setManualOverride(true);
        item.setAuditRequired(true);
        when(unansweredRepository.findById(3L)).thenReturn(Optional.of(item));

        service.markAuditReviewed(3L, 9L, "Клиент подтвердил ответ по телефону");

        assertFalse(item.isAuditRequired());
        assertEquals(ClientChatMessageTrackerService.ANSWERED_OUTSIDE_CHAT, item.getResolutionReasonCode());
        assertEquals("Ответила лично по телефону\nПроверка: Клиент подтвердил ответ по телефону", item.getResolutionComment());
        assertEquals(30L, item.getResolvedByUserId());
        assertFalse(ClientChatMessageTrackerService.ownerCheckPending(item));
    }

    @Test
    void editOfAnAnsweredClientMessageKeepsTheOriginalAndNeverReopensOrCreatesAnItem() {
        ClientChatMessage original = clientMessage("Сколько стоит отзыв?");
        ClientChatUnansweredItem answered = openItem("Сколько стоит отзыв?");
        answered.setStatus(ClientChatUnansweredStatus.ANSWERED);
        answered.setLastClientMessage(original);
        when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(ClientChatPlatform.WHATSAPP, "12001@g.us", "client-1"))
                .thenReturn(Optional.of(original));
        when(unansweredRepository.findByLastClientMessage(original)).thenReturn(List.of(answered));
        LocalDateTime editedAt = original.getMessageAt().plusMinutes(30);

        service.trackEdit(edit("Сколько стоит отзыв с фото?", editedAt));

        assertEquals("Сколько стоит отзыв?", original.getMessageText());
        assertEquals("Сколько стоит отзыв с фото?", original.getEditedText());
        assertEquals(editedAt, original.getEditedAt());
        assertEquals(1, original.getEditCount());
        verify(messageRepository).save(original);
        assertEquals(ClientChatUnansweredStatus.ANSWERED, answered.getStatus());
        assertEquals("Сколько стоит отзыв?", answered.getLastMessageText());
        verify(unansweredRepository, never()).save(any());
        verify(unansweredRepository, never()).findFirstByPlatformAndChatIdAndStatusOrderByLastClientMessageAtDesc(any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void editOfAStillOpenClientMessageShowsTheCorrectedQuestionAndKeepsTheWaitingTime() {
        ClientChatMessage original = clientMessage("Когда публикация?");
        ClientChatUnansweredItem open = openItem("Когда публикация?");
        open.setId(41L);
        open.setLastClientMessage(original);
        LocalDateTime waitingSince = open.getLastClientMessageAt();
        when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(ClientChatPlatform.WHATSAPP, "12001@g.us", "client-1"))
                .thenReturn(Optional.of(original));
        when(unansweredRepository.findByLastClientMessage(original)).thenReturn(List.of(open));

        service.trackEdit(edit("Когда следующая публикация в 2ГИС?", LocalDateTime.now()));

        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        assertEquals("Когда следующая публикация в 2ГИС?", open.getLastMessageText());
        assertEquals(waitingSince, open.getLastClientMessageAt());
        verify(unansweredRepository).save(open);
        verify(events).publishEvent(new ClientChatReviewPrefetch.Requested(41L));
    }

    @Test
    void repeatedOlderOrUnknownEditsAndStaffEditsDoNotTouchClientItems() {
        ClientChatMessage reply = clientMessage("Даты задаются системой");
        reply.setSenderRole(ClientChatSenderRole.STAFF);
        when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(ClientChatPlatform.WHATSAPP, "12001@g.us", "client-1"))
                .thenReturn(Optional.of(reply));
        LocalDateTime editedAt = reply.getMessageAt().plusMinutes(2);

        service.trackEdit(edit("Даты задаются системой. Следующая публикация 22.10", editedAt));
        service.trackEdit(edit("Даты задаются системой. Следующая публикация 22.10", editedAt));
        service.trackEdit(edit("Промежуточная версия", editedAt.minusMinutes(1)));

        assertEquals("Даты задаются системой", reply.getMessageText());
        assertEquals("Даты задаются системой. Следующая публикация 22.10", reply.getEditedText());
        assertEquals(1, reply.getEditCount());
        verify(messageRepository).save(reply);
        verify(unansweredRepository, never()).findByLastClientMessage(any());

        when(messageRepository.findByPlatformAndChatIdAndExternalMessageId(ClientChatPlatform.WHATSAPP, "12001@g.us", "unknown"))
                .thenReturn(Optional.empty());
        service.trackEdit(new com.hunt.otziv.client_chat_control.dto.ClientChatMessageEditCommand(
                ClientChatPlatform.WHATSAPP, "12001@g.us", "unknown", "Новый текст", LocalDateTime.now()));
        verify(unansweredRepository, never()).save(any());
        verify(messageRepository, never()).save(org.mockito.ArgumentMatchers.argThat(message -> message != reply));
    }

    private static ClientChatMessage clientMessage(String text) {
        ClientChatMessage message = new ClientChatMessage();
        message.setId(501L);
        message.setPlatform(ClientChatPlatform.WHATSAPP);
        message.setChatId("12001@g.us");
        message.setExternalMessageId("client-1");
        message.setSenderRole(ClientChatSenderRole.CLIENT);
        message.setMessageText(text);
        message.setMessageAt(LocalDateTime.now().minusHours(1));
        return message;
    }

    private static com.hunt.otziv.client_chat_control.dto.ClientChatMessageEditCommand edit(String text, LocalDateTime editedAt) {
        return new com.hunt.otziv.client_chat_control.dto.ClientChatMessageEditCommand(
                ClientChatPlatform.WHATSAPP, "12001@g.us", "client-1", text, editedAt);
    }

    private static ClientChatMessageCommand command(String messageId) {
        return new ClientChatMessageCommand(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.OUTGOING,
                "12001@g.us",
                "Группа",
                messageId,
                "12001@g.us",
                "Менеджер",
                "Ответ менеджера",
                LocalDateTime.now()
        );
    }

    @Test
    void lateClientQuestionIsClosedByTheStaffReplyStoredAfterIt() {
        LocalDateTime askedAt = LocalDateTime.of(2026, 11, 5, 23, 21, 23);
        ClientChatMessage reply = storedReply("Хорошо, в ближайшее время отправлю вам 20 текстов для проверки",
                askedAt.plusSeconds(18), ClientChatDirection.INCOMING, null);
        clientSenders();
        when(messageRepository.findByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
                eq(ClientChatPlatform.WHATSAPP), eq("12001@g.us"), eq(ClientChatSenderRole.STAFF), any()))
                .thenReturn(List.of(reply));
        ArgumentCaptor<ClientChatUnansweredItem> saved = ArgumentCaptor.forClass(ClientChatUnansweredItem.class);

        service.track(clientMessage("late-20", "20", askedAt));

        verify(unansweredRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        ClientChatUnansweredItem item = saved.getValue();
        assertEquals(ClientChatUnansweredStatus.ANSWERED, item.getStatus());
        assertEquals("Ответ сотрудника", item.getCloseReason());
        assertSame(reply, item.getResolutionMessage());
    }

    @Test
    void lateClientQuestionStaysOpenWhenOnlyTheOwnerOrAReactionFollowed() {
        LocalDateTime askedAt = LocalDateTime.of(2026, 11, 5, 23, 21, 23);
        Role ownerRole = new Role();
        ownerRole.setName("ROLE_OWNER");
        User owner = User.builder().id(9L).active(true).roles(Set.of(ownerRole)).build();
        clientSenders();
        when(messageRepository.findByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
                eq(ClientChatPlatform.WHATSAPP), eq("12001@g.us"), eq(ClientChatSenderRole.STAFF), any()))
                .thenReturn(List.of(
                        storedReply("Спасибо", askedAt.plusSeconds(10), ClientChatDirection.INCOMING, owner),
                        storedReply("👍", askedAt.plusSeconds(20), ClientChatDirection.OUTGOING, null)));
        ArgumentCaptor<ClientChatUnansweredItem> saved = ArgumentCaptor.forClass(ClientChatUnansweredItem.class);

        service.track(clientMessage("late-20", "20", askedAt));

        verify(unansweredRepository).save(saved.capture());
        assertEquals(ClientChatUnansweredStatus.OPEN, saved.getValue().getStatus());
    }

    @Test
    void lateOlderClientMessageKeepsTheNewerQuestionOnTheOpenCard() {
        LocalDateTime askedAt = LocalDateTime.of(2026, 11, 5, 23, 21, 23);
        ClientChatUnansweredItem open = openItem("Имена мастеров");
        open.setPlatform(ClientChatPlatform.WHATSAPP);
        open.setChatId("12001@g.us");
        open.setLastClientMessageAt(askedAt.plusMinutes(2));
        clientSenders();
        when(unansweredRepository.findFirstByPlatformAndChatIdAndStatusOrderByLastClientMessageAtDesc(
                ClientChatPlatform.WHATSAPP, "12001@g.us", ClientChatUnansweredStatus.OPEN)).thenReturn(Optional.of(open));

        service.track(clientMessage("late-older", "20", askedAt));

        assertEquals("Имена мастеров", open.getLastMessageText());
        assertEquals(askedAt.plusMinutes(2), open.getLastClientMessageAt());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
    }

    @Test
    void historyCheckClosesOnlyCardsWhoseReplyIsAlreadyStored() {
        Manager manager = new Manager();
        manager.setId(3L);
        LocalDateTime askedAt = LocalDateTime.of(2026, 11, 5, 23, 21, 23);
        ClientChatUnansweredItem answered = openItem("20");
        answered.setPlatform(ClientChatPlatform.WHATSAPP);
        answered.setChatId("toyama@g.us");
        answered.setLastClientMessageAt(askedAt);
        ClientChatUnansweredItem waiting = openItem("Когда будут тексты?");
        waiting.setPlatform(ClientChatPlatform.MAX);
        waiting.setChatId("-100");
        waiting.setLastClientMessageAt(askedAt);
        when(unansweredRepository.findByManagerAndPlatformAndStatus(eq(manager), any(), eq(ClientChatUnansweredStatus.OPEN)))
                .thenAnswer(invocation -> switch ((ClientChatPlatform) invocation.getArgument(1)) {
                    case WHATSAPP -> List.of(answered);
                    case MAX -> List.of(waiting);
                    default -> List.of();
                });
        when(messageRepository.findByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
                eq(ClientChatPlatform.WHATSAPP), eq("toyama@g.us"), eq(ClientChatSenderRole.STAFF), any()))
                .thenReturn(List.of(storedReply("Хорошо, отправлю тексты", askedAt.plusSeconds(18),
                        ClientChatDirection.INCOMING, null)));

        assertEquals(1, service.closeOpenItemsAnsweredInHistory(manager));
        assertEquals(ClientChatUnansweredStatus.ANSWERED, answered.getStatus());
        assertEquals(ClientChatUnansweredStatus.OPEN, waiting.getStatus());
    }

    private void clientSenders() {
        when(participantClassifier.classify(any(), any(), any(), any(), any(), any())).thenReturn(ClientChatSenderRole.CLIENT);
    }

    private static ClientChatMessageCommand clientMessage(String id, String text, LocalDateTime at) {
        return new ClientChatMessageCommand(ClientChatPlatform.WHATSAPP, ClientChatDirection.INCOMING,
                "12001@g.us", "Компания", id, "79059969991@c.us", "Арсен", text, at);
    }

    private static ClientChatMessage storedReply(String text, LocalDateTime at, ClientChatDirection direction, User actor) {
        ClientChatMessage message = new ClientChatMessage();
        message.setPlatform(ClientChatPlatform.WHATSAPP);
        message.setChatId("12001@g.us");
        message.setDirection(direction);
        message.setSenderRole(ClientChatSenderRole.STAFF);
        message.setActorUser(actor);
        message.setMessageText(text);
        message.setMessageAt(at);
        return message;
    }

    private static ClientChatUnansweredItem openItem(String text) {
        ClientChatUnansweredItem item = new ClientChatUnansweredItem();
        item.setStatus(ClientChatUnansweredStatus.OPEN);
        item.setLastMessageText(text);
        item.setLastClientMessageAt(LocalDateTime.now().minusMinutes(5));
        return item;
    }

    private static ClientChatNoResponseAiReviewService.Review aiReview(boolean confirmed, String reason) {
        return new ClientChatNoResponseAiReviewService.Review(
                true,
                confirmed,
                confirmed ? "NO_RESPONSE_NEEDED" : "RESPONSE_REQUIRED",
                98,
                reason,
                "deepseek"
        );
    }
}
