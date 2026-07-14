package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.dto.ManagerQueueStateResponse;
import com.hunt.otziv.manager_control.model.*;
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
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
public class ManagerQueueStateService {
    private final ManagerDailyControlRepository controlRepository;
    private final ManagerDailyControlItemRepository itemRepository;
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
                .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                .toList();
        long within = 0, missed = 0, overdue = 0, total = 0;
        for (ManagerDailyControlItem item : open) {
            long count = Math.max(1, item.getCount());
            total += count;
            LocalDateTime first = item.getCreatedAt() == null ? now : item.getCreatedAt();
            int target = targetMinutes(item.getReasonCode());
            int hard = hardMinutes(item.getReasonCode());
            if (now.isAfter(first.plusMinutes(hard))) overdue += count;
            else if (now.isAfter(first.plusMinutes(target))) missed += count;
            else within += count;
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

    private int targetMinutes(String reason) { return settingMinutes("target", reason, 120); }
    private int hardMinutes(String reason) { return settingMinutes("hard", reason, 720); }
    private int settingMinutes(String kind, String reason, int fallback) {
        String type = "CLIENT_CHAT_UNANSWERED".equals(reason) ? "message" : "LEADS".equals(reason) ? "lead"
                : reason != null && reason.contains("RISK") ? "risk" : "default";
        return Math.max(1, settings.getInt("manager.sla." + kind + "." + type + "-minutes", fallback));
    }
}
