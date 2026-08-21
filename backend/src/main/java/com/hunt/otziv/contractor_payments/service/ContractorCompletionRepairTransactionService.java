package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import java.util.Objects;
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
    private final BadReviewTaskRepository badReviewTaskRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repairOrder(Long orderId) {
        completionRewardService.ensureOrderCompletionAccrual(orderId);
        repairStateRepository.deleteById(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repairCompletedBadReviewTask(Long orderId, Long taskId) {
        BadReviewTask task = badReviewTaskRepository.findByIdForMutation(taskId).orElse(null);
        if (task == null) {
            if (orderId != null) {
                repairStateRepository.deleteById(orderId);
            }
            return;
        }
        Long actualOrderId = task.getOrder() == null ? null : task.getOrder().getId();
        if (!Objects.equals(actualOrderId, orderId)) {
            throw new IllegalStateException("Recovery task belongs to another order");
        }
        completionRewardService.ensureCompletedBadReviewTask(task);
        repairStateRepository.deleteById(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repairCanceledTask(Long orderId, Long taskId) {
        completionRewardService.adjustCanceledBadReviewTaskAccrual(orderId, taskId);
        repairStateRepository.deleteById(orderId);
    }
}
