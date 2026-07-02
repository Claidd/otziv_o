package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerPerformanceServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);

    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private ManagerDailyControlRepository controlRepository;
    @Mock
    private ManagerDailyControlItemRepository itemRepository;
    @Mock
    private ManagerDailyControlConcreteItemRepository concreteItemRepository;
    @Mock
    private ClientChatUnansweredItemRepository unansweredItemRepository;
    @Mock
    private WorkerRiskIncidentRepository riskIncidentRepository;

    @InjectMocks
    private ManagerPerformanceService service;

    @Test
    void noPerformanceDataDoesNotProduceExcellentScore() {
        Manager manager = manager(1L, 101L);
        stubManagers(manager);
        when(controlRepository.findByControlDateBetween(DATE.withDayOfMonth(1), DATE.withDayOfMonth(31)))
                .thenReturn(List.of());

        ManagerPerformanceScoreResponse score = service.score(DATE).getFirst();

        assertEquals(0, score.performanceScore());
        assertEquals(0, score.loadAdjustedPerformanceScore());
        assertEquals("-", score.grade());
    }

    @Test
    void overdueRateUsesAverageDailyOverdueAgainstDailyWorkload() {
        Manager manager = manager(1L, 101L);
        stubManagers(manager);
        List<ManagerDailyControl> controls = controls(manager, 10);
        List<ManagerDailyControlItem> items = new ArrayList<>();
        controls.forEach(control -> {
            items.add(item(control, "ORDERS_WORKLOAD", ManagerDailyControlGroup.WORKLOAD, ManagerDailyControlItemType.PROBLEM, 100));
            items.add(item(control, "OVERDUE_ORDERS", ManagerDailyControlGroup.ACTION, ManagerDailyControlItemType.ORDER_STATUS, 1));
        });
        when(controlRepository.findByControlDateBetween(DATE.withDayOfMonth(1), DATE.withDayOfMonth(31)))
                .thenReturn(controls);
        when(itemRepository.findByControlIn(controls)).thenReturn(items);
        when(concreteItemRepository.findByParentItemIn(items)).thenReturn(List.of());

        ManagerPerformanceScoreResponse score = service.score(DATE).getFirst();

        assertEquals(1.0, score.avgDailyOverdue());
        assertEquals(1.0, score.overdueRate());
        assertEquals(100, score.workloadOrder());
    }

    @Test
    void highWorkloadAddsModerateAdjustmentWithoutChangingBaseScore() {
        Manager manager = manager(1L, 101L);
        stubManagers(manager);
        ManagerDailyControl control = control(manager, DATE);
        ManagerDailyControlItem workload = item(control, "ORDERS_WORKLOAD", ManagerDailyControlGroup.WORKLOAD, ManagerDailyControlItemType.PROBLEM, 100);
        ManagerDailyControlItem problem = item(control, "REQUIRES_ATTENTION", ManagerDailyControlGroup.ACTION, ManagerDailyControlItemType.PROBLEM, 1);
        ManagerDailyControlConcreteItem concrete = concrete(control, problem);
        List<ManagerDailyControlItem> items = List.of(workload, problem);
        when(controlRepository.findByControlDateBetween(DATE.withDayOfMonth(1), DATE.withDayOfMonth(31)))
                .thenReturn(List.of(control));
        when(itemRepository.findByControlIn(List.of(control))).thenReturn(items);
        when(concreteItemRepository.findByParentItemIn(items)).thenReturn(List.of(concrete));

        ManagerPerformanceScoreResponse score = service.score(DATE).getFirst();

        assertTrue(score.workloadIndex() >= 90);
        assertTrue(score.loadAdjustedPerformanceScore() > score.performanceScore());
        assertTrue(score.loadAdjustedPerformanceScore() - score.performanceScore() <= 4);
    }

    @Test
    void openProblemInsideSlaDoesNotReceiveZeroSpeedScore() {
        Manager manager = manager(1L, 101L);
        stubManagers(manager);
        ManagerDailyControl control = control(manager, DATE);
        ManagerDailyControlItem problem = item(control, "REQUIRES_ATTENTION", ManagerDailyControlGroup.ACTION, ManagerDailyControlItemType.PROBLEM, 1);
        ManagerDailyControlConcreteItem concrete = concrete(control, problem);
        concrete.setCreatedAt(LocalDateTime.now().minusHours(1));
        List<ManagerDailyControlItem> items = List.of(problem);
        when(controlRepository.findByControlDateBetween(DATE.withDayOfMonth(1), DATE.withDayOfMonth(31)))
                .thenReturn(List.of(control));
        when(itemRepository.findByControlIn(List.of(control))).thenReturn(items);
        when(concreteItemRepository.findByParentItemIn(items)).thenReturn(List.of(concrete));

        ManagerPerformanceScoreResponse score = service.score(DATE).getFirst();

        assertEquals(100.0, score.problemSlaRate());
        assertEquals(100, score.problemSpeedScore());
    }

    private void stubManagers(Manager manager) {
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        lenient().when(unansweredItemRepository.findPerformanceItems(anyCollection(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private Manager manager(Long managerId, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setActive(true);
        user.setUsername("manager-" + managerId);
        Manager manager = new Manager();
        manager.setId(managerId);
        manager.setUser(user);
        return manager;
    }

    private List<ManagerDailyControl> controls(Manager manager, int days) {
        List<ManagerDailyControl> controls = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            controls.add(control(manager, DATE.withDayOfMonth(day)));
        }
        return controls;
    }

    private ManagerDailyControl control(Manager manager, LocalDate date) {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId((long) date.getDayOfMonth());
        control.setManager(manager);
        control.setControlDate(date);
        control.setMorningCompletedAt(date.atTime(9, 0));
        control.setClosedAt(date.atTime(21, 0));
        return control;
    }

    private ManagerDailyControlItem item(
            ManagerDailyControl control,
            String reasonCode,
            ManagerDailyControlGroup group,
            ManagerDailyControlItemType type,
            long count
    ) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(Math.abs((control.getId() + ":" + reasonCode).hashCode()) + 1L);
        item.setControl(control);
        item.setReasonCode(reasonCode);
        item.setGroup(group);
        item.setItemType(type);
        item.setSeverity(group == ManagerDailyControlGroup.ACTION
                ? ManagerDailyControlSeverity.CRITICAL
                : ManagerDailyControlSeverity.INFO);
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setCount(count);
        return item;
    }

    private ManagerDailyControlConcreteItem concrete(ManagerDailyControl control, ManagerDailyControlItem parent) {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(900L);
        item.setControl(control);
        item.setParentItem(parent);
        item.setEntityType("ORDER");
        item.setEntityId(700L);
        item.setTitle("Требует внимания");
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setCreatedAt(LocalDateTime.of(2026, 5, 10, 10, 0));
        return item;
    }
}
