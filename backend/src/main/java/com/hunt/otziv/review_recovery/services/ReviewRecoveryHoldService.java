package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewRecoveryHoldService {

    private static final Set<String> TERMINAL_ORDER_STATUSES = Set.of("Оплачено", "Архив", "Бан");
    private static final Set<String> CLIENT_SILENT_RECOVERY_STATUSES = Set.of("Архив", "Бан");

    private final ReviewRecoveryBatchRepository batchRepository;
    private final OrderRepository orderRepository;
    private final ReviewRecoveryGateService recoveryGateService;

    @Transactional(readOnly = true)
    public boolean shouldPauseClientMessages(Order order) {
        if (order == null || order.getId() == null || isTerminal(order)) {
            return false;
        }
        return recoveryGateService.hasActiveRecoveryTasks(order.getId());
    }

    @Transactional
    public void releaseDeadlineHold(ReviewRecoveryBatch batch) {
        if (batch == null || batch.getId() == null) {
            return;
        }

        ReviewRecoveryBatch managed = batchRepository.findById(batch.getId()).orElse(null);
        if (managed == null || managed.getHoldReleasedAt() != null) {
            return;
        }

        Instant releasedAt = managed.getClientNotifiedAt() == null ? Instant.now() : managed.getClientNotifiedAt();
        managed.setHoldReleasedAt(releasedAt);

        Order order = managed.getOrder();
        long shiftSeconds = holdDurationSeconds(managed, releasedAt);
        managed.setDeadlineShiftSeconds(shiftSeconds);
        managed.setDeadlineShiftAppliedAt(Instant.now());

        if (order != null && order.getId() != null && !isTerminal(order) && shiftSeconds > 0) {
            Order managedOrder = orderRepository.findByIdForMutation(order.getId()).orElse(order);
            managedOrder.setStatusChangedAt(shift(managedOrder.getStatusChangedAt(), shiftSeconds));
            if (managedOrder.isWaitingForClient()) {
                managedOrder.setWaitingForClientChangedAt(shift(managedOrder.getWaitingForClientChangedAt(), shiftSeconds));
            }
            orderRepository.save(managedOrder);
        }

        batchRepository.save(managed);
    }

    @Transactional
    public ReviewRecoveryBatch releaseWithoutClientNotice(ReviewRecoveryBatch batch) {
        if (batch == null || batch.getId() == null) {
            return batch;
        }
        ReviewRecoveryBatch managed = batchRepository.findById(batch.getId()).orElse(batch);
        releaseDeadlineHold(managed);
        return batchRepository.findById(managed.getId()).orElse(managed);
    }

    public boolean isTerminal(Order order) {
        return TERMINAL_ORDER_STATUSES.contains(statusTitle(order));
    }

    public boolean shouldSkipClientRecoveryNotice(Order order) {
        return CLIENT_SILENT_RECOVERY_STATUSES.contains(statusTitle(order));
    }

    private String statusTitle(Order order) {
        String status = order == null || order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle();
        return status;
    }

    private long holdDurationSeconds(ReviewRecoveryBatch batch, Instant releasedAt) {
        Instant startedAt = batch.getHoldStartedAt();
        if (startedAt == null) {
            startedAt = batch.getCreatedAt();
        }
        if (startedAt == null || releasedAt == null || !releasedAt.isAfter(startedAt)) {
            return 0;
        }
        return Math.max(0, Duration.between(startedAt, releasedAt).getSeconds());
    }

    private LocalDateTime shift(LocalDateTime value, long shiftSeconds) {
        if (value == null) {
            return LocalDateTime.ofInstant(Instant.now().plusSeconds(shiftSeconds), ZoneId.systemDefault());
        }
        return value.plusSeconds(shiftSeconds);
    }
}
