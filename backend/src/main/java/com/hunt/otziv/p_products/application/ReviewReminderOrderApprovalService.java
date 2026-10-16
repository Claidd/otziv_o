package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.ReviewReminderOrderApprovals;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewReminderOrderApprovalService implements ReviewReminderOrderApprovals {
    private final OrderAggregateMutationLockService orderLockService;
    private final OrderPublicationApprovalService publicationApprovalService;
    private final ReviewRecoveryMessageHold reviewRecoveryMessageHold;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockEligible(Long orderId, String reviewCycleKey) {
        Order order = orderLockService.lockIfLive(orderId).orElse(null);
        if (order == null || order.getStatus() == null || !"На проверке".equals(order.getStatus().getTitle())
                || reviewRecoveryMessageHold.shouldPauseActiveOrder(orderId)) {
            return false;
        }
        LocalDateTime changedAt = order.getStatusChangedAt();
        if (changedAt == null && order.getChanged() != null) changedAt = order.getChanged().atStartOfDay();
        if (changedAt == null && order.getCreated() != null) changedAt = order.getCreated().atStartOfDay();
        return changedAt != null && ("order:" + orderId + ":" + changedAt.withNano(0)).equals(reviewCycleKey);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void approve(Long orderId, String auditDetails) {
        publicationApprovalService.approveExistingOrderAfterReminders(orderId, auditDetails);
    }
}
