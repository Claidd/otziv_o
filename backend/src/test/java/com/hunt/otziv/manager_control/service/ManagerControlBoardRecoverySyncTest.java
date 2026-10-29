package com.hunt.otziv.manager_control.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.*;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ManagerControlBoardRecoverySyncTest {
    @Test void recoveredSystemDeliveryCardsAreSynchronizedEvenWhenTheirParentIsInformationalWorkload() {
        var board = mock(ManagerControlBoardWorkflow.class, CALLS_REAL_METHODS);
        var controls = mock(ManagerDailyControlRepository.class);
        var items = mock(ManagerDailyControlItemRepository.class);
        var lifecycle = mock(ManagerControlCardLifecycle.class);
        ReflectionTestUtils.setField(board,"dailyControlRepository",controls);
        ReflectionTestUtils.setField(board,"dailyControlItemRepository",items);
        ReflectionTestUtils.setField(board,"cardLifecycle",lifecycle);
        var manager = new Manager(); var date = LocalDate.of(2026,10,29); var control = new ManagerDailyControl();
        var delivery = new ManagerDailyControlItem(); delivery.setGroup(ManagerDailyControlGroup.WORKLOAD);
        delivery.setReasonCode("AUTOMATION_FAILURES"); delivery.setCount(1);
        var other = new ManagerDailyControlItem(); other.setGroup(ManagerDailyControlGroup.WORKLOAD); other.setReasonCode("OTHER"); other.setCount(1);
        var action = new ManagerDailyControlItem(); action.setGroup(ManagerDailyControlGroup.ACTION); action.setCount(0);
        when(controls.findByControlDateAndManager(date,manager)).thenReturn(Optional.of(control));
        when(items.findByControl(control)).thenReturn(List.of(delivery,other,action));
        doReturn(List.of()).when(board).detailExamples(manager,delivery,date);
        doReturn(List.of()).when(board).syncConcreteExamples(eq(delivery),anyList());
        doReturn(List.of()).when(board).syncConcreteExamples(eq(action),anyList());
        board.syncManagerActionConcreteItems(manager,date);
        verify(board).syncConcreteExamples(delivery,List.of());
        verify(board).syncConcreteExamples(action,List.of());
        verify(board,never()).syncConcreteExamples(eq(other),anyList());
    }
}
