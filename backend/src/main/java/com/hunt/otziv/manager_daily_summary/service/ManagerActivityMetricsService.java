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

        var site = activityRepository.findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(managerId, from, end);
        var points = staffMessagePoints(messageRepository.findByActorManagerIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(managerId, from, end));
        return dailyAndAverage(site, points, date, end);
    }

    private DailyAndAverage dailyAndAverage(List<ManagerSiteActivityEvent> events, List<LocalDateTime> points,
            LocalDate date, LocalDateTime end) {
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDateTime selectedDayStart = date.atStartOfDay();
        var siteByDate = events.stream().filter(event -> event.getOccurredAt() != null)
                .collect(Collectors.groupingBy(event -> event.getOccurredAt().toLocalDate()));
        var messengerByDate = points.stream().collect(Collectors.groupingBy(LocalDateTime::toLocalDate));
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

    @Transactional(readOnly = true)
    public Map<Long, DailyAndAverage> dailyAndMonthAverages(java.util.Collection<Long> ids, LocalDate date, LocalDateTime until) {
        if (ids == null || ids.isEmpty() || date == null || until == null) return Map.of();
        LocalDateTime start = date.atStartOfDay(), limit = date.plusDays(1).atStartOfDay();
        LocalDateTime end = until.isBefore(start) ? start : until.isAfter(limit) ? limit : until;
        var activity = loadBatch(ids, date.withDayOfMonth(1).atStartOfDay(), end);
        Map<Long, DailyAndAverage> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, dailyAndAverage(activity.site().getOrDefault(id, List.of()),
                activity.messages().getOrDefault(id, List.of()), date, end)));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<Long, Metrics> calculateForManagers(java.util.Collection<Long> ids, LocalDateTime from, LocalDateTime to) {
        if (ids == null || ids.isEmpty() || from == null || to == null || !to.isAfter(from)) return Map.of();
        var activity = loadBatch(ids, from, to);
        Map<Long, Metrics> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, calculateFromEvents(activity.site().getOrDefault(id, List.of()),
                activity.messages().getOrDefault(id, List.of()), from, to)));
        return result;
    }

    /** One authorized team read, shared only within this assembly; daily and monthly boundaries stay independent. */
    @Transactional(readOnly = true)
    public TeamActivity forTeam(java.util.Collection<Long> ids, LocalDate date, LocalDateTime until,
                                LocalDate month, LocalDateTime monthUntil) {
        if (ids == null || ids.isEmpty()) return new TeamActivity(Map.of(), Map.of());
        LocalDateTime dayStart = date.atStartOfDay(), dayLimit = date.plusDays(1).atStartOfDay();
        LocalDateTime dayEnd = until.isBefore(dayStart) ? dayStart : until.isAfter(dayLimit) ? dayLimit : until;
        LocalDateTime dailyFrom = date.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthFrom = month.withDayOfMonth(1).atStartOfDay();
        // A historical month picker must not expand a read across all intervening months.
        if (monthUntil.isBefore(dailyFrom) || dayEnd.isBefore(monthFrom)) {
            return new TeamActivity(dailyAndMonthAverages(ids, date, dayEnd), calculateForManagers(ids, monthFrom, monthUntil));
        }
        LocalDateTime from = dailyFrom.isBefore(monthFrom) ? dailyFrom : monthFrom;
        LocalDateTime to = dayEnd.isAfter(monthUntil) ? dayEnd : monthUntil;
        var activity = loadBatch(ids, from, to);
        Map<Long, DailyAndAverage> daily = new LinkedHashMap<>();
        Map<Long, Metrics> monthly = new LinkedHashMap<>();
        ids.forEach(id -> {
            var site = activity.site().getOrDefault(id, List.of());
            // The message repository's upper bound is exclusive, including when reusing a wider read.
            var messages = activity.messages().getOrDefault(id, List.of());
            daily.put(id, dailyAndAverage(site, messages.stream().filter(at -> at.isBefore(dayEnd)).toList(), date, dayEnd));
            monthly.put(id, calculateFromEvents(site, messages.stream().filter(at -> at.isBefore(monthUntil)).toList(), monthFrom, monthUntil));
        });
        return new TeamActivity(Map.copyOf(daily), Map.copyOf(monthly));
    }

    public record TeamActivity(Map<Long, DailyAndAverage> daily, Map<Long, Metrics> monthly) {}

    private record BatchActivity(Map<Long, List<ManagerSiteActivityEvent>> site, Map<Long, List<LocalDateTime>> messages) {}

    private BatchActivity loadBatch(java.util.Collection<Long> ids, LocalDateTime from, LocalDateTime to) {
        // Only activity timestamps/types cross the read boundary; message bodies and identities are not loaded.
        Map<Long, List<ManagerSiteActivityEvent>> site = new LinkedHashMap<>();
        for (var row : activityRepository.pointsForManagers(ids, from, to)) {
            var event = new ManagerSiteActivityEvent(); event.setOccurredAt(row.getOccurredAt()); event.setActivityType(row.getActivityType());
            site.computeIfAbsent(row.getManagerId(), ignored -> new ArrayList<>()).add(event);
        }
        Map<Long, List<LocalDateTime>> messages = new LinkedHashMap<>();
        for (var row : messageRepository.staffPointsForManagers(ids, from, to))
            messages.computeIfAbsent(row.getManagerId(), ignored -> new ArrayList<>()).add(row.getMessageAt());
        return new BatchActivity(site, messages);
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
