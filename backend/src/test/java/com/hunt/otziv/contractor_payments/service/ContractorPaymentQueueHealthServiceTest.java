package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardRepairState;
import com.hunt.otziv.contractor_payments.model.ContractorRewardRepairClaim;
import com.hunt.otziv.contractor_payments.model.ContractorShadowBackfillClaim;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorShadowBackfillClaimRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentQueueHealthServiceTest {

    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;
    @Mock
    private ContractorRewardRepairClaimRepository rewardClaimRepository;
    @Mock
    private ContractorShadowBackfillClaimRepository backfillClaimRepository;
    @Mock
    private ContractorCompletionRewardRepairStateRepository completionRepairStateRepository;

    private ContractorPaymentQueueHealthService service;

    @BeforeEach
    void setUp() {
        service = new ContractorPaymentQueueHealthService(
                allocationRepository, rewardClaimRepository, backfillClaimRepository, completionRepairStateRepository
        );
    }

    @Test
    void expiredLeasesAndDueRetriesRemainVisibleEvenWithoutActiveClaims() {
        LocalDateTime oldest = LocalDateTime.of(2026, 8, 7, 8, 0);
        LocalDateTime oldestDue = LocalDateTime.of(2026, 8, 7, 9, 0);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setReconcileLastErrorCode("AllocationFailure");
        ContractorRewardRepairClaim reward = new ContractorRewardRepairClaim();
        reward.setLastErrorCode("RewardFailure");
        ContractorShadowBackfillClaim backfill = new ContractorShadowBackfillClaim();
        backfill.setLastErrorCode("BackfillFailure");
        ContractorCompletionRewardRepairState completionRepair = new ContractorCompletionRewardRepairState();
        completionRepair.setLastError("CompletionFailure");

        when(allocationRepository.countByReconcileLeaseUntilAfter(any())).thenReturn(0L);
        when(allocationRepository.countExpiredReconcileClaims(any())).thenReturn(2L);
        when(allocationRepository.countByReconcileAttemptsGreaterThan(0)).thenReturn(3L);
        when(allocationRepository.countDueReconcileRetries(any())).thenReturn(1L);
        when(allocationRepository.findOldestReconcileRetryAt()).thenReturn(oldest);
        when(allocationRepository.findOldestDueReconcileRetryAt(any())).thenReturn(oldestDue);
        when(allocationRepository.findFirstByReconcileAttemptsGreaterThanOrderByUpdatedAtDesc(0))
                .thenReturn(Optional.of(allocation));

        when(rewardClaimRepository.countByLeaseUntilAfter(any())).thenReturn(0L);
        when(rewardClaimRepository.countExpiredClaims(any())).thenReturn(1L);
        when(rewardClaimRepository.countByRetryAttemptsGreaterThan(0)).thenReturn(4L);
        when(rewardClaimRepository.countDueRetries(any())).thenReturn(2L);
        when(rewardClaimRepository.findOldestRetryAt()).thenReturn(oldest);
        when(rewardClaimRepository.findOldestDueRetryAt(any())).thenReturn(oldestDue);
        when(rewardClaimRepository.findFirstByRetryAttemptsGreaterThanOrderByUpdatedAtDesc(0))
                .thenReturn(Optional.of(reward));

        when(backfillClaimRepository.countByCompletedAtIsNullAndLeaseUntilAfter(any())).thenReturn(0L);
        when(backfillClaimRepository.countExpiredClaims(any())).thenReturn(5L);
        when(backfillClaimRepository.countByCompletedAtIsNullAndRetryAttemptsGreaterThan(0)).thenReturn(6L);
        when(backfillClaimRepository.countDueRetries(any())).thenReturn(3L);
        when(backfillClaimRepository.findOldestRetryAt()).thenReturn(oldest);
        when(backfillClaimRepository.findOldestDueRetryAt(any())).thenReturn(oldestDue);
        when(backfillClaimRepository
                .findFirstByCompletedAtIsNullAndRetryAttemptsGreaterThanOrderByUpdatedAtDesc(0))
                .thenReturn(Optional.of(backfill));

        when(completionRepairStateRepository.countByAttemptCountGreaterThan(0)).thenReturn(7L);
        when(completionRepairStateRepository.countByNextAttemptAtLessThanEqual(any())).thenReturn(4L);
        when(completionRepairStateRepository.findOldestRetryAt()).thenReturn(oldest);
        when(completionRepairStateRepository.findOldestDueRetryAt(any())).thenReturn(oldestDue);
        when(completionRepairStateRepository.findFirstByAttemptCountGreaterThanOrderByUpdatedAtDesc(0))
                .thenReturn(Optional.of(completionRepair));

        var health = service.health();

        assertThat(health.allocationReconciliation().activeClaims()).isZero();
        assertThat(health.allocationReconciliation().expiredClaims()).isEqualTo(2L);
        assertThat(health.allocationReconciliation().retrying()).isEqualTo(3L);
        assertThat(health.allocationReconciliation().dueRetries()).isEqualTo(1L);
        assertThat(health.allocationReconciliation().oldestRetryAt()).isEqualTo(oldest);
        assertThat(health.allocationReconciliation().oldestDueRetryAt()).isEqualTo(oldestDue);
        assertThat(health.allocationReconciliation().lastErrorCode()).isEqualTo("AllocationFailure");
        assertThat(health.rewardRepair().expiredClaims()).isEqualTo(1L);
        assertThat(health.shadowBackfill().expiredClaims()).isEqualTo(5L);
        assertThat(health.completionRewardRepair().activeClaims()).isZero();
        assertThat(health.completionRewardRepair().expiredClaims()).isZero();
        assertThat(health.completionRewardRepair().retrying()).isEqualTo(7L);
        assertThat(health.completionRewardRepair().dueRetries()).isEqualTo(4L);
        assertThat(health.completionRewardRepair().oldestRetryAt()).isEqualTo(oldest);
        assertThat(health.completionRewardRepair().oldestDueRetryAt()).isEqualTo(oldestDue);
        assertThat(health.completionRewardRepair().lastErrorCode()).isEqualTo("CompletionFailure");
        assertThat(health.observedAt()).isNotNull();
    }
}
