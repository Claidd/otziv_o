package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.dto.ClientChatUnansweredExample;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_chat_control.service.ClientChatReplySuggestionService;
import com.hunt.otziv.client_messages.api.UncertainClientMessageDelivery;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlStaleChatInviteResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.model.ManagerDailyControlStatus;
import com.hunt.otziv.manager_control.repository.ManagerClientReplyOperationRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** «Ответил в другом мессенджере», the owner's check of it, and «Ссылка-приглашение устарела». */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagerControlClientChatOutcomeTest {

    private static final String CLAIM = "Ответила клиенту лично в WhatsApp 05.11 в 22:10: приглашение в группу сброшено";

    @Mock private ManagerDailyControlConcreteItemRepository concreteRepository;
    @Mock private ManagerDailyControlRepository controlRepository;
    @Mock private ManagerDailyControlItemRepository itemRepository;
    @Mock private ManagerDailyControlEventRepository eventRepository;
    @Mock private ClientChatUnansweredItemRepository unansweredRepository;
    @Mock private ClientChatMessageTrackerService tracker;
    @Mock private ClientChatMessageReconciliationService reconciliation;
    @Mock private ManagerClientReplyOperationRepository replyOperations;
    @Mock private ManagerRepository managerRepository;
    @Mock private UserService userService;
    @Mock private ManagerAccessService managerAccessService;
    @Mock private ManagerPermissionService permissions;

    private ManagerControlClientConversationWorkflow workflow;
    private ManagerControlItemActions itemActions;
    private ManagerDailyControl control;
    private ManagerDailyControlItem parent;
    private ManagerDailyControlConcreteItem card;
    private ClientChatUnansweredItem message;

    @BeforeEach
    void setUp() {
        var accessPolicy = new ManagerControlAccessPolicy(managerRepository, userService, managerAccessService, permissions);
        var cardLifecycle = new ManagerControlCardLifecycle(concreteRepository, itemRepository, eventRepository,
                mock(com.hunt.otziv.manager_performance.service.ManagerPerformanceService.class),
                mock(ManagerControlReadSnapshots.class),
                mock(com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.class));
        var presenter = mock(ManagerControlConcretePresenter.class);
        workflow = new ManagerControlClientConversationWorkflow(concreteRepository, controlRepository, unansweredRepository,
                tracker, mock(ClientChatReplySuggestionService.class), reconciliation, replyOperations, accessPolicy,
                cardLifecycle, presenter);
        itemActions = new ManagerControlItemActions(mock(ManagerControlWorkerTaskWorkflow.class),
                mock(ManagerControlReminderWorkflow.class), mock(ManagerControlDayLifecycle.class),
                mock(ManagerControlProblemExamples.class, Mockito.CALLS_REAL_METHODS), accessPolicy, cardLifecycle,
                mock(ManagerControlSlaPolicy.class), presenter, mock(ManagerControlWorkerTaskLookup.class), permissions,
                mock(OrderService.class), tracker, mock(OrderRepository.class), mock(ManagerControlInvoiceDiagnostics.class),
                mock(ManagerAutomationFailureService.class), mock(UncertainClientMessageDelivery.class), controlRepository,
                itemRepository, concreteRepository, mock(GamificationEventService.class),
                mock(ManagerControlOrderDeliveryState.class), workflow, new ManagerControlTransactionRunner());

        Manager manager = new Manager();
        manager.setId(3L);
        control = new ManagerDailyControl();
        control.setId(10L);
        control.setManager(manager);
        control.setControlDate(LocalDate.now());
        control.setStatus(ManagerDailyControlStatus.RED);
        parent = new ManagerDailyControlItem();
        parent.setId(20L);
        parent.setControl(control);
        parent.setReasonCode("UNANSWERED_CLIENT_MESSAGES");
        parent.setCount(1L);
        parent.setSeverity(ManagerDailyControlSeverity.CRITICAL);
        parent.setGroup(ManagerDailyControlGroup.ACTION);
        parent.setStatus(ManagerDailyControlItemStatus.OPEN);
        card = new ManagerDailyControlConcreteItem();
        card.setId(30L);
        card.setControl(control);
        card.setParentItem(parent);
        card.setEntityType("CLIENT_CHAT_UNANSWERED");
        card.setEntityId(6914L);
        card.setTitle("Toyama motors");
        card.setStatus(ManagerDailyControlItemStatus.OPEN);
        Company company = new Company();
        company.setId(191L);
        message = new ClientChatUnansweredItem();
        message.setId(6914L);
        message.setCompany(company);
        message.setStatus(ClientChatUnansweredStatus.OPEN);

        when(concreteRepository.findById(30L)).thenReturn(Optional.of(card));
        when(concreteRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(card));
        when(concreteRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(concreteRepository.findByParentItem(parent)).thenReturn(List.of(card));
        when(itemRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(itemRepository.findByControl(control)).thenReturn(List.of(parent));
        when(controlRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(unansweredRepository.findById(6914L)).thenReturn(Optional.of(message));
        when(unansweredRepository.findByIdForUpdate(6914L)).thenReturn(Optional.of(message));
        when(replyOperations.findBlockingForUpdate(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void managerRecordsAnAnswerGivenOutsideTheChatAndTheCardCloses() {
        Authentication manager = managerAuthentication(true);

        workflow.markAnsweredOutsideChat(30L, new ManagerControlItemActionRequest(null, CLAIM, null), principal(), manager);

        verify(tracker).markAnsweredOutsideChat(6914L, 2L, CLAIM);
        assertEquals(ManagerDailyControlItemStatus.RESOLVED, card.getStatus());
        assertEquals("Ответил вне чата: " + CLAIM, card.getComment());
        assertEquals(ManagerDailyControlItemStatus.RESOLVED, parent.getStatus());
        ArgumentCaptor<ManagerDailyControlEvent> event = ArgumentCaptor.forClass(ManagerDailyControlEvent.class);
        verify(eventRepository).save(event.capture());
        assertEquals(ManagerDailyControlEventType.ITEM_RESOLVED, event.getValue().getEventType());
        assertEquals(2L, event.getValue().getActorUserId());
        assertTrue(event.getValue().getComment().contains("ждёт проверки владельца"));
    }

    @Test
    void answerOutsideTheChatFollowsTheCardPermissionsAndPendingReplies() {
        Authentication otherManagersChat = managerAuthentication(false);
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class, () -> workflow.markAnsweredOutsideChat(
                30L, new ManagerControlItemActionRequest(null, CLAIM, null), principal(), otherManagersChat));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        Authentication manager = managerAuthentication(true);
        when(replyOperations.findBlockingForUpdate(6914L)).thenReturn(Optional.of(new ManagerClientReplyOperationRepository.Operation(
                "token", 6914L, 30L, "hash", "PREPARED", LocalDateTime.now(), "{}", null, false)));
        ResponseStatusException pending = assertThrows(ResponseStatusException.class, () -> workflow.markAnsweredOutsideChat(
                30L, new ManagerControlItemActionRequest(null, CLAIM, null), principal(), manager));
        assertEquals(HttpStatus.CONFLICT, pending.getStatusCode());

        verify(tracker, never()).markAnsweredOutsideChat(anyLong(), any(), anyString());
        assertEquals(ManagerDailyControlItemStatus.OPEN, card.getStatus());
    }

    @Test
    void managerOnlyHandsTheirOwnClaimToTheOwnerAndCannotConfirmIt() {
        Authentication manager = managerAuthentication(true);
        auditCardWaitingForOwner();

        itemActions.actionConcreteItem(30L, new ManagerControlItemActionRequest("ACTION_TAKEN", "Подтверждаю", null),
                principal(), manager);

        verify(tracker, never()).markAuditReviewed(any(), any(), any());
        assertEquals(ManagerDailyControlItemStatus.ACTION_TAKEN, card.getStatus());
        assertTrue(card.getComment().startsWith(ManagerControlItemActions.OWNER_CHECK_HANDOFF_COMMENT));
        assertNull(card.getResolvedAt());
    }

    @Test
    void ownerConfirmsTheAnswerGivenOutsideTheChat() {
        Authentication owner = ownerAuthentication();
        auditCardWaitingForOwner();

        itemActions.actionConcreteItem(30L, new ManagerControlItemActionRequest("ACTION_TAKEN",
                "Клиент подтвердил, что получил ответ", null), () -> "owner", owner);

        verify(tracker).markAuditReviewed(6914L, 9L, "Клиент подтвердил, что получил ответ");
        assertEquals(ManagerDailyControlItemStatus.RESOLVED, card.getStatus());
    }

    @Test
    void staleInviteOffersTheCurrentInviteWithoutChangingAnything() {
        Authentication manager = managerAuthentication(true);
        when(tracker.chatInviteContext(6914L)).thenReturn(new ClientChatMessageTrackerService.ChatInviteContext(
                6914L, "WHATSAPP", "120363160089865886@g.us", 191L, "Toyama motors",
                "https://chat.whatsapp.com/Bxdmj71FH80B0BxZksCLAj?s=cl&p=a&iam=2", "whatsapp_lika"));
        when(reconciliation.currentWhatsAppInviteLink("whatsapp_lika", "120363160089865886@g.us"))
                .thenReturn(Optional.of("https://chat.whatsapp.com/NewInviteCode12345"));

        ManagerControlStaleChatInviteResponse response = itemActions.reportStaleChatInvite(30L, principal(), manager);

        assertEquals("https://chat.whatsapp.com/NewInviteCode12345", response.inviteUrl());
        assertTrue(response.message().contains("https://chat.whatsapp.com/NewInviteCode12345"));
        assertTrue(response.message().contains("Привязка группы не менялась"));
        assertEquals(ManagerDailyControlItemStatus.OPEN, card.getStatus());
        assertTrue(card.getComment().startsWith("Ссылка-приглашение устарела."));
        verify(reconciliation).currentWhatsAppInviteLink("whatsapp_lika", "120363160089865886@g.us");
        verifyNoMoreInteractions(reconciliation);
        verify(tracker).chatInviteContext(6914L);
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void staleInviteWithoutAReadableInvitePointsToTheReplyFromTheCard() {
        Authentication manager = managerAuthentication(true);
        String companyLink = "https://chat.whatsapp.com/Bxdmj71FH80B0BxZksCLAj";
        when(tracker.chatInviteContext(6914L)).thenReturn(new ClientChatMessageTrackerService.ChatInviteContext(
                6914L, "WHATSAPP", "1@g.us", 191L, "Toyama motors", companyLink, "whatsapp_lika"));

        when(reconciliation.currentWhatsAppInviteLink(any(), any())).thenReturn(Optional.empty());
        ManagerControlStaleChatInviteResponse unknown = itemActions.reportStaleChatInvite(30L, principal(), manager);
        assertNull(unknown.inviteUrl());
        assertTrue(unknown.message().contains("«Ответить клиенту сейчас»"));

        when(reconciliation.currentWhatsAppInviteLink(any(), any())).thenReturn(Optional.of(companyLink + "?s=cl"));
        ManagerControlStaleChatInviteResponse same = itemActions.reportStaleChatInvite(30L, principal(), manager);
        assertNull(same.inviteUrl());
        assertTrue(same.message().startsWith("Подключённый WhatsApp-аккаунт видит ту же ссылку"));

        when(reconciliation.currentWhatsAppInviteLink(any(), any())).thenThrow(new IllegalStateException("gateway down"));
        assertNull(itemActions.reportStaleChatInvite(30L, principal(), manager).inviteUrl());
        assertEquals(ManagerDailyControlItemStatus.OPEN, card.getStatus());
    }

    @Test
    void staleInviteForMaxAndTelegramDoesNotCallWhatsApp() {
        Authentication manager = managerAuthentication(true);
        when(tracker.chatInviteContext(6914L)).thenReturn(new ClientChatMessageTrackerService.ChatInviteContext(
                6914L, "MAX", "-74761808437879", 263L, "Розмарин", "https://max.ru/join/abc", null));

        ManagerControlStaleChatInviteResponse response = itemActions.reportStaleChatInvite(30L, principal(), manager);

        assertNull(response.inviteUrl());
        assertTrue(response.message().startsWith("Для MAX"));
        assertTrue(response.message().contains("через бота компании"));
        verify(reconciliation, never()).currentWhatsAppInviteLink(any(), any());
    }

    @Test
    void staleInviteIsRejectedForAChatOfAnotherManager() {
        Authentication otherManagersChat = managerAuthentication(false);

        assertThrows(ResponseStatusException.class, () -> itemActions.reportStaleChatInvite(30L, principal(), otherManagersChat));

        verify(tracker, never()).chatInviteContext(any());
        verify(reconciliation, never()).currentWhatsAppInviteLink(any(), any());
    }

    @Test
    void ownerCheckAuditCardShowsTheManagersClaim() {
        var examples = mock(ManagerControlProblemExamples.class, Mockito.CALLS_REAL_METHODS);
        var example = new ClientChatUnansweredExample(6914L, ClientChatPlatform.WHATSAPP, 191L, "Toyama motors",
                "1@g.us", "Toyama", "Арсен", "Здравствуйте", LocalDateTime.now().minusHours(1), 60L,
                "/companies/191", null, "Специалист", ClientChatMessageTrackerService.ANSWERED_OUTSIDE_CHAT, CLAIM);

        var card = examples.suspiciousClientClosureExample(example);

        assertEquals("CLIENT_CHAT_AUDIT", card.type());
        assertEquals(ManagerControlProblemExamples.OWNER_CHECK_STATUS, card.status());
        assertTrue(card.reason().startsWith("Арсен: Здравствуйте. Менеджер ответил вне чата: «" + CLAIM + "»"));
        assertTrue(card.reason().length() <= 500);

        var ordinary = examples.suspiciousClientClosureExample(new ClientChatUnansweredExample(1L, ClientChatPlatform.MAX,
                1L, "Компания", "c", "t", "Клиент", "Вопрос", LocalDateTime.now(), 1L, "/companies/1", null, "-",
                "OUTGOING_STAFF_MESSAGE", null));
        assertEquals("Нужен аудит", ordinary.status());
    }

    private void auditCardWaitingForOwner() {
        card.setEntityType("CLIENT_CHAT_AUDIT");
        parent.setReasonCode("SUSPICIOUS_CLIENT_CLOSURES");
        message.setStatus(ClientChatUnansweredStatus.ACTION_COMPLETED);
        message.setResolutionReasonCode(ClientChatMessageTrackerService.ANSWERED_OUTSIDE_CHAT);
        message.setResolutionComment(CLAIM);
        message.setManualOverride(true);
        message.setAuditRequired(true);
    }

    private Authentication managerAuthentication(boolean ownsTheCompany) {
        Authentication auth = new UsernamePasswordAuthenticationToken("manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
        User user = new User();
        user.setId(2L);
        user.setUsername("manager");
        Manager own = new Manager();
        own.setId(3L);
        when(permissions.hasRole(auth, "ADMIN")).thenReturn(false);
        when(permissions.hasRole(auth, "OWNER")).thenReturn(false);
        when(permissions.hasRole(auth, "MANAGER")).thenReturn(true);
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(managerRepository.findByUserId(2L)).thenReturn(Optional.of(own));
        when(managerAccessService.canAccessCompany(eq(191L), eq(auth))).thenReturn(ownsTheCompany);
        return auth;
    }

    private Authentication ownerAuthentication() {
        Authentication auth = new UsernamePasswordAuthenticationToken("owner", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        User user = new User();
        user.setId(9L);
        user.setUsername("owner");
        user.setOwnerControlViewMode("ALL_MANAGERS");
        when(permissions.hasRole(auth, "ADMIN")).thenReturn(false);
        when(permissions.hasRole(auth, "OWNER")).thenReturn(true);
        when(userService.findByUserName("owner")).thenReturn(Optional.of(user));
        return auth;
    }

    private static Principal principal() {
        return () -> "manager";
    }
}
