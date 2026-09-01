package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.common_billing.service.CommonBillingNextOrderFailureMarker;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.dto.NextOrderRequestFailedEvent;
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
class NextOrderRequestFailureListenerTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CommonBillingNextOrderFailureMarker commonBillingNextOrderFailureMarker;
    @Mock private NextOrderFailureNotifier nextOrderFailureNotifier;

    @Test
    void notificationFailureCannotUndoDurableFailedRequestOrStarveOtherNotification() {
        Order sourceOrder = Order.builder().id(30L).build();
        RuntimeException cause = new RuntimeException("creation failed");
        when(orderRepository.findByIdForMutation(30L)).thenReturn(Optional.of(sourceOrder));
        doThrow(new IllegalStateException("common billing unavailable"))
                .when(commonBillingNextOrderFailureMarker)
                .markAttentionForSourceOrder(sourceOrder, 50L, cause);

        new NextOrderRequestFailureListener(
                orderRepository,
                commonBillingNextOrderFailureMarker,
                nextOrderFailureNotifier
        ).handle(new NextOrderRequestFailedEvent(50L, 30L, cause));

        verify(nextOrderFailureNotifier).notifyManager(
                sourceOrder,
                null,
                "автосоздание следующего заказа по заявке #50",
                cause
        );
    }
}
