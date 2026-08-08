package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardRepairState;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded, fail-safe repair for completed but still unpaid rollout orders. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorCompletionRewardRepairService {

    static final List<String> DATED_COMPLETION_STATUSES = List.of(
            "Опубликовано",
            "Выставлен счет",
            "Ожидает общего счета",
            "Напоминание",
            "Не оплачено",
            "Бан",
            "Оплачено"
    );
    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final OrderRepository orderRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ContractorCompletionRewardService completionRewardService;
    private final ContractorCompletionRewardRepairStateRepository repairStateRepository;
    private final ContractorPaymentBusinessClock businessClock;

    @Value("${otziv.contractor-payments.completion-repair-batch-size:25}")
    private int configuredBatchSize;

    @Scheduled(
            initialDelayString = "${otziv.contractor-payments.completion-repair-initial-delay-ms:45000}",
            fixedDelayString = "${otziv.contractor-payments.completion-repair-delay-ms:60000}"
    )
    public void repairCompletedUnpaidOrders() {
        if (!runtimeSwitch.rewardAttributionLiveEnabled()) {
            return;
        }
        int batchSize = Math.max(1, Math.min(100, configuredBatchSize));
        LocalDateTime now = businessClock.now();
        LinkedHashSet<Long> orderIds = new LinkedHashSet<>(orderRepository.findCompletionRewardRepairOrderIds(
                DATED_COMPLETION_STATUSES,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS,
                ContractorRewardSourceCodes.REQUIRED_ORDER_COMPLETION_MARKERS.size(),
                now,
                PageRequest.of(0, batchSize)
        ));
        if (orderIds.size() < batchSize) {
            for (Long orderId : badReviewTaskRepository.findCompletionRewardRepairGapOrderIds(
                    BadReviewTaskStatus.DONE.name(),
                    ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX,
                    now,
                    PageRequest.of(0, batchSize)
            )) {
                orderIds.add(orderId);
                if (orderIds.size() >= batchSize) {
                    break;
                }
            }
        }
        for (Long orderId : orderIds) {
            try {
                // The scheduled method itself is not transactional, so every
                // call gets an independent REQUIRED transaction and one bad
                // historical row cannot roll back the remainder of the batch.
                completionRewardService.ensureOrderCompletionAccrual(orderId);
                repairStateRepository.deleteById(orderId);
            } catch (RuntimeException exception) {
                try {
                    deferFailedOrder(orderId, exception);
                } catch (RuntimeException stateFailure) {
                    // A concurrent node can win the repair-state primary-key
                    // insert. Idempotent order locking still protects reward
                    // creation; health-state contention must never abort the
                    // remainder of this bounded batch.
                    log.warn(
                            "Не удалось обновить backoff начисления: orderId={}, code={}",
                            orderId,
                            stateFailure.getClass().getSimpleName()
                    );
                }
                log.error(
                        "Не удалось восстановить начисления завершенного заказа: orderId={}, code={}",
                        orderId,
                        exception.getClass().getSimpleName()
                );
            }
        }

        repairCanceledTaskGaps(batchSize, now);
    }

    private void repairCanceledTaskGaps(int batchSize, LocalDateTime now) {
        List<Long> taskIds = badReviewTaskRepository.findCompletionRewardCancellationRepairGapTaskIds(
                BadReviewTaskStatus.CANCELED.name(),
                ContractorRewardSourceCodes.BAD_REVIEW_DONE_MARKER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_CANCEL_MARKER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_MANAGER_PREFIX,
                ContractorRewardSourceCodes.BAD_REVIEW_SPECIALIST_PREFIX,
                now,
                PageRequest.of(0, batchSize)
        );
        for (Long taskId : taskIds) {
            Optional<Long> orderId = badReviewTaskRepository.findOrderIdById(taskId);
            if (orderId.isEmpty()) {
                continue;
            }
            try {
                completionRewardService.adjustCanceledBadReviewTaskAccrual(orderId.get(), taskId);
                repairStateRepository.deleteById(orderId.get());
            } catch (RuntimeException exception) {
                try {
                    deferFailedOrder(orderId.get(), exception);
                } catch (RuntimeException stateFailure) {
                    log.warn(
                            "Не удалось обновить backoff отмены начисления: orderId={}, code={}",
                            orderId.get(),
                            stateFailure.getClass().getSimpleName()
                    );
                }
                log.error(
                        "Не удалось восстановить отмену выполненной работы: orderId={}, taskId={}, code={}",
                        orderId.get(),
                        taskId,
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    private void deferFailedOrder(Long orderId, RuntimeException exception) {
        LocalDateTime now = businessClock.now();
        ContractorCompletionRewardRepairState state = repairStateRepository.findById(orderId)
                .orElseGet(ContractorCompletionRewardRepairState::new);
        state.setOrderId(orderId);
        int attempts = Math.min(30, Math.max(0, state.getAttemptCount()) + 1);
        state.setAttemptCount(attempts);
        long delayMinutes = Math.min(24L * 60L, 5L << Math.min(8, attempts - 1));
        state.setNextAttemptAt(now.plusMinutes(delayMinutes));
        // Persist only a stable class code. Exception/SQL messages can contain
        // customer data, credentials or database connection details.
        state.setLastError(limit(exception.getClass().getSimpleName(), 160));
        state.setUpdatedAt(now);
        repairStateRepository.save(state);
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
