package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.model.ManagerDailyControlStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/** Episode accounting, aggregate state and audit writes in the caller's transaction. */
@Service
@RequiredArgsConstructor
public class ManagerControlCardLifecycle {
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final ManagerDailyControlItemRepository dailyControlItemRepository;
    private final ManagerDailyControlEventRepository dailyControlEventRepository;
    private final ManagerPerformanceService managerPerformanceService;
    private final ManagerControlReadSnapshots readSnapshots;

    void recordItemEpisode(
            ManagerDailyControlItem item,
            ManagerDailyControlItemStatus outcome,
            boolean automatic
    ) {
        if (item == null || item.getStatus() != ManagerDailyControlItemStatus.OPEN || outcome == null) {
            return;
        }
        long count = Math.max(1, item.getCount());
        if (automatic) {
            item.setAutoClosedEpisodeCount(item.getAutoClosedEpisodeCount() + count);
            return;
        }
        switch (outcome) {
            case RESOLVED -> item.setResolvedEpisodeCount(item.getResolvedEpisodeCount() + count);
            case ACTION_TAKEN -> item.setActionTakenEpisodeCount(item.getActionTakenEpisodeCount() + count);
            case DEFERRED -> item.setDeferredEpisodeCount(item.getDeferredEpisodeCount() + count);
            case ACKNOWLEDGED -> item.setAcknowledgedEpisodeCount(item.getAcknowledgedEpisodeCount() + count);
            case OPEN -> { }
        }
    }

    void recordConcreteEpisode(
            ManagerDailyControlConcreteItem item,
            ManagerDailyControlItemStatus outcome,
            boolean automatic
    ) {
        if (item == null || item.getStatus() != ManagerDailyControlItemStatus.OPEN || outcome == null) {
            return;
        }
        if (automatic) {
            item.setAutoClosedEpisodeCount(item.getAutoClosedEpisodeCount() + 1);
            return;
        }
        switch (outcome) {
            case RESOLVED -> item.setResolvedEpisodeCount(item.getResolvedEpisodeCount() + 1);
            case ACTION_TAKEN -> item.setActionTakenEpisodeCount(item.getActionTakenEpisodeCount() + 1);
            case DEFERRED -> item.setDeferredEpisodeCount(item.getDeferredEpisodeCount() + 1);
            case ACKNOWLEDGED -> item.setAcknowledgedEpisodeCount(item.getAcknowledgedEpisodeCount() + 1);
            case OPEN -> { }
        }
    }

    void updateParentItemFromConcreteItems(ManagerDailyControlItem parentItem) {
        if (parentItem == null || parentItem.getGroup() != ManagerDailyControlGroup.ACTION) {
            return;
        }
        List<ManagerDailyControlConcreteItem> concreteItems = dailyControlConcreteItemRepository.findByParentItem(parentItem);
        if (concreteItems.isEmpty() || concreteItems.size() < parentItem.getCount()) {
            return;
        }
        boolean allHandled = concreteItems.stream().noneMatch(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN);
        if (!allHandled) {
            reopenParentItemIfConcreteOpen(parentItem);
            return;
        }
        boolean allResolved = concreteItems.stream()
                .allMatch(item -> item.getStatus() == ManagerDailyControlItemStatus.RESOLVED);
        if (allResolved) {
            parentItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            parentItem.setActionType(ManagerDailyControlActionType.RESOLVED);
            parentItem.setComment("Все конкретные карточки внутри пункта закрыты");
            parentItem.setResolvedAt(LocalDateTime.now());
            parentItem.setAutomaticResolution(false);
        } else {
            parentItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
            parentItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
            parentItem.setComment("Все конкретные карточки внутри пункта обработаны");
            parentItem.setResolvedAt(null);
            parentItem.setAutomaticResolution(false);
        }
        dailyControlItemRepository.save(parentItem);
    }

    void reopenParentItemIfConcreteOpen(ManagerDailyControlItem parentItem) {
        if (parentItem == null
                || parentItem.getStatus() == ManagerDailyControlItemStatus.OPEN
                || parentItem.getGroup() != ManagerDailyControlGroup.ACTION) {
            return;
        }
        boolean hasOpenConcrete = dailyControlConcreteItemRepository.findByParentItem(parentItem).stream()
                .anyMatch(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN);
        if (!hasOpenConcrete) {
            return;
        }
        parentItem.setStatus(ManagerDailyControlItemStatus.OPEN);
        parentItem.setActionType(null);
        parentItem.setComment(null);
        parentItem.setResolvedAt(null);
        parentItem.setAutomaticResolution(false);
        dailyControlItemRepository.save(parentItem);
    }

    boolean isOpenActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getStatus() == ManagerDailyControlItemStatus.OPEN;
    }

    boolean isHandledActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getStatus() != ManagerDailyControlItemStatus.OPEN
                && item.getStatus() != ManagerDailyControlItemStatus.RESOLVED;
    }

    boolean isOpenCriticalActionItem(ManagerDailyControlItem item) {
        return isOpenActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL;
    }

    boolean isHandledCriticalActionItem(ManagerDailyControlItem item) {
        return isHandledActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL;
    }

    ManagerDailyControlStatus recalculateControlStatus(ManagerDailyControl control) {
        return recalculateControlStatus(dailyControlItemRepository.findByControl(control));
    }

    ManagerDailyControlStatus recalculateControlStatus(List<ManagerDailyControlItem> items) {
        boolean hasOpenCritical = items.stream().anyMatch(this::isOpenCriticalActionItem);
        if (hasOpenCritical) {
            return ManagerDailyControlStatus.RED;
        }
        boolean hasOpenWarning = items.stream().anyMatch(item -> isOpenActionItem(item) && item.getSeverity() == ManagerDailyControlSeverity.WARNING);
        boolean hasHandledCritical = items.stream().anyMatch(this::isHandledCriticalActionItem);
        if (hasOpenWarning || hasHandledCritical) {
            return ManagerDailyControlStatus.YELLOW;
        }
        return ManagerDailyControlStatus.GREEN;
    }

    void saveEvent(
            ManagerDailyControl control,
            ManagerDailyControlItem item,
            Long actorUserId,
            ManagerDailyControlEventType eventType,
            ManagerDailyControlActionType actionType,
            String comment
    ) {
        ManagerDailyControlEvent event = new ManagerDailyControlEvent();
        event.setControl(control);
        event.setItem(item);
        event.setActorUserId(actorUserId);
        event.setEventType(eventType);
        event.setActionType(actionType);
        event.setComment(limit(comment, 1000));
        dailyControlEventRepository.save(event);
        if (control != null && control.managerId() != null) {
            readSnapshots.invalidate(List.of(control.managerId()));
        }
        invalidateManagerPerformance();
    }

    void invalidateManagerPerformance() {
        if (managerPerformanceService != null) {
            managerPerformanceService.invalidate();
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
