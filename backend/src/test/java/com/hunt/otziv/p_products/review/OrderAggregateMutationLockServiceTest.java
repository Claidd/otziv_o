package com.hunt.otziv.p_products.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class OrderAggregateMutationLockServiceTest {

    @Test
    void aggregateLockRequiresCallerTransaction() throws Exception {
        Method method = OrderAggregateMutationLockService.class.getMethod("lock", Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        org.assertj.core.api.Assertions.assertThat(transactional).isNotNull();
        org.assertj.core.api.Assertions.assertThat(transactional.propagation())
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void detailMutationLocksCanonicalOrderThenRechecksBinding() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderDetailsRepository detailsRepository = mock(OrderDetailsRepository.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UUID detailId = UUID.randomUUID();
        Order order = Order.builder().id(11L).build();
        when(detailsRepository.findOrderIdById(detailId)).thenReturn(Optional.of(11L));
        when(orderRepository.findByIdForCounterUpdate(11L)).thenReturn(Optional.of(order));

        service(orderRepository, detailsRepository, reviewRepository).lockForOrderDetail(detailId);

        var ordered = inOrder(detailsRepository, orderRepository);
        ordered.verify(detailsRepository).findOrderIdById(detailId);
        ordered.verify(orderRepository).findByIdForCounterUpdate(11L);
        ordered.verify(detailsRepository).findOrderIdById(detailId);
    }

    @Test
    void changedReviewBindingIsRejectedAfterParentLock() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderDetailsRepository detailsRepository = mock(OrderDetailsRepository.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        when(reviewRepository.findOrderIdByReviewId(17L))
                .thenReturn(Optional.of(11L), Optional.of(12L));
        when(orderRepository.findByIdForCounterUpdate(11L))
                .thenReturn(Optional.of(Order.builder().id(11L).build()));

        assertThatThrownBy(() -> service(orderRepository, detailsRepository, reviewRepository)
                .lockForReview(17L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        var ordered = inOrder(reviewRepository, orderRepository);
        ordered.verify(reviewRepository).findOrderIdByReviewId(17L);
        ordered.verify(orderRepository).findByIdForCounterUpdate(11L);
        ordered.verify(reviewRepository).findOrderIdByReviewId(17L);
    }

    private OrderAggregateMutationLockService service(
            OrderRepository orderRepository,
            OrderDetailsRepository detailsRepository,
            ReviewRepository reviewRepository
    ) {
        return new OrderAggregateMutationLockService(
                orderRepository,
                detailsRepository,
                reviewRepository
        );
    }
}
