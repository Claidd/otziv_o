package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Audit 2026-11-04: a restart or a late pass must never reopen or rescore a closed or past control day. */
class ManagerControlDayWindowTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime NIGHT = TODAY.atTime(3, 30);
    private static final LocalDateTime MORNING = TODAY.atTime(5, 0);

    @Test
    void previousDayIsCurrentOnlyWhileOpenInsideItsClosingWindow() {
        ManagerDailyControl yesterday = control(TODAY.minusDays(1), null);
        assertThat(ManagerControlDayLifecycle.isControlDayCurrent(control(TODAY, TODAY.atTime(21, 0)), MORNING)).isTrue();
        assertThat(ManagerControlDayLifecycle.isControlDayCurrent(yesterday, NIGHT)).isTrue();
        assertThat(ManagerControlDayLifecycle.isControlDayCurrent(yesterday, MORNING)).isFalse();
        assertThat(ManagerControlDayLifecycle.isControlDayCurrent(control(TODAY.minusDays(1), TODAY.minusDays(1).atTime(22, 0)), NIGHT)).isFalse();
        assertThat(ManagerControlDayLifecycle.isControlDayCurrent(control(TODAY.minusDays(2), null), NIGHT)).isFalse();
    }

    @Test
    void snapshotMayRewriteTodayAndOnlyAnOpenPreviousDayBeforeFive() {
        var snapshot = mock(ManagerControlDailySnapshotWorkflow.class, CALLS_REAL_METHODS);
        var controls = mock(ManagerDailyControlRepository.class);
        ReflectionTestUtils.setField(snapshot, "dailyControlRepository", controls);
        Manager manager = new Manager();
        manager.setId(7L);
        ManagerDailyControl yesterday = control(TODAY.minusDays(1), null);
        when(controls.findByControlDateAndManager(TODAY.minusDays(1), manager)).thenReturn(Optional.of(yesterday));

        assertThat(snapshot.mayPersistControlDay(manager, TODAY, MORNING)).isTrue();
        assertThat(snapshot.mayPersistControlDay(manager, TODAY.minusDays(1), NIGHT)).isTrue();
        assertThat(snapshot.mayPersistControlDay(manager, TODAY.minusDays(1), MORNING)).isFalse();
        assertThat(snapshot.mayPersistControlDay(manager, TODAY.minusDays(2), NIGHT)).isFalse();

        yesterday.setClosedAt(TODAY.minusDays(1).atTime(21, 30));
        assertThat(snapshot.mayPersistControlDay(manager, TODAY.minusDays(1), NIGHT)).isFalse();

        // A missing past control is never created afterwards.
        when(controls.findByControlDateAndManager(TODAY.minusDays(1), manager)).thenReturn(Optional.empty());
        assertThat(snapshot.mayPersistControlDay(manager, TODAY.minusDays(1), NIGHT)).isFalse();
    }

    @Test
    void cardClosedDuringItsOwnDayIsNotReopenedWhenThatDayIsRefreshed() {
        var concrete = mock(ManagerControlConcreteSnapshotWorkflow.class, CALLS_REAL_METHODS);
        ManagerDailyControlConcreteItem card = new ManagerDailyControlConcreteItem();
        card.setControl(control(LocalDate.now().minusDays(1), null));
        card.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        card.setResolvedAt(LocalDate.now().minusDays(1).atTime(23, 10));

        assertThat(concrete.reopenResolvedConcreteItemIfExpired(card)).isFalse();
        assertThat(card.getStatus()).isEqualTo(ManagerDailyControlItemStatus.RESOLVED);

        card.setResolvedAt(LocalDate.now().minusDays(2).atTime(23, 10));
        assertThat(concrete.reopenResolvedConcreteItemIfExpired(card)).isTrue();
    }

    @Test
    void dueFollowUpKeepsAPendingSpecialistRequestButAnyOtherReopenClearsIt() {
        var concrete = mock(ManagerControlConcreteSnapshotWorkflow.class, CALLS_REAL_METHODS);
        LocalDateTime sentAt = LocalDateTime.now().minusHours(3).minusMinutes(1);
        ManagerDailyControlConcreteItem waiting = new ManagerDailyControlConcreteItem();
        waiting.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        waiting.setFollowUpAt(LocalDateTime.now().minusMinutes(1));
        waiting.setWorkerNotificationAttemptedAt(sentAt);
        waiting.setWorkerNotificationSentAt(sentAt);
        waiting.setWorkerExplanationRequestedAt(sentAt);
        waiting.setWorkerReminderCount(1);

        assertThat(concrete.reopenConcreteItemIfFollowUpDue(waiting)).isTrue();

        assertThat(waiting.getStatus()).isEqualTo(ManagerDailyControlItemStatus.OPEN);
        assertThat(waiting.getWorkerNotificationSentAt()).isEqualTo(sentAt);
        assertThat(waiting.getWorkerExplanationRequestedAt()).isEqualTo(sentAt);
        assertThat(waiting.getWorkerReminderCount()).isEqualTo(1);

        ManagerDailyControlConcreteItem answered = new ManagerDailyControlConcreteItem();
        answered.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        answered.setFollowUpAt(LocalDateTime.now().minusMinutes(1));
        answered.setWorkerNotificationSentAt(sentAt);
        answered.setWorkerExplanationRequestedAt(sentAt);
        answered.setWorkerExplanationAt(sentAt.plusHours(1));
        assertThat(concrete.reopenConcreteItemIfFollowUpDue(answered)).isTrue();
        assertThat(answered.getWorkerNotificationSentAt()).isNull();
    }

    @Test
    void synchronizationRunsPerManagerAndReportsAFailureAfterTheOthers() {
        Fixture fixture = new Fixture();
        Manager first = fixture.manager(1L);
        Manager second = fixture.manager(2L);
        when(fixture.managers.findAllWithUserAndImage()).thenReturn(List.of(first, second));
        when(fixture.snapshot.mayPersistControlDay(any(), any(), any())).thenReturn(true);
        when(fixture.snapshot.managerControl(eq(first), any(), isNull(), eq(true), eq(false)))
                .thenThrow(new IllegalStateException("broken manager"));

        LocalDate today = LocalDate.now();
        assertThrows(IllegalStateException.class, () -> fixture.board.synchronizeDailySnapshot(today));

        verify(fixture.snapshot).managerControl(second, today, null, true, false);
        verify(fixture.snapshots).invalidate(List.of(2L));
        verify(fixture.snapshots, never()).invalidate(List.of(1L));
        // One transaction for the manager list and one per manager.
        verify(fixture.transactions, times(3)).required(any());
    }

    @Test
    void closedOrFinishedDayIsNeitherReopenedNorRescoredBySynchronization() {
        Fixture fixture = new Fixture();
        fixture.board.synchronizeDailySnapshot(LocalDate.now().minusDays(3));
        verifyNoInteractions(fixture.managers, fixture.snapshot);

        Manager manager = fixture.manager(5L);
        when(fixture.managers.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(fixture.snapshot.mayPersistControlDay(any(), any(), any())).thenReturn(false);
        fixture.board.synchronizeDailySnapshot(LocalDate.now());
        verify(fixture.snapshot, never()).managerControl(any(), any(), any(), anyBoolean(), anyBoolean());
        verify(fixture.controls, never()).findByControlDateAndManager(any(), any());
    }

    @Test
    void syncButtonFailsOnlyWhenNoVisibleManagerCouldBeSynchronized() {
        Fixture fixture = new Fixture();
        Manager only = fixture.manager(3L);
        when(fixture.access.visibleManagers(any(), any())).thenReturn(List.of(only));
        when(fixture.snapshot.mayPersistControlDay(any(), any(), any())).thenReturn(true);
        when(fixture.snapshot.managerControl(eq(only), any(), isNull(), eq(true), eq(false)))
                .thenThrow(new IllegalStateException("broken manager"));

        assertThrows(IllegalStateException.class, () -> fixture.board.syncToday(() -> "manager", null));
    }

    private static ManagerDailyControl control(LocalDate date, LocalDateTime closedAt) {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setControlDate(date);
        control.setClosedAt(closedAt);
        return control;
    }

    private static final class Fixture {
        final ManagerControlDailySnapshotWorkflow snapshot = mock(ManagerControlDailySnapshotWorkflow.class);
        final ManagerControlAccessPolicy access = mock(ManagerControlAccessPolicy.class);
        final ManagerRepository managers = mock(ManagerRepository.class);
        final ManagerDailyControlRepository controls = mock(ManagerDailyControlRepository.class);
        final ManagerControlReadSnapshots snapshots = mock(ManagerControlReadSnapshots.class);
        final ManagerControlTransactionRunner transactions = spy(new ManagerControlTransactionRunner());
        final ManagerControlBoardWorkflow board = new ManagerControlBoardWorkflow(snapshot, mock(ManagerControlDayLifecycle.class),
                mock(ManagerControlConcreteSnapshotWorkflow.class), mock(ManagerControlProblemExamples.class), access,
                mock(ManagerControlCardLifecycle.class), mock(ManagerControlWorkerExplanationQueries.class),
                mock(ManagerControlQualityQueries.class), managers, mock(com.hunt.otziv.manager.service.ManagerPermissionService.class),
                null, controls, mock(ManagerDailyControlItemRepository.class),
                mock(com.hunt.otziv.manager_performance.service.ManagerPerformanceService.class), snapshots,
                mock(com.hunt.otziv.u_users.api.CabinetCacheScope.class), transactions);

        Manager manager(long id) {
            Manager manager = new Manager();
            manager.setId(id);
            when(managers.findByIdWithUser(id)).thenReturn(Optional.of(manager));
            when(managers.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
            return manager;
        }
    }
}
