package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.dto.ManagerQueueStateResponse;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_control.repository.ManagerQueueStateEventRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
public class ManagerQueueStateService {
    private static final String CONTROL_CARD_TARGET_SETTING = "manager.sla.target.control-card-minutes";
    private static final String CONTROL_CARD_HARD_SETTING = "manager.sla.hard.control-card-minutes";
    private static final int CONTROL_CARD_TARGET_MINUTES = 30;
    private static final int CONTROL_CARD_HARD_MINUTES = 60;

    private final ManagerDailyControlRepository controlRepository;
    private final ManagerDailyControlItemRepository itemRepository;
    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ManagerQueueStateEventRepository eventRepository;
    private final ManagerRepository managerRepository;
    private final UserService userService;
    private final AppSettingService settings;
    private final GamificationEventService gamification;

    @Scheduled(fixedDelayString = "${manager.sla.observer-delay-ms:60000}", initialDelay = 30000)
    @Transactional
    public void observeToday() {
        if (!settings.getBoolean("manager.sla.enabled", false)) return;
        LocalDateTime now = LocalDateTime.now();
        for (ManagerDailyControl control : controlRepository.findByControlDate(now.toLocalDate())) observe(control, now);
    }

    @Transactional
    public ManagerQueueStateResponse current(Principal principal) {
        User user = principal == null ? null : userService.findByUserName(principal.getName()).orElse(null);
        Manager manager = user == null ? null : managerRepository.findByUserId(user.getId()).orElse(null);
        if (manager == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль менеджера не найден");
        LocalDateTime now = LocalDateTime.now();
        if (settings.getBoolean("manager.sla.enabled", false)) {
            controlRepository.findByControlDateAndManager(now.toLocalDate(), manager).ifPresent(control -> observe(control, now));
        }
        return aggregate(manager, now.toLocalDate(), now);
    }

    @Transactional(readOnly = true)
    public ManagerQueueStateResponse aggregate(Manager manager, LocalDate date, LocalDateTime until) {
        boolean enabled = settings.getBoolean("manager.sla.enabled", false);
        int targetHours = Math.max(1, Math.min(24, settings.getInt("manager.sla.control-target-hours", 14)));
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime end = until.isAfter(date.plusDays(1).atStartOfDay()) ? date.plusDays(1).atStartOfDay() : until;
        List<ManagerQueueStateEvent> events = eventRepository.findByManager_IdAndObservedAtBetweenOrderByObservedAtAscIdAsc(manager.getId(), from, end.plusNanos(1));
        long controlled = 0, clean = 0, streak = 0, hardBreaches = 0;
        for (int i = 0; i < events.size(); i++) {
            ManagerQueueStateEvent event = events.get(i);
            LocalDateTime segmentEnd = i + 1 < events.size() ? events.get(i + 1).getObservedAt() : end;
            long seconds = Math.max(0, Duration.between(event.getObservedAt(), segmentEnd).getSeconds());
            if (!"OVERDUE".equals(event.getStateCode())) controlled += seconds;
            if ("CLEAN".equals(event.getStateCode())) clean += seconds;
            hardBreaches = Math.max(hardBreaches, event.getOverdueCount());
        }
        ManagerQueueStateEvent last = events.isEmpty() ? null : events.get(events.size() - 1);
        if (last != null && !"OVERDUE".equals(last.getStateCode())) {
            LocalDateTime streakStart = last.getObservedAt();
            for (int i = events.size() - 2; i >= 0; i--) {
                if ("OVERDUE".equals(events.get(i).getStateCode())) break;
                streakStart = events.get(i).getObservedAt();
            }
            streak = Math.max(0, Duration.between(streakStart, end).getSeconds());
        }
        long targetSeconds = targetHours * 3600L;
        int percent = (int) Math.min(100, Math.round(controlled * 100.0 / targetSeconds));
        return new ManagerQueueStateResponse(enabled, date, last == null ? "NOT_OBSERVED" : last.getStateCode(),
                last == null ? 0 : last.getOpenActionCount(), last == null ? 0 : last.getWithinTargetCount(),
                last == null ? 0 : last.getTargetMissedCount(), last == null ? 0 : last.getOverdueCount(),
                hardBreaches, controlled, clean, streak, targetHours, percent, last == null ? null : last.getObservedAt());
    }

    private void observe(ManagerDailyControl control, LocalDateTime now) {
        List<ManagerDailyControlItem> open = itemRepository.findByControl(control).stream()
                .filter(item -> item.getGroup() == ManagerDailyControlGroup.ACTION)
                .filter(item -> item.getItemType() != ManagerDailyControlItemType.ORDER_STATUS)
                .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                .toList();
        Map<Long, List<ManagerDailyControlConcreteItem>> concreteByParent = open.isEmpty()
                ? Map.of()
                : concreteItemRepository.findByParentItemIn(open).stream()
                .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                .filter(item -> item.getParentItem() != null && item.getParentItem().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getParentItem().getId()));
        long within = 0, missed = 0, overdue = 0, total = 0;
        for (ManagerDailyControlItem item : open) {
            long count = Math.max(1, item.getCount());
            int target = targetMinutes(item.getReasonCode());
            int hard = hardMinutes(item.getReasonCode());
            List<ManagerDailyControlConcreteItem> concrete = item.getId() == null
                    ? List.of()
                    : concreteByParent.getOrDefault(item.getId(), List.of());
            for (ManagerDailyControlConcreteItem concreteItem : concrete) {
                SlaBucket bucket = classify(
                        concreteItem.getCreatedAt() == null ? item.getCreatedAt() : concreteItem.getCreatedAt(),
                        1,
                        target,
                        hard,
                        now
                );
                within += bucket.within(); missed += bucket.missed(); overdue += bucket.overdue(); total += bucket.total();
            }
            long aggregateRemainder = Math.max(0, count - concrete.size());
            if (aggregateRemainder > 0) {
                LocalDateTime aggregateStartedAt = concrete.stream()
                        .map(ManagerDailyControlConcreteItem::getCreatedAt)
                        .filter(java.util.Objects::nonNull)
                        .min(LocalDateTime::compareTo)
                        .orElse(item.getCreatedAt());
                SlaBucket bucket = classify(aggregateStartedAt, aggregateRemainder, target, hard, now);
                within += bucket.within(); missed += bucket.missed(); overdue += bucket.overdue(); total += bucket.total();
            }
        }
        String state = total == 0 ? "CLEAN" : overdue > 0 ? "OVERDUE" : missed > 0 ? "LATE" : "CONTROLLED";
        ManagerQueueStateEvent previous = eventRepository.findTopByManager_IdOrderByObservedAtDescIdDesc(control.getManager().getId()).orElse(null);
        if (previous != null && previous.getStateCode().equals(state) && previous.getOpenActionCount() == total
                && previous.getWithinTargetCount() == within && previous.getTargetMissedCount() == missed && previous.getOverdueCount() == overdue) return;
        ManagerQueueStateEvent event = new ManagerQueueStateEvent();
        event.setManager(control.getManager()); event.setStateCode(state); event.setOpenActionCount(total);
        event.setWithinTargetCount(within); event.setTargetMissedCount(missed); event.setOverdueCount(overdue); event.setObservedAt(now);
        eventRepository.save(event);
        if ("CLEAN".equals(state) && previous != null && !"CLEAN".equals(previous.getStateCode())) {
            gamification.recordManagerMilestone(GamificationEventService.MANAGER_QUEUE_CLEARED, control.getManager(),
                    control.getControlDate() + ":" + event.getId(), now, "{\"previousState\":\"" + previous.getStateCode() + "\"}");
        }
    }

    private SlaBucket classify(LocalDateTime startedAt, long count, int targetMinutes, int hardMinutes, LocalDateTime now) {
        long safeCount = Math.max(0, count);
        LocalDateTime first = startedAt == null ? now : startedAt;
        if (now.isAfter(first.plusMinutes(hardMinutes))) return new SlaBucket(0, 0, safeCount, safeCount);
        if (now.isAfter(first.plusMinutes(targetMinutes))) return new SlaBucket(0, safeCount, 0, safeCount);
        return new SlaBucket(safeCount, 0, 0, safeCount);
    }

    private int targetMinutes(String reason) {
        return Math.max(1, settings.getInt(CONTROL_CARD_TARGET_SETTING, CONTROL_CARD_TARGET_MINUTES));
    }

    private int hardMinutes(String reason) {
        return Math.max(targetMinutes(reason), settings.getInt(CONTROL_CARD_HARD_SETTING, CONTROL_CARD_HARD_MINUTES));
    }

    private record SlaBucket(long within, long missed, long overdue, long total) {}
}
