package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.model.ManagerQueueStateEvent;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_control.repository.ManagerQueueStateEventRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerQueueStateServiceTest {

    @Mock private ManagerDailyControlRepository controlRepository;
    @Mock private ManagerDailyControlItemRepository itemRepository;
    @Mock private ManagerDailyControlConcreteItemRepository concreteItemRepository;
    @Mock private ManagerQueueStateEventRepository eventRepository;
    @Mock private ManagerRepository managerRepository;
    @Mock private UserService userService;
    @Mock private AppSettingService settings;
    @Mock private GamificationEventService gamification;
    @Mock private ManagerOperationalMetricsService operationalMetricsService;

    private ManagerQueueStateService service;

    @BeforeEach
    void setUp() {
        service = new ManagerQueueStateService(
                controlRepository,
                itemRepository,
                concreteItemRepository,
                eventRepository,
                managerRepository,
                userService,
                settings,
                gamification,
                operationalMetricsService
        );
        when(settings.getBoolean("manager.sla.enabled", false)).thenReturn(true);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void concreteSourceTimePreventsDailySnapshotFromResettingHardSla() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        Manager manager = new Manager();
        manager.setId(2L);

        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(10L);
        control.setControlDate(today);
        control.setManager(manager);

        ManagerDailyControlItem parent = new ManagerDailyControlItem();
        parent.setId(20L);
        parent.setControl(control);
        parent.setGroup(ManagerDailyControlGroup.ACTION);
        parent.setItemType(ManagerDailyControlItemType.PROBLEM);
        parent.setReasonCode("OVERDUE_ORDERS");
        parent.setStatus(ManagerDailyControlItemStatus.OPEN);
        parent.setCount(1);
        parent.setCreatedAt(now);

        ManagerDailyControlConcreteItem concrete = new ManagerDailyControlConcreteItem();
        concrete.setId(30L);
        concrete.setControl(control);
        concrete.setParentItem(parent);
        concrete.setStatus(ManagerDailyControlItemStatus.OPEN);
        concrete.setCreatedAt(now.minusDays(2));

        when(controlRepository.findByControlDate(today)).thenReturn(List.of(control));
        when(itemRepository.findByControl(control)).thenReturn(List.of(parent));
        when(concreteItemRepository.findByParentItemIn(List.of(parent))).thenReturn(List.of(concrete));
        when(eventRepository.findTopByManager_IdOrderByObservedAtDescIdDesc(manager.getId())).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.observeToday();

        ArgumentCaptor<ManagerQueueStateEvent> captor = ArgumentCaptor.forClass(ManagerQueueStateEvent.class);
        org.mockito.Mockito.verify(eventRepository).save(captor.capture());
        assertEquals("OVERDUE", captor.getValue().getStateCode());
        assertEquals(1, captor.getValue().getOpenActionCount());
        assertEquals(1, captor.getValue().getOverdueCount());
    }
}
