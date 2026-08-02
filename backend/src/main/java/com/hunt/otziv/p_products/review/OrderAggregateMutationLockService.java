package com.hunt.otziv.p_products.review;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Canonical mutex for every live order aggregate mutation.
 *
 * <p>Relationship-based entry points first resolve an order id, lock the
 * canonical {@code orders} row and then resolve the relationship again. This
 * closes the read/auth/write window without ever acquiring a child-row lock
 * before the parent order lock.</p>
 */
@Service
@RequiredArgsConstructor
public class OrderAggregateMutationLockService {

    private final OrderRepository orderRepository;
    private final OrderDetailsRepository orderDetailsRepository;
    private final ReviewRepository reviewRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Order lock(Long orderId) {
        if (orderId == null) {
            throw notFound();
        }
        return orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(OrderAggregateMutationLockService::notFound);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lockIfLive(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return orderRepository.findByIdForCounterUpdate(orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Order lockForOrderDetail(UUID orderDetailId) {
        if (orderDetailId == null) {
            throw notFound();
        }
        Long candidateOrderId = orderDetailsRepository.findOrderIdById(orderDetailId)
                .orElseThrow(OrderAggregateMutationLockService::notFound);
        Order lockedOrder = lock(candidateOrderId);
        Long currentOrderId = orderDetailsRepository.findOrderIdById(orderDetailId)
                .orElseThrow(OrderAggregateMutationLockService::notFound);
        if (!Objects.equals(candidateOrderId, currentOrderId)) {
            throw bindingChanged();
        }
        return lockedOrder;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Order lockForReview(Long reviewId) {
        if (reviewId == null) {
            throw notFound();
        }
        Long candidateOrderId = reviewRepository.findOrderIdByReviewId(reviewId)
                .orElseThrow(OrderAggregateMutationLockService::notFound);
        Order lockedOrder = lock(candidateOrderId);
        Long currentOrderId = reviewRepository.findOrderIdByReviewId(reviewId)
                .orElseThrow(OrderAggregateMutationLockService::notFound);
        if (!Objects.equals(candidateOrderId, currentOrderId)) {
            throw bindingChanged();
        }
        return lockedOrder;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
    }

    private static ResponseStatusException bindingChanged() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Состав заказа изменился. Обновите страницу и повторите действие."
        );
    }
}
