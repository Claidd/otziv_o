package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically repairs one immutable source group and clears its durable backoff state. */
@Service
@RequiredArgsConstructor
public class ContractorCompletionRepairTransactionService {

    private final ContractorCompletionRewardService completionRewardService;
    private final ContractorCompletionRewardRepairStateRepository repairStateRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repairOrder(Long orderId) {
        completionRewardService.ensureOrderCompletionAccrual(orderId);
        repairStateRepository.deleteById(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repairCanceledTask(Long orderId, Long taskId) {
        completionRewardService.adjustCanceledBadReviewTaskAccrual(orderId, taskId);
        repairStateRepository.deleteById(orderId);
    }
}
