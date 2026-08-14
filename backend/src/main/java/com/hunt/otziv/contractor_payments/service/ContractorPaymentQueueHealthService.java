package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentQueueHealthResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentQueueHealthResponse.QueueHealth;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorShadowBackfillClaimRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContractorPaymentQueueHealthService {

    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorRewardRepairClaimRepository rewardClaimRepository;
    private final ContractorShadowBackfillClaimRepository backfillClaimRepository;
    private final ContractorCompletionRewardRepairStateRepository completionRepairStateRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public ContractorPaymentQueueHealthResponse health() {
        LocalDateTime now = LocalDateTime.now();
        return new ContractorPaymentQueueHealthResponse(
                new QueueHealth(
                        allocationRepository.countByReconcileLeaseUntilAfter(now),
                        allocationRepository.countExpiredReconcileClaims(now),
                        allocationRepository.countByReconcileAttemptsGreaterThan(0),
                        allocationRepository.countDueReconcileRetries(now),
                        allocationRepository.findOldestReconcileRetryAt(),
                        allocationRepository.findOldestDueReconcileRetryAt(now),
                        allocationRepository
                                .findFirstByReconcileAttemptsGreaterThanOrderByUpdatedAtDesc(0)
                                .map(value -> value.getReconcileLastErrorCode())
                                .orElse(null)
                ),
                new QueueHealth(
                        rewardClaimRepository.countByLeaseUntilAfter(now),
                        rewardClaimRepository.countExpiredClaims(now),
                        rewardClaimRepository.countByRetryAttemptsGreaterThan(0),
                        rewardClaimRepository.countDueRetries(now),
                        rewardClaimRepository.findOldestRetryAt(),
                        rewardClaimRepository.findOldestDueRetryAt(now),
                        rewardClaimRepository
                                .findFirstByRetryAttemptsGreaterThanOrderByUpdatedAtDesc(0)
                                .map(value -> value.getLastErrorCode())
                                .orElse(null)
                ),
                new QueueHealth(
                        backfillClaimRepository.countByCompletedAtIsNullAndLeaseUntilAfter(now),
                        backfillClaimRepository.countExpiredClaims(now),
                        backfillClaimRepository.countByCompletedAtIsNullAndRetryAttemptsGreaterThan(0),
                        backfillClaimRepository.countDueRetries(now),
                        backfillClaimRepository.findOldestRetryAt(),
                        backfillClaimRepository.findOldestDueRetryAt(now),
                        backfillClaimRepository
                                .findFirstByCompletedAtIsNullAndRetryAttemptsGreaterThanOrderByUpdatedAtDesc(0)
                                .map(value -> value.getLastErrorCode())
                                .orElse(null)
                ),
                new QueueHealth(
                        0,
                        0,
                        completionRepairStateRepository.countByAttemptCountGreaterThan(0),
                        completionRepairStateRepository.countByNextAttemptAtLessThanEqual(now),
                        completionRepairStateRepository.findOldestRetryAt(),
                        completionRepairStateRepository.findOldestDueRetryAt(now),
                        completionRepairStateRepository
                                .findFirstByAttemptCountGreaterThanOrderByUpdatedAtDesc(0)
                                .map(value -> value.getLastError())
                                .orElse(null)
                ),
                orderRepository.countCompletionRewardDeferredByActiveRecovery(
                        ContractorCompletionRewardRepairService.DATED_COMPLETION_STATUSES,
                        ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS,
                        ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS.size()
                ),
                now
        );
    }
}
