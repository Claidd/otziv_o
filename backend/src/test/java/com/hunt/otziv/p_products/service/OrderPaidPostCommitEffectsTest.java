package com.hunt.otziv.p_products.service;

import com.hunt.otziv.bad_reviews.api.PaidBadReviewReminders;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaidPostCommitEffectsTest {

    @Mock private OrderRepository orderRepository;
    @Mock private PaidBadReviewReminders paidBadReviewReminders;
    @Mock private GamificationEventService gamificationEventService;
    @Mock private MobilePushBusinessNotificationService mobilePushBusinessNotificationService;

    @Test
    void oneOptionalFailureDoesNotStarveTheOtherEffect() {
        Order order = Order.builder().id(42L).build();
        when(orderRepository.findByIdForMutation(42L)).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("gamification unavailable"))
                .when(gamificationEventService).recordOrderPaid(order);

        new OrderPaidPostCommitEffects(
                orderRepository,
                paidBadReviewReminders,
                gamificationEventService,
                mobilePushBusinessNotificationService
        ).apply(42L);

        verify(paidBadReviewReminders).closeForPaidOrder(42L);
        verify(mobilePushBusinessNotificationService).notifyOwnersOrderPaid(order);
    }

    @Test
    void reminderCleanupFailureDoesNotRollBackPaidOrderEffects() {
        Order order = Order.builder().id(43L).build();
        when(orderRepository.findByIdForMutation(43L)).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("reminders unavailable"))
                .when(paidBadReviewReminders).closeForPaidOrder(43L);

        new OrderPaidPostCommitEffects(
                orderRepository,
                paidBadReviewReminders,
                gamificationEventService,
                mobilePushBusinessNotificationService
        ).apply(43L);

        verify(gamificationEventService).recordOrderPaid(order);
        verify(mobilePushBusinessNotificationService).notifyOwnersOrderPaid(order);
    }
}
