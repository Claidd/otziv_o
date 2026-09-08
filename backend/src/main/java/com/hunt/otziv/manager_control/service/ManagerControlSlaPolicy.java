package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** One SLA calculation for aggregate and concrete control-card presentation. */
@Service
@RequiredArgsConstructor
public class ManagerControlSlaPolicy {
    private static final String CONTROL_CARD_TARGET_SETTING = "manager.sla.target.control-card-minutes";
    private static final String CONTROL_CARD_HARD_SETTING = "manager.sla.hard.control-card-minutes";
    private static final int CONTROL_CARD_TARGET_MINUTES = 30;
    private static final int CONTROL_CARD_HARD_MINUTES = 60;
    private final AppSettingService appSettingService;

    SlaWindow slaWindow(String code, LocalDateTime firstObservedAt, LocalDateTime completedAt) {
        if (!appSettingService.getBoolean("manager.sla.enabled", false)) {
            return new SlaWindow(null, null, null, null);
        }
        LocalDateTime started = firstObservedAt == null ? LocalDateTime.now() : firstObservedAt;
        int targetMinutes = controlCardTargetMinutes();
        int hardMinutes = controlCardHardMinutes(targetMinutes);
        LocalDateTime target = started.plusMinutes(targetMinutes);
        LocalDateTime hard = started.plusMinutes(hardMinutes);
        LocalDateTime reference = completedAt == null ? LocalDateTime.now() : completedAt;
        String state = reference.isAfter(hard) ? "OVERDUE" : reference.isAfter(target) ? "LATE" : "TARGET";
        if (completedAt != null) state = "COMPLETED_" + state;
        return new SlaWindow(started, target, hard, state);
    }

    int controlCardTargetMinutes() {
        return Math.max(1, appSettingService.getInt(CONTROL_CARD_TARGET_SETTING, CONTROL_CARD_TARGET_MINUTES));
    }

    int controlCardHardMinutes(int targetMinutes) {
        return Math.max(targetMinutes, appSettingService.getInt(CONTROL_CARD_HARD_SETTING, CONTROL_CARD_HARD_MINUTES));
    }

    ManagerControlConcreteItemResponse decorateConcreteSla(
            ManagerDailyControlItem parentItem,
            ManagerControlConcreteItemResponse response
    ) {
        if (response == null) {
            return null;
        }
        LocalDateTime firstObservedAt = firstNonNullTime(
                response.firstObservedAt(),
                parentItem == null ? null : parentItem.getCreatedAt(),
                LocalDateTime.now()
        );
        String code = firstNonBlank(
                parentItem == null ? null : parentItem.getReasonCode(),
                parentItem == null ? null : parentItem.getSectionCode(),
                response.type()
        );
        SlaWindow sla = slaWindow(code, firstObservedAt, response.resolvedAt());
        return response.withSla(
                sla.firstObservedAt(),
                sla.targetDeadlineAt(),
                sla.hardDeadlineAt(),
                sla.state()
        );
    }

    record SlaWindow(
            LocalDateTime firstObservedAt,
            LocalDateTime targetDeadlineAt,
            LocalDateTime hardDeadlineAt,
            String state
    ) {
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = safe(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private LocalDateTime firstNonNullTime(LocalDateTime... values) {
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
