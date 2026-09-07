package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_control.dto.ManagerControlEventResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads control events and calculates quality without changing the control or its history.
 * The mutation workflow decides whether to apply and persist the immutable result in its transaction.
 */
@Component
@RequiredArgsConstructor
public class ManagerControlQualityQueries {
    private final ManagerDailyControlEventRepository dailyControlEventRepository;
    private final AppSettingService appSettingService;

    Quality evaluate(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        long openCritical = items.stream().filter(this::isOpenCriticalActionItem).count();
        long openAction = items.stream().filter(this::isOpenActionItem).count();
        long deferred = items.stream().filter(item -> item.getStatus() == ManagerDailyControlItemStatus.DEFERRED).count();
        int riskScore = (int) Math.min(100, openCritical * 20 + openAction * 8 + deferred * 4);
        boolean fastClickRisk = hasFastClickRisk(control);
        if (fastClickRisk) {
            riskScore = Math.min(100, riskScore + 25);
        }
        int stageScore = 0;
        stageScore += control.getMorningCompletedAt() == null && hasActionItems(items) ? 10 : 0;
        stageScore += control.getClosedAt() == null && hasActionItems(items) ? 8 : 0;
        int quality = Math.max(0, 100 - riskScore - stageScore);
        String qualityGrade = quality >= 90 ? "A" : quality >= 75 ? "B" : quality >= 55 ? "C" : "D";
        return new Quality(riskScore, fastClickRisk, quality, qualityGrade);
    }

    record Quality(int riskScore, boolean fastClickRisk, int qualityScore, String qualityGrade) { }


    boolean hasFastClickRisk(ManagerDailyControl control) {
        List<ManagerDailyControlEvent> actions = dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control).stream()
                .filter(event -> isManagerClientMessageResolutionEvent(control, event))
                .sorted(Comparator.comparing(ManagerDailyControlEvent::getCreatedAt))
                .toList();
        if (actions.size() < 3) {
            return false;
        }
        int warningCount = Math.max(3, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_WARNING_COUNT,
                3
        ));
        int warningSeconds = Math.max(3, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_WARNING_SECONDS,
                10
        ));
        int criticalCount = Math.max(warningCount, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_CRITICAL_COUNT,
                10
        ));
        int criticalSeconds = Math.max(warningSeconds, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_CRITICAL_SECONDS,
                60
        ));
        return hasActionBurst(actions, warningCount, warningSeconds)
                || hasActionBurst(actions, criticalCount, criticalSeconds);
    }

    boolean isManagerClientMessageResolutionEvent(
            ManagerDailyControl control,
            ManagerDailyControlEvent event
    ) {
        if (control == null || event == null || event.getCreatedAt() == null
                || event.getActorUserId() == null || event.getItem() == null
                || event.getActionType() == null
                || event.getActionType() == ManagerDailyControlActionType.DEFERRED
                || (event.getEventType() != ManagerDailyControlEventType.ITEM_ACTION
                && event.getEventType() != ManagerDailyControlEventType.ITEM_RESOLVED)) {
            return false;
        }
        Long managerUserId = control.getManagerUserId();
        if (managerUserId == null && control.getManager() != null
                && control.getManager().getUser() != null) {
            managerUserId = control.getManager().getUser().getId();
        }
        if (!Objects.equals(event.getActorUserId(), managerUserId)) {
            return false;
        }
        String reasonCode = safe(event.getItem().getReasonCode());
        return "UNANSWERED_CLIENT_MESSAGES".equals(reasonCode)
                || "SUSPICIOUS_CLIENT_CLOSURES".equals(reasonCode);
    }

    boolean hasActionBurst(
            List<ManagerDailyControlEvent> actions,
            int count,
            int seconds
    ) {
        if (actions == null || actions.size() < count) {
            return false;
        }
        for (int index = count - 1; index < actions.size(); index++) {
            LocalDateTime first = actions.get(index - count + 1).getCreatedAt();
            LocalDateTime last = actions.get(index).getCreatedAt();
            if (first != null && last != null && ChronoUnit.SECONDS.between(first, last) <= seconds) {
                return true;
            }
        }
        return false;
    }

    List<ManagerControlEventResponse> events(ManagerDailyControl control) {
        return dailyControlEventRepository.findByControlOrderByCreatedAtDesc(control).stream()
                .map(event -> new ManagerControlEventResponse(
                        event.getId(),
                        event.getItem() == null ? null : event.getItem().getId(),
                        event.getItem() == null ? null : event.getItem().getLabel(),
                        event.getActorUserId(),
                        event.getEventType().name(),
                        event.getActionType() == null ? null : event.getActionType().name(),
                        event.getComment(),
                        event.getCreatedAt()
                ))
                .toList();
    }

    private boolean hasActionItems(List<ManagerDailyControlItem> items) {
        return items != null && items.stream()
                .anyMatch(item -> item.getGroup() == ManagerDailyControlGroup.ACTION && item.getCount() > 0);
    }

    private boolean isOpenActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getStatus() == ManagerDailyControlItemStatus.OPEN;
    }

    private boolean isOpenCriticalActionItem(ManagerDailyControlItem item) {
        return isOpenActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
