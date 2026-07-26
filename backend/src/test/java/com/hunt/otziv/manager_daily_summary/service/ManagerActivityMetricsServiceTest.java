package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerActivityMetricsServiceTest {

    @Mock private ManagerSiteActivityEventRepository activityRepository;
    @Mock private ClientChatMessageRepository messageRepository;
    @Mock private AppSettingService settings;

    private ManagerActivityMetricsService service;

    @BeforeEach
    void setUp() {
        service = new ManagerActivityMetricsService(activityRepository, messageRepository, settings);
        when(settings.getInt("manager.summary.activity-idle-minutes", 15)).thenReturn(15);
    }

    @Test
    void mergesSiteAndMessengerWithoutDoubleCountingAndCalculatesMonthAverage() {
        Long managerId = 20L;
        LocalDate date = LocalDate.of(2026, 7, 3);
        LocalDateTime until = date.atTime(12, 0);
        LocalDateTime monthStart = date.withDayOfMonth(1).atStartOfDay();
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(
                managerId,
                monthStart,
                until
        )).thenReturn(List.of(
                activity(date.withDayOfMonth(1).atTime(9, 0)),
                activity(date.withDayOfMonth(1).atTime(9, 10)),
                activity(date.atTime(11, 0)),
                activity(date.atTime(11, 4))
        ));
        when(messageRepository.findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
                managerId,
                monthStart,
                until
        )).thenReturn(List.of(
                message(date.withDayOfMonth(1).atTime(9, 5)),
                message(date.minusDays(1).atTime(10, 0)),
                message(date.minusDays(1).atTime(10, 5)),
                message(date.atTime(11, 10))
        ));

        ManagerActivityMetricsService.DailyAndAverage result =
                service.calculateDailyAndMonthAverage(managerId, date, until);

        assertEquals(300, result.daily().siteSeconds());
        assertEquals(60, result.daily().messengerOutsideSiteSeconds());
        assertEquals(360, result.daily().confirmedSeconds());
        assertEquals(460, result.averageDailyConfirmedSeconds());
    }

    @Test
    void assignedCompanyStaffMessagesCountOnlyForTheActualManagerAuthor() {
        LocalDate date = LocalDate.of(2026, 7, 3);
        LocalDateTime from = date.atTime(9, 0);
        LocalDateTime to = date.atTime(10, 0);
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(20L, from, to))
                .thenReturn(List.of());

        ManagerActivityMetricsService.Metrics result = service.calculate(
                20L,
                200L,
                from,
                to,
                List.of(
                        message(200L, from),
                        message(200L, from.plusMinutes(5)),
                        message(300L, from.plusMinutes(10))
                )
        );

        assertEquals(360, result.confirmedSeconds());
        assertEquals(360, result.messengerOutsideSiteSeconds());
    }

    private ManagerSiteActivityEvent activity(LocalDateTime occurredAt) {
        ManagerSiteActivityEvent event = new ManagerSiteActivityEvent();
        event.setOccurredAt(occurredAt);
        return event;
    }

    private ClientChatMessage message(LocalDateTime messageAt) {
        return message(null, messageAt);
    }

    private ClientChatMessage message(Long actorUserId, LocalDateTime messageAt) {
        ClientChatMessage message = new ClientChatMessage();
        message.setSenderRole(ClientChatSenderRole.STAFF);
        if (actorUserId != null) {
            User actor = new User();
            actor.setId(actorUserId);
            message.setActorUser(actor);
        }
        message.setMessageAt(messageAt);
        return message;
    }
}
