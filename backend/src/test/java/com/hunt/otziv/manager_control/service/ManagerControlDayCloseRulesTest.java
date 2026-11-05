package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Day closing and the 05:00 owner notice depend on open work, not on stage timestamps (05.11 stand data). */
class ManagerControlDayCloseRulesTest {
    private final ManagerDailyControlConcreteItemRepository cards = mock(ManagerDailyControlConcreteItemRepository.class);
    private final ManagerControlProblemExamples problemExamples = mock(ManagerControlProblemExamples.class);
    private final ManagerControlCardLifecycle cardLifecycle = new ManagerControlCardLifecycle(cards,
            mock(ManagerDailyControlItemRepository.class), mock(ManagerDailyControlEventRepository.class),
            mock(ManagerPerformanceService.class), mock(ManagerControlReadSnapshots.class),
            mock(ManagerProblemDiagnosticsService.class));
    private final ManagerControlDayLifecycle lifecycle = new ManagerControlDayLifecycle(problemExamples,
            mock(ManagerControlAccessPolicy.class), cardLifecycle, mock(ManagerControlQualityQueries.class),
            mock(ManagerDailyControlRepository.class), mock(ManagerDailyControlItemRepository.class), cards);

    @BeforeEach
    void setUp() {
        when(problemExamples.safe(any())).thenAnswer(call -> call.getArgument(0) == null ? "" : call.<String>getArgument(0).trim());
    }

    /** Every unclosed day without open work in the last 30 days was held only by this blocker. */
    @Test
    void resolvedRedItemWithFewerCardsThanItsCountNoLongerBlocksTheClose() {
        ManagerDailyControlItem resolved = item(1L, ManagerDailyControlItemStatus.RESOLVED, 1, null);
        when(cards.findByParentItemIn(any())).thenReturn(List.of());

        assertThat(lifecycle.closeBlockers(acceptedControl(), List.of(resolved))).isEmpty();
    }

    @Test
    void unresolvedRedItemStillNeedsAllItsCards() {
        ManagerDailyControlItem handled = item(2L, ManagerDailyControlItemStatus.ACTION_TAKEN, 2, "Связался с клиентом");
        when(cards.findByParentItemIn(any())).thenReturn(List.of(card(handled, ManagerDailyControlItemStatus.ACTION_TAKEN)));

        assertThat(lifecycle.closeBlockers(acceptedControl(), List.of(handled)))
                .anySatisfy(blocker -> assertThat(blocker).startsWith("Не раскрыты все карточки"));
    }

    @Test
    void unresolvedActionWorkMeansAnOpenActionItemOrAnOpenCardInsideOne() {
        ManagerDailyControlItem open = item(3L, ManagerDailyControlItemStatus.OPEN, 1, null);
        assertThat(lifecycle.hasUnresolvedActionWork(List.of(open))).isTrue();
        verify(cards, never()).findByParentItemIn(any());

        ManagerDailyControlItem handled = item(4L, ManagerDailyControlItemStatus.ACTION_TAKEN, 2, "Ждём ответ");
        when(cards.findByParentItemIn(List.of(handled))).thenReturn(List.of(card(handled, ManagerDailyControlItemStatus.OPEN)));
        assertThat(lifecycle.hasUnresolvedActionWork(List.of(handled))).isTrue();

        when(cards.findByParentItemIn(List.of(handled))).thenReturn(List.of(
                card(handled, ManagerDailyControlItemStatus.ACTION_TAKEN), card(handled, ManagerDailyControlItemStatus.DEFERRED)));
        assertThat(lifecycle.hasUnresolvedActionWork(List.of(handled))).isFalse();

        ManagerDailyControlItem workload = item(5L, ManagerDailyControlItemStatus.OPEN, 3, null);
        workload.setGroup(ManagerDailyControlGroup.WORKLOAD);
        assertThat(lifecycle.hasUnresolvedActionWork(List.of(workload))).isFalse();
        assertThat(lifecycle.hasUnresolvedActionWork(List.of())).isFalse();
    }

    private ManagerDailyControl acceptedControl() {
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(1L);
        control.setMorningCompletedAt(LocalDateTime.of(2026, 11, 4, 9, 0));
        return control;
    }

    private ManagerDailyControlItem item(long id, ManagerDailyControlItemStatus status, long count, String comment) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(id);
        item.setGroup(ManagerDailyControlGroup.ACTION);
        item.setSeverity(ManagerDailyControlSeverity.CRITICAL);
        item.setStatus(status);
        item.setCount(count);
        item.setLabel("Пункт " + id);
        item.setComment(comment);
        return item;
    }

    private ManagerDailyControlConcreteItem card(ManagerDailyControlItem parent, ManagerDailyControlItemStatus status) {
        ManagerDailyControlConcreteItem card = new ManagerDailyControlConcreteItem();
        card.setParentItem(parent);
        card.setStatus(status);
        card.setTitle("Карточка");
        return card;
    }
}
