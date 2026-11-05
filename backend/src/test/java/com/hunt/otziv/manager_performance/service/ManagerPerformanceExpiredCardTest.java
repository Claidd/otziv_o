package com.hunt.otziv.manager_performance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ManagerPerformanceExpiredCardTest {

    @Test
    void cardClosedOnlyByTheExpiryJobStaysUnhandled() {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        item.setExpiredAt(LocalDateTime.of(2026, 11, 7, 3, 40));

        assertThat(ManagerPerformanceService.handledByManager(item)).isFalse();
    }

    @Test
    void cardTheManagerWorkedOnBeforeExpiryStaysHandled() {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        item.setLastManualTouchAt(LocalDateTime.of(2026, 8, 2, 11, 0));
        item.setExpiredAt(LocalDateTime.of(2026, 11, 7, 3, 40));

        assertThat(ManagerPerformanceService.handledByManager(item)).isTrue();
    }

    @Test
    void regularCardsKeepTheStatusRule() {
        ManagerDailyControlConcreteItem open = new ManagerDailyControlConcreteItem();
        open.setStatus(ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlConcreteItem taken = new ManagerDailyControlConcreteItem();
        taken.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);

        assertThat(ManagerPerformanceService.handledByManager(open)).isFalse();
        assertThat(ManagerPerformanceService.handledByManager(taken)).isTrue();
    }
}
