package com.hunt.otziv.gamification.service;

import com.hunt.otziv.gamification.dto.GamificationBalanceResponse;
import com.hunt.otziv.gamification.dto.GamificationBalancesResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerSummaryResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerRebuildResponse;
import com.hunt.otziv.gamification.dto.GamificationScorePreviewActorResponse;
import com.hunt.otziv.gamification.model.GamificationEvent;
import com.hunt.otziv.gamification.model.GamificationRule;
import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class GamificationShadowScoreService {

    private final GamificationScoreLedgerRepository repository;
    private final com.hunt.otziv.gamification.repository.GamificationEventRepository eventRepository;
    private final GamificationSettingsService settingsService;
    private final GamificationRuleService ruleService;
    private final GamificationTimelinessService timelinessService;

    @Transactional
    public void recordForEvent(GamificationEvent event) {
        recordForEventInternal(event);
    }

    @Transactional
    public GamificationScoreLedgerRebuildResponse rebuild(int days) {
        Period period = period(days);
        boolean enabled = settingsService.isShadowScoringEnabled();
        if (!enabled) {
            return new GamificationScoreLedgerRebuildResponse(
                    period.from(), period.to(), period.days(), false, 0, 0, 0,
                    number(repository.sumPoints(period.fromInclusive(), period.toExclusive()))
            );
        }

        List<GamificationEvent> events = eventRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                        period.fromInclusive(),
                        period.toExclusive()
                );
        long deleted = repository.deleteBySourceEventCreatedAtGreaterThanEqualAndSourceEventCreatedAtLessThan(
                period.fromInclusive(),
                period.toExclusive()
        );
        long created = 0;
        for (GamificationEvent event : events) {
            if (recordForEventInternal(event)) {
                created++;
            }
        }
        long totalPoints = number(repository.sumPoints(period.fromInclusive(), period.toExclusive()));

        return new GamificationScoreLedgerRebuildResponse(
                period.from(),
                period.to(),
                period.days(),
                true,
                events.size(),
                deleted,
                created,
                totalPoints
        );
    }

    private boolean recordForEventInternal(GamificationEvent event) {
        if (event == null || event.getEventType() == null || !settingsService.isShadowScoringEnabled()) {
            return false;
        }

        GamificationRule rule = ruleService.readRules().get(event.getEventType());
        if (rule == null || !rule.isEnabled() || rule.getPoints() <= 0) {
            return false;
        }

        int points = timelinessService.score(rule.getPoints(), event.getTimelinessMultiplier());
        String uniqueScoreKey = uniqueScoreKey(event);
        try {
            if (repository.existsByUniqueScoreKey(uniqueScoreKey)) {
                return false;
            }
            repository.save(GamificationScoreLedger.builder()
                    .eventId(event.getId())
                    .eventType(event.getEventType())
                    .actorUserId(event.getActorUserId())
                    .actorRole(event.getActorRole())
                    .actorName(event.getActorName())
                    .points(points)
                    .rulePoints(rule.getPoints())
                    .basePoints(rule.getPoints())
                    .timelinessMultiplier(event.getTimelinessMultiplier())
                    .delayDays(event.getDelayDays())
                    .timelinessBucket(event.getTimelinessBucket())
                    .orderId(event.getOrderId())
                    .reviewId(event.getReviewId())
                    .uniqueScoreKey(uniqueScoreKey)
                    .sourceEventCreatedAt(event.getCreatedAt())
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Gamification score duplicate skipped: {}", uniqueScoreKey);
        } catch (RuntimeException e) {
            log.warn("Gamification shadow score was not recorded: eventId={}, eventType={}",
                    event.getId(), event.getEventType(), e);
        }
        return false;
    }

    @Transactional(readOnly = true)
    public GamificationScoreLedgerSummaryResponse summary(int days, long previewPoints) {
        Period period = period(days);
        long totalEvents = repository.countBySourceEventCreatedAtGreaterThanEqualAndSourceEventCreatedAtLessThan(
                period.fromInclusive(),
                period.toExclusive()
        );
        long totalPoints = number(repository.sumPoints(period.fromInclusive(), period.toExclusive()));
        List<GamificationScorePreviewActorResponse> topActors = repository
                .topActors(period.fromInclusive(), period.toExclusive(), PageRequest.of(0, 20))
                .stream()
                .map(this::actor)
                .toList();

        return new GamificationScoreLedgerSummaryResponse(
                period.from(),
                period.to(),
                period.days(),
                totalEvents,
                totalPoints,
                previewPoints,
                previewPoints - totalPoints,
                topActors
        );
    }

    @Transactional(readOnly = true)
    public GamificationBalancesResponse balances(int days) {
        Period period = period(days);
        Map<String, BalanceAccumulator> balances = new LinkedHashMap<>();
        for (Object[] row : repository.balanceRows(period.fromInclusive(), period.toExclusive())) {
            if (row == null || row.length < 6) {
                continue;
            }
            Long actorUserId = numberOrNull(row[0]);
            String actorName = row[1] == null ? null : String.valueOf(row[1]);
            String actorRole = row[2] == null ? null : String.valueOf(row[2]);
            String eventType = row[3] == null ? "" : String.valueOf(row[3]);
            long events = number(row[4]);
            long points = number(row[5]);
            long basePoints = row.length > 6 ? number(row[6]) : points;
            long onTimeEvents = row.length > 7 ? number(row[7]) : events;
            long delayedEvents = row.length > 8 ? number(row[8]) : 0L;
            BalanceAccumulator balance = balances.computeIfAbsent(
                    actorKey(actorUserId, actorName, actorRole),
                    key -> new BalanceAccumulator(actorUserId, actorName, actorRole)
            );
            balance.add(eventType, events, points, basePoints, onTimeEvents, delayedEvents);
        }

        List<GamificationBalanceResponse> rows = balances.values().stream()
                .sorted(Comparator.comparingLong(BalanceAccumulator::totalPoints).reversed())
                .limit(100)
                .map(BalanceAccumulator::response)
                .toList();
        return new GamificationBalancesResponse(period.from(), period.to(), period.days(), rows);
    }

    private GamificationScorePreviewActorResponse actor(Object[] row) {
        Long actorUserId = row != null && row.length > 0 ? numberOrNull(row[0]) : null;
        String actorName = row != null && row.length > 1 && row[1] != null ? String.valueOf(row[1]) : null;
        String actorRole = row != null && row.length > 2 && row[2] != null ? String.valueOf(row[2]) : null;
        long events = row != null && row.length > 3 ? number(row[3]) : 0L;
        long points = row != null && row.length > 4 ? number(row[4]) : 0L;
        return new GamificationScorePreviewActorResponse(actorUserId, actorName, actorRole, events, points);
    }

    private String uniqueScoreKey(GamificationEvent event) {
        String sourceKey = event.getUniqueEventKey() != null && !event.getUniqueEventKey().isBlank()
                ? event.getUniqueEventKey()
                : "event:" + event.getId();
        return "shadow:" + sourceKey;
    }

    private Period period(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(safeDays - 1L);
        return new Period(from, to, safeDays, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Long numberOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String actorKey(Long actorUserId, String actorName, String actorRole) {
        return (actorUserId == null ? "system" : actorUserId)
                + ":" + (actorRole == null ? "" : actorRole)
                + ":" + (actorName == null ? "" : actorName);
    }

    private record Period(
            LocalDate from,
            LocalDate to,
            int days,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
    }

    private static class BalanceAccumulator {
        private final Long actorUserId;
        private final String actorName;
        private final String actorRole;
        private long totalEvents;
        private long totalPoints;
        private long reviewPublishedEvents;
        private long reviewPublishedPoints;
        private long orderPaidEvents;
        private long orderPaidPoints;
        private long badReviewTaskDoneEvents;
        private long badReviewTaskDonePoints;
        private long reviewRecoveryTaskDoneEvents;
        private long reviewRecoveryTaskDonePoints;
        private long onTimeEvents;
        private long delayedEvents;
        private long lostPoints;

        private BalanceAccumulator(Long actorUserId, String actorName, String actorRole) {
            this.actorUserId = actorUserId;
            this.actorName = actorName;
            this.actorRole = actorRole;
        }

        private void add(String eventType, long events, long points, long basePoints, long onTimeEvents, long delayedEvents) {
            totalEvents += events;
            totalPoints += points;
            this.onTimeEvents += onTimeEvents;
            this.delayedEvents += delayedEvents;
            this.lostPoints += Math.max(0, basePoints - points);
            if (GamificationEventService.REVIEW_PUBLISHED.equals(eventType)) {
                reviewPublishedEvents += events;
                reviewPublishedPoints += points;
            } else if (GamificationEventService.ORDER_PAID.equals(eventType)) {
                orderPaidEvents += events;
                orderPaidPoints += points;
            } else if (GamificationEventService.BAD_REVIEW_TASK_DONE.equals(eventType)) {
                badReviewTaskDoneEvents += events;
                badReviewTaskDonePoints += points;
            } else if (GamificationEventService.REVIEW_RECOVERY_TASK_DONE.equals(eventType)) {
                reviewRecoveryTaskDoneEvents += events;
                reviewRecoveryTaskDonePoints += points;
            }
        }

        private long totalPoints() {
            return totalPoints;
        }

        private GamificationBalanceResponse response() {
            return new GamificationBalanceResponse(
                    actorUserId,
                    actorName,
                    actorRole,
                    totalEvents,
                    totalPoints,
                    reviewPublishedEvents,
                    reviewPublishedPoints,
                    orderPaidEvents,
                    orderPaidPoints,
                    badReviewTaskDoneEvents,
                    badReviewTaskDonePoints,
                    reviewRecoveryTaskDoneEvents,
                    reviewRecoveryTaskDonePoints,
                    onTimeEvents,
                    delayedEvents,
                    lostPoints
            );
        }
    }
}
