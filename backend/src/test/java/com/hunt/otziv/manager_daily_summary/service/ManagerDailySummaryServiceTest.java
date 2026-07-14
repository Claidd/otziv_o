package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.dto.ManagerQueueStateResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.service.ManagerQueueStateService;
import com.hunt.otziv.manager_control.service.ManagerControlService;
import com.hunt.otziv.manager_control.service.ManagerActionBalanceService;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_daily_summary.model.ManagerPerformanceDaily;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ManagerDailySummaryServiceTest {

    @Mock private ManagerRepository managerRepository;
    @Mock private ManagerPerformanceService performanceService;
    @Mock private ManagerDailyControlRepository controlRepository;
    @Mock private ManagerDailyControlItemRepository itemRepository;
    @Mock private ManagerDailyControlConcreteItemRepository concreteItemRepository;
    @Mock private ClientChatMessageRepository messageRepository;
    @Mock private ManagerSiteActivityEventRepository activityRepository;
    @Mock private ManagerPerformanceDailyRepository dailyRepository;
    @Mock private AppSettingService appSettingService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ManagerQueueStateService queueStateService;
    @Mock private ManagerControlService managerControlService;
    @Mock private GamificationScoreLedgerRepository scoreLedgerRepository;
    @Mock private GamificationEventService gamificationEventService;

    private ManagerDailySummaryService service;

    @BeforeEach
    void setUp() {
        service = new ManagerDailySummaryService(
                managerRepository, performanceService, controlRepository, itemRepository, concreteItemRepository,
                messageRepository, activityRepository, dailyRepository, appSettingService, jdbcTemplate,
                queueStateService, managerControlService, new ManagerActionBalanceService(), scoreLedgerRepository, gamificationEventService
        );
        when(appSettingService.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(dailyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dailyRepository.findBySummaryDateBetweenOrderByManager_IdAscSummaryDateAsc(any(), any())).thenReturn(List.of());
        when(queueStateService.aggregate(any(), any(), any())).thenAnswer(invocation -> new ManagerQueueStateResponse(
                false, invocation.getArgument(1), "NOT_OBSERVED", 0, 0, 0, 0, 0, 0, 0, 0, 14, 0, null));
    }

    @Test
    void repeatedClientMessagesBeforeStaffReplyFormOneWaitingPeriod() {
        LocalDate date = LocalDate.of(2026, 7, 13);
        Manager manager = manager();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(performanceService.score(date)).thenReturn(List.of());
        when(controlRepository.findByControlDateAndManager(date, manager)).thenReturn(Optional.empty());
        when(dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())).thenReturn(Optional.empty());
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(
                        message(1L, ClientChatSenderRole.CLIENT, date.atTime(9, 0)),
                        message(2L, ClientChatSenderRole.CLIENT, date.atTime(9, 5)),
                        message(3L, ClientChatSenderRole.STAFF, date.atTime(9, 15))
                ));

        var response = service.calculate(date, false).getFirst();

        assertEquals(1, response.replyCount());
        assertEquals(900, response.allReplyAverageSeconds());
        assertEquals(900, response.firstReplyAverageSeconds());
        assertEquals(1, response.repliesInSla());
    }

    @Test
    void replyTodayIncludesClientMessageFromPreviousDay() {
        LocalDate date = LocalDate.of(2026, 7, 14);
        Manager manager = manager();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(performanceService.score(date)).thenReturn(List.of());
        when(controlRepository.findByControlDateAndManager(date, manager)).thenReturn(Optional.empty());
        when(dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())).thenReturn(Optional.empty());
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(
                        message(1L, ClientChatSenderRole.CLIENT, date.minusDays(1).atTime(23, 50)),
                        message(2L, ClientChatSenderRole.STAFF, date.atTime(0, 10))
                ));

        var response = service.calculate(date, false).getFirst();

        assertEquals(1, response.replyCount());
        assertEquals(1200, response.allReplyAverageSeconds());
        assertEquals(1, response.repliesInSla());
    }

    @Test
    void evenReplySampleUsesAverageOfTwoMiddleValuesForMedian() {
        LocalDate date = LocalDate.of(2026, 7, 15);
        Manager manager = manager();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(performanceService.score(date)).thenReturn(List.of());
        when(controlRepository.findByControlDateAndManager(date, manager)).thenReturn(Optional.empty());
        when(dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())).thenReturn(Optional.empty());
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(
                        message(1L, "chat-1", ClientChatSenderRole.CLIENT, date.atStartOfDay()),
                        message(2L, "chat-1", ClientChatSenderRole.STAFF, date.atStartOfDay().plusSeconds(54)),
                        message(3L, "chat-2", ClientChatSenderRole.CLIENT, date.atStartOfDay()),
                        message(4L, "chat-2", ClientChatSenderRole.STAFF, date.atStartOfDay().plusSeconds(14_447)),
                        message(5L, "chat-3", ClientChatSenderRole.CLIENT, date.atTime(1, 0).minusSeconds(200_584)),
                        message(6L, "chat-3", ClientChatSenderRole.STAFF, date.atTime(1, 0)),
                        message(7L, "chat-4", ClientChatSenderRole.CLIENT, date.atTime(2, 0).minusSeconds(360_043)),
                        message(8L, "chat-4", ClientChatSenderRole.STAFF, date.atTime(2, 0))
                ));

        var response = service.calculate(date, false).getFirst();

        assertEquals(4, response.replyCount());
        assertEquals(107_516, response.allReplyMedianSeconds());
        assertEquals(107_516, response.firstReplyMedianSeconds());
    }

    @Test
    void replyOlderThanSevenDaysIsStillIncludedWithinRawRetentionWindow() {
        LocalDate date = LocalDate.of(2026, 7, 15);
        Manager manager = manager();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(performanceService.score(date)).thenReturn(List.of());
        when(controlRepository.findByControlDateAndManager(date, manager)).thenReturn(Optional.empty());
        when(dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())).thenReturn(Optional.empty());
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(
                        message(1L, ClientChatSenderRole.CLIENT, date.minusDays(30).atTime(12, 0)),
                        message(2L, ClientChatSenderRole.STAFF, date.atTime(12, 0))
                ));

        var response = service.calculate(date, false).getFirst();

        assertEquals(1, response.replyCount());
        assertEquals(30L * 24 * 3600, response.allReplyAverageSeconds());
    }

    @Test
    void whatsappTelegramAndMaxChatsAreMeasuredIndependently() {
        LocalDate date = LocalDate.of(2026, 7, 15);
        Manager manager = manager();
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(performanceService.score(date)).thenReturn(List.of());
        when(controlRepository.findByControlDateAndManager(date, manager)).thenReturn(Optional.empty());
        when(dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())).thenReturn(Optional.empty());
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(
                        message(1L, ClientChatPlatform.WHATSAPP, "shared-id", ClientChatSenderRole.CLIENT, date.atTime(10, 0)),
                        message(2L, ClientChatPlatform.WHATSAPP, "shared-id", ClientChatSenderRole.STAFF, date.atTime(10, 5)),
                        message(3L, ClientChatPlatform.TELEGRAM, "shared-id", ClientChatSenderRole.CLIENT, date.atTime(11, 0)),
                        message(4L, ClientChatPlatform.TELEGRAM, "shared-id", ClientChatSenderRole.STAFF, date.atTime(11, 10)),
                        message(5L, ClientChatPlatform.MAX, "shared-id", ClientChatSenderRole.CLIENT, date.atTime(12, 0)),
                        message(6L, ClientChatPlatform.MAX, "shared-id", ClientChatSenderRole.STAFF, date.atTime(12, 15))
                ));

        var response = service.calculate(date, false).getFirst();

        assertEquals(3, response.replyCount());
        assertEquals(600, response.allReplyAverageSeconds());
        assertEquals(3, response.repliesInSla());
    }

    @Test
    void problemCardsExposeFinalActionTakenAndOpenStatesSeparately() {
        LocalDate date = LocalDate.of(2026, 7, 15);
        Manager manager = manager();
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(100L);
        control.setControlDate(date);
        control.setManager(manager);

        ManagerDailyControlItem parent = new ManagerDailyControlItem();
        parent.setId(101L);
        parent.setControl(control);
        parent.setGroup(ManagerDailyControlGroup.ACTION);
        parent.setItemType(ManagerDailyControlItemType.PROBLEM);
        parent.setReasonCode("PUBLICATION");
        parent.setStatus(ManagerDailyControlItemStatus.OPEN);
        parent.setCount(3);

        LocalDateTime created = date.atTime(10, 0);
        ManagerDailyControlConcreteItem resolved = concrete(201L, control, parent, ManagerDailyControlItemStatus.RESOLVED, created);
        resolved.setResolvedAt(created.plusMinutes(10));
        ManagerDailyControlConcreteItem actionTaken = concrete(202L, control, parent, ManagerDailyControlItemStatus.ACTION_TAKEN, created);
        ManagerDailyControlConcreteItem open = concrete(203L, control, parent, ManagerDailyControlItemStatus.OPEN, created);

        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(performanceService.score(date)).thenReturn(List.of());
        when(controlRepository.findByControlDateAndManager(date, manager)).thenReturn(Optional.of(control));
        when(itemRepository.findByControl(control)).thenReturn(List.of(parent));
        when(concreteItemRepository.findByControl(control)).thenReturn(List.of(resolved, actionTaken, open));
        when(dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())).thenReturn(Optional.empty());
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(any(), any(), any())).thenReturn(List.of());

        var response = service.calculate(date, false).getFirst();

        assertEquals(3, response.problemCount());
        assertEquals(1, response.problemResolvedCount());
        assertEquals(1, response.problemActionTakenCount());
        assertEquals(1, response.problemOpenCount());
        assertEquals(600, response.problemResolutionAverageSeconds());
    }

    private Manager manager() {
        User user = new User();
        user.setId(10L);
        user.setFio("Менеджер");
        user.setActive(true);
        Manager manager = new Manager();
        manager.setId(1L);
        manager.setUser(user);
        return manager;
    }

    private ClientChatMessage message(Long id, ClientChatSenderRole role, LocalDateTime at) {
        return message(id, "chat-1", role, at);
    }

    private ClientChatMessage message(Long id, String chatId, ClientChatSenderRole role, LocalDateTime at) {
        return message(id, ClientChatPlatform.TELEGRAM, chatId, role, at);
    }

    private ClientChatMessage message(
            Long id,
            ClientChatPlatform platform,
            String chatId,
            ClientChatSenderRole role,
            LocalDateTime at
    ) {
        ClientChatMessage message = new ClientChatMessage();
        message.setId(id);
        message.setPlatform(platform);
        message.setChatId(chatId);
        message.setDirection(ClientChatDirection.INCOMING);
        message.setSenderRole(role);
        message.setMessageAt(at);
        return message;
    }

    private ManagerDailyControlConcreteItem concrete(
            Long id,
            ManagerDailyControl control,
            ManagerDailyControlItem parent,
            ManagerDailyControlItemStatus status,
            LocalDateTime createdAt
    ) {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(id);
        item.setControl(control);
        item.setParentItem(parent);
        item.setEntityType("PUBLISH_REVIEW");
        item.setStatus(status);
        item.setCreatedAt(createdAt);
        return item;
    }
}
