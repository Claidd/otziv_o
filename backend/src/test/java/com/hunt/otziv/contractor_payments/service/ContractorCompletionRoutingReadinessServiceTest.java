package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverPreflightRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionRoutingReadinessServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private BadReviewTaskRepository badReviewTaskRepository;
    @Mock private ContractorCompletionRewardRepairStateRepository repairStateRepository;
    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ContractorRewardRepairClaimRepository rewardRepairClaimRepository;
    @Mock private ZpRepository zpRepository;
    @Mock private ContractorCompletionCutoverPreflightRepository cutoverPreflightRepository;
    @Mock private ContractorCompletionCutoverStateService cutoverStateService;
    @Mock private ContractorPaymentBusinessClock businessClock;

    private ContractorCompletionRoutingReadinessService service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        service = new ContractorCompletionRoutingReadinessService(
                orderRepository,
                badReviewTaskRepository,
                repairStateRepository,
                profileRepository,
                rewardRepairClaimRepository,
                zpRepository,
                cutoverPreflightRepository,
                cutoverStateService,
                businessClock
        );
        now = LocalDateTime.of(2026, 8, 7, 12, 0);
        lenient().when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 7));
        lenient().when(profileRepository.findEnabledIdsRequiringCurrentMonthSync(any(), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(rewardRepairClaimRepository.count()).thenReturn(0L);
        lenient().when(zpRepository.countActiveIncompatibleContractorRewardSources()).thenReturn(0L);
        lenient().when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(cutoverStateService.lockedStartDate()).thenReturn(java.util.Optional.of(LocalDate.of(2026, 8, 1)));
        lenient().when(cutoverPreflightRepository.countActiveLegacyRewardCutoverConflicts(any()))
                .thenReturn(0L);
        lenient().when(orderRepository.countCompletionRewardDeferredByActiveRecovery(any(), any(), eq(3L)))
                .thenReturn(0L);
    }

    @Test
    void durableRepairFailureKeepsLiveRoutingClosedWithoutScanningOrders() {
        when(repairStateRepository.count()).thenReturn(1L);

        assertThat(service.readyForLiveRouting()).isFalse();

        verifyNoInteractions(orderRepository, badReviewTaskRepository, businessClock);
    }

    @Test
    void partialBaseMarkerSetKeepsLiveRoutingClosedWithoutScanningTasks() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(),
                any(),
                eq(3L),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of(71L));

        assertThat(service.readyForLiveRouting()).isFalse();

        verifyNoInteractions(badReviewTaskRepository);
    }

    @Test
    void activeRecoveryBaseGapKeepsLiveRoutingClosedButIsNotSubmittedToRepair() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(orderRepository.countCompletionRewardDeferredByActiveRecovery(
                any(), any(), eq(3L)
        )).thenReturn(14L);

        assertThat(service.readyForLiveRouting()).isFalse();

        verifyNoInteractions(badReviewTaskRepository);
    }

    @Test
    void missingDoneTaskMarkerKeepsLiveRoutingClosed() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(),
                any(),
                eq(3L),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardRepairGapOrderIds(
                eq("DONE"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of(91L));

        assertThat(service.readyForLiveRouting()).isFalse();
    }

    @Test
    void incompleteInitialMonthSyncKeepsLiveRoutingClosed() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(profileRepository.findEnabledIdsRequiringCurrentMonthSync(any(), any(Pageable.class)))
                .thenReturn(List.of(17L));

        assertThat(service.readyForLiveRouting()).isFalse();

        verifyNoInteractions(orderRepository, badReviewTaskRepository);
    }

    @Test
    void rewardLedgerRepairOrLegacyOverlapKeepsLiveRoutingClosed() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(rewardRepairClaimRepository.count()).thenReturn(1L);

        assertThat(service.readyForLiveRouting()).isFalse();

        when(rewardRepairClaimRepository.count()).thenReturn(0L);
        when(cutoverPreflightRepository.countActiveLegacyRewardCutoverConflicts(any()))
                .thenReturn(1L);

        assertThat(service.readyForLiveRouting()).isFalse();
        verifyNoInteractions(orderRepository, badReviewTaskRepository);
    }

    @Test
    void currentSyncMarkerCannotHideIncompatibleSourceRoleFromLiveRouting() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(zpRepository.countActiveIncompatibleContractorRewardSources()).thenReturn(1L);

        assertThat(service.readyForLiveRouting()).isFalse();

        verifyNoInteractions(orderRepository, badReviewTaskRepository);
    }

    @Test
    void missingCancellationMarkerKeepsLiveRoutingClosed() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(), any(), eq(3L), eq(now), any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardRepairGapOrderIds(
                eq("DONE"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardCancellationRepairGapTaskIds(
                eq("CANCELED"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_CANCEL_MARKER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_MANAGER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_SPECIALIST_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of(92L));

        assertThat(service.readyForLiveRouting()).isFalse();
    }

    @Test
    void liveRoutingIsReadyOnlyWhenRepairStateAndEveryMarkerScanAreEmpty() {
        when(repairStateRepository.count()).thenReturn(0L);
        when(businessClock.now()).thenReturn(now);
        when(orderRepository.findCompletionRewardRepairOrderIds(
                any(),
                any(),
                eq(3L),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardRepairGapOrderIds(
                eq("DONE"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(badReviewTaskRepository.findCompletionRewardCancellationRepairGapTaskIds(
                eq("CANCELED"),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_CANCEL_MARKER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_MANAGER_PREFIX),
                eq(ContractorRewardSourceCodes.BAD_REVIEW_SPECIALIST_PREFIX),
                eq(now),
                any(Pageable.class)
        )).thenReturn(List.of());

        assertThat(service.readyForLiveRouting()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> requiredMarkers = ArgumentCaptor.forClass(Collection.class);
        verify(orderRepository).findCompletionRewardRepairOrderIds(
                any(),
                requiredMarkers.capture(),
                eq(3L),
                eq(now),
                any(Pageable.class)
        );
        assertThat(requiredMarkers.getValue()).containsExactlyInAnyOrder(
                ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER,
                ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST,
                ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION
        );
    }
}
