package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent live-routing preflight; it deliberately does not depend on the attribution gate. */
@Service
@RequiredArgsConstructor
public class ContractorCompletionRoutingReadinessService {

    private final OrderRepository orderRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ContractorCompletionRewardRepairStateRepository repairStateRepository;
    private final ContractorPaymentBusinessClock businessClock;

    @Transactional(readOnly = true)
    public boolean readyForLiveRouting() {
        if (repairStateRepository.count() > 0L) {
            return false;
        }
        var now = businessClock.now();
        if (!orderRepository.findCompletionRewardRepairOrderIds(
                ContractorCompletionRewardRepairService.DATED_COMPLETION_STATUSES,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS.size(),
                now,
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }
        if (!badReviewTaskRepository.findCompletionRewardRepairGapOrderIds(
                BadReviewTaskStatus.DONE.name(),
                ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX,
                now,
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }
        return badReviewTaskRepository.findCompletionRewardCancellationRepairGapTaskIds(
                BadReviewTaskStatus.CANCELED.name(),
                ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_CANCEL_MARKER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_MANAGER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_SPECIALIST_PREFIX,
                now,
                PageRequest.of(0, 1)
        ).isEmpty();
    }
}
