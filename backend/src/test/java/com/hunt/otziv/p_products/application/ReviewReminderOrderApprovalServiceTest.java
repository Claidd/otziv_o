package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewReminderOrderApprovalServiceTest {
    @Mock OrderAggregateMutationLockService orderLockService;
    @Mock OrderPublicationApprovalService publicationApprovalService;
    @Mock ReviewRecoveryMessageHold recoveryHold;
    @InjectMocks ReviewReminderOrderApprovalService service;

    @Test
    void rejectsStaleReviewCycleAndRecoveryHold() {
        Order order = new Order();
        order.setId(42L);
        order.setStatus(OrderStatus.builder().title("На проверке").build());
        order.setStatusChangedAt(LocalDateTime.of(2026, 10, 1, 12, 30));
        when(orderLockService.lockIfLive(42L)).thenReturn(Optional.of(order));

        assertFalse(service.lockEligible(42L, "order:42:2026-09-01T12:30"));
        when(recoveryHold.shouldPauseActiveOrder(42L)).thenReturn(true);
        assertFalse(service.lockEligible(42L, "order:42:2026-10-01T12:30"));
        verifyNoInteractions(publicationApprovalService);
    }
}
