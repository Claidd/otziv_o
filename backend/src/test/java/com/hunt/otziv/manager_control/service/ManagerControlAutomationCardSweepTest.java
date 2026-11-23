package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

class ManagerControlAutomationCardSweepTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 11, 25);

    private final SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
    private final ManagerDailyControlConcreteItemRepository cards = mock(ManagerDailyControlConcreteItemRepository.class);
    private final ManagerDailyControlEventRepository events = mock(ManagerDailyControlEventRepository.class);
    private final ManagerAutomationFailureService failures = mock(ManagerAutomationFailureService.class);
    private final ManagerControlOrderDeliveryState delivery = mock(ManagerControlOrderDeliveryState.class);
    private final ManagerControlCardLifecycle lifecycle = mock(ManagerControlCardLifecycle.class);
    private final ManagerControlReadSnapshots snapshots = mock(ManagerControlReadSnapshots.class);
    private final ManagerControlAutomationCardSweep sweep = new ManagerControlAutomationCardSweep(
            leases, cards, events, failures, delivery, lifecycle, snapshots, mock(PlatformTransactionManager.class));
    private final Manager manager = new Manager();
    private ManagerDailyControlConcreteItem recovered;
    private ManagerDailyControlConcreteItem stillFailing;

    @BeforeEach
    void setUp() {
        manager.setId(3L);
        ManagerDailyControl yesterday = new ManagerDailyControl();
        yesterday.setControlDate(LocalDate.of(2026, 11, 22));
        yesterday.setManager(manager);
        recovered = card(10480L, yesterday, 2237481L);
        stillFailing = card(10481L, yesterday, 2296844L);
        var lease = new SchedulerLeaseService.Lease("fixture", "owner", 1);
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(cards.findUnresolvedIdsOfTypesBetweenControlDays(anyCollection(), any(), any(), any(), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(10480L, 10481L));
        when(cards.findAllById(List.of(10480L, 10481L))).thenReturn(List.of(recovered, stillFailing));
        when(cards.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(recovered));
        when(failures.issues(eq(manager), anyInt())).thenReturn(List.of(issue("AUTOMATION_FAILURE", 2296844L)));
    }

    @Test
    void aCardOfAnEarlierDayClosesOnceTheAutoresponderNoLongerReportsItsProblem() {
        assertThat(sweep.run(NOW)).isEqualTo(1);

        assertThat(recovered.getStatus()).isEqualTo(ManagerDailyControlItemStatus.RESOLVED);
        assertThat(recovered.isAutomaticResolution()).isTrue();
        assertThat(recovered.getComment()).startsWith("Проблема больше не актуальна");
        assertThat(stillFailing.getStatus()).isEqualTo(ManagerDailyControlItemStatus.OPEN);
        verify(cards).findAllByIdInForUpdate(java.util.Set.of(10480L));
        verify(lifecycle).updateParentItemFromConcreteItems(recovered.getParentItem());
        verify(snapshots).invalidate(any());
    }

    @Test
    void aPendingManualDeliveryKeepsTheCardOpen() {
        when(delivery.pending(recovered)).thenReturn(true);

        assertThat(sweep.run(NOW)).isZero();

        assertThat(recovered.getStatus()).isEqualTo(ManagerDailyControlItemStatus.OPEN);
        verify(cards, never()).save(any());
    }

    @Test
    void unavailableIssuesLeaveEveryCardOfThatManagerOpen() {
        when(failures.issues(eq(manager), anyInt())).thenThrow(new IllegalStateException("database busy"));

        assertThat(sweep.run(NOW)).isZero();

        verify(cards, never()).findAllByIdInForUpdate(anyCollection());
        assertThat(recovered.getStatus()).isEqualTo(ManagerDailyControlItemStatus.OPEN);
    }

    @Test
    void anotherInstanceHoldingTheLeaseMeansNoWork() {
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        assertThat(sweep.run(NOW)).isZero();

        verify(cards, never()).findUnresolvedIdsOfTypesBetweenControlDays(anyCollection(), any(), any(), any(), anyLong(), any(Pageable.class));
    }

    private static ManagerDailyControlConcreteItem card(long id, ManagerDailyControl control, long stateId) {
        ManagerDailyControlConcreteItem card = new ManagerDailyControlConcreteItem();
        card.setId(id);
        card.setControl(control);
        card.setEntityType("AUTOMATION_FAILURE");
        card.setEntityId(stateId);
        card.setStatus(ManagerDailyControlItemStatus.OPEN);
        card.setTitle("АВТОКАР");
        return card;
    }

    private static ManagerAutomationFailureService.AutomationFailureIssue issue(String type, long id) {
        return new ManagerAutomationFailureService.AutomationFailureIssue(type + ":" + id, type, id, id, null,
                "title", "subtitle", "status", "reason", null, null, null, 4, NOW.minusDays(2), NOW.minusHours(1), null);
    }
}
