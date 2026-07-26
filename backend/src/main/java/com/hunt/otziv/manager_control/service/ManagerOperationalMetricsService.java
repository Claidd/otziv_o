package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_daily_summary.service.ManagerActivityMetricsService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerOperationalMetricsService {

    private final ManagerActivityMetricsService activityMetricsService;
    private final ManagerDailyControlEventRepository controlEventRepository;

    @Transactional(readOnly = true)
    public Metrics calculate(Manager manager, LocalDate date, LocalDateTime until) {
        if (manager == null || manager.getId() == null || date == null || until == null) {
            return Metrics.empty();
        }

        LocalDateTime from = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        LocalDateTime end = until.isBefore(from) ? from : until.isAfter(dayEnd) ? dayEnd : until;
        ManagerActivityMetricsService.DailyAndAverage activity =
                activityMetricsService.calculateDailyAndMonthAverage(manager.getId(), date, end);

        Long managerUserId = manager.getUser() == null ? null : manager.getUser().getId();
        Map<Long, Long> firstReactionByItem = new LinkedHashMap<>();
        if (managerUserId != null) {
            controlEventRepository.findForManagerAudit(manager.getId(), from, end).stream()
                    .filter(event -> Objects.equals(event.getActorUserId(), managerUserId))
                    .filter(this::isManagerReaction)
                    .filter(event -> event.getItem() != null
                            && event.getItem().getId() != null
                            && event.getItem().getCreatedAt() != null
                            && event.getCreatedAt() != null)
                    .sorted(Comparator.comparing(ManagerDailyControlEvent::getCreatedAt))
                    .forEach(event -> firstReactionByItem.putIfAbsent(
                            event.getItem().getId(),
                            Math.max(0, Duration.between(
                                    event.getItem().getCreatedAt(),
                                    event.getCreatedAt()
                            ).getSeconds())
                    ));
        }

        long reactionCount = firstReactionByItem.size();
        long averageReactionSeconds = reactionCount == 0
                ? 0
                : Math.round(firstReactionByItem.values().stream()
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0));
        return new Metrics(
                activity.daily().confirmedSeconds(),
                activity.averageDailyConfirmedSeconds(),
                averageReactionSeconds,
                reactionCount
        );
    }

    private boolean isManagerReaction(ManagerDailyControlEvent event) {
        return event.getEventType() == ManagerDailyControlEventType.ITEM_ACTION
                || event.getEventType() == ManagerDailyControlEventType.ITEM_RESOLVED;
    }

    public record Metrics(
            long activeWorkSeconds,
            long averageDailyWorkSeconds,
            long averageReactionSeconds,
            long reactionCount
    ) {
        static Metrics empty() {
            return new Metrics(0, 0, 0, 0);
        }
    }
}
