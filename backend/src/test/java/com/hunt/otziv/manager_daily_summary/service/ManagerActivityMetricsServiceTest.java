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
        when(settings.getInt("manager.summary.heartbeat-credit-seconds", 60)).thenReturn(60);
        when(settings.getInt("manager.summary.active-heartbeat-credit-seconds", 30)).thenReturn(30);
        when(settings.getInt("manager.summary.interaction-credit-seconds", 30)).thenReturn(30);
        when(settings.getInt("manager.summary.action-credit-seconds", 15)).thenReturn(15);
        when(settings.getInt("manager.summary.message-credit-seconds", 60)).thenReturn(60);
    }

    @Test
    void batchKeepsManagersSeparateAndUsesTheSameIntervalArithmetic() {
        LocalDate date = LocalDate.of(2026, 7, 3);
        var from = date.withDayOfMonth(1).atStartOfDay();
        var until = date.atTime(12, 0);
        var ids = List.of(20L, 21L, 22L);
        when(activityRepository.pointsForManagers(ids, from, until)).thenReturn(List.of(
                point(20, date.atTime(11, 0), "HEARTBEAT"),
                point(20, date.atTime(11, 1), "HEARTBEAT"),
                point(21, date.minusDays(1).atTime(10, 0), "HEARTBEAT")));
        when(messageRepository.staffPointsForManagers(ids, from, until)).thenReturn(List.of(
                messagePoint(20, date.atTime(11, 0, 30)), messagePoint(20, date.atTime(11, 10))));
        var result = service.dailyAndMonthAverages(ids, date, until);
        assertEquals(180, result.get(20L).daily().confirmedSeconds());
        assertEquals(60, result.get(20L).averageDailyConfirmedSeconds());
        assertEquals(0, result.get(21L).daily().confirmedSeconds());
        assertEquals(20, result.get(21L).averageDailyConfirmedSeconds());
        assertEquals(0, result.get(22L).averageDailyConfirmedSeconds());
        org.mockito.Mockito.verify(activityRepository).pointsForManagers(ids, from, until);
        org.mockito.Mockito.verify(messageRepository).staffPointsForManagers(ids, from, until);
        org.mockito.Mockito.verifyNoMoreInteractions(activityRepository, messageRepository);
    }

    @Test
    void combinedTeamReadMatchesIndependentPeriodsIncludingExclusiveMessagesAndMidnightCredits() {
        var ids = List.of(20L, 21L, 22L);
        var day = LocalDate.of(2026, 7, 3);
        var end = day.atTime(12, 0);
        var points = List.of(
                point(20, day.minusMonths(1).atTime(11, 0), "HEARTBEAT"),
                point(20, day.withDayOfMonth(1).atStartOfDay(), "HEARTBEAT"),
                point(20, day.atTime(0, 0, 15), "heartbeat"),
                point(20, day.atTime(11, 0, 0, 123_000_000), "ACTIVE_HEARTBEAT"),
                point(20, day.atTime(11, 0, 15, 456_000_000), "INTERACTION"),
                point(20, end, " STAFF_MESSAGE_SENT "),
                point(20, end.plusHours(2), "OTHER"),
                point(21, day.atTime(11, 0), "HEARTBEAT"),
                point(20, day.plusDays(1).atStartOfDay(), "HEARTBEAT"));
        var messages = List.of(messagePoint(20, day.atTime(11, 0, 10)),
                messagePoint(20, end), messagePoint(21, end), messagePoint(20, end.plusHours(2)),
                messagePoint(20, day.plusDays(1).atStartOfDay()));
        when(activityRepository.pointsForManagers(org.mockito.ArgumentMatchers.eq(ids), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> points.stream().filter(row -> !row.getOccurredAt().isBefore(call.getArgument(1, LocalDateTime.class))
                        && !row.getOccurredAt().isAfter(call.getArgument(2, LocalDateTime.class))).toList());
        when(messageRepository.staffPointsForManagers(org.mockito.ArgumentMatchers.eq(ids), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> messages.stream().filter(row -> !row.getMessageAt().isBefore(call.getArgument(1, LocalDateTime.class))
                        && row.getMessageAt().isBefore(call.getArgument(2, LocalDateTime.class))).toList());
        for (var month : List.of(day.withDayOfMonth(1), day.minusMonths(1).withDayOfMonth(1), day.minusMonths(3).withDayOfMonth(1))) {
            var monthEnd = month.plusMonths(1).atStartOfDay();
            var actual = service.forTeam(ids, day, end, month, monthEnd);
            var expectedDaily = service.dailyAndMonthAverages(ids, day, end);
            var expectedMonthly = service.calculateForManagers(ids, month.atStartOfDay(), monthEnd);
            assertEquals(expectedDaily, actual.daily());
            assertEquals(expectedMonthly, actual.monthly());
            assertEquals(0, actual.daily().get(22L).daily().confirmedSeconds());
        }
        org.mockito.Mockito.clearInvocations(activityRepository, messageRepository);
        service.forTeam(ids, day, end, day.withDayOfMonth(1), end);
        org.mockito.Mockito.verify(activityRepository).pointsForManagers(ids, day.withDayOfMonth(1).atStartOfDay(), end);
        org.mockito.Mockito.verify(messageRepository).staffPointsForManagers(ids, day.withDayOfMonth(1).atStartOfDay(), end);
        org.mockito.Mockito.verifyNoMoreInteractions(activityRepository, messageRepository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void readsOneCreditPolicyPerAssemblyAndObservesChangesOnTheNextRequest(boolean historicalMonth) {
        var ids = List.of(20L, 21L, 22L);
        var date = LocalDate.of(2026, 7, 31);
        var until = date.atTime(12, 30);
        var month = historicalMonth ? date.minusMonths(2).withDayOfMonth(1) : date.withDayOfMonth(1);
        var monthUntil = month.plusMonths(1).atStartOfDay();
        var event = point(20, date.atTime(12, 0), "HEARTBEAT");
        when(activityRepository.pointsForManagers(org.mockito.ArgumentMatchers.eq(ids),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> event.getOccurredAt().isBefore(call.getArgument(1, LocalDateTime.class))
                        || event.getOccurredAt().isAfter(call.getArgument(2, LocalDateTime.class)) ? List.of() : List.of(event));
        when(messageRepository.staffPointsForManagers(org.mockito.ArgumentMatchers.eq(ids),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(settings.getInt("manager.summary.heartbeat-credit-seconds", 60)).thenReturn(60, 0, 900);

        for (long expected : new long[] {60, 1, 120}) {
            var result = service.forTeam(ids, date, until, month, monthUntil);
            assertEquals(expected, result.daily().get(20L).daily().confirmedSeconds());
            assertEquals(Math.round(expected / 31.0), result.daily().get(20L).averageDailyConfirmedSeconds());
            assertEquals(historicalMonth ? 0 : expected, result.monthly().get(20L).confirmedSeconds());
            assertEquals(0, result.daily().get(21L).daily().confirmedSeconds());
            assertEquals(0, result.monthly().get(22L).confirmedSeconds());
        }
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.times(3)).getInt("manager.summary.heartbeat-credit-seconds", 60);
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.times(3)).getInt("manager.summary.active-heartbeat-credit-seconds", 30);
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.times(3)).getInt("manager.summary.interaction-credit-seconds", 30);
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.times(3)).getInt("manager.summary.action-credit-seconds", 15);
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.times(3)).getInt("manager.summary.message-credit-seconds", 60);
        org.mockito.Mockito.verifyNoMoreInteractions(settings);
    }

    private ManagerSiteActivityEventRepository.ActivityPoint point(long id, LocalDateTime at, String type) {
        return new ManagerSiteActivityEventRepository.ActivityPoint(id, at, type);
    }

    private ClientChatMessageRepository.ManagerMessagePoint messagePoint(long id, LocalDateTime at) {
        return new ClientChatMessageRepository.ManagerMessagePoint(id, at);
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
                activity(date.withDayOfMonth(1).atTime(9, 0), "HEARTBEAT"),
                activity(date.withDayOfMonth(1).atTime(9, 1), "HEARTBEAT"),
                activity(date.atTime(11, 0), "HEARTBEAT"),
                activity(date.atTime(11, 1), "HEARTBEAT")
        ));
        when(messageRepository.findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
                managerId,
                monthStart,
                until
        )).thenReturn(List.of(
                message(date.withDayOfMonth(1).atTime(9, 0, 30)),
                message(date.minusDays(1).atTime(10, 0)),
                message(date.minusDays(1).atTime(10, 5)),
                message(date.atTime(11, 10))
        ));

        ManagerActivityMetricsService.DailyAndAverage result =
                service.calculateDailyAndMonthAverage(managerId, date, until);

        assertEquals(120, result.daily().siteSeconds());
        assertEquals(60, result.daily().messengerOutsideSiteSeconds());
        assertEquals(180, result.daily().confirmedSeconds());
        assertEquals(140, result.averageDailyConfirmedSeconds());
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
                        message(200L, from.plusMinutes(1)),
                        message(200L, from.plusMinutes(5)),
                        message(300L, from.plusMinutes(10))
                )
        );

        assertEquals(120, result.confirmedSeconds());
        assertEquals(120, result.messengerOutsideSiteSeconds());
    }

    @Test
    void doesNotBridgeIdleTimeBetweenSparseActions() {
        LocalDate date = LocalDate.of(2026, 7, 3);
        LocalDateTime from = date.atTime(9, 0);
        LocalDateTime to = date.atTime(10, 0);
        when(activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(20L, from, to))
                .thenReturn(List.of(
                        activity(from.plusMinutes(1), "NAVIGATION"),
                        activity(from.plusMinutes(15), "NAVIGATION")
                ));
        when(messageRepository.findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(20L, from, to))
                .thenReturn(List.of(
                        message(from.plusMinutes(30)),
                        message(from.plusMinutes(44))
                ));

        ManagerActivityMetricsService.Metrics result = service.calculate(20L, from, to);

        assertEquals(30, result.siteSeconds());
        assertEquals(120, result.messengerOutsideSiteSeconds());
        assertEquals(150, result.confirmedSeconds());
    }

    private ManagerSiteActivityEvent activity(LocalDateTime occurredAt, String activityType) {
        ManagerSiteActivityEvent event = new ManagerSiteActivityEvent();
        event.setOccurredAt(occurredAt);
        event.setActivityType(activityType);
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
