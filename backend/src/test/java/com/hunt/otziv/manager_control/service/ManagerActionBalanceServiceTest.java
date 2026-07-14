package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagerActionBalanceServiceTest {

    private final ManagerActionBalanceService service = new ManagerActionBalanceService();

    @Test
    void buildsExplainableBalanceAndMutuallyExclusiveBreakdowns() {
        ManagerDailyControlItem handled = item(1L, "HANDLED", 4, ManagerDailyControlItemStatus.ACTION_TAKEN);
        ManagerDailyControlItem automatic = item(2L, "AUTO", 1, ManagerDailyControlItemStatus.RESOLVED);
        ManagerDailyControlItem risks = item(3L, "OPEN_RISKS", 15, ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlItem unanswered = item(4L, "UNANSWERED_CLIENT_MESSAGES", 9, ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlItem other = item(5L, "COMMON_INVOICES", 2, ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlItem duplicateOverdueStatus = item(6L, "Новые", 8, ManagerDailyControlItemStatus.OPEN);
        duplicateOverdueStatus.setItemType(ManagerDailyControlItemType.ORDER_STATUS);

        var balance = service.calculate(
                List.of(handled, automatic, risks, unanswered, other, duplicateOverdueStatus),
                List.of(
                        concrete(handled, ManagerDailyControlItemStatus.RESOLVED, true, "Закрыто менеджером"),
                        concrete(handled, ManagerDailyControlItemStatus.ACTION_TAKEN, true, "Действие выполнено"),
                        concrete(handled, ManagerDailyControlItemStatus.DEFERRED, true, "Отложено"),
                        concrete(handled, ManagerDailyControlItemStatus.ACKNOWLEDGED, true, "Принято"),
                        concrete(automatic, ManagerDailyControlItemStatus.RESOLVED, false, "Автоматически закрыто: больше не актуально")
                )
        );

        assertEquals(31, balance.total());
        assertEquals(4, balance.handledByManager());
        assertEquals(1, balance.autoClosed());
        assertEquals(26, balance.remaining());
        assertEquals(1, balance.resolved());
        assertEquals(1, balance.actionTaken());
        assertEquals(1, balance.deferred());
        assertEquals(1, balance.acknowledged());
        assertEquals(15, balance.riskRemaining());
        assertEquals(9, balance.unansweredRemaining());
        assertEquals(2, balance.otherRemaining());
        assertTrue(balance.isConsistent());
    }

    @Test
    void usesParentRemainderWhenAggregateCannotExposeEveryConcreteCard() {
        ManagerDailyControlItem handled = item(10L, "COMMON_INVOICES", 3, ManagerDailyControlItemStatus.ACTION_TAKEN);

        var balance = service.calculate(
                List.of(handled),
                List.of(concrete(handled, ManagerDailyControlItemStatus.ACTION_TAKEN, true, "Действие выполнено"))
        );

        assertEquals(3, balance.total());
        assertEquals(3, balance.handledByManager());
        assertEquals(3, balance.actionTaken());
        assertEquals(0, balance.remaining());
        assertTrue(balance.isConsistent());
    }

    @Test
    void keepsHandledEpisodesWhenConcreteCardIsReopened() {
        ManagerDailyControlItem parent = item(20L, "OPEN_RISKS", 1, ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlConcreteItem reopened = concrete(parent, ManagerDailyControlItemStatus.OPEN, true, null);
        reopened.setActionTakenEpisodeCount(2);

        var balance = service.calculate(List.of(parent), List.of(reopened));

        assertEquals(3, balance.total());
        assertEquals(2, balance.handledByManager());
        assertEquals(2, balance.actionTaken());
        assertEquals(1, balance.remaining());
        assertTrue(balance.isConsistent());
    }

    private ManagerDailyControlItem item(Long id, String reason, long count, ManagerDailyControlItemStatus status) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(id);
        item.setItemType(ManagerDailyControlItemType.PROBLEM);
        item.setGroup(ManagerDailyControlGroup.ACTION);
        item.setReasonCode(reason);
        item.setCount(count);
        item.setStatus(status);
        return item;
    }

    private ManagerDailyControlConcreteItem concrete(
            ManagerDailyControlItem parent,
            ManagerDailyControlItemStatus status,
            boolean manual,
            String comment
    ) {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setParentItem(parent);
        item.setStatus(status);
        item.setComment(comment);
        item.setActionType(action(status));
        item.setAutomaticResolution(!manual);
        item.setLastManualTouchAt(manual ? LocalDateTime.of(2026, 7, 14, 12, 0) : null);
        return item;
    }

    private ManagerDailyControlActionType action(ManagerDailyControlItemStatus status) {
        return switch (status) {
            case RESOLVED -> ManagerDailyControlActionType.RESOLVED;
            case ACTION_TAKEN -> ManagerDailyControlActionType.ACTION_TAKEN;
            case DEFERRED -> ManagerDailyControlActionType.DEFERRED;
            case ACKNOWLEDGED -> ManagerDailyControlActionType.ACKNOWLEDGED;
            case OPEN -> null;
        };
    }
}
