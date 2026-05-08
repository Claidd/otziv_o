package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.board.OrderBoardQueryService;
import com.hunt.otziv.p_products.deletion.OrderDeletionService;
import com.hunt.otziv.p_products.editing.OrderEditService;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.OrderReviewMutationService;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.statistics.OrderStatisticsService;
import com.hunt.otziv.p_products.status.OrderBotLifecycleService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDetailsRepository orderDetailsRepository;

    @Mock
    private CompanyService companyService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private ReviewArchiveService reviewArchiveService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Mock
    private OrderStatusCheckerService orderStatusCheckerService;

    @Mock
    private OrderDtoMapper orderDtoMapper;

    @Mock
    private OrderBoardQueryService orderBoardQueryService;

    @Mock
    private OrderStatisticsService orderStatisticsService;

    @Mock
    private OrderDeletionService orderDeletionService;

    @Mock
    private OrderEditService orderEditService;

    @Mock
    private OrderBotLifecycleService orderBotLifecycleService;

    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;

    @Mock
    private OrderReviewMutationService orderReviewMutationService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void changeStatusAndOrderCounterSynchronizesCounterToActualPublishedReviews() throws Exception {
        Order order = order(10L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review alreadyPublished = review(1L, true, "Уже опубликован", details);
        Review reviewToPublish = review(2L, false, "Новый отзыв", details);
        details.setReviews(List.of(alreadyPublished, reviewToPublish));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(10L));
        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(reviewToPublish));
        when(reviewRepository.countPublishedByOrderId(10L)).thenReturn(2);
        doAnswer(invocation -> {
            Order synchronizedOrder = invocation.getArgument(0);
            Integer actualPublished = invocation.getArgument(1);
            synchronizedOrder.setCounter(actualPublished);
            return null;
        }).when(orderStatusCheckerService).validateCounterConsistency(same(order), eq(2));

        assertTrue(orderService.changeStatusAndOrderCounter(2L));

        assertTrue(reviewToPublish.isPublish());
        verify(reviewRepository).save(reviewToPublish);
        verify(reviewRepository).countPublishedByOrderId(10L);
        verify(orderStatusCheckerService).validateCounterConsistency(order, 2);
        verify(orderStatusCheckerService).checkAndMarkOrderCompleted(order);
        verify(reviewService, never()).save(reviewToPublish);
    }

    private Order order(Long id, int counter) {
        Order order = new Order();
        order.setId(id);
        order.setCounter(counter);
        order.setAmount(5);
        return order;
    }

    private Review review(Long id, boolean publish, String text, OrderDetails details) {
        Review review = new Review();
        review.setId(id);
        review.setPublish(publish);
        review.setText(text);
        review.setOrderDetails(details);
        return review;
    }
}
