package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerActivityMetricsService {
    private static final String HEARTBEAT_CREDIT_SETTING =
            "manager.summary.heartbeat-credit-seconds";
    private static final String ACTIVE_HEARTBEAT_CREDIT_SETTING =
            "manager.summary.active-heartbeat-credit-seconds";
    private static final String INTERACTION_CREDIT_SETTING =
            "manager.summary.interaction-credit-seconds";
    private static final String ACTION_CREDIT_SETTING =
            "manager.summary.action-credit-seconds";
    private static final String MESSAGE_CREDIT_SETTING =
            "manager.summary.message-credit-seconds";
    private static final int DEFAULT_HEARTBEAT_CREDIT_SECONDS = 60;
    private static final int DEFAULT_ACTIVE_HEARTBEAT_CREDIT_SECONDS = 30;
    private static final int DEFAULT_INTERACTION_CREDIT_SECONDS = 30;
    private static final int DEFAULT_ACTION_CREDIT_SECONDS = 15;
    private static final int DEFAULT_MESSAGE_CREDIT_SECONDS = 60;
    private static final int MAX_ACTIVITY_CREDIT_SECONDS = 120;

    private final ManagerSiteActivityEventRepository activityRepository;
    private final ClientChatMessageRepository messageRepository;
    private final AppSettingService settings;

    @Transactional(readOnly = true)
    public Metrics calculate(Long managerId, LocalDateTime from, LocalDateTime to) {
        if (managerId == null || from == null || to == null || !to.isAfter(from)) {
            return Metrics.empty();
        }
        List<ClientChatMessage> messages = messageRepository
                .findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(managerId, from, to);
        return calculateFromMessages(managerId, from, to, messages);
    }

    @Transactional(readOnly = true)
    public Metrics calculate(
            Long managerId,
            Long managerUserId,
            LocalDateTime from,
            LocalDateTime to,
            List<ClientChatMessage> messages
    ) {
        List<ClientChatMessage> actualManagerMessages = messages == null
                ? List.of()
                : messages.stream()
                        .filter(message -> message.getActorUser() != null)
                        .filter(message -> Objects.equals(message.getActorUser().getId(), managerUserId))
                        .toList();
        return calculateFromMessages(managerId, from, to, actualManagerMessages);
    }

    private Metrics calculateFromMessages(
            Long managerId,
            LocalDateTime from,
            LocalDateTime to,
            List<ClientChatMessage> messages
    ) {
        if (managerId == null || from == null || to == null || !to.isAfter(from)) {
            return Metrics.empty();
        }
        List<ManagerSiteActivityEvent> siteEvents = activityRepository
                .findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(managerId, from, to);
        List<LocalDateTime> messengerPoints = staffMessagePoints(messages);
        return calculateFromEvents(siteEvents, messengerPoints, from, to);
    }

    @Transactional(readOnly = true)
    public DailyAndAverage calculateDailyAndMonthAverage(
            Long managerId,
            LocalDate date,
            LocalDateTime until
    ) {
        if (managerId == null || date == null || until == null) {
            return new DailyAndAverage(Metrics.empty(), 0);
        }
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime selectedDayStart = date.atStartOfDay();
        LocalDateTime selectedDayEnd = date.plusDays(1).atStartOfDay();
        LocalDateTime end = until.isBefore(selectedDayStart)
                ? selectedDayStart
                : until.isAfter(selectedDayEnd) ? selectedDayEnd : until;

        Map<LocalDate, List<ManagerSiteActivityEvent>> siteByDate = activityRepository
                .findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(managerId, from, end).stream()
                .filter(event -> event.getOccurredAt() != null)
                .sorted(Comparator.comparing(ManagerSiteActivityEvent::getOccurredAt))
                .collect(Collectors.groupingBy(
                        event -> event.getOccurredAt().toLocalDate(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<LocalDate, List<LocalDateTime>> messengerByDate = staffMessagePoints(
                messageRepository.findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(managerId, from, end)
        ).stream().collect(Collectors.groupingBy(
                LocalDateTime::toLocalDate,
                LinkedHashMap::new,
                Collectors.toList()
        ));

        Metrics daily = calculateFromEvents(
                siteByDate.getOrDefault(date, List.of()),
                messengerByDate.getOrDefault(date, List.of()),
                selectedDayStart,
                end
        );
        long monthConfirmedSeconds = 0;
        for (LocalDate day = monthStart; !day.isAfter(date); day = day.plusDays(1)) {
            LocalDateTime limit = day.equals(date) ? end : day.plusDays(1).atStartOfDay();
            monthConfirmedSeconds += calculateFromEvents(
                    siteByDate.getOrDefault(day, List.of()),
                    messengerByDate.getOrDefault(day, List.of()),
                    day.atStartOfDay(),
                    limit
            ).confirmedSeconds();
        }
        long elapsedDays = ChronoUnit.DAYS.between(monthStart, date) + 1;
        return new DailyAndAverage(
                daily,
                Math.round(monthConfirmedSeconds / (double) elapsedDays)
        );
    }

    private Metrics calculateFromEvents(
            List<ManagerSiteActivityEvent> siteEvents,
            List<LocalDateTime> messengerPoints,
            LocalDateTime from,
            LocalDateTime limit
    ) {
        long heartbeatCredit = activityCreditSeconds(
                HEARTBEAT_CREDIT_SETTING,
                DEFAULT_HEARTBEAT_CREDIT_SECONDS
        );
        long activeHeartbeatCredit = activityCreditSeconds(
                ACTIVE_HEARTBEAT_CREDIT_SETTING,
                DEFAULT_ACTIVE_HEARTBEAT_CREDIT_SECONDS
        );
        long interactionCredit = activityCreditSeconds(
                INTERACTION_CREDIT_SETTING,
                DEFAULT_INTERACTION_CREDIT_SECONDS
        );
        long actionCredit = activityCreditSeconds(
                ACTION_CREDIT_SETTING,
                DEFAULT_ACTION_CREDIT_SECONDS
        );
        long messageCredit = activityCreditSeconds(
                MESSAGE_CREDIT_SETTING,
                DEFAULT_MESSAGE_CREDIT_SECONDS
        );
        List<Interval> site = siteIntervals(
                siteEvents,
                from,
                limit,
                heartbeatCredit,
                activeHeartbeatCredit,
                interactionCredit,
                actionCredit,
                messageCredit
        );
        List<Interval> messenger = creditedIntervals(messengerPoints, from, limit, messageCredit);
        long siteSeconds = duration(site);
        long messengerSeconds = duration(messenger);
        long confirmedSeconds = duration(merge(concat(site, messenger)));
        long overlap = siteSeconds + messengerSeconds - confirmedSeconds;
        return new Metrics(
                siteSeconds,
                Math.max(0, messengerSeconds - overlap),
                confirmedSeconds
        );
    }

    private long activityCreditSeconds(String key, int defaultValue) {
        return Math.min(
                MAX_ACTIVITY_CREDIT_SECONDS,
                Math.max(1, settings.getInt(key, defaultValue))
        );
    }

    private List<Interval> siteIntervals(
            List<ManagerSiteActivityEvent> events,
            LocalDateTime from,
            LocalDateTime limit,
            long heartbeatCredit,
            long activeHeartbeatCredit,
            long interactionCredit,
            long actionCredit,
            long messageCredit
    ) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Interval> intervals = events.stream()
                .filter(Objects::nonNull)
                .filter(event -> event.getOccurredAt() != null)
                .map(event -> creditedInterval(
                        event.getOccurredAt(),
                        from,
                        limit,
                        siteEventCredit(
                                event.getActivityType(),
                                heartbeatCredit,
                                activeHeartbeatCredit,
                                interactionCredit,
                                actionCredit,
                                messageCredit
                        )
                ))
                .filter(Objects::nonNull)
                .toList();
        return merge(intervals);
    }

    private long siteEventCredit(
            String activityType,
            long heartbeatCredit,
            long activeHeartbeatCredit,
            long interactionCredit,
            long actionCredit,
            long messageCredit
    ) {
        String normalized = activityType == null ? "" : activityType.trim().toUpperCase(Locale.ROOT);
        if ("HEARTBEAT".equals(normalized)) {
            return heartbeatCredit;
        }
        if ("ACTIVE_HEARTBEAT".equals(normalized)) {
            return activeHeartbeatCredit;
        }
        if ("INTERACTION".equals(normalized)) {
            return interactionCredit;
        }
        if (normalized.endsWith("_MESSAGE_SENT")) {
            return messageCredit;
        }
        return actionCredit;
    }

    private List<LocalDateTime> staffMessagePoints(List<ClientChatMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> message.getSenderRole() == ClientChatSenderRole.STAFF)
                .map(ClientChatMessage::getMessageAt)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    private List<Interval> creditedIntervals(
            List<LocalDateTime> source,
            LocalDateTime from,
            LocalDateTime limit,
            long creditSeconds
    ) {
        List<LocalDateTime> points = source == null
                ? List.of()
                : source.stream().filter(Objects::nonNull).sorted().toList();
        if (points.isEmpty()) {
            return List.of();
        }
        return merge(points.stream()
                .map(point -> creditedInterval(point, from, limit, creditSeconds))
                .filter(Objects::nonNull)
                .toList());
    }

    private Interval creditedInterval(
            LocalDateTime point,
            LocalDateTime from,
            LocalDateTime limit,
            long creditSeconds
    ) {
        if (point == null || from == null || limit == null || point.isBefore(from) || point.isAfter(limit)) {
            return null;
        }
        LocalDateTime start = max(from, point.minusSeconds(creditSeconds));
        LocalDateTime end = min(point, limit);
        return end.isAfter(start) ? new Interval(start, end) : null;
    }

    private List<Interval> merge(List<Interval> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<Interval> sorted = source.stream()
                .sorted(Comparator.comparing(Interval::start))
                .toList();
        List<Interval> merged = new ArrayList<>();
        Interval current = sorted.getFirst();
        for (int index = 1; index < sorted.size(); index++) {
            Interval next = sorted.get(index);
            if (!next.start().isAfter(current.end())) {
                current = new Interval(current.start(), max(current.end(), next.end()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private long duration(List<Interval> intervals) {
        return intervals.stream()
                .mapToLong(interval -> Math.max(
                        0,
                        Duration.between(interval.start(), interval.end()).toSeconds()
                ))
                .sum();
    }

    private List<Interval> concat(List<Interval> first, List<Interval> second) {
        List<Interval> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    public record Metrics(
            long siteSeconds,
            long messengerOutsideSiteSeconds,
            long confirmedSeconds
    ) {
        static Metrics empty() {
            return new Metrics(0, 0, 0);
        }
    }

    public record DailyAndAverage(
            Metrics daily,
            long averageDailyConfirmedSeconds
    ) {
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {
    }
}
