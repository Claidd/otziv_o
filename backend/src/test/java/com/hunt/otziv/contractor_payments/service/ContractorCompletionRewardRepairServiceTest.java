package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardRepairState;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionRewardRepairServiceTest {

    @Mock private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock private OrderRepository orderRepository;
    @Mock private BadReviewTaskRepository badReviewTaskRepository;
    @Mock private ContractorCompletionRepairTransactionService repairTransactionService;
    @Mock private ContractorCompletionRewardRepairStateRepository repairStateRepository;
    @Mock private ContractorPaymentBusinessClock businessClock;

    private ContractorCompletionRewardRepairService service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        service = new ContractorCompletionRewardRepairService(
                runtimeSwitch,
                orderRepository,
                badReviewTaskRepository,
                repairTransactionService,
                repairStateRepository,
                businessClock
        );
        ReflectionTestUtils.setField(service, "configuredBatchSize", 25);
        now = LocalDateTime.of(2026, 9, 1, 2, 30);
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        when(businessClock.now()).thenReturn(now);
    }

    @Test
    void poisonOrderGetsDurableSanitizedBackoffWithoutStarvingTail() {
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of(10L, 20L));
        when(repairStateRepository.findById(10L)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("secret customer/SQL payload"))
                .when(repairTransactionService).repairOrder(10L);

        service.repairCompletedUnpaidOrders();

        verify(repairTransactionService).repairOrder(20L);
        ArgumentCaptor<ContractorCompletionRewardRepairState> state =
                ArgumentCaptor.forClass(ContractorCompletionRewardRepairState.class);
        verify(repairStateRepository).save(state.capture());
        assertThat(state.getValue().getOrderId()).isEqualTo(10L);
        assertThat(state.getValue().getLastError()).isEqualTo("IllegalStateException");
        assertThat(state.getValue().getLastError()).doesNotContain("secret", "SQL", "customer");
        assertThat(state.getValue().getNextAttemptAt()).isAfter(now);
    }

    @Test
    void repairSelectionCoversEveryCompletedPaymentStatusWithoutHidingPartialLegacyRows() {
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of());

        service.repairCompletedUnpaidOrders();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> markers = ArgumentCaptor.forClass(Collection.class);
        verify(orderRepository).findCompletionRewardRepairOrderIds(
                statuses.capture(),
                markers.capture(),
                eq(3L),
                eq(now),
                any(Pageable.class)
        );
        assertThat(statuses.getValue()).containsExactly("Оплачено");
        assertThat(markers.getValue()).containsExactlyInAnyOrderElementsOf(
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS
        );
    }

    @Test
    void undatedCompletedTaskKeepsDurableRepairStateInsteadOfBeingCleared() {
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of(10L));
        ContractorCompletionRewardRepairState existing = new ContractorCompletionRewardRepairState();
        existing.setOrderId(10L);
        existing.setAttemptCount(2);
        when(repairStateRepository.findById(10L)).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "private diagnostic"))
                .when(repairTransactionService).repairOrder(10L);

        service.repairCompletedUnpaidOrders();

        verify(repairStateRepository, never()).deleteById(10L);
        ArgumentCaptor<ContractorCompletionRewardRepairState> state =
                ArgumentCaptor.forClass(ContractorCompletionRewardRepairState.class);
        verify(repairStateRepository).save(state.capture());
        assertThat(state.getValue()).isSameAs(existing);
        assertThat(state.getValue().getAttemptCount()).isEqualTo(3);
        assertThat(state.getValue().getLastError()).isEqualTo("ResponseStatusException");
        assertThat(state.getValue().getLastError()).doesNotContain("private", "diagnostic");
    }

    @Test
    void missingDoneTaskMarkerRepairsTheSpecificTaskWithoutRequiringWholeOrderCompletion() {
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardRepairGapTaskIds(
                eq("DONE"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of(44L));
        when(badReviewTaskRepository.findOrderIdById(44L)).thenReturn(Optional.of(30L));

        service.repairCompletedUnpaidOrders();

        verify(repairTransactionService).repairCompletedBadReviewTask(30L, 44L);
        verify(repairTransactionService, never()).repairOrder(30L);
    }

    @Test
    void doneTaskRepairRunsEvenWhenOrderRepairBatchIsFull() {
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of(10L, 11L, 12L));
        when(badReviewTaskRepository.findCompletionRewardRepairGapTaskIds(
                eq("DONE"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of(44L));
        when(badReviewTaskRepository.findOrderIdById(44L)).thenReturn(Optional.of(30L));

        service.repairCompletedUnpaidOrders();

        verify(repairTransactionService).repairOrder(10L);
        verify(repairTransactionService).repairOrder(11L);
        verify(repairTransactionService).repairOrder(12L);
        verify(repairTransactionService).repairCompletedBadReviewTask(30L, 44L);
    }

    @Test
    void canceledTaskWithUnfinishedAdjustmentIsRepairedAndClearsBackoff() {
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardCancellationRepairGapTaskIds(
                eq("CANCELED"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_CANCEL_MARKER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_MANAGER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_SPECIALIST_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of(44L));
        when(badReviewTaskRepository.findOrderIdById(44L)).thenReturn(Optional.of(30L));

        service.repairCompletedUnpaidOrders();

        verify(repairTransactionService).repairCanceledTask(30L, 44L);
    }
}
