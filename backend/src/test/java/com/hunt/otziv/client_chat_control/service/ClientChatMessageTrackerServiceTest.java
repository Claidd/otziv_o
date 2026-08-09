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
                new ClientChatReplyQualityService()
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
                        10L,
                        false
                )
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
        assertFalse(open.isAuditRequired());
        verify(unansweredRepository, never()).save(open);
    }

    @Test
    void actionCompletedWithStaffReplyEvidenceClosesAsAnswered() {
        ClientChatUnansweredItem open = openItem("Когда отправите готовые тексты?");
        open.setPlatform(ClientChatPlatform.WHATSAPP);
        open.setChatId("12001@g.us");
        ClientChatMessage reply = new ClientChatMessage();
        reply.setSenderRole(ClientChatSenderRole.STAFF);
        reply.setMessageText("Отправим готовые тексты сегодня до 18:00");
        reply.setMessageAt(LocalDateTime.now());
        when(unansweredRepository.findById(59L)).thenReturn(Optional.of(open));
        when(messageRepository.findFirstByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                ClientChatSenderRole.STAFF,
                open.getLastClientMessageAt()
        )).thenReturn(Optional.of(reply));

        service.markFromManagerControl(
                59L,
                ManagerDailyControlActionType.RESOLVED,
                "Ответ проверен",
                10L,
                false
        );

        assertEquals(ClientChatUnansweredStatus.ANSWERED, open.getStatus());
        assertSame(reply, open.getResolutionMessage());
        assertFalse(open.isAuditRequired());
        verify(unansweredRepository).save(open);
    }

    @Test
    void administrativeActionWithoutReplyEvidenceAlwaysRequiresAudit() {
        ClientChatUnansweredItem open = openItem("Клиент просит выполнить действие");
        when(unansweredRepository.findById(60L)).thenReturn(Optional.of(open));

        service.markFromManagerControl(
                60L,
                ManagerDailyControlActionType.RESOLVED,
                "Проверено владельцем: ответ клиенту не требовался",
                10L,
                true
        );

        assertEquals(ClientChatUnansweredStatus.ACTION_COMPLETED, open.getStatus());
        assertTrue(open.isManualOverride());
        assertTrue(open.isAuditRequired());
        assertEquals("ACTION_COMPLETED_WITHOUT_REPLY_EVIDENCE", open.getResolutionReasonCode());
        assertEquals(
                com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality.SUSPICIOUS,
                open.getReplyQuality()
        );
        verify(unansweredRepository).save(open);
    }

    @Test
    void questionCannotBeMarkedAsNoResponseNeeded() {
        ClientChatUnansweredItem open = openItem("Когда опубликуете отзывы?");
        when(unansweredRepository.findById(55L)).thenReturn(Optional.of(open));

        assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        55L,
                        com.hunt.otziv.manager_control.model.ManagerDailyControlActionType.ACKNOWLEDGED,
                        "Сообщение клиента не требует ответа",
                        10L,
                        false
                )
        );
        assertEquals(ClientChatUnansweredStatus.OPEN, open.getStatus());
    }

    @Test
    void acknowledgementCanBeMarkedAsNoResponseNeeded() {
        ClientChatUnansweredItem open = openItem("Спасибо большое");
        when(unansweredRepository.findById(56L)).thenReturn(Optional.of(open));

        service.markFromManagerControl(
                56L,
                com.hunt.otziv.manager_control.model.ManagerDailyControlActionType.ACKNOWLEDGED,
                "Подтверждение клиента",
                10L,
                false
        );

        assertEquals(ClientChatUnansweredStatus.NO_RESPONSE_NEEDED, open.getStatus());
    }

    @Test
    void administrativeNoResponseOverrideRejectsGenericMobileComment() {
        ClientChatUnansweredItem open = openItem("Когда опубликуете отзывы?");
        when(unansweredRepository.findById(61L)).thenReturn(Optional.of(open));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.markFromManagerControl(
                        61L,
                        ManagerDailyControlActionType.ACKNOWLEDGED,
                        "Сообщение клиента не требует ответа.",
                        10L,
                        true
                )
        );

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.getStatusCode());
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

    private static ClientChatUnansweredItem openItem(String text) {
        ClientChatUnansweredItem item = new ClientChatUnansweredItem();
        item.setStatus(ClientChatUnansweredStatus.OPEN);
        item.setLastMessageText(text);
        item.setLastClientMessageAt(LocalDateTime.now().minusMinutes(5));
        return item;
    }
}
