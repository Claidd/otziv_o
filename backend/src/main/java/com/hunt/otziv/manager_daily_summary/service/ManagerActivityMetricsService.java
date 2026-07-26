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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerActivityMetricsService {
    private static final long EVENT_TAIL_SECONDS = 60;

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
        List<LocalDateTime> sitePoints = activityRepository
                .findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(managerId, from, to).stream()
                .map(ManagerSiteActivityEvent::getOccurredAt)
                .filter(Objects::nonNull)
                .toList();
        List<LocalDateTime> messengerPoints = staffMessagePoints(messages);
        return calculateFromPoints(sitePoints, messengerPoints, to);
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

        Map<LocalDate, List<LocalDateTime>> siteByDate = activityRepository
                .findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(managerId, from, end).stream()
                .map(ManagerSiteActivityEvent::getOccurredAt)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.groupingBy(
                        LocalDateTime::toLocalDate,
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

        Metrics daily = calculateFromPoints(
                siteByDate.getOrDefault(date, List.of()),
                messengerByDate.getOrDefault(date, List.of()),
                end
        );
        long monthConfirmedSeconds = 0;
        for (LocalDate day = monthStart; !day.isAfter(date); day = day.plusDays(1)) {
            LocalDateTime limit = day.equals(date) ? end : day.plusDays(1).atStartOfDay();
            monthConfirmedSeconds += calculateFromPoints(
                    siteByDate.getOrDefault(day, List.of()),
                    messengerByDate.getOrDefault(day, List.of()),
                    limit
            ).confirmedSeconds();
        }
        long elapsedDays = ChronoUnit.DAYS.between(monthStart, date) + 1;
        return new DailyAndAverage(
                daily,
                Math.round(monthConfirmedSeconds / (double) elapsedDays)
        );
    }

    private Metrics calculateFromPoints(
            List<LocalDateTime> sitePoints,
            List<LocalDateTime> messengerPoints,
            LocalDateTime limit
    ) {
        Duration idle = Duration.ofMinutes(Math.max(
                1,
                settings.getInt("manager.summary.activity-idle-minutes", 15)
        ));
        List<Interval> site = sessions(sitePoints, idle, limit);
        List<Interval> messenger = sessions(messengerPoints, idle, limit);
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

    private List<Interval> sessions(List<LocalDateTime> source, Duration idle, LocalDateTime limit) {
        List<LocalDateTime> points = source == null
                ? List.of()
                : source.stream().filter(Objects::nonNull).sorted().toList();
        if (points.isEmpty()) {
            return List.of();
        }
        List<Interval> intervals = new ArrayList<>();
        LocalDateTime start = points.getFirst();
        LocalDateTime last = start;
        for (int index = 1; index < points.size(); index++) {
            LocalDateTime point = points.get(index);
            if (Duration.between(last, point).compareTo(idle) > 0) {
                intervals.add(new Interval(start, min(last.plusSeconds(EVENT_TAIL_SECONDS), limit)));
                start = point;
            }
            last = point;
        }
        intervals.add(new Interval(start, min(last.plusSeconds(EVENT_TAIL_SECONDS), limit)));
        return merge(intervals);
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
