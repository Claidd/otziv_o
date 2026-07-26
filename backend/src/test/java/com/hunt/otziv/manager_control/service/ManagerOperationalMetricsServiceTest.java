package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_daily_summary.service.ManagerActivityMetricsService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerOperationalMetricsServiceTest {

    @Mock private ManagerActivityMetricsService activityMetricsService;
    @Mock private ManagerDailyControlEventRepository controlEventRepository;

    private ManagerOperationalMetricsService service;

    @BeforeEach
    void setUp() {
        service = new ManagerOperationalMetricsService(activityMetricsService, controlEventRepository);
    }

    @Test
    void usesCanonicalActivityAndCountsFirstReactionOnly() {
        LocalDate date = LocalDate.of(2026, 7, 3);
        LocalDateTime until = date.atTime(12, 0);
        User user = User.builder().id(10L).build();
        Manager manager = Manager.builder().id(20L).user(user).build();
        when(activityMetricsService.calculateDailyAndMonthAverage(20L, date, until))
                .thenReturn(new ManagerActivityMetricsService.DailyAndAverage(
                        new ManagerActivityMetricsService.Metrics(300, 60, 360),
                        340
                ));

        ManagerDailyControlItem first = item(101L, date.atTime(8, 0));
        ManagerDailyControlItem second = item(102L, date.atTime(9, 0));
        when(controlEventRepository.findForManagerAudit(20L, date.atStartOfDay(), until)).thenReturn(List.of(
                reaction(first, 10L, date.atTime(8, 10)),
                reaction(first, 10L, date.atTime(8, 20)),
                reaction(second, 999L, date.atTime(9, 5)),
                reaction(second, 10L, date.atTime(9, 30))
        ));

        ManagerOperationalMetricsService.Metrics result = service.calculate(manager, date, until);

        assertEquals(360, result.activeWorkSeconds());
        assertEquals(340, result.averageDailyWorkSeconds());
        assertEquals(1200, result.averageReactionSeconds());
        assertEquals(2, result.reactionCount());
    }

    private ManagerDailyControlItem item(Long id, LocalDateTime createdAt) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(id);
        item.setCreatedAt(createdAt);
        return item;
    }

    private ManagerDailyControlEvent reaction(
            ManagerDailyControlItem item,
            Long actorUserId,
            LocalDateTime createdAt
    ) {
        ManagerDailyControlEvent event = new ManagerDailyControlEvent();
        event.setItem(item);
        event.setActorUserId(actorUserId);
        event.setEventType(ManagerDailyControlEventType.ITEM_ACTION);
        event.setCreatedAt(createdAt);
        return event;
    }
}
