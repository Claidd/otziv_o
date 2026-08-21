package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverPreflightRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
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
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorRewardRepairClaimRepository rewardRepairClaimRepository;
    private final ZpRepository zpRepository;
    private final ContractorCompletionCutoverPreflightRepository cutoverPreflightRepository;
    private final ContractorCompletionCutoverStateService cutoverStateService;
    private final ContractorPaymentBusinessClock businessClock;

    @Transactional(readOnly = true)
    public boolean readyForLiveRouting() {
        if (repairStateRepository.count() > 0L) {
            return false;
        }
        var now = businessClock.now();
        var monthStart = businessClock.today().withDayOfMonth(1);
        if (!profileRepository.findEnabledIdsRequiringCurrentMonthSync(
                monthStart.atStartOfDay(),
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }
        if (rewardRepairClaimRepository.count() > 0L
                || zpRepository.countActiveIncompatibleContractorRewardSources() > 0L
                || !zpRepository.findContractorRewardsNeedingGlobalRepair(
                        now,
                        PageRequest.of(0, 1)
                ).isEmpty()) {
            return false;
        }
        var lockedCutover = cutoverStateService.lockedStartDate().orElse(null);
        if (lockedCutover == null
                || cutoverPreflightRepository.countActiveLegacyRewardCutoverConflicts(lockedCutover) > 0L) {
            return false;
        }

        if (!orderRepository.findCompletionRewardRepairOrderIds(
                ContractorCompletionRewardRepairService.DATED_COMPLETION_STATUSES,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS.size(),
                now,
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }
        if (!badReviewTaskRepository.findCompletionRewardRepairGapTaskIds(
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
