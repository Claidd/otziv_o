package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class ManagerControlCardExpiryJobTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 3, 40);
    private static final LocalDate OLD_DAY = LocalDate.of(2026, 8, 12);

    private final SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
    private final SchedulerLeaseService.Lease lease = new SchedulerLeaseService.Lease("manager-control-card-expiry", "owner", 1);
    private final ManagerDailyControlConcreteItemRepository cards = mock(ManagerDailyControlConcreteItemRepository.class);
    private final ManagerDailyControlItemRepository items = mock(ManagerDailyControlItemRepository.class);
    private final ManagerDailyControlEventRepository events = mock(ManagerDailyControlEventRepository.class);
    private final ManagerControlReadSnapshots snapshots = mock(ManagerControlReadSnapshots.class);
    private final ManagerControlCardLifecycle lifecycle = mock(ManagerControlCardLifecycle.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ManagerControlCardExpiryJob job =
            new ManagerControlCardExpiryJob(leases, cards, items, events, snapshots, lifecycle, transactions);

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(leases.tryAcquire(ManagerControlCardExpiryJob.LEASE_NAME, ManagerControlCardExpiryJob.LEASE_DURATION))
                .thenReturn(Optional.of(lease));
    }

    @Test
    void cardsOfTodayYesterdayAndTheLastThirtyDaysAreKept() {
        LocalDate today = NOW.toLocalDate();
        assertThat(ManagerControlCardExpiryJob.keptFrom(today)).isEqualTo(today.minusDays(30));
        LocalDate before = ManagerControlCardExpiryJob.keptFrom(today);

        assertThat(ManagerControlCardExpiryJob.expires(card(1L, control(today, null), parent(1L, 1), ManagerDailyControlItemStatus.OPEN), before, NOW)).isFalse();
        assertThat(ManagerControlCardExpiryJob.expires(card(2L, control(today.minusDays(1), null), parent(2L, 1), ManagerDailyControlItemStatus.OPEN), before, NOW)).isFalse();
        assertThat(ManagerControlCardExpiryJob.expires(card(3L, control(today.minusDays(30), null), parent(3L, 1), ManagerDailyControlItemStatus.OPEN), before, NOW)).isFalse();
        assertThat(ManagerControlCardExpiryJob.expires(card(4L, control(today.minusDays(31), null), parent(4L, 1), ManagerDailyControlItemStatus.DEFERRED), before, NOW)).isTrue();
    }

    @Test
    void staleCardIsClosedWithADistinguishableSystemOutcomeAndItsParentFollows() {
        ManagerDailyControl closedDay = control(OLD_DAY, OLD_DAY.atTime(21, 30));
        closedDay.setStatus(ManagerDailyControlStatus.RED);
        closedDay.setQualityScore(62);
        ManagerDailyControlItem parent = parent(10L, 2);
        parent.setControl(closedDay);
        ManagerDailyControlConcreteItem open = card(101L, closedDay, parent, ManagerDailyControlItemStatus.OPEN);
        open.setFollowUpAt(OLD_DAY.atTime(23, 0));
        ManagerDailyControlConcreteItem waiting = card(102L, closedDay, parent, ManagerDailyControlItemStatus.ACTION_TAKEN);
        when(cards.findAllByIdInForUpdate(List.of(101L, 102L))).thenReturn(List.of(open, waiting));
        when(cards.findByParentItem(parent)).thenReturn(List.of(open, waiting));

        int closed = job.expireBatch(List.of(101L, 102L), ManagerControlCardExpiryJob.keptFrom(NOW.toLocalDate()), NOW);

        assertThat(closed).isEqualTo(2);
        for (ManagerDailyControlConcreteItem card : List.of(open, waiting)) {
            assertThat(card.getStatus()).isEqualTo(ManagerDailyControlItemStatus.RESOLVED);
            assertThat(card.getActionType()).isEqualTo(ManagerDailyControlActionType.RESOLVED);
            assertThat(card.isAutomaticResolution()).isTrue();
            assertThat(card.getExpiredAt()).isEqualTo(NOW);
            assertThat(card.getResolvedAt()).isEqualTo(NOW);
            assertThat(card.getFollowUpAt()).isNull();
            assertThat(card.getComment()).isEqualTo("Срок истёк: карточка старше 30 дней закрыта автоматически");
        }
        assertThat(open.getAutoClosedEpisodeCount()).isEqualTo(1);
        assertThat(waiting.getAutoClosedEpisodeCount()).isZero();

        assertThat(parent.getStatus()).isEqualTo(ManagerDailyControlItemStatus.RESOLVED);
        assertThat(parent.isAutomaticResolution()).isTrue();
        assertThat(parent.getComment()).startsWith("Срок истёк");
        verify(items).save(parent);

        ArgumentCaptor<ManagerDailyControlEvent> recorded = ArgumentCaptor.forClass(ManagerDailyControlEvent.class);
        verify(events, times(3)).save(recorded.capture());
        assertThat(recorded.getAllValues()).allSatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo(ManagerDailyControlEventType.ITEM_RESOLVED);
            assertThat(event.getActorUserId()).isNull();
            assertThat(event.getComment()).startsWith("Срок истёк");
        });

        // The closed day keeps its status, score and closure: nothing rescored or reopened.
        assertThat(closedDay.getClosedAt()).isEqualTo(OLD_DAY.atTime(21, 30));
        assertThat(closedDay.getStatus()).isEqualTo(ManagerDailyControlStatus.RED);
        assertThat(closedDay.getQualityScore()).isEqualTo(62);
        verify(snapshots).invalidate(java.util.Set.of(7L));
    }

    @Test
    void parentWithUnexpandedCardsOrAnotherOpenCardStaysAsItWas() {
        ManagerDailyControl day = control(OLD_DAY, null);
        ManagerDailyControlItem unexpanded = parent(20L, 3);
        unexpanded.setControl(day);
        ManagerDailyControlConcreteItem only = card(201L, day, unexpanded, ManagerDailyControlItemStatus.OPEN);
        when(cards.findAllByIdInForUpdate(List.of(201L))).thenReturn(List.of(only));
        when(cards.findByParentItem(unexpanded)).thenReturn(List.of(only));

        job.expireBatch(List.of(201L), ManagerControlCardExpiryJob.keptFrom(NOW.toLocalDate()), NOW);

        assertThat(only.getStatus()).isEqualTo(ManagerDailyControlItemStatus.RESOLVED);
        assertThat(unexpanded.getStatus()).isEqualTo(ManagerDailyControlItemStatus.OPEN);
        verify(items, never()).save(any());
    }

    @Test
    void resolvedCardsRecentCardsAndUnfinishedDeliveriesAreLeftUntouched() {
        ManagerDailyControl old = control(OLD_DAY, null);
        ManagerDailyControlItem parent = parent(30L, 5);
        ManagerDailyControlConcreteItem resolved = card(301L, old, parent, ManagerDailyControlItemStatus.RESOLVED);
        resolved.setComment("Закрыто менеджером");
        ManagerDailyControlConcreteItem fenced = card(302L, old, parent, ManagerDailyControlItemStatus.ACTION_TAKEN);
        fenced.setComment("client_message_delivery_unknown:1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        ManagerDailyControlConcreteItem recent = card(303L, control(NOW.toLocalDate().minusDays(5), null), parent, ManagerDailyControlItemStatus.OPEN);
        when(cards.findAllByIdInForUpdate(List.of(301L, 302L, 303L))).thenReturn(List.of(resolved, fenced, recent));

        int closed = job.expireBatch(List.of(301L, 302L, 303L), ManagerControlCardExpiryJob.keptFrom(NOW.toLocalDate()), NOW);

        assertThat(closed).isZero();
        assertThat(resolved.getComment()).isEqualTo("Закрыто менеджером");
        assertThat(resolved.getExpiredAt()).isNull();
        assertThat(fenced.getStatus()).isEqualTo(ManagerDailyControlItemStatus.ACTION_TAKEN);
        assertThat(recent.getStatus()).isEqualTo(ManagerDailyControlItemStatus.OPEN);
        verify(cards, never()).save(any());
        verifyNoInteractions(events, items, snapshots);
    }

    @Test
    void backlogIsHandledInBoundedBatchesUnderTheRenewedLease() {
        List<Long> first = LongStream.rangeClosed(1, ManagerControlCardExpiryJob.BATCH_SIZE).boxed().toList();
        when(cards.findUnresolvedIdsOfControlDaysBefore(eq(NOW.toLocalDate().minusDays(30)), eq(ManagerDailyControlItemStatus.RESOLVED), eq(0L), any(Pageable.class)))
                .thenReturn(first);
        when(cards.findUnresolvedIdsOfControlDaysBefore(any(), any(), eq((long) ManagerControlCardExpiryJob.BATCH_SIZE), any(Pageable.class)))
                .thenReturn(List.of(150L, 151L));
        when(cards.findUnresolvedIdsOfControlDaysBefore(any(), any(), eq(151L), any(Pageable.class))).thenReturn(List.of());
        ManagerDailyControl old = control(OLD_DAY, null);
        when(cards.findAllByIdInForUpdate(any())).thenAnswer(call -> {
            List<ManagerDailyControlConcreteItem> locked = new ArrayList<>();
            for (Long id : call.<java.util.Collection<Long>>getArgument(0)) {
                locked.add(card(id, old, parent(9L, 999), ManagerDailyControlItemStatus.OPEN));
            }
            return locked;
        });

        int closed = job.run(NOW);

        assertThat(closed).isEqualTo(ManagerControlCardExpiryJob.BATCH_SIZE + 2);
        verify(leases, times(2)).holdForTransaction(lease, ManagerControlCardExpiryJob.LEASE_DURATION);
        verify(transactions, times(2)).commit(any());
        verify(leases).release(lease);
    }

    @Test
    void anotherInstanceHoldingTheLeaseSkipsTheRun() {
        when(leases.tryAcquire(ManagerControlCardExpiryJob.LEASE_NAME, ManagerControlCardExpiryJob.LEASE_DURATION))
                .thenReturn(Optional.empty());

        assertThat(job.run(NOW)).isZero();

        verifyNoInteractions(cards, items, events, snapshots);
        verify(leases, never()).release(any());
    }

    @Test
    void lostLeaseRollsTheBatchBackAndStopsTheRun() {
        when(cards.findUnresolvedIdsOfControlDaysBefore(any(), any(), anyLong(), any(Pageable.class))).thenReturn(List.of(1L));
        doThrow(new IllegalStateException("Projection refresh lease lost"))
                .when(leases).holdForTransaction(lease, ManagerControlCardExpiryJob.LEASE_DURATION);

        assertThatThrownBy(() -> job.run(NOW)).isInstanceOf(IllegalStateException.class);

        verify(cards, never()).findAllByIdInForUpdate(any());
        verify(transactions).rollback(any());
        verify(leases).release(lease);
    }

    private ManagerDailyControl control(LocalDate date, LocalDateTime closedAt) {
        Manager manager = new Manager();
        manager.setId(7L);
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(date.toEpochDay());
        control.setManager(manager);
        control.setControlDate(date);
        control.setClosedAt(closedAt);
        return control;
    }

    private ManagerDailyControlItem parent(long id, long count) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(id);
        item.setGroup(ManagerDailyControlGroup.ACTION);
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setCount(count);
        item.setLabel("Просроченные заказы");
        return item;
    }

    private ManagerDailyControlConcreteItem card(long id, ManagerDailyControl control, ManagerDailyControlItem parent,
                                                 ManagerDailyControlItemStatus status) {
        ManagerDailyControlConcreteItem card = new ManagerDailyControlConcreteItem();
        card.setId(id);
        card.setControl(control);
        card.setParentItem(parent);
        card.setStatus(status);
        card.setTitle("Карточка " + id);
        return card;
    }
}
