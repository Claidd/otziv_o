package com.hunt.otziv.p_products.review.service;

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

    /**
     * Same lock order and binding re-check as {@link #lockForReview(Long)}, but a review,
     * order link or order row that is already gone is reported as {@link Optional#empty()}.
     *
     * <p>This method participates in the caller's transaction, so any exception thrown
     * through its proxy marks that whole transaction rollback-only. A caller that catches
     * the 404 of {@link #lockForReview(Long)} and answers "not found" normally would then
     * fail at commit with {@code UnexpectedRollbackException} (HTTP 500). Callers that
     * treat a vanished review as an ordinary result must use this variant instead. A moved
     * review is still a real conflict, so the 409 keeps propagating.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lockIfLiveForReview(Long reviewId) {
        if (reviewId == null) {
            return Optional.empty();
        }
        Optional<Long> candidateOrderId = reviewRepository.findOrderIdByReviewId(reviewId);
        if (candidateOrderId.isEmpty()) {
            return Optional.empty();
        }
        Optional<Order> lockedOrder = lockIfLive(candidateOrderId.get());
        if (lockedOrder.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> currentOrderId = reviewRepository.findOrderIdByReviewId(reviewId);
        if (currentOrderId.isEmpty()) {
            return Optional.empty();
        }
        if (!Objects.equals(candidateOrderId.get(), currentOrderId.get())) {
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
