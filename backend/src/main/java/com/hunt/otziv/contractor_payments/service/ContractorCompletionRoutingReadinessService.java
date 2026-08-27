package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverPreflightRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent live-routing preflight; it deliberately does not depend on the attribution gate.
 * Activation preflight and runtime health are deliberately different contracts. Activation is
 * strict and scans the whole historical dataset. After cutover, historical bridge rows and
 * order-scoped repair gaps are diagnostics: they must fail the affected order closed, but must
 * never silently disable requisites for every unrelated order.
 */
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
        return hardRuntimeBlockers().isEmpty();
    }

    /**
     * Only conditions that make every new route unsafe belong here. Historical overlap scans and
     * order/task repair queues are intentionally reported by {@link #runtimeWarnings()} instead.
     */
    @Transactional(readOnly = true)
    public List<String> hardRuntimeBlockers() {
        List<String> blockers = new ArrayList<>();
        if (repairStateRepository.count() > 0L) {
            blockers.add("Выполняется восстановление финансового состояния");
        }
        var monthStart = businessClock.today().withDayOfMonth(1);
        if (!profileRepository.findEnabledIdsRequiringCurrentMonthSync(
                monthStart.atStartOfDay(),
                PageRequest.of(0, 1)
        ).isEmpty()) {
            blockers.add("Есть активные платежные профили без синхронизации текущего месяца");
        }
        if (rewardRepairClaimRepository.count() > 0L) {
            blockers.add("Идёт транзакционное восстановление начислений");
        }
        if (zpRepository.countActiveIncompatibleContractorRewardSources() > 0L) {
            blockers.add("Обнаружены несовместимые активные источники начислений");
        }
        if (cutoverStateService.lockedStartDate().isEmpty()) {
            blockers.add("Не зафиксирована дата перехода на новый учёт");
        }
        return List.copyOf(blockers);
    }

    /**
     * Local/audit findings visible to OWNER/ADMIN. They remain strict activation blockers, but do
     * not flap the already activated global runtime switch.
     */
    @Transactional(readOnly = true)
    public List<String> runtimeWarnings() {
        List<String> warnings = new ArrayList<>();
        var lockedCutover = cutoverStateService.lockedStartDate().orElse(null);
        if (lockedCutover != null) {
            long historicalConflicts = cutoverPreflightRepository
                    .countActiveLegacyRewardCutoverConflicts(lockedCutover);
            if (historicalConflicts > 0L) {
                warnings.add("Исторические начисления требуют локальной сверки: заказов — "
                        + historicalConflicts);
            }
        }

        var now = businessClock.now();
        if (!orderRepository.findCompletionRewardRepairOrderIds(
                ContractorCompletionRewardRepairService.DATED_COMPLETION_STATUSES,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS.size(),
                now,
                PageRequest.of(0, 1)
        ).isEmpty()) {
            warnings.add("Есть заказы с локальной очередью восстановления начислений");
        }
        if (!badReviewTaskRepository.findCompletionRewardRepairGapTaskIds(
                BadReviewTaskStatus.DONE.name(),
                ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX,
                now,
                PageRequest.of(0, 1)
        ).isEmpty()) {
            warnings.add("Есть выполненные задачи отзывов с локальной очередью начислений");
        }
        if (!badReviewTaskRepository.findCompletionRewardCancellationRepairGapTaskIds(
                BadReviewTaskStatus.CANCELED.name(),
                ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_CANCEL_MARKER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_MANAGER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_SPECIALIST_PREFIX,
                now,
                PageRequest.of(0, 1)
        ).isEmpty()) {
            warnings.add("Есть отменённые задачи отзывов с локальной очередью корректировок");
        }
        return List.copyOf(warnings);
    }
}
