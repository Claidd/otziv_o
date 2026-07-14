package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.dto.ManagerActionBalance;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ManagerActionBalanceService {

    private static final String OVERDUE = "OVERDUE_ORDERS";
    private static final String RISKS = "OPEN_RISKS";
    private static final String UNANSWERED = "UNANSWERED_CLIENT_MESSAGES";

    public ManagerActionBalance calculate(
            List<ManagerDailyControlItem> sourceItems,
            List<ManagerDailyControlConcreteItem> sourceConcreteItems
    ) {
        List<ManagerDailyControlItem> items = sourceItems == null ? List.of() : sourceItems;
        List<ManagerDailyControlConcreteItem> concreteItems = sourceConcreteItems == null ? List.of() : sourceConcreteItems;
        Map<Long, Long> concreteCountByParent = new HashMap<>();

        long resolved = 0;
        long actionTaken = 0;
        long deferred = 0;
        long acknowledged = 0;
        long autoClosed = 0;

        for (ManagerDailyControlConcreteItem concrete : concreteItems) {
            if (!isPrimaryActionConcrete(concrete)) {
                continue;
            }
            if (concrete.getParentItem().getId() != null) {
                concreteCountByParent.merge(concrete.getParentItem().getId(), 1L, Long::sum);
            }
            long recordedEpisodes = concrete.getResolvedEpisodeCount()
                    + concrete.getActionTakenEpisodeCount()
                    + concrete.getDeferredEpisodeCount()
                    + concrete.getAcknowledgedEpisodeCount()
                    + concrete.getAutoClosedEpisodeCount();
            if (recordedEpisodes > 0) {
                resolved += concrete.getResolvedEpisodeCount();
                actionTaken += concrete.getActionTakenEpisodeCount();
                deferred += concrete.getDeferredEpisodeCount();
                acknowledged += concrete.getAcknowledgedEpisodeCount();
                autoClosed += concrete.getAutoClosedEpisodeCount();
                continue;
            }
            if (concrete.getStatus() == null || concrete.getStatus() == ManagerDailyControlItemStatus.OPEN) {
                continue;
            }
            if (isAutomaticallyClosed(concrete)) {
                autoClosed++;
                continue;
            }
            switch (concrete.getStatus()) {
                case RESOLVED -> resolved++;
                case ACTION_TAKEN -> actionTaken++;
                case DEFERRED -> deferred++;
                case ACKNOWLEDGED -> acknowledged++;
                case OPEN -> { }
            }
        }

        for (ManagerDailyControlItem item : items) {
            if (!isPrimaryActionItem(item)) {
                continue;
            }
            long coveredByConcrete = item.getId() == null ? 0 : concreteCountByParent.getOrDefault(item.getId(), 0L);
            long recordedEpisodes = item.getResolvedEpisodeCount()
                    + item.getActionTakenEpisodeCount()
                    + item.getDeferredEpisodeCount()
                    + item.getAcknowledgedEpisodeCount()
                    + item.getAutoClosedEpisodeCount();
            if (coveredByConcrete == 0 && recordedEpisodes > 0) {
                resolved += item.getResolvedEpisodeCount();
                actionTaken += item.getActionTakenEpisodeCount();
                deferred += item.getDeferredEpisodeCount();
                acknowledged += item.getAcknowledgedEpisodeCount();
                autoClosed += item.getAutoClosedEpisodeCount();
                continue;
            }
            if (item.getStatus() == null || item.getStatus() == ManagerDailyControlItemStatus.OPEN) {
                continue;
            }
            long count = Math.max(0, item.getCount() - coveredByConcrete);
            if (count == 0) {
                continue;
            }
            if (isAutomaticallyClosed(item)) {
                autoClosed += count;
                continue;
            }
            switch (item.getStatus()) {
                case RESOLVED -> resolved += count;
                case ACTION_TAKEN -> actionTaken += count;
                case DEFERRED -> deferred += count;
                case ACKNOWLEDGED -> acknowledged += count;
                case OPEN -> { }
            }
        }

        long remaining = items.stream()
                .filter(this::isOpenPrimaryActionItem)
                .mapToLong(item -> Math.max(0, item.getCount()))
                .sum();
        long overdue = openCount(items, OVERDUE);
        long risks = openCount(items, RISKS);
        long unanswered = openCount(items, UNANSWERED);
        long knownRemaining = overdue + risks + unanswered;
        if (knownRemaining > remaining) {
            remaining = knownRemaining;
        }
        long other = Math.max(0, remaining - knownRemaining);
        long handled = resolved + actionTaken + deferred + acknowledged;

        ManagerActionBalance balance = new ManagerActionBalance(
                handled + autoClosed + remaining,
                handled,
                autoClosed,
                remaining,
                resolved,
                actionTaken,
                deferred,
                acknowledged,
                overdue,
                risks,
                unanswered,
                other
        );
        if (!balance.isConsistent()) {
            throw new IllegalStateException("Нарушен баланс дневного контроля менеджера");
        }
        return balance;
    }

    private long openCount(List<ManagerDailyControlItem> items, String reasonCode) {
        return items.stream()
                .filter(this::isOpenPrimaryActionItem)
                .filter(item -> reasonCode.equals(item.getReasonCode()))
                .mapToLong(item -> Math.max(0, item.getCount()))
                .sum();
    }

    private boolean isOpenPrimaryActionItem(ManagerDailyControlItem item) {
        return isPrimaryActionItem(item) && item.getStatus() == ManagerDailyControlItemStatus.OPEN;
    }

    private boolean isPrimaryActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getItemType() != ManagerDailyControlItemType.ORDER_STATUS;
    }

    private boolean isPrimaryActionConcrete(ManagerDailyControlConcreteItem concrete) {
        return concrete != null
                && concrete.getParentItem() != null
                && isPrimaryActionItem(concrete.getParentItem());
    }

    private boolean isAutomaticallyClosed(ManagerDailyControlConcreteItem item) {
        return item.getStatus() == ManagerDailyControlItemStatus.RESOLVED
                && (item.isAutomaticResolution()
                || item.getLastManualTouchAt() == null && containsAutomaticMarker(item.getComment()));
    }

    private boolean isAutomaticallyClosed(ManagerDailyControlItem item) {
        return item.getStatus() == ManagerDailyControlItemStatus.RESOLVED
                && (item.isAutomaticResolution() || containsAutomaticMarker(item.getComment()));
    }

    private boolean containsAutomaticMarker(String comment) {
        if (comment == null) {
            return false;
        }
        String normalized = comment.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("автоматически")
                || normalized.contains("больше не актуальн")
                || normalized.contains("группа привязана");
    }
}
